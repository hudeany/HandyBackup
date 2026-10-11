package de.soderer.handybackup;

import java.io.File;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

import de.soderer.utilities.ConfigurationProperties;

/**
 * Configuration of HandyBackup, split into two files in "~/.HandyBackup/":
 * <ul>
 * <li>".HandyBackup.config": application settings (language, proxy, update check) via
 * {@link ConfigurationProperties}, editable in the configuration dialog</li>
 * <li>".HandyBackup.devices.config": shares and device assignments (properties format)</li>
 * </ul>
 *
 * Devices are identified by the MTP serial number (as shown in Explorer's device properties).
 *
 * Keys of the devices file:
 * <pre>
 * Share.&lt;n&gt;.Name / .Hostname / .Port / .Sharename / .BasePath / .Username / .Password (DPAPI encrypted)
 * Device.&lt;n&gt;.SerialNumber / .DeviceName / .Share / .DeleteAfterDays
 * </pre>
 * with n = 1, 2, 3 ... without gaps.
 */
public class HandyBackupConfiguration {
	private static final String SHARE_PREFIX = "Share.";
	private static final String DEVICE_PREFIX = "Device.";

	/** Default for {@link DeviceAssignment#deleteAfterDays()}: backed-up files older than 90 days are deleted from the device */
	public static final int DEFAULT_DELETE_AFTER_DAYS = 90;

	/**
	 * Assignment of a device to a share.
	 *
	 * @param serialNumber MTP serial number of the device
	 * @param deviceName   last known display name of the device, for information only
	 * @param shareName    name of the assigned share
	 * @param deleteAfterDays deletion of backed-up files from the device after the backup:
	 *                        &lt; 0 never delete, 0 delete all backed-up files, &gt; 0 delete backed-up files older than this many days
	 */
	public record DeviceAssignment(String serialNumber, String deviceName, String shareName, int deleteAfterDays) {
		public DeviceAssignment withShareName(final String newShareName) {
			return new DeviceAssignment(serialNumber, deviceName, newShareName, deleteAfterDays);
		}
	}

	private ConfigurationProperties configurationProperties;
	private final Map<String, ShareDefinition> shares = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	private final Map<String, DeviceAssignment> deviceAssignments = new TreeMap<>();

	private static File getApplicationDirectory() {
		return new File(System.getProperty("user.home") + File.separator + "." + HandyBackup.APPLICATION_NAME);
	}

	/**
	 * Location of the application configuration file, for messages only.
	 */
	public static File getConfigFile() {
		return new File(getApplicationDirectory(), "." + HandyBackup.APPLICATION_NAME + ".config");
	}

	/**
	 * Location of the file with shares and device assignments.
	 */
	public static File getDevicesFile() {
		return new File(getApplicationDirectory(), "." + HandyBackup.APPLICATION_NAME + ".devices.config");
	}

	/**
	 * Loads the application configuration (setting up the default keys of the library: language, proxy,
	 * daily update check) and the shares and device assignments.
	 *
	 * Shares and assignments of former versions, which were stored in the application configuration,
	 * are moved to the devices file once.
	 */
	public synchronized void load() throws Exception {
		configurationProperties = new ConfigurationProperties(HandyBackup.APPLICATION_NAME, true);
		configurationProperties.setupDefaultConfig();

		shares.clear();
		deviceAssignments.clear();
		final File devicesFile = getDevicesFile();
		if (devicesFile.exists()) {
			final Properties properties = new Properties();
			try (Reader reader = Files.newBufferedReader(devicesFile.toPath(), StandardCharsets.UTF_8)) {
				properties.load(reader);
			}
			parse(properties::getProperty);
		} else if (containsDeviceKeys(configurationProperties)) {
			// Migration: move the entries into the devices file and remove them from the application configuration
			parse(configurationProperties::get);
			save();
			for (final String key : configurationProperties.getKeyList()) {
				if (key.startsWith(SHARE_PREFIX) || key.startsWith(DEVICE_PREFIX)) {
					configurationProperties.removeKey(key);
				}
			}
			configurationProperties.save();
		}
	}

	private static boolean containsDeviceKeys(final ConfigurationProperties properties) {
		for (final String key : properties.getKeyList()) {
			if (key.startsWith(SHARE_PREFIX) || key.startsWith(DEVICE_PREFIX)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The application configuration, e.g. for the configuration dialog. Created empty, if loading failed.
	 */
	public synchronized ConfigurationProperties getConfigurationProperties() throws Exception {
		if (configurationProperties == null) {
			configurationProperties = new ConfigurationProperties(HandyBackup.APPLICATION_NAME, true);
		}
		return configurationProperties;
	}

	/**
	 * Configured application language (language code like "de"), null if not available.
	 */
	public synchronized String getLanguage() {
		return configurationProperties == null ? null : configurationProperties.getLanguage();
	}

	/**
	 * Reads shares and device assignments.
	 *
	 * @param values returns the value of a key, null if the key does not exist
	 */
	private void parse(final UnaryOperator<String> values) {
		for (int index = 1; values.apply(SHARE_PREFIX + index + ".Name") != null; index++) {
			final String prefix = SHARE_PREFIX + index + ".";
			final ShareDefinition share = new ShareDefinition();
			share.setName(values.apply(prefix + "Name"));
			share.setHostname(nullToEmpty(values.apply(prefix + "Hostname")));
			share.setPort(parsePort(values.apply(prefix + "Port")));
			share.setSharename(nullToEmpty(values.apply(prefix + "Sharename")));
			share.setBasePath(values.apply(prefix + "BasePath"));
			share.setUsername(nullToEmpty(values.apply(prefix + "Username")));
			final String encryptedPassword = values.apply(prefix + "Password");
			share.setEncryptedPassword(encryptedPassword == null || encryptedPassword.isEmpty() ? null : encryptedPassword);
			shares.put(share.getName(), share);
		}

		for (int index = 1; values.apply(DEVICE_PREFIX + index + ".SerialNumber") != null; index++) {
			final String prefix = DEVICE_PREFIX + index + ".";
			final DeviceAssignment assignment = new DeviceAssignment(
				values.apply(prefix + "SerialNumber"),
				nullToEmpty(values.apply(prefix + "DeviceName")),
				nullToEmpty(values.apply(prefix + "Share")),
				parseDeleteAfterDays(values.apply(prefix + "DeleteAfterDays")));
			deviceAssignments.put(assignment.serialNumber(), assignment);
		}
	}

	/**
	 * Saves all shares and assignments into the devices file. The file is written to a temporary file
	 * first, so a failed write never destroys the existing file.
	 */
	public synchronized void save() throws Exception {
		final Properties properties = new Properties();

		int index = 1;
		for (final ShareDefinition share : shares.values()) {
			final String prefix = SHARE_PREFIX + index++ + ".";
			properties.setProperty(prefix + "Name", share.getName());
			properties.setProperty(prefix + "Hostname", nullToEmpty(share.getHostname()));
			properties.setProperty(prefix + "Port", Integer.toString(share.getPort()));
			properties.setProperty(prefix + "Sharename", nullToEmpty(share.getSharename()));
			properties.setProperty(prefix + "BasePath", nullToEmpty(share.getBasePath()));
			properties.setProperty(prefix + "Username", nullToEmpty(share.getUsername()));
			properties.setProperty(prefix + "Password", nullToEmpty(share.getEncryptedPassword()));
		}

		index = 1;
		for (final DeviceAssignment assignment : deviceAssignments.values()) {
			final String prefix = DEVICE_PREFIX + index++ + ".";
			properties.setProperty(prefix + "SerialNumber", assignment.serialNumber());
			properties.setProperty(prefix + "DeviceName", nullToEmpty(assignment.deviceName()));
			properties.setProperty(prefix + "Share", assignment.shareName());
			properties.setProperty(prefix + "DeleteAfterDays", Integer.toString(assignment.deleteAfterDays()));
		}

		final File devicesFile = getDevicesFile();
		final File directory = devicesFile.getParentFile();
		if (!directory.isDirectory() && !directory.mkdirs()) {
			throw new Exception("Cannot create configuration folder: " + directory);
		}
		final File tempFile = new File(directory, devicesFile.getName() + ".tmp");
		try (Writer writer = Files.newBufferedWriter(tempFile.toPath(), StandardCharsets.UTF_8)) {
			properties.store(writer, HandyBackup.APPLICATION_NAME + " shares and device assignments (passwords are DPAPI encrypted)");
		}
		Files.move(tempFile.toPath(), devicesFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
	}

	public synchronized List<ShareDefinition> getShares() {
		return new ArrayList<>(shares.values());
	}

	public synchronized ShareDefinition getShare(final String name) {
		return name == null ? null : shares.get(name);
	}

	/**
	 * Adds a new share or replaces an existing one. If the share was renamed, all device
	 * assignments are moved to the new name.
	 *
	 * @param previousName previous name of an edited share, null for a new share
	 */
	public synchronized void putShare(final String previousName, final ShareDefinition share) {
		if (previousName != null && !previousName.equals(share.getName())) {
			shares.remove(previousName);
			for (final DeviceAssignment assignment : new ArrayList<>(deviceAssignments.values())) {
				if (assignment.shareName().equalsIgnoreCase(previousName)) {
					deviceAssignments.put(assignment.serialNumber(), assignment.withShareName(share.getName()));
				}
			}
		}
		shares.put(share.getName(), share);
	}

	/**
	 * Removes a share and all device assignments to it.
	 */
	public synchronized void removeShare(final String name) {
		shares.remove(name);
		deviceAssignments.values().removeIf(assignment -> assignment.shareName().equalsIgnoreCase(name));
	}

	public synchronized DeviceAssignment getAssignment(final String serialNumber) {
		return serialNumber == null ? null : deviceAssignments.get(serialNumber);
	}

	/**
	 * Assigns a device to a share, or removes the assignment if shareName is null.
	 * The deletion setting of an existing assignment is kept, a new assignment gets {@link #DEFAULT_DELETE_AFTER_DAYS}.
	 */
	public synchronized void assign(final String serialNumber, final String deviceName, final String shareName) {
		if (shareName == null) {
			deviceAssignments.remove(serialNumber);
		} else {
			final DeviceAssignment existing = deviceAssignments.get(serialNumber);
			final int deleteAfterDays = existing == null ? DEFAULT_DELETE_AFTER_DAYS : existing.deleteAfterDays();
			deviceAssignments.put(serialNumber, new DeviceAssignment(serialNumber, deviceName, shareName, deleteAfterDays));
		}
	}

	/**
	 * Changes the deletion setting of an assigned device. Does nothing if the device is not assigned.
	 *
	 * @param deleteAfterDays &lt; 0 never delete, 0 delete all backed-up files, &gt; 0 delete backed-up files older than this many days
	 */
	public synchronized void setDeleteAfterDays(final String serialNumber, final int deleteAfterDays) {
		final DeviceAssignment existing = serialNumber == null ? null : deviceAssignments.get(serialNumber);
		if (existing != null) {
			deviceAssignments.put(serialNumber, new DeviceAssignment(existing.serialNumber(), existing.deviceName(), existing.shareName(), deleteAfterDays));
		}
	}

	public synchronized List<DeviceAssignment> getAssignmentsOfShare(final String shareName) {
		final List<DeviceAssignment> result = new ArrayList<>();
		for (final DeviceAssignment assignment : deviceAssignments.values()) {
			if (assignment.shareName().equalsIgnoreCase(shareName)) {
				result.add(assignment);
			}
		}
		return result;
	}

	private static int parsePort(final String value) {
		try {
			final int port = Integer.parseInt(value == null ? "" : value.trim());
			return port > 0 && port <= 65535 ? port : 445;
		} catch (@SuppressWarnings("unused") final NumberFormatException e) {
			return 445;
		}
	}

	/**
	 * Missing or invalid values (e.g. configuration of a former version) get the default.
	 * Negative values are normalized to -1.
	 */
	private static int parseDeleteAfterDays(final String value) {
		if (value == null || value.isBlank()) {
			return DEFAULT_DELETE_AFTER_DAYS;
		}
		try {
			final int days = Integer.parseInt(value.trim());
			return days < 0 ? -1 : days;
		} catch (@SuppressWarnings("unused") final NumberFormatException e) {
			return DEFAULT_DELETE_AFTER_DAYS;
		}
	}

	private static String nullToEmpty(final String value) {
		return value == null ? "" : value;
	}
}

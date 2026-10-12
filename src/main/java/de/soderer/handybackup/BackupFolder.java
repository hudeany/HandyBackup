package de.soderer.handybackup;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * A folder on the device that is backed up, with the kinds of files to back up from it.
 * Used for the folders added manually per device in the device tree, and as the result of
 * {@link StoragePaths#findBackupFolder(String, Collection)}, which also covers the defaults of {@link StoragePaths}.
 *
 * @param devicePath  MTP path of the folder including the storage, e.g. "/&lt;storage&gt;/Documents/Scans"
 * @param backupTypes kinds of files to back up, not empty; contains only {@link BackupType#ALL} if ALL is included
 * @param custom      true for a folder added manually for a device, false for a default of {@link StoragePaths}
 */
public record BackupFolder(String devicePath, Set<BackupType> backupTypes, boolean custom) {
	public BackupFolder {
		if (devicePath == null || devicePath.isBlank()) {
			throw new IllegalArgumentException("Path must not be empty");
		} else if (backupTypes == null || backupTypes.isEmpty()) {
			throw new IllegalArgumentException("At least one backup type is required");
		}
		// ALL already covers every other type
		backupTypes = backupTypes.contains(BackupType.ALL)
			? Collections.unmodifiableSet(EnumSet.of(BackupType.ALL))
			: Collections.unmodifiableSet(EnumSet.copyOf(backupTypes));
	}

	/**
	 * Checks whether a file with the given name should be backed up from this folder.
	 */
	public boolean matches(final String fileName) {
		for (final BackupType backupType : backupTypes) {
			if (backupType.matches(fileName)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the given device path is this folder or lies below it (case-insensitive).
	 */
	public boolean contains(final String path) {
		if (path == null) {
			return false;
		}
		final String lowerPath = path.toLowerCase(Locale.ROOT);
		final String lowerFolder = devicePath.toLowerCase(Locale.ROOT);
		return lowerPath.equals(lowerFolder) || lowerPath.startsWith(lowerFolder + "/");
	}

	/**
	 * Configuration value: "&lt;devicePath&gt;|&lt;TYPE&gt;,&lt;TYPE&gt;...". The separator is searched from the end
	 * when parsing, because type names never contain "|", while folder names may.
	 */
	public String toConfigValue() {
		final StringBuilder value = new StringBuilder(devicePath).append('|');
		boolean first = true;
		for (final BackupType backupType : backupTypes) {
			if (!first) {
				value.append(',');
			}
			value.append(backupType.name());
			first = false;
		}
		return value.toString();
	}

	/**
	 * Parses a value written by {@link #toConfigValue()}. Unknown type names are ignored.
	 *
	 * @return the folder, null if the value is invalid or contains no known type
	 */
	public static BackupFolder fromConfigValue(final String value) {
		if (value == null) {
			return null;
		}
		final int separatorIndex = value.lastIndexOf('|');
		if (separatorIndex <= 0) {
			return null;
		}
		final String path = HandyBackupConfiguration.normalizeDevicePath(value.substring(0, separatorIndex));
		if (path == null) {
			return null;
		}
		final Set<BackupType> types = EnumSet.noneOf(BackupType.class);
		for (final String typeName : value.substring(separatorIndex + 1).split(",")) {
			try {
				types.add(BackupType.valueOf(typeName.trim().toUpperCase(Locale.ROOT)));
			} catch (@SuppressWarnings("unused") final IllegalArgumentException e) {
				// Unknown type (e.g. from a newer version): ignore
			}
		}
		return types.isEmpty() ? null : new BackupFolder(path, types, true);
	}

	@Override
	public String toString() {
		return devicePath + " " + backupTypes;
	}
}

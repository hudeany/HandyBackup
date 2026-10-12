package de.soderer.handybackup;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import de.soderer.handybackup.mtp.MtpDevice;
import de.soderer.handybackup.mtp.MtpErrorType;
import de.soderer.handybackup.mtp.MtpFile;
import de.soderer.handybackup.mtp.MtpFileSystem;
import de.soderer.handybackup.mtp.MtpManager;
import de.soderer.utilities.ConfigurationProperties;
import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Result;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.Version;
import de.soderer.utilities.appupdate.ApplicationUpdateUtilities;
import de.soderer.utilities.remote.FileInfo;
import de.soderer.utilities.remote.FolderInfo;
import de.soderer.utilities.remote.SmbConnection;
import de.soderer.utilities.swing.ApplicationConfigurationDialog;
import de.soderer.utilities.swing.ErrorDialog;
import de.soderer.utilities.swing.UpdateableGuiApplication;

/**
 * HandyBackup: backs up images, videos and contacts of Android phones (connected via USB in "File transfer"
 * mode, MTP) into Samba shares.
 *
 * Each device is identified by its MTP serial number and assigned to one share; one share may
 * be used by several devices (e.g. old and new phone of the same person).
 *
 * Left side: folder tree of the connected device (folders that are backed up are bold).
 * Right side: folder tree of the assigned share.
 * A double click on a file in either tree opens a temporary copy with the system's default application.
 * Files on the device and in the share can be deleted manually via context menu (after confirmation);
 * deleting in the share also removes the file's entries from the backup index.
 *
 * After a backup, backed-up files can be deleted from the device, configured per device
 * ("delete after days": &lt; 0 never, 0 all backed-up files, &gt; 0 files older than that).
 *
 * All MTP and SMB operations run one after another in a single background thread.
 */
public class HandyBackup extends UpdateableGuiApplication {
	private static final long serialVersionUID = 1L;

	public static final String APPLICATION_NAME = "HandyBackup";

	/**
	 * Startup class name of the application, also used as AWT window manager class (e.g. for pinning the
	 * application to the Linux Gnome dock).
	 */
	public static final String APPLICATION_STARTUPCLASS_NAME = "de-soderer-handybackup";

	/** Resource containing version number, build time, versioninfo download url and trusted update CA certificates */
	public static final String VERSION_RESOURCE_FILE = "/application_version.txt";

	/** Keystore file of the application (e.g. for remembered update credentials) */
	public static final File KEYSTORE_FILE = new File(System.getProperty("user.home") + File.separator + "." + APPLICATION_NAME + File.separator + "." + APPLICATION_NAME + ".keystore");

	/** The version is filled in at application start from the application_version.txt file */
	public static Version VERSION = null;

	/** The version build time is filled in at application start from the application_version.txt file */
	public static LocalDateTime VERSION_BUILDTIME = null;

	/** The versioninfo download url is filled in at application start from the application_version.txt file */
	public static String VERSIONINFO_DOWNLOAD_URL = null;

	/** Trusted CA certificates for updates, filled in at application start from the application_version.txt file */
	public static String TRUSTED_UPDATE_CA_CERTIFICATES = null;

	/** Address offered by the error dialog for sending error reports (adjust as needed) */
	public static final String APPLICATION_ERROR_EMAIL_ADDRESS = "handybackup@soderer.de";

	private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

	/** Interval for checking whether a disconnected device is available again */
	private static final long DEVICE_POLL_INTERVAL_MILLIS = 2000;

	/** Prefix of the temporary folders holding copies of files opened for viewing */
	private static final String VIEW_FOLDER_PREFIX = "handybackup_view_";

	/** Single background thread for all MTP and SMB access (neither is designed for parallel use) */
	private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
		final Thread thread = new Thread(runnable, "HandyBackup-IO");
		thread.setDaemon(true);
		return thread;
	});

	private final HandyBackupConfiguration configuration;

	// Only accessed in the IO thread
	private MtpManager mtpManager;
	private MtpFileSystem currentFileSystem;
	private final Map<String, SmbConnection> smbConnections = new HashMap<>();

	// Only accessed in the event dispatch thread
	private MtpDevice currentDevice;
	private volatile BackupEngine runningBackup;
	private volatile boolean cancelRequested = false;
	private boolean backupActive = false;
	private boolean updatingDeleteAfterDays = false;

	// Only accessed in the IO thread: temporary folder for viewed files and counter for unique subfolders
	private Path viewFolder;
	private int viewFileCounter = 0;
	private boolean updatingShareCombo = false;
	private boolean updatingDeviceCombo = false;

	/** Combo entry for "no share"; an instance field, because the texts are only available after the locale was set in main() */
	private final String noShareText = LangResources.get("noShareAssigned");

	private final JComboBox<MtpDevice> deviceCombo = new JComboBox<>();
	private final JButton refreshDevicesButton = new JButton(LangResources.get("searchDevices"));
	private final JComboBox<String> shareCombo = new JComboBox<>();
	private final JButton manageSharesButton = new JButton(LangResources.get("shares"));
	private final JButton configButton = createIconButton("wrench.png", LangResources.get("configuration"));
	private final JButton helpButton = createIconButton("question.png", LangResources.get("help"));
	private final JLabel deviceInfoLabel = new JLabel(" ");
	private final JLabel deleteAfterDaysLabel = new JLabel(LangResources.get("deleteFromPhoneAfter"));
	private final JSpinner deleteAfterDaysSpinner = new JSpinner(new SpinnerNumberModel(HandyBackupConfiguration.DEFAULT_DELETE_AFTER_DAYS, -1, 36500, 1));
	private final JLabel deleteAfterDaysInfoLabel = new JLabel(" ");
	private final LazyTree deviceTree = new LazyTree(ioExecutor, LangResources.get("noDeviceSelected"));
	private final LazyTree shareTree = new LazyTree(ioExecutor, LangResources.get("noShareAssignedTree"));
	private final JButton refreshTreesButton = new JButton(LangResources.get("refreshView"));
	private final JButton startButton = new JButton(LangResources.get("startBackup"));
	private final JButton cancelButton = new JButton(LangResources.get("cancel"));
	private final JButton closeButton = new JButton(LangResources.get("close"));
	private final JProgressBar progressBar = new JProgressBar();
	private final JLabel statusLabel = new JLabel(" ");
	private final JTextArea logArea = new JTextArea(8, 80);

	public static void main(final String[] args) {
		ApplicationUpdateUtilities.removeUpdateLeftovers();
		readVersionInfo();

		// The configuration is loaded first, because its language decides the locale of all dialogs
		final HandyBackupConfiguration configuration = new HandyBackupConfiguration();
		Exception configurationError = null;
		try {
			configuration.load();
		} catch (final Exception e) {
			configurationError = e;
		}
		if ("de".equalsIgnoreCase(configuration.getLanguage())) {
			Locale.setDefault(Locale.GERMAN);
		} else {
			Locale.setDefault(Locale.ENGLISH);
		}

		// Command line interface (all arguments except "gui"), e.g. for scheduled backups
		if (HandyBackupCli.isCliCall(args)) {
			System.exit(new HandyBackupCli(configuration, configurationError).run(args));
		}

		// Allows pinning the application to the Linux Gnome dock (needs "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED")
		de.soderer.utilities.swing.SwingUtilities.setAwtWmClass(APPLICATION_STARTUPCLASS_NAME);
		de.soderer.utilities.swing.SwingUtilities.setSystemLookAndFeel();

		final Exception finalConfigurationError = configurationError;
		SwingUtilities.invokeLater(() -> {
			try {
				final HandyBackup application = new HandyBackup(configuration);
				application.setVisible(true);
				if (finalConfigurationError != null) {
					showErrorDialog(application, LangResources.get("configurationReadFailed", HandyBackupConfiguration.getConfigFile() + " / " + HandyBackupConfiguration.getDevicesFile()), finalConfigurationError);
				}
				application.start();
			} catch (final Exception e) {
				new ErrorDialog(null, APPLICATION_NAME, VERSION.toString(), APPLICATION_ERROR_EMAIL_ADDRESS, e).open();
				System.exit(1);
			}
		});
	}

	/**
	 * Reads version, build time, versioninfo download url and trusted update CA certificates from
	 * application_version.txt (one value per line, as written by the build).
	 */
	private static void readVersionInfo() {
		try (InputStream resourceStream = HandyBackup.class.getResourceAsStream(VERSION_RESOURCE_FILE)) {
			final List<String> versionInfoLines = Utilities.readLines(resourceStream, StandardCharsets.UTF_8);
			VERSION = new Version(versionInfoLines.get(0));
			if (versionInfoLines.size() >= 2) {
				VERSION_BUILDTIME = DateUtilities.parseLocalDateTime(DateUtilities.YYYY_MM_DD_HHMMSS, versionInfoLines.get(1));
			}
			if (versionInfoLines.size() >= 3 && Utilities.isNotBlank(versionInfoLines.get(2))) {
				VERSIONINFO_DOWNLOAD_URL = versionInfoLines.get(2);
			}
			if (versionInfoLines.size() >= 4 && Utilities.isNotBlank(versionInfoLines.get(3))) {
				TRUSTED_UPDATE_CA_CERTIFICATES = versionInfoLines.get(3);
			}
		} catch (@SuppressWarnings("unused") final Exception e) {
			// Missing or invalid application_version.txt (e.g. when started from the IDE without build): run without update support
			System.err.println("Invalid application_version.txt");
		}

		if (VERSION == null) {
			try {
				VERSION = new Version("0.0.0");
			} catch (final Exception e) {
				throw new IllegalStateException("Cannot create default version", e);
			}
		}
	}

	public HandyBackup(final HandyBackupConfiguration configuration) throws Exception {
		super(APPLICATION_NAME, VERSION, KEYSTORE_FILE);
		this.configuration = configuration;

		setTitle(APPLICATION_NAME + " (" + VERSION + ")");
		final ImageIcon applicationIcon = getImageIcon("HandyBackup.png");
		if (applicationIcon != null) {
			setIconImage(applicationIcon.getImage());
		}

		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosing(final WindowEvent event) {
				onExit();
			}

			@Override
			public void windowOpened(final WindowEvent event) {
				// Deferred, so the main window is completely shown before a possible update dialog appears
				SwingUtilities.invokeLater(() -> runDailyUpdateCheck());
			}
		});

		deviceCombo.setRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index, final boolean isSelected, final boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof final MtpDevice device) {
					setText(device.name() + (device.model() != null && !device.model().equals(device.name()) ? " (" + device.model() + ")" : ""));
				} else if (value == null) {
					setText(LangResources.get("noDeviceFound"));
				}
				return this;
			}
		});
		deviceCombo.setPreferredSize(new Dimension(260, deviceCombo.getPreferredSize().height));
		shareCombo.setPreferredSize(new Dimension(220, shareCombo.getPreferredSize().height));

		deviceCombo.addActionListener(event -> {
			if (!updatingDeviceCombo) {
				selectDevice((MtpDevice) deviceCombo.getSelectedItem());
			}
		});
		shareCombo.addActionListener(event -> {
			if (!updatingShareCombo) {
				onShareAssignmentChanged();
			}
		});
		deleteAfterDaysSpinner.addChangeListener(event -> {
			if (!updatingDeleteAfterDays) {
				onDeleteAfterDaysChanged();
			}
		});
		final String deleteAfterDaysToolTip = LangResources.get("deleteAfterDaysTooltip");
		deleteAfterDaysLabel.setToolTipText(deleteAfterDaysToolTip);
		deleteAfterDaysSpinner.setToolTipText(deleteAfterDaysToolTip);
		if (deleteAfterDaysSpinner.getEditor() instanceof final JSpinner.DefaultEditor editor) {
			editor.getTextField().setColumns(4);
			editor.getTextField().setToolTipText(deleteAfterDaysToolTip);
		}
		deviceTree.setFileOpenHandler(this::openDeviceFile);
		deviceTree.setFileDeleteHandler(this::deleteDeviceFiles);
		shareTree.setFileDeleteHandler(this::deleteShareFiles);
		shareTree.setFileOpenHandler(this::openShareFile);
		refreshDevicesButton.addActionListener(event -> refreshDevices());
		manageSharesButton.addActionListener(event -> onManageShares());
		configButton.addActionListener(event -> openConfiguration());
		helpButton.addActionListener(event -> openHelp());
		refreshTreesButton.addActionListener(event -> {
			deviceTree.refresh();
			shareTree.refresh();
		});
		startButton.addActionListener(event -> onStartBackup());
		closeButton.addActionListener(event -> onExit());
		cancelButton.addActionListener(event -> {
			// The engine may not be created yet (IO thread), so the request is also remembered
			cancelRequested = true;
			final BackupEngine backup = runningBackup;
			if (backup != null) {
				backup.cancel();
			}
			cancelButton.setEnabled(false);
			log(LangResources.get("cancelRequested"));
		});

		// Top: device and share selection
		final JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
		topPanel.add(new JLabel(LangResources.get("phoneLabel")));
		topPanel.add(deviceCombo);
		topPanel.add(refreshDevicesButton);
		topPanel.add(new JLabel("   " + LangResources.get("backupTargetLabel")));
		topPanel.add(shareCombo);
		topPanel.add(manageSharesButton);
		// Configuration and help sit at the right edge of the first row (like RestClient)
		final JPanel applicationButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 6));
		applicationButtonPanel.add(configButton);
		applicationButtonPanel.add(helpButton);
		final JPanel firstRow = new JPanel(new BorderLayout());
		firstRow.add(topPanel, BorderLayout.CENTER);
		firstRow.add(applicationButtonPanel, BorderLayout.EAST);
		final JPanel northPanel = new JPanel(new BorderLayout());
		northPanel.add(firstRow, BorderLayout.NORTH);
		deviceInfoLabel.setBorder(BorderFactory.createEmptyBorder(0, 10, 4, 10));
		// Second row: device information and the device's deletion setting
		final JPanel deletePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		deletePanel.add(deleteAfterDaysLabel);
		deletePanel.add(deleteAfterDaysSpinner);
		deletePanel.add(new JLabel(LangResources.get("deleteFromPhoneAfterUnit")));
		deletePanel.add(deleteAfterDaysInfoLabel);
		deletePanel.setBorder(BorderFactory.createEmptyBorder(0, 10, 4, 6));
		final JPanel secondRow = new JPanel(new BorderLayout());
		secondRow.add(deviceInfoLabel, BorderLayout.CENTER);
		secondRow.add(deletePanel, BorderLayout.EAST);
		northPanel.add(secondRow, BorderLayout.SOUTH);

		// Center: device tree (left) and share tree (right)
		final JScrollPane deviceScrollPane = new JScrollPane(deviceTree);
		deviceScrollPane.setBorder(BorderFactory.createTitledBorder(LangResources.get("deviceTreeTitle")));
		final JScrollPane shareScrollPane = new JScrollPane(shareTree);
		shareScrollPane.setBorder(BorderFactory.createTitledBorder(LangResources.get("shareTreeTitle")));
		final JSplitPane treeSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, deviceScrollPane, shareScrollPane);
		treeSplitPane.setResizeWeight(0.5);
		treeSplitPane.setContinuousLayout(true);

		// Bottom: log, progress and buttons
		logArea.setEditable(false);
		final JScrollPane logScrollPane = new JScrollPane(logArea);
		logScrollPane.setBorder(BorderFactory.createTitledBorder(LangResources.get("logTitle")));
		final JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeSplitPane, logScrollPane);
		mainSplitPane.setResizeWeight(0.75);

		progressBar.setStringPainted(true);
		progressBar.setString("");
		cancelButton.setEnabled(false);
		final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttonPanel.add(refreshTreesButton);
		buttonPanel.add(startButton);
		buttonPanel.add(cancelButton);
		buttonPanel.add(closeButton);
		final JPanel statusPanel = new JPanel(new BorderLayout(10, 0));
		statusPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 4));
		statusPanel.add(progressBar, BorderLayout.WEST);
		statusPanel.add(statusLabel, BorderLayout.CENTER);
		statusPanel.add(buttonPanel, BorderLayout.EAST);
		progressBar.setPreferredSize(new Dimension(220, progressBar.getPreferredSize().height));

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(northPanel, BorderLayout.NORTH);
		getContentPane().add(mainSplitPane, BorderLayout.CENTER);
		getContentPane().add(statusPanel, BorderLayout.SOUTH);

		setSize(1200, 800);
		setLocationRelativeTo(null);
		updateControls();
	}

	/**
	 * Loads the configuration and starts the MTP helper process.
	 */
	private void start() {
		fillShareCombo();
		log(LangResources.get("configurationFilesInfo", HandyBackupConfiguration.getConfigFile(), HandyBackupConfiguration.getDevicesFile()));

		setBusy(LangResources.get("startingMtp"));
		ioExecutor.submit(() -> {
			deleteOldViewFolders();
			try {
				mtpManager = new MtpManager();
				SwingUtilities.invokeLater(this::refreshDevices);
			} catch (final Throwable e) {
				SwingUtilities.invokeLater(() -> {
					setIdle();
					logError(LangResources.get("mtpStartFailed"), e);
					showErrorDialog(this, LangResources.get("mtpStartFailed"), e);
				});
			}
		});
	}

	// ---------------------------------------------------------------- devices

	private void refreshDevices() {
		final String previousSerial = currentDevice == null ? null : currentDevice.serialNumber();
		setBusy(LangResources.get("searchingDevices"));
		ioExecutor.submit(() -> {
			try {
				if (mtpManager == null) {
					throw new Exception(LangResources.get("mtpNotStarted"));
				}
				final List<MtpDevice> devices = new ArrayList<>();
				for (final MtpDevice device : mtpManager.getConnectedDevices()) {
					if (device.errorMessage() != null) {
						SwingUtilities.invokeLater(() -> log(LangResources.get("deviceNotReadable", device.name(), device.errorMessage())));
					} else if (isUsableDevice(device)) {
						devices.add(device);
					}
				}
				SwingUtilities.invokeLater(() -> {
					setIdle();
					showDevices(devices, previousSerial);
				});
			} catch (final Throwable e) {
				SwingUtilities.invokeLater(() -> {
					setIdle();
					logError(LangResources.get("deviceSearchFailed"), e);
				});
			}
		});
	}

	/**
	 * Readable MTP devices (WPD may also report other portable devices).
	 */
	private static boolean isUsableDevice(final MtpDevice device) {
		return device.errorMessage() == null && (device.protocol() == null || device.protocol().toUpperCase(Locale.ROOT).startsWith("MTP"));
	}

	private void showDevices(final List<MtpDevice> devices, final String previousSerial) {
		updatingDeviceCombo = true;
		MtpDevice selection = null;
		try {
			deviceCombo.setModel(new DefaultComboBoxModel<>(devices.toArray(new MtpDevice[0])));
			for (final MtpDevice device : devices) {
				if (previousSerial != null && previousSerial.equals(device.serialNumber())) {
					selection = device;
				}
			}
			if (selection == null && !devices.isEmpty()) {
				selection = devices.get(0);
			}
			deviceCombo.setSelectedItem(selection);
		} finally {
			updatingDeviceCombo = false;
		}

		if (devices.isEmpty()) {
			log(LangResources.get("noPhoneFound"));
		} else {
			log(LangResources.get("devicesFound", devices.size()));
		}
		selectDevice(selection);
	}

	private void selectDevice(final MtpDevice device) {
		currentDevice = device;
		deviceTree.clear(LangResources.get(device == null ? "noDeviceSelected" : "openingDevice"));
		updateDeviceInfo();
		updateShareSelection();
		updateControls();
		if (device == null) {
			ioExecutor.submit(this::closeFileSystem);
			return;
		}

		ioExecutor.submit(() -> {
			closeFileSystem();
			try {
				final MtpFileSystem fileSystem = mtpManager.open(device);
				currentFileSystem = fileSystem;
				SwingUtilities.invokeLater(() -> {
					if (currentDevice == device) {
						final TreeEntry root = new TreeEntry(device.name(), "/", true, -1, null, false);
						deviceTree.setRoot(root, folder -> loadDeviceFolder(fileSystem, folder));
					}
				});
			} catch (final Throwable e) {
				SwingUtilities.invokeLater(() -> {
					deviceTree.clear(LangResources.get("deviceOpenFailedTree"));
					logError(LangResources.get("deviceOpenFailed", device.name()), e);
				});
			}
		});
	}

	/** Called in the IO thread */
	private static List<TreeEntry> loadDeviceFolder(final MtpFileSystem fileSystem, final TreeEntry folder) throws Exception {
		final List<TreeEntry> result = new ArrayList<>();
		for (final MtpFile file : fileSystem.list(folder.path())) {
			result.add(new TreeEntry(file.name(), file.path(), file.directory(), file.size(), file.modified(),
				file.directory() && isBackupRelevant(file)));
		}
		return result;
	}

	/**
	 * Checks whether a device folder is (part of) a folder listed in {@link StoragePaths}.
	 * Device paths start with the storage name: "/&lt;storage&gt;/DCIM/Camera".
	 */
	private static boolean isBackupRelevant(final MtpFile folder) {
		final String path = folder.path();
		final int storageEnd = path.indexOf('/', 1);
		if (storageEnd < 0 || BackupEngine.isExcludedFolder(folder)) {
			return false;
		}
		return StoragePaths.findStoragePath(path.substring(storageEnd)) != null;
	}

	/** Called in the IO thread */
	private void closeFileSystem() {
		if (currentFileSystem != null) {
			try {
				currentFileSystem.close();
			} catch (@SuppressWarnings("unused") final Exception e) {
				// ignore, device may already be disconnected
			}
			currentFileSystem = null;
		}
	}

	private void updateDeviceInfo() {
		final MtpDevice device = currentDevice;
		if (device == null) {
			deviceInfoLabel.setText(" ");
			return;
		}
		final StringBuilder info = new StringBuilder();
		appendInfo(info, LangResources.get("deviceManufacturer"), device.manufacturer());
		appendInfo(info, LangResources.get("deviceModel"), device.model());
		appendInfo(info, LangResources.get("deviceSerialNumber"), device.serialNumber() != null ? device.serialNumber() : LangResources.get("unknownValue"));
		appendInfo(info, LangResources.get("deviceBattery"), device.powerLevel() != null ? device.powerLevel() + " %" : null);
		deviceInfoLabel.setText(info.toString());
	}

	private static void appendInfo(final StringBuilder info, final String label, final String value) {
		if (value != null && !value.isEmpty()) {
			if (info.length() > 0) {
				info.append("     ");
			}
			info.append(label).append(": ").append(value);
		}
	}

	// ---------------------------------------------------------------- shares

	private void fillShareCombo() {
		updatingShareCombo = true;
		try {
			final List<String> items = new ArrayList<>();
			items.add(noShareText);
			for (final ShareDefinition share : configuration.getShares()) {
				items.add(share.getName());
			}
			shareCombo.setModel(new DefaultComboBoxModel<>(items.toArray(new String[0])));
		} finally {
			updatingShareCombo = false;
		}
		updateShareSelection();
	}

	/**
	 * Selects the share assigned to the current device and shows its tree.
	 */
	private void updateShareSelection() {
		final ShareDefinition share = getAssignedShare();
		updatingShareCombo = true;
		try {
			shareCombo.setSelectedItem(share == null ? noShareText : share.getName());
		} finally {
			updatingShareCombo = false;
		}

		if (currentDevice != null && currentDevice.serialNumber() != null && share == null) {
			log(LangResources.get("deviceNotAssigned", currentDevice.name()));
		}
		showShareTree(share);
		updateDeleteAfterDays();
		updateControls();
	}

	/**
	 * Shows the deletion setting of the current device (the default for unassigned devices).
	 */
	private void updateDeleteAfterDays() {
		final HandyBackupConfiguration.DeviceAssignment assignment = currentDevice == null ? null : configuration.getAssignment(currentDevice.serialNumber());
		final int days = assignment == null ? HandyBackupConfiguration.DEFAULT_DELETE_AFTER_DAYS : assignment.deleteAfterDays();
		updatingDeleteAfterDays = true;
		try {
			deleteAfterDaysSpinner.setValue(days);
		} finally {
			updatingDeleteAfterDays = false;
		}
		deleteAfterDaysInfoLabel.setText(describeDeleteAfterDays(days));
	}

	private static String describeDeleteAfterDays(final int days) {
		if (days < 0) {
			return LangResources.get("deleteNever");
		} else if (days == 0) {
			return LangResources.get("deleteAllBackedUp");
		} else {
			return " ";
		}
	}

	private void onDeleteAfterDaysChanged() {
		final HandyBackupConfiguration.DeviceAssignment assignment = currentDevice == null ? null : configuration.getAssignment(currentDevice.serialNumber());
		if (assignment == null) {
			updateDeleteAfterDays();
			return;
		}
		final int days = ((Number) deleteAfterDaysSpinner.getValue()).intValue();
		if (days == assignment.deleteAfterDays()) {
			return;
		}
		configuration.setDeleteAfterDays(assignment.serialNumber(), days);
		try {
			configuration.save();
		} catch (final Exception e) {
			showErrorDialog(this, LangResources.get("configurationSaveFailed", HandyBackupConfiguration.getDevicesFile()), e);
		}
		deleteAfterDaysInfoLabel.setText(describeDeleteAfterDays(days));
		if (days < 0) {
			log(LangResources.get("deleteSettingNever", currentDevice.name()));
		} else if (days == 0) {
			log(LangResources.get("deleteSettingAll", currentDevice.name()));
		} else {
			log(LangResources.get("deleteSettingDays", currentDevice.name(), days));
		}
	}

	private ShareDefinition getAssignedShare() {
		if (currentDevice == null || currentDevice.serialNumber() == null) {
			return null;
		}
		final HandyBackupConfiguration.DeviceAssignment assignment = configuration.getAssignment(currentDevice.serialNumber());
		return assignment == null ? null : configuration.getShare(assignment.shareName());
	}

	private void onShareAssignmentChanged() {
		if (currentDevice == null || currentDevice.serialNumber() == null) {
			showError(LangResources.get(currentDevice == null ? "noPhoneSelected" : "noSerialNumber"));
			updateShareSelection();
			return;
		}

		final String selected = (String) shareCombo.getSelectedItem();
		final String shareName = noShareText.equals(selected) ? null : selected;
		configuration.assign(currentDevice.serialNumber(), currentDevice.name(), shareName);
		try {
			configuration.save();
		} catch (final Exception e) {
			showErrorDialog(this, LangResources.get("configurationSaveFailed", HandyBackupConfiguration.getDevicesFile()), e);
		}
		log(shareName == null
			? LangResources.get("assignmentRemoved", currentDevice.name())
			: LangResources.get("assignmentSet", currentDevice.name(), shareName));
		updateShareSelection();
	}

	private void showShareTree(final ShareDefinition share) {
		if (share == null) {
			shareTree.clear(LangResources.get("noShareAssignedTree"));
			return;
		}
		shareTree.clear(LangResources.get("connectingTo", share.getDisplayPath()));
		ioExecutor.submit(() -> {
			try {
				final SmbConnection connection = getSmbConnection(share);
				SwingUtilities.invokeLater(() -> {
					if (share == getAssignedShare()) {
						final TreeEntry root = new TreeEntry(share.getDisplayPath(), share.getBasePath(), true, -1, null, false);
						shareTree.setRoot(root, folder -> loadShareFolder(connection, share, folder));
					}
				});
			} catch (final Throwable e) {
				SwingUtilities.invokeLater(() -> {
					shareTree.clear(LangResources.get("noConnectionTo", share.getDisplayPath()));
					logError(LangResources.get("connectionFailed", share.getDisplayPath()), e);
				});
			}
		});
	}

	/** Called in the IO thread */
	private static List<TreeEntry> loadShareFolder(final SmbConnection connection, final ShareDefinition share, final TreeEntry folder) throws Exception {
		final List<TreeEntry> result = new ArrayList<>();
		if (folder.path().equals(share.getBasePath()) && !share.getBasePath().isEmpty() && !connection.folderExists(share.getBasePath())) {
			// Base folder is created by the first backup
			return result;
		}
		final String prefix = folder.path().isEmpty() ? "" : folder.path() + "/";
		final var directory = connection.getDirectory(folder.path());
		for (final FolderInfo folderInfo : directory.getFirst()) {
			result.add(new TreeEntry(folderInfo.getFolderName(), prefix + folderInfo.getFolderName(), true, -1, toLocalDateTime(folderInfo.getModificationDate()), false));
		}
		for (final FileInfo fileInfo : directory.getSecond()) {
			result.add(new TreeEntry(fileInfo.getFileName(), prefix + fileInfo.getFileName(), false, fileInfo.getFileSize(), toLocalDateTime(fileInfo.getModificationDate()), false));
		}
		return result;
	}

	private static LocalDateTime toLocalDateTime(final ZonedDateTime dateTime) {
		return dateTime == null ? null : dateTime.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
	}

	/**
	 * Returns an open connection to the share, one per share. Called in the IO thread.
	 */
	private SmbConnection getSmbConnection(final ShareDefinition share) throws Exception {
		final String key = share.getName().toLowerCase(Locale.ROOT);
		SmbConnection connection = smbConnections.get(key);
		if (connection == null) {
			final char[] password = share.getPassword();
			try {
				connection = new SmbConnection(share.getHostname(), share.getPort(), share.getUsername(), share.getSharename(), password);
			} finally {
				Arrays.fill(password, '\0');
			}
			smbConnections.put(key, connection);
		}
		return connection;
	}

	private void onManageShares() {
		if (new ShareManagementDialog(this, configuration).showDialog()) {
			// Share settings may have changed: drop all connections, they are re-established on demand
			ioExecutor.submit(this::closeSmbConnections);
			fillShareCombo();
		}
	}

	/** Called in the IO thread */
	private void closeSmbConnections() {
		for (final SmbConnection connection : smbConnections.values()) {
			try {
				connection.close();
			} catch (@SuppressWarnings("unused") final Exception e) {
				// ignore
			}
		}
		smbConnections.clear();
	}

	// ---------------------------------------------------------------- backup

	private void onStartBackup() {
		final MtpDevice device = currentDevice;
		final ShareDefinition share = getAssignedShare();
		if (device == null || share == null) {
			return;
		}

		final BackupEngine.BackupListener listener = new BackupEngine.BackupListener() {
			@Override
			public void onLog(final String message) {
				SwingUtilities.invokeLater(() -> log(message));
			}

			@Override
			public void onProgress(final int done, final int total, final String currentFile) {
				SwingUtilities.invokeLater(() -> {
					if (total > 0) {
						progressBar.setIndeterminate(false);
						progressBar.setMaximum(total);
						progressBar.setValue(done);
						progressBar.setString(done + " / " + total);
					} else {
						progressBar.setIndeterminate(true);
						progressBar.setString(LangResources.get("searching"));
					}
					statusLabel.setText(currentFile == null ? " " : currentFile);
				});
			}
		};

		log(LangResources.get("backupStarted", device.name(), share.getDisplayPath()));
		cancelRequested = false;
		backupActive = true;
		cancelButton.setEnabled(true);
		updateControls();
		setBusy(LangResources.get("backupRunning"));
		ioExecutor.submit(() -> {
			BackupEngine engine = null;
			try {
				if (currentFileSystem == null) {
					throw new Exception(LangResources.get("deviceNotOpen"));
				}
				engine = new BackupEngine(currentFileSystem, device, getSmbConnection(share), share, listener);
				runningBackup = engine;
				if (cancelRequested) {
					engine.cancel();
				}
				final BackupEngine.BackupResult result = engine.run();
				final BackupEngine finishedEngine = engine;
				SwingUtilities.invokeLater(() -> onBackupFinished(finishedEngine, device, result, null));
			} catch (final Throwable e) {
				// The engine is passed on, so the error dialog can tell how much was saved before the abort
				final BackupEngine failedEngine = engine;
				SwingUtilities.invokeLater(() -> onBackupFinished(failedEngine, device, null, e));
			} finally {
				runningBackup = null;
			}
		});
	}

	private void onBackupFinished(final BackupEngine engine, final MtpDevice device, final BackupEngine.BackupResult result, final Throwable error) {
		setIdle();
		backupActive = false;
		cancelButton.setEnabled(false);
		updateControls();
		if (error != null) {
			logError(LangResources.get("backupAborted"), error);
			shareTree.refresh();
			String progressText = null;
			if (engine != null) {
				progressText = engine.isScanFinished()
					? LangResources.get("backupSavedBeforeAbort", engine.getSavedCount())
					: LangResources.get("connectionLostDuringScan");
			}
			showFailure(LangResources.get("backupFailed"), error, device, progressText, this::onStartBackup);
			return;
		} else {
			final String title = LangResources.get(result.cancelled() ? "backupCancelled" : "backupFinished");
			final Object[] values = { result.found(), result.copied(), result.alreadySaved(), result.duplicates(), result.errors() };
			log(title + ": " + LangResources.get("backupSummary", values));
			JOptionPane.showMessageDialog(this, title + ":\n" + LangResources.get("backupSummaryLines", values), APPLICATION_NAME,
				result.errors() > 0 ? JOptionPane.WARNING_MESSAGE : JOptionPane.INFORMATION_MESSAGE);
		}
		shareTree.refresh();

		if (engine != null && !result.cancelled()) {
			startDeletionPhase(engine, device);
		}
	}

	// ---------------------------------------------------------------- deletion on the device

	/**
	 * Determines the backed-up files to delete from the device according to the device's setting,
	 * asks for confirmation and deletes them. Runs only after a complete (not cancelled) backup.
	 */
	private void startDeletionPhase(final BackupEngine engine, final MtpDevice device) {
		final HandyBackupConfiguration.DeviceAssignment assignment = configuration.getAssignment(device.serialNumber());
		if (assignment == null || assignment.deleteAfterDays() < 0 || currentDevice != device) {
			return;
		}
		final int days = assignment.deleteAfterDays();

		cancelRequested = false;
		backupActive = true;
		runningBackup = engine;
		cancelButton.setEnabled(true);
		updateControls();
		setBusy(LangResources.get("checkingDeletableFiles"));
		log(days == 0
			? LangResources.get("checkingAllForDeletion")
			: LangResources.get("checkingOlderForDeletion", days));
		ioExecutor.submit(() -> {
			try {
				final List<BackupEngine.DeletionCandidate> candidates = engine.findDeletionCandidates(days);
				SwingUtilities.invokeLater(() -> onDeletionCandidatesFound(engine, device, days, candidates, null));
			} catch (final Throwable e) {
				SwingUtilities.invokeLater(() -> onDeletionCandidatesFound(engine, device, days, null, e));
			}
		});
	}

	private void onDeletionCandidatesFound(final BackupEngine engine, final MtpDevice device, final int days, final List<BackupEngine.DeletionCandidate> candidates, final Throwable error) {
		if (error != null) {
			finishDeletionPhase();
			logError(LangResources.get("deletionCheckFailed"), error);
			showErrorDialog(this, LangResources.get("deletionCheckFailed"), error);
			return;
		} else if (cancelRequested) {
			finishDeletionPhase();
			log(LangResources.get("phoneDeletionCancelledNothingDeleted"));
			return;
		} else if (candidates.isEmpty()) {
			finishDeletionPhase();
			log(LangResources.get("noFilesToDelete"));
			return;
		}

		long totalBytes = 0;
		for (final BackupEngine.DeletionCandidate candidate : candidates) {
			totalBytes += Math.max(0, candidate.file().size());
		}
		final ShareDefinition share = getAssignedShare();
		final String question = (days == 0
			? LangResources.get("autoDeleteQuestionAll", candidates.size(), formatSize(totalBytes), device.name())
			: LangResources.get("autoDeleteQuestionDays", candidates.size(), formatSize(totalBytes), days, device.name()))
			+ "\n\n" + LangResources.get("autoDeleteVerified", share == null ? LangResources.get("theShare") : share.getDisplayPath())
			+ "\n" + LangResources.get("phoneDeletionIrreversible");
		final Object[] options = { LangResources.get("deleteFromPhone"), LangResources.get("doNotDelete") };
		setBusy(LangResources.get("waitingForConfirmation"));
		final int answer = JOptionPane.showOptionDialog(this, question, APPLICATION_NAME, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
		if (answer != 0) {
			finishDeletionPhase();
			log(LangResources.get("phoneDeletionSkipped"));
			return;
		}

		setBusy(LangResources.get("deletingOnPhone"));
		ioExecutor.submit(() -> {
			try {
				final BackupEngine.DeletionResult result = engine.deleteFromDevice(candidates);
				SwingUtilities.invokeLater(() -> onDeletionFinished(result, null));
			} catch (final Throwable e) {
				SwingUtilities.invokeLater(() -> onDeletionFinished(null, e));
			}
		});
	}

	private void onDeletionFinished(final BackupEngine.DeletionResult result, final Throwable error) {
		finishDeletionPhase();
		if (error != null) {
			logError(LangResources.get("phoneDeletionFailed"), error);
			showFailure(LangResources.get("phoneDeletionFailed"), error, currentDevice, LangResources.get("deletionAbortedProgress"), null);
		} else {
			final String title = LangResources.get(result.cancelled() ? "phoneDeletionCancelled" : "phoneDeletionFinished");
			final Object[] values = { result.deleted(), formatSize(result.deletedBytes()), result.skipped(), result.errors() };
			log(title + ": " + LangResources.get("phoneDeletionSummary", values));
			if (result.errors() > 0 || result.skipped() > 0) {
				JOptionPane.showMessageDialog(this, title + ":\n" + LangResources.get("phoneDeletionSummaryLines", values), APPLICATION_NAME, JOptionPane.WARNING_MESSAGE);
			}
		}
		deviceTree.refresh();
	}

	private void finishDeletionPhase() {
		setIdle();
		backupActive = false;
		runningBackup = null;
		cancelButton.setEnabled(false);
		updateControls();
	}

	private static String formatSize(final long bytes) {
		if (bytes >= 1024L * 1024 * 1024) {
			return String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024));
		} else {
			return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024));
		}
	}

	// ---------------------------------------------------------------- manual deletion on the device

	/**
	 * Backup status of a file selected for manual deletion.
	 *
	 * @param entry     the file in the device tree
	 * @param backedUp  true if saved in the share, false if not, null if it could not be checked
	 */
	private record ManualDeletionItem(TreeEntry entry, Boolean backedUp) {
	}

	/**
	 * Deletes the selected files from the device after a confirmation. Before asking, the backup index
	 * of the assigned share is checked, so the question can warn about files that are not backed up.
	 */
	private void deleteDeviceFiles(final List<TreeEntry> entries) {
		if (backupActive) {
			log(LangResources.get("noDeleteDuringBackup"));
			return;
		}
		final MtpDevice device = currentDevice;
		if (device == null) {
			return;
		}
		final ShareDefinition share = getAssignedShare();
		final List<TreeEntry> files = new ArrayList<>(entries);

		backupActive = true;
		updateControls();
		setBusy(LangResources.get("checkingBackupStatus"));
		ioExecutor.submit(() -> {
			final List<ManualDeletionItem> items = new ArrayList<>();
			String indexProblem = null;
			BackupIndex index = null;
			if (share == null) {
				indexProblem = LangResources.get("statusUnknownNoShare");
			} else if (device.serialNumber() == null) {
				indexProblem = LangResources.get("statusUnknownNoSerial");
			} else {
				try {
					index = BackupIndex.load(getSmbConnection(share), share);
				} catch (final Throwable e) {
					indexProblem = LangResources.get("statusUnknownIndexError", e.getMessage());
				}
			}
			for (final TreeEntry file : files) {
				final Boolean backedUp = index == null ? null
					: Boolean.valueOf(index.findBySource(device.serialNumber(), file.path(), file.size(), BackupEngine.toEpochMillis(file.modified())) != null);
				items.add(new ManualDeletionItem(file, backedUp));
			}
			final String finalIndexProblem = indexProblem;
			SwingUtilities.invokeLater(() -> confirmManualDeletion(device, items, finalIndexProblem));
		});
	}

	private void confirmManualDeletion(final MtpDevice device, final List<ManualDeletionItem> items, final String indexProblem) {
		if (currentDevice != device) {
			finishManualDeletion(null);
			return;
		}

		long totalBytes = 0;
		int notBackedUp = 0;
		for (final ManualDeletionItem item : items) {
			totalBytes += Math.max(0, item.entry().size());
			if (Boolean.FALSE.equals(item.backedUp())) {
				notBackedUp++;
			}
		}

		final StringBuilder question = new StringBuilder();
		if (items.size() == 1) {
			final TreeEntry entry = items.get(0).entry();
			question.append(LangResources.get("manualDeleteQuestionSingle", entry.name(), LazyTree.formatSize(entry.size()), device.name(), entry.path()));
		} else {
			question.append(LangResources.get("manualDeleteQuestionMultiple", items.size(), formatSize(totalBytes), device.name()));
			final int shownNames = Math.min(items.size(), 10);
			for (int i = 0; i < shownNames; i++) {
				question.append("\n  ").append(items.get(i).entry().name());
			}
			if (items.size() > shownNames) {
				question.append("\n  ").append(LangResources.get("andMore", items.size() - shownNames));
			}
		}
		question.append("\n\n");
		if (indexProblem != null) {
			question.append(LangResources.get("attention", indexProblem));
		} else if (notBackedUp == 0) {
			question.append(LangResources.get(items.size() == 1 ? "fileIsBackedUp" : "allFilesBackedUp"));
		} else if (items.size() == 1) {
			question.append(LangResources.get("fileNotBackedUp"));
		} else {
			question.append(LangResources.get("filesNotBackedUp", notBackedUp));
		}
		question.append("\n").append(LangResources.get("phoneDeletionIrreversible"));

		final Object[] options = { LangResources.get("deleteButton"), LangResources.get("cancel") };
		setBusy(LangResources.get("waitingForConfirmation"));
		final int answer = JOptionPane.showOptionDialog(this, question.toString(), LangResources.get("deleteFromPhone"), JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE, null, options, options[1]);
		if (answer != 0) {
			finishManualDeletion(null);
			return;
		}

		setBusy(LangResources.get("deletingOnPhone"));
		ioExecutor.submit(() -> {
			int deleted = 0;
			int errors = 0;
			final MtpFileSystem fileSystem = currentFileSystem;
			for (final ManualDeletionItem item : items) {
				final TreeEntry entry = item.entry();
				try {
					if (fileSystem == null) {
						throw new IOException(LangResources.get("deviceNotOpen"));
					}
					fileSystem.delete(entry.path(), false);
					deleted++;
					SwingUtilities.invokeLater(() -> log(LangResources.get(Boolean.FALSE.equals(item.backedUp()) ? "deletedFromPhoneNotBackedUp" : "deletedFromPhone", entry.path())));
				} catch (final Throwable e) {
					errors++;
					SwingUtilities.invokeLater(() -> logError(LangResources.get("deleteErrorFor", entry.path()), e));
				}
			}
			final int finalDeleted = deleted;
			final int finalErrors = errors;
			SwingUtilities.invokeLater(() -> {
				log(LangResources.get("manualDeleteSummary", finalDeleted, finalErrors));
				if (finalErrors > 0) {
					JOptionPane.showMessageDialog(this, LangResources.get("filesCouldNotBeDeleted", finalErrors), APPLICATION_NAME, JOptionPane.WARNING_MESSAGE);
				}
				finishManualDeletion(deviceTree);
			});
		});
	}

	/**
	 * @param treeToRefresh tree to reload after files were deleted, null if nothing was deleted
	 */
	private void finishManualDeletion(final LazyTree treeToRefresh) {
		setIdle();
		backupActive = false;
		updateControls();
		if (treeToRefresh != null) {
			treeToRefresh.refresh();
		}
	}

	// ---------------------------------------------------------------- manual deletion in the share

	/**
	 * File in the share selected for manual deletion.
	 *
	 * @param entry           the file in the share tree (path relative to the share root)
	 * @param relativePath    path relative to the share's base path, as used in the index
	 * @param onCurrentDevice true if the file's source still exists on the connected device, false if not, null if unknown
	 */
	private record ShareDeletionItem(TreeEntry entry, String relativePath, Boolean onCurrentDevice) {
	}

	/**
	 * Deletes the selected files from the share after a confirmation. The confirmation shows whether the
	 * files still exist on the connected device. Files of the index folder cannot be deleted.
	 */
	private void deleteShareFiles(final List<TreeEntry> entries) {
		if (backupActive) {
			log(LangResources.get("noDeleteDuringBackup"));
			return;
		}
		final ShareDefinition share = getAssignedShare();
		if (share == null) {
			return;
		}
		final MtpDevice device = currentDevice;

		final List<TreeEntry> files = new ArrayList<>();
		for (final TreeEntry entry : entries) {
			final String relativePath = toShareRelativePath(share, entry.path());
			if (relativePath == null || relativePath.equals(BackupIndex.INDEX_FOLDER) || relativePath.startsWith(BackupIndex.INDEX_FOLDER + "/")) {
				log(LangResources.get("notDeletedIndexFolder", entry.path()));
			} else {
				files.add(entry);
			}
		}
		if (files.isEmpty()) {
			return;
		}

		backupActive = true;
		updateControls();
		setBusy(LangResources.get("checkingFileOrigin"));
		ioExecutor.submit(() -> {
			final List<ShareDeletionItem> items = new ArrayList<>();
			String indexProblem = null;
			BackupIndex index = null;
			try {
				index = BackupIndex.load(getSmbConnection(share), share);
			} catch (final Throwable e) {
				indexProblem = LangResources.get("indexReadError", e.getMessage());
			}
			final MtpFileSystem fileSystem = currentFileSystem;
			for (final TreeEntry file : files) {
				final String relativePath = toShareRelativePath(share, file.path());
				Boolean onCurrentDevice = null;
				if (index != null && fileSystem != null && device != null && device.serialNumber() != null) {
					onCurrentDevice = Boolean.FALSE;
					for (final BackupIndex.Entry indexEntry : index.findByTargetPath(relativePath)) {
						if (device.serialNumber().equals(indexEntry.serialNumber())) {
							try {
								if (fileSystem.getFile(indexEntry.sourcePath()) != null) {
									onCurrentDevice = Boolean.TRUE;
									break;
								}
							} catch (@SuppressWarnings("unused") final Exception e) {
								onCurrentDevice = null;
							}
						}
					}
				}
				items.add(new ShareDeletionItem(file, relativePath, onCurrentDevice));
			}
			final String finalIndexProblem = indexProblem;
			SwingUtilities.invokeLater(() -> confirmShareDeletion(share, items, finalIndexProblem));
		});
	}

	private void confirmShareDeletion(final ShareDefinition share, final List<ShareDeletionItem> items, final String indexProblem) {
		if (share != getAssignedShare()) {
			finishManualDeletion(null);
			return;
		}

		long totalBytes = 0;
		int onDevice = 0;
		int notOnDevice = 0;
		for (final ShareDeletionItem item : items) {
			totalBytes += Math.max(0, item.entry().size());
			if (Boolean.TRUE.equals(item.onCurrentDevice())) {
				onDevice++;
			} else if (Boolean.FALSE.equals(item.onCurrentDevice())) {
				notOnDevice++;
			}
		}

		final StringBuilder question = new StringBuilder();
		if (items.size() == 1) {
			final TreeEntry entry = items.get(0).entry();
			question.append(LangResources.get("shareDeleteQuestionSingle", entry.name(), LazyTree.formatSize(entry.size()),
				share.getDisplayPath() + "\\" + items.get(0).relativePath().replace("/", "\\")));
		} else {
			question.append(LangResources.get("shareDeleteQuestionMultiple", items.size(), formatSize(totalBytes), share.getDisplayPath()));
			final int shownNames = Math.min(items.size(), 10);
			for (int i = 0; i < shownNames; i++) {
				question.append("\n  ").append(items.get(i).relativePath());
			}
			if (items.size() > shownNames) {
				question.append("\n  ").append(LangResources.get("andMore", items.size() - shownNames));
			}
		}
		question.append("\n\n");
		if (indexProblem != null) {
			question.append(LangResources.get("attention", indexProblem)).append("\n").append(LangResources.get("originUnknown")).append("\n");
		} else if (currentDevice == null) {
			question.append(LangResources.get("noPhoneOriginUnknown")).append("\n");
		} else {
			if (onDevice > 0) {
				question.append(items.size() == 1 ? LangResources.get("fileStillOnPhone") : LangResources.get("filesStillOnPhone", onDevice)).append("\n");
			}
			if (notOnDevice > 0) {
				question.append(items.size() == 1 ? LangResources.get("fileNotOnPhone") : LangResources.get("filesNotOnPhone", notOnDevice)).append("\n");
			}
		}
		question.append(LangResources.get("shareDeletionIrreversible"));

		final Object[] options = { LangResources.get("deleteButton"), LangResources.get("cancel") };
		setBusy(LangResources.get("waitingForConfirmation"));
		final int answer = JOptionPane.showOptionDialog(this, question.toString(), LangResources.get("deleteFromBackup"), JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE, null, options, options[1]);
		if (answer != 0) {
			finishManualDeletion(null);
			return;
		}

		setBusy(LangResources.get("deletingOnShare"));
		ioExecutor.submit(() -> {
			int deleted = 0;
			int errors = 0;
			String indexError = null;
			BackupIndex index = null;
			try {
				final SmbConnection connection = getSmbConnection(share);
				// Reloaded, so the index is current when it is saved again
				index = BackupIndex.load(connection, share);
				for (final ShareDeletionItem item : items) {
					try {
						connection.deleteFile(item.entry().path());
						deleted++;
						final int removedEntries = index.removeByTargetPath(item.relativePath());
						SwingUtilities.invokeLater(() -> log(removedEntries > 0 ? LangResources.get("deletedFromBackupWithIndex", item.relativePath(), removedEntries) : LangResources.get("deletedFromBackup", item.relativePath())));
					} catch (final Throwable e) {
						errors++;
						SwingUtilities.invokeLater(() -> logError(LangResources.get("deleteErrorFor", item.relativePath()), e));
					}
				}
			} catch (final Throwable e) {
				errors++;
				indexError = e.getMessage();
			} finally {
				if (index != null && index.isChanged()) {
					try {
						index.save(getSmbConnection(share), share);
					} catch (final Throwable e) {
						indexError = LangResources.get("indexSaveFailed", e.getMessage());
					}
				}
			}
			final int finalDeleted = deleted;
			final int finalErrors = errors;
			final String finalIndexError = indexError;
			SwingUtilities.invokeLater(() -> {
				if (finalIndexError != null) {
					log(LangResources.get("logError", finalIndexError));
				}
				log(LangResources.get("shareDeleteSummary", finalDeleted, finalErrors));
				if (finalErrors > 0 || finalIndexError != null) {
					JOptionPane.showMessageDialog(this, LangResources.get("deleteErrorsOccurred"), APPLICATION_NAME, JOptionPane.WARNING_MESSAGE);
				}
				finishManualDeletion(shareTree);
			});
		});
	}

	/**
	 * Converts a path relative to the share root (as used in the share tree) into a path relative to the
	 * share's base path (as used in the index). Returns null if the path is outside the base path.
	 */
	private static String toShareRelativePath(final ShareDefinition share, final String shareRootPath) {
		final String basePath = share.getBasePath();
		if (basePath.isEmpty()) {
			return shareRootPath;
		} else if (shareRootPath.startsWith(basePath + "/")) {
			return shareRootPath.substring(basePath.length() + 1);
		} else {
			return null;
		}
	}

	// ---------------------------------------------------------------- viewing files

	/**
	 * Opens a file of the device tree: downloads it into a temporary folder and opens it with the default application.
	 */
	private void openDeviceFile(final TreeEntry entry) {
		if (!checkViewPossible()) {
			return;
		}
		final MtpDevice device = currentDevice;
		setBusy(LangResources.get("loadingForView", entry.name()));
		ioExecutor.submit(() -> {
			try {
				final MtpFileSystem fileSystem = currentFileSystem;
				if (fileSystem == null || currentDevice != device) {
					throw new Exception(LangResources.get("deviceNotOpen"));
				}
				final File localFile = createViewFile(entry.name());
				fileSystem.download(entry.path(), localFile);
				SwingUtilities.invokeLater(() -> openWithDefaultApplication(entry, localFile, null));
			} catch (final Throwable e) {
				SwingUtilities.invokeLater(() -> openWithDefaultApplication(entry, null, e));
			}
		});
	}

	/**
	 * Opens a file of the share tree: downloads it into a temporary folder and opens it with the default application.
	 */
	private void openShareFile(final TreeEntry entry) {
		if (!checkViewPossible()) {
			return;
		}
		final ShareDefinition share = getAssignedShare();
		if (share == null) {
			return;
		}
		setBusy(LangResources.get("loadingForView", entry.name()));
		ioExecutor.submit(() -> {
			try {
				final SmbConnection connection = getSmbConnection(share);
				final File localFile = createViewFile(entry.name());
				final File partFile = new File(localFile.getPath() + ".part");
				try (InputStream inputStream = connection.downloadFile(entry.path())) {
					Files.copy(inputStream, partFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
				} catch (final Exception e) {
					Files.deleteIfExists(partFile.toPath());
					throw e;
				}
				Files.move(partFile.toPath(), localFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
				if (entry.modified() != null) {
					localFile.setLastModified(entry.modified().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
				}
				SwingUtilities.invokeLater(() -> openWithDefaultApplication(entry, localFile, null));
			} catch (final Throwable e) {
				SwingUtilities.invokeLater(() -> openWithDefaultApplication(entry, null, e));
			}
		});
	}

	/**
	 * Viewing uses the IO thread, so it is not possible while a backup or deletion is running
	 * (the request would wait until the end).
	 */
	private boolean checkViewPossible() {
		if (backupActive) {
			log(LangResources.get("noOpenDuringBackup"));
			return false;
		} else if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
			showError(LangResources.get("openNotSupported"));
			return false;
		} else {
			return true;
		}
	}

	/**
	 * Returns a not yet existing local file with the original file name (the default application
	 * is chosen by its extension and shows its name). Every file gets its own subfolder, so files
	 * with the same name from different folders do not collide. Called in the IO thread.
	 */
	private File createViewFile(final String fileName) throws IOException {
		if (viewFolder == null || !Files.isDirectory(viewFolder)) {
			viewFolder = Files.createTempDirectory(VIEW_FOLDER_PREFIX);
		}
		final Path fileFolder = viewFolder.resolve(Integer.toString(++viewFileCounter));
		Files.createDirectories(fileFolder);
		final String safeName = fileName.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
		return fileFolder.resolve(safeName.isBlank() ? "file" : safeName).toFile();
	}

	private void openWithDefaultApplication(final TreeEntry entry, final File localFile, final Throwable error) {
		setIdle();
		if (error != null) {
			logError(LangResources.get("fileLoadFailed", entry.name()), error);
			showFailure(LangResources.get("fileLoadFailed", entry.name()), error, currentDevice, null, null);
			return;
		}
		// Desktop.open may block for a moment (starting the application), so it runs in its own thread
		final Thread openThread = new Thread(() -> {
			try {
				Desktop.getDesktop().open(localFile);
			} catch (final Throwable e) {
				SwingUtilities.invokeLater(() -> {
					logError(LangResources.get("fileOpenFailed", entry.name()), e);
					showErrorDialog(this, LangResources.get("fileOpenFailed", entry.name()), e);
				});
			}
		}, "HandyBackup-Open");
		openThread.setDaemon(true);
		openThread.start();
	}

	/**
	 * Deletes a folder with viewed files. Files still opened by an application cannot be deleted
	 * on Windows; they are removed at a later application start.
	 */
	private static void deleteViewFolder(final Path folder) {
		if (folder == null || !Files.isDirectory(folder)) {
			return;
		}
		try (var paths = Files.walk(folder)) {
			paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (@SuppressWarnings("unused") final IOException e) {
					// still opened by the viewer, removed at a later start
				}
			});
		} catch (@SuppressWarnings("unused") final IOException e) {
			// ignore
		}
	}

	/**
	 * Removes leftovers of viewed files from former application runs.
	 */
	private static void deleteOldViewFolders() {
		final Path tempDirectory = Paths.get(System.getProperty("java.io.tmpdir"));
		try (DirectoryStream<Path> folders = Files.newDirectoryStream(tempDirectory, VIEW_FOLDER_PREFIX + "*")) {
			for (final Path folder : folders) {
				deleteViewFolder(folder);
			}
		} catch (@SuppressWarnings("unused") final Exception e) {
			// ignore
		}
	}

	// ---------------------------------------------------------------- misc

	private void updateControls() {
		final boolean ready = currentDevice != null && getAssignedShare() != null;
		startButton.setEnabled(ready && !backupActive);
		cancelButton.setEnabled(backupActive && cancelButton.isEnabled());
		deviceCombo.setEnabled(!backupActive);
		shareCombo.setEnabled(currentDevice != null && currentDevice.serialNumber() != null && !backupActive);
		final boolean deleteSettingEditable = ready && !backupActive;
		deleteAfterDaysLabel.setEnabled(deleteSettingEditable);
		deleteAfterDaysSpinner.setEnabled(deleteSettingEditable);
		refreshDevicesButton.setEnabled(!backupActive);
		manageSharesButton.setEnabled(!backupActive);
		configButton.setEnabled(!backupActive);
	}

	private void setBusy(final String text) {
		progressBar.setIndeterminate(true);
		progressBar.setString("");
		statusLabel.setText(text);
	}

	private void setIdle() {
		progressBar.setIndeterminate(false);
		progressBar.setValue(0);
		progressBar.setString("");
		statusLabel.setText(" ");
	}

	private void log(final String message) {
		logArea.append(LocalDateTime.now().format(LOG_TIME_FORMAT) + "  " + message + "\n");
		logArea.setCaretPosition(logArea.getDocument().getLength());
	}

	/**
	 * Logs an error as "ERROR: &lt;text&gt;: &lt;error message&gt;" in the current language.
	 */
	private void logError(final String text, final Throwable error) {
		log(LangResources.get("logError", text + ": " + (error == null ? "" : error.getMessage())));
	}

	private void showError(final String message) {
		JOptionPane.showMessageDialog(this, message, APPLICATION_NAME, JOptionPane.ERROR_MESSAGE);
	}

	/**
	 * Shows an error. A lost connection or denied access to the device is explained with instructions for the user;
	 * the technical details (stack trace) are only shown on request. Other errors are shown in the ErrorDialog.
	 * Must be called in the event dispatch thread.
	 *
	 * @param message      describes what failed
	 * @param device       device the error refers to, may be null
	 * @param progressText what was already done before the error (e.g. number of saved files), may be null
	 * @param retryAction  started when the user chooses "Try again" and the device is available again;
	 *                     null if the dialog offers no retry
	 */
	private void showFailure(final String message, final Throwable error, final MtpDevice device, final String progressText, final Runnable retryAction) {
		final MtpErrorType errorType = MtpErrorType.classify(error);
		if (errorType == MtpErrorType.OTHER) {
			showErrorDialog(this, message, error);
			return;
		}

		if (errorType == MtpErrorType.CONNECTION_LOST) {
			// The device's file system is unusable now; it is reopened when the device is found again
			deviceTree.clear(LangResources.get("deviceConnectionLostTree"));
			ioExecutor.submit(this::closeFileSystem);
		}

		final String deviceName = device == null ? "" : device.name();
		final StringBuilder text = new StringBuilder(message).append("\n\n");
		text.append(LangResources.get(errorType == MtpErrorType.CONNECTION_LOST ? "connectionLostText" : "accessDeniedText", deviceName));
		if (progressText != null) {
			text.append("\n\n").append(progressText);
		}

		final boolean retryPossible = retryAction != null && device != null && device.serialNumber() != null;
		final String retryOption = LangResources.get("retryButton");
		final String detailsOption = LangResources.get("detailsButton");
		final String closeOption = LangResources.get("close");
		final Object[] options = retryPossible ? new Object[] { retryOption, detailsOption, closeOption } : new Object[] { detailsOption, closeOption };
		while (true) {
			final int answer = JOptionPane.showOptionDialog(this, text.toString(), APPLICATION_NAME, JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
			final Object chosen = answer >= 0 ? options[answer] : closeOption;
			if (chosen == detailsOption) {
				// Back to this dialog after the details were closed
				showErrorDialog(this, message, error);
			} else if (chosen == retryOption) {
				waitForDeviceAndRun(device, retryAction);
				return;
			} else {
				// Show the actual device state (device may be gone or reconnected meanwhile)
				refreshDevices();
				return;
			}
		}
	}

	/**
	 * Waits (with a cancelable dialog) until the device with the serial number of the given device is connected
	 * and readable, selects it and runs the action. Must be called in the event dispatch thread.
	 */
	private void waitForDeviceAndRun(final MtpDevice device, final Runnable action) {
		final String serialNumber = device.serialNumber();
		final AtomicBoolean stopped = new AtomicBoolean(false);
		final AtomicReference<List<MtpDevice>> foundDevices = new AtomicReference<>();

		final JOptionPane waitPane = new JOptionPane(LangResources.get("waitingForDeviceText", device.name()), JOptionPane.INFORMATION_MESSAGE,
			JOptionPane.DEFAULT_OPTION, null, new Object[] { LangResources.get("cancel") });
		final JDialog waitDialog = waitPane.createDialog(this, APPLICATION_NAME);

		setBusy(LangResources.get("waitingForDevice", device.name()));
		ioExecutor.submit(() -> {
			while (!stopped.get()) {
				try {
					if (mtpManager != null) {
						final List<MtpDevice> devices = new ArrayList<>();
						boolean found = false;
						for (final MtpDevice connectedDevice : mtpManager.getConnectedDevices()) {
							if (isUsableDevice(connectedDevice)) {
								devices.add(connectedDevice);
								found |= serialNumber.equals(connectedDevice.serialNumber());
							}
						}
						if (found) {
							SwingUtilities.invokeLater(() -> {
								if (!stopped.getAndSet(true)) {
									foundDevices.set(devices);
									waitDialog.dispose();
								}
							});
							return;
						}
					}
				} catch (@SuppressWarnings("unused") final Throwable e) {
					// Device list temporarily not available (e.g. while the device is being reconnected): keep waiting
				}
				try {
					Thread.sleep(DEVICE_POLL_INTERVAL_MILLIS);
				} catch (@SuppressWarnings("unused") final InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		});

		// Blocks until the device was found or the user canceled
		waitDialog.setVisible(true);
		stopped.set(true);
		setIdle();

		final List<MtpDevice> devices = foundDevices.get();
		if (devices == null) {
			log(LangResources.get("waitForDeviceCancelled"));
			refreshDevices();
		} else {
			log(LangResources.get("deviceReconnected", device.name()));
			// Reopens the device's file system in the IO thread before the action's own IO tasks run
			showDevices(devices, serialNumber);
			action.run();
		}
	}

	/**
	 * Shows an error with its details (stack trace) in the ErrorDialog. Must be called in the event dispatch thread.
	 *
	 * @param parent  window the error occurred in (or a component inside it); the dialog is owned by that window,
	 *                so it stays in front of it, even if it is a modal dialog
	 * @param message describes what failed; shown together with the error's own message, the original error is kept as cause for the details
	 */
	public static void showErrorDialog(final Component parent, final String message, final Throwable error) {
		final String errorText = describe(error);
		final String visibleMessage = errorText == null ? message : message + ":\n" + errorText;
		new ErrorDialog(parent instanceof final Window window ? window : SwingUtilities.getWindowAncestor(parent), APPLICATION_NAME, VERSION == null ? null : VERSION.toString(), APPLICATION_ERROR_EMAIL_ADDRESS, new Exception(visibleMessage, error)).open();
	}

	/**
	 * Short description of an error. Errors like NoClassDefFoundError only carry a class name as
	 * message, so their type is prepended.
	 */
	private static String describe(final Throwable error) {
		if (error == null) {
			return null;
		} else if (error instanceof Exception && error.getMessage() != null && !error.getMessage().isBlank()) {
			return error.getMessage();
		} else {
			return error.getClass().getSimpleName() + (error.getMessage() != null ? ": " + error.getMessage() : "");
		}
	}

	// ---------------------------------------------------------------- configuration, help and update

	private void openConfiguration() {
		try {
			final ConfigurationProperties applicationConfiguration = configuration.getConfigurationProperties();
			final ImageIcon applicationIcon = getImageIcon("HandyBackup.png");
			final ApplicationConfigurationDialog dialog = new ApplicationConfigurationDialog(this, APPLICATION_NAME, APPLICATION_STARTUPCLASS_NAME, VERSION, VERSION_BUILDTIME, applicationConfiguration,
				readResource("/images/icons/HandyBackup.ico"), applicationIcon == null ? null : applicationIcon.getImage(), VERSIONINFO_DOWNLOAD_URL, TRUSTED_UPDATE_CA_CERTIFICATES, null);
			if (dialog.open() == Result.OK) {
				applicationConfiguration.save();
			}
		} catch (final Exception e) {
			showErrorDialog(this, LangResources.get("configurationOpenFailed"), e);
		}
	}

	private void openHelp() {
		try {
			new HelpDialog(this, APPLICATION_NAME + " (" + VERSION + ") " + LangResources.get("help"), configuration.getConfigurationProperties()).open();
		} catch (final Exception e) {
			showErrorDialog(this, LangResources.get("helpOpenFailed"), e);
		}
	}

	private void runDailyUpdateCheck() {
		if (Utilities.isNotBlank(VERSIONINFO_DOWNLOAD_URL) && dailyUpdateCheckIsPending()) {
			setDailyUpdateCheckStatus(true);
			try {
				final ConfigurationProperties applicationConfiguration = configuration.getConfigurationProperties();
				if (ApplicationUpdateUtilities.checkForNewVersionAvailable(VERSIONINFO_DOWNLOAD_URL, applicationConfiguration.getProxyConfiguration(), APPLICATION_NAME, VERSION) != null) {
					ApplicationUpdateUtilities.executeUpdate(this, VERSIONINFO_DOWNLOAD_URL, applicationConfiguration.getProxyConfiguration(), APPLICATION_NAME, VERSION, TRUSTED_UPDATE_CA_CERTIFICATES, null, null, null, true, false);
				}
			} catch (final Exception e) {
				showErrorDialog(this, LangResources.get("updateCheckFailed"), e);
			}
		}
	}

	@Override
	protected void setDailyUpdateCheckStatus(final boolean checkboxStatus) {
		try {
			final ConfigurationProperties applicationConfiguration = configuration.getConfigurationProperties();
			applicationConfiguration.set(ConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK, checkboxStatus);
			applicationConfiguration.set(ConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK, LocalDateTime.now().plusDays(1));
			applicationConfiguration.save();
		} catch (final Exception e) {
			log(LangResources.get("logError", LangResources.get("updateSettingSaveFailed", e.getMessage())));
		}
	}

	@Override
	protected Boolean isDailyUpdateCheckActivated() {
		try {
			return configuration.getConfigurationProperties().getBoolean(ConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK);
		} catch (@SuppressWarnings("unused") final Exception e) {
			return false;
		}
	}

	private boolean dailyUpdateCheckIsPending() {
		try {
			final ConfigurationProperties applicationConfiguration = configuration.getConfigurationProperties();
			final LocalDateTime nextCheck = applicationConfiguration.getDate(ConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK);
			return applicationConfiguration.getBoolean(ConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK) && (nextCheck == null || nextCheck.isBefore(LocalDateTime.now()));
		} catch (@SuppressWarnings("unused") final Exception e) {
			return false;
		}
	}

	/**
	 * Image from the resources "images/icons/" (in this application or one of its libraries).
	 *
	 * @return the image, or null if it is not available
	 */
	static ImageIcon getImageIcon(final String imageName) {
		final byte[] imageData = readResource("/images/icons/" + imageName);
		return imageData == null ? null : new ImageIcon(imageData);
	}

	private static byte[] readResource(final String resourcePath) {
		try (InputStream inputStream = HandyBackup.class.getResourceAsStream(resourcePath)) {
			return inputStream == null ? null : IoUtilities.toByteArray(inputStream);
		} catch (@SuppressWarnings("unused") final Exception e) {
			return null;
		}
	}

	/**
	 * Small button showing an icon; falls back to the text if the icon is not available.
	 */
	private static JButton createIconButton(final String imageName, final String text) {
		final ImageIcon icon = getImageIcon(imageName);
		final JButton button = icon == null ? new JButton(text) : new JButton(icon);
		button.setToolTipText(text);
		if (icon != null) {
			button.setMargin(new Insets(2, 2, 2, 2));
		}
		return button;
	}

	private void onExit() {
		final BackupEngine backup = runningBackup;
		if (backup != null) {
			if (JOptionPane.showConfirmDialog(this, LangResources.get("backupStillRunning"), APPLICATION_NAME, JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) {
				return;
			}
			cancelRequested = true;
			backup.cancel();
			setBusy(LangResources.get("waitingForCurrentFile"));
		}
		setEnabled(false);
		// Runs after a running backup has finished its current file and saved the index
		ioExecutor.submit(() -> {
			closeFileSystem();
			closeSmbConnections();
			deleteViewFolder(viewFolder);
			if (mtpManager != null) {
				try {
					mtpManager.close();
				} catch (@SuppressWarnings("unused") final Exception e) {
					// ignore
				}
			}
			SwingUtilities.invokeLater(() -> {
				dispose();
				System.exit(0);
			});
		});
	}
}

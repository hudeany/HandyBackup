package de.soderer.handybackup;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
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
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

import de.soderer.applicationbase.ApplicationConfigurationDialog;
import de.soderer.applicationbase.ApplicationConfigurationProperties;
import de.soderer.applicationbase.ApplicationUpdateUtilities;
import de.soderer.applicationbase.UpdateableGuiApplication;
import de.soderer.handybackup.mtp.MtpDevice;
import de.soderer.handybackup.mtp.MtpErrorType;
import de.soderer.handybackup.mtp.MtpFile;
import de.soderer.handybackup.mtp.MtpFileSystem;
import de.soderer.handybackup.mtp.MtpManager;
import de.soderer.remote.DirectoryListing;
import de.soderer.remote.FileInfo;
import de.soderer.remote.FolderInfo;
import de.soderer.swing.ErrorDialog;
import de.soderer.swing.ModernUi;
import de.soderer.utilities.ConfigurationProperties;
import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Result;
import de.soderer.utilities.StringUtilities;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.Version;

/**
 * HandyBackup: backs up images, videos and contacts of Android phones (connected via USB in "File transfer"
 * mode, MTP) into Samba shares or folders on the local computer.
 *
 * Each device is identified by its MTP serial number and assigned to one backup target ("share", a Samba share
 * or a local folder, see {@link ShareDefinition}); one target may be used by several devices (e.g. old and new
 * phone of the same person).
 *
 * Left side: folder tree of the connected device (folders that are backed up are bold).
 * Right side: folder tree of the assigned share.
 * A double click on a file in either tree opens a temporary copy with the system's default application.
 * Files on the device and in the share can be deleted manually via context menu (after confirmation);
 * deleting in the share also removes the file's entries from the backup index.
 *
 * After a backup, backed-up files can be deleted from the device, configured per device
 * ("delete after days": &lt; 0 never, 0 all backed-up files, &gt; 0 files older than that).
 * Single device folders (including their subfolders) can be excluded from this deletion via the context menu
 * of the device tree; excluded folders are marked in the tree. Some folders of {@link StoragePaths} are excluded
 * by default; this default can be lifted per device in the same way.
 *
 * Further device folders can be added to the backup per device via the context menu of the device tree, together
 * with the file types to back up from them ({@link BackupFolder}). Such a folder may also be a default folder of
 * {@link StoragePaths} or one of its subfolders; the most specific folder decides which files are backed up.
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

	/** Text styles of the log: grey timestamp, normal text and red error lines */
	private static final SimpleAttributeSet LOG_TIME_STYLE = createLogStyle(ModernUi.MUTED, false);
	private static final SimpleAttributeSet LOG_TEXT_STYLE = createLogStyle(ModernUi.TEXT, false);
	private static final SimpleAttributeSet LOG_ERROR_STYLE = createLogStyle(ModernUi.DANGER, true);

	/** Interval for checking whether a disconnected device is available again */
	private static final long DEVICE_POLL_INTERVAL_MILLIS = 2000;

	/** Text color of device folders and files excluded from the deletion after a backup */
	private static final Color DELETE_PROTECTED_COLOR = new Color(0, 110, 160);

	/** Text color of device folders whose default exclusion from the deletion was lifted */
	private static final Color DELETE_RELEASED_COLOR = new Color(170, 100, 0);

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
	private final Map<String, BackupTarget> backupTargets = new HashMap<>();

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
	private final JButton refreshDevicesButton = new ModernUi.ModernButton(LangResources.get("searchDevices"), ModernUi.IconKind.REFRESH, ModernUi.ButtonStyle.SECONDARY);
	private final JComboBox<String> shareCombo = new JComboBox<>();
	private final JButton manageSharesButton = new ModernUi.ModernButton(LangResources.get("shares"), ModernUi.IconKind.SERVER, ModernUi.ButtonStyle.SECONDARY);
	private final JButton configButton = createHeaderButton(ModernUi.IconKind.GEAR, LangResources.get("configuration"));
	private final JButton helpButton = createHeaderButton(ModernUi.IconKind.HELP, LangResources.get("help"));
	/** Device information (manufacturer, model, serial number, battery) shown as chips */
	private final JPanel deviceInfoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
	/** Path of the assigned backup target shown as chip */
	private final JPanel shareInfoPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
	private final JLabel deleteAfterDaysLabel = ModernUi.createMutedLabel(LangResources.get("deleteFromPhoneAfter"));
	private final JSpinner deleteAfterDaysSpinner = new JSpinner(new SpinnerNumberModel(HandyBackupConfiguration.DEFAULT_DELETE_AFTER_DAYS, -1, 36500, 1));
	private final JLabel deleteAfterDaysInfoLabel = new JLabel(" ");
	private final LazyTree deviceTree = new LazyTree(ioExecutor, LangResources.get("noDeviceSelected"));
	private final LazyTree shareTree = new LazyTree(ioExecutor, LangResources.get("noShareAssignedTree"));
	private final JButton refreshTreesButton = new ModernUi.ModernButton(LangResources.get("refreshView"), ModernUi.IconKind.REFRESH, ModernUi.ButtonStyle.SECONDARY);
	private final JButton startButton = new ModernUi.ModernButton(LangResources.get("startBackup"), ModernUi.IconKind.PLAY, ModernUi.ButtonStyle.PRIMARY);
	private final JButton cancelButton = new ModernUi.ModernButton(LangResources.get("cancel"), ModernUi.IconKind.STOP, ModernUi.ButtonStyle.DANGEROUS);
	private final JButton closeButton = new ModernUi.ModernButton(LangResources.get("close"), null, ModernUi.ButtonStyle.SECONDARY);
	private final JProgressBar progressBar = ModernUi.createProgressBar();
	private final JLabel statusLabel = new JLabel(" ");
	private final JTextPane logPane = new JTextPane();

	private static SimpleAttributeSet createLogStyle(final Color color, final boolean bold) {
		final SimpleAttributeSet style = new SimpleAttributeSet();
		StyleConstants.setForeground(style, color);
		StyleConstants.setBold(style, bold);
		return style;
	}

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

		de.soderer.swing.SwingUtilities.setSystemLookAndFeel();

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
			if (versionInfoLines.size() >= 3 && StringUtilities.isNotBlank(versionInfoLines.get(2))) {
				VERSIONINFO_DOWNLOAD_URL = versionInfoLines.get(2);
			}
			if (versionInfoLines.size() >= 4 && StringUtilities.isNotBlank(versionInfoLines.get(3))) {
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
		deviceTree.setFolderMenuProvider(this::addDeviceFolderMenuItems);
		deviceTree.setDecorator(this::decorateDeviceEntry);
		deviceTree.setBackupInfoProvider(this::describeDeviceBackupFolder);
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

		getContentPane().setLayout(new BorderLayout());
		getContentPane().setBackground(ModernUi.BACKGROUND);
		getContentPane().add(createHeader(applicationIcon), BorderLayout.NORTH);

		final JPanel mainPanel = new JPanel(new BorderLayout(0, 4));
		mainPanel.setBackground(ModernUi.BACKGROUND);
		mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 4, 10));
		mainPanel.add(createSelectionRow(), BorderLayout.NORTH);
		mainPanel.add(createContentArea(), BorderLayout.CENTER);
		mainPanel.add(createActionBar(), BorderLayout.SOUTH);
		getContentPane().add(mainPanel, BorderLayout.CENTER);

		setMinimumSize(new Dimension(980, 640));
		setSize(1240, 840);
		setLocationRelativeTo(null);
		updateDeviceInfo();
		updateControls();
	}

	// ---------------------------------------------------------------- layout

	/**
	 * Gradient header with logo, application name, version and the configuration and help buttons.
	 */
	private JPanel createHeader(final ImageIcon applicationIcon) {
		final ModernUi.GradientHeader header = new ModernUi.GradientHeader();
		header.add(ModernUi.createLogo(applicationIcon), BorderLayout.WEST);

		final JLabel titleLabel = new JLabel(APPLICATION_NAME);
		titleLabel.setForeground(Color.WHITE);
		titleLabel.setFont(ModernUi.baseFont().deriveFont(Font.BOLD, 21f));
		final JLabel versionLabel = new JLabel("Version " + VERSION);
		versionLabel.setForeground(new Color(255, 255, 255, 190));
		final JPanel titlePanel = new JPanel(new GridLayout(2, 1, 0, 0));
		titlePanel.setOpaque(false);
		titlePanel.add(titleLabel);
		titlePanel.add(versionLabel);
		header.add(titlePanel, BorderLayout.CENTER);

		final JPanel applicationButtonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		applicationButtonPanel.setOpaque(false);
		applicationButtonPanel.add(configButton);
		applicationButtonPanel.add(helpButton);
		final JPanel eastPanel = new JPanel(new GridBagLayout());
		eastPanel.setOpaque(false);
		eastPanel.add(applicationButtonPanel);
		header.add(eastPanel, BorderLayout.EAST);
		return header;
	}

	/**
	 * Two cards side by side: phone selection with device details and deletion setting, backup target selection.
	 */
	private JPanel createSelectionRow() {
		final JPanel deviceSelection = createRow(deviceCombo, refreshDevicesButton);
		deviceInfoPanel.setOpaque(false);
		deleteAfterDaysInfoLabel.setForeground(ModernUi.ACCENT_PRESSED);
		final JLabel unitLabel = ModernUi.createMutedLabel(LangResources.get("deleteFromPhoneAfterUnit"));
		final JPanel deleteRow = createRow(deleteAfterDaysLabel, deleteAfterDaysSpinner, unitLabel, deleteAfterDaysInfoLabel);
		final ModernUi.Card deviceCard = new ModernUi.Card(stripColon(LangResources.get("phoneLabel")), ModernUi.IconKind.PHONE, 10);
		deviceCard.setContent(createStack(deviceSelection, deviceInfoPanel, deleteRow));

		final JPanel shareSelection = createRow(shareCombo, manageSharesButton);
		shareInfoPanel.setOpaque(false);
		final ModernUi.Card shareCard = new ModernUi.Card(stripColon(LangResources.get("backupTargetLabel")), ModernUi.IconKind.SERVER, 10);
		shareCard.setContent(createStack(shareSelection, shareInfoPanel));

		final JPanel selectionRow = new JPanel(new GridBagLayout());
		selectionRow.setOpaque(false);
		final GridBagConstraints constraints = new GridBagConstraints();
		constraints.fill = GridBagConstraints.BOTH;
		constraints.weighty = 1;
		constraints.weightx = 0.58;
		selectionRow.add(deviceCard, constraints);
		constraints.weightx = 0.42;
		selectionRow.add(shareCard, constraints);
		return selectionRow;
	}

	/**
	 * Device tree and share tree as cards side by side, the log card below; both splits are resizable.
	 */
	private JSplitPane createContentArea() {
		final ModernUi.Card deviceTreeCard = createTreeCard(deviceTree, LangResources.get("deviceTreeTitle"), ModernUi.IconKind.PHONE);
		final ModernUi.Card shareTreeCard = createTreeCard(shareTree, LangResources.get("shareTreeTitle"), ModernUi.IconKind.FOLDER);
		final JSplitPane treeSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, deviceTreeCard, shareTreeCard);
		treeSplitPane.setResizeWeight(0.5);
		treeSplitPane.setContinuousLayout(true);
		ModernUi.styleSplitPane(treeSplitPane);

		logPane.setEditable(false);
		logPane.setBackground(ModernUi.LOG_BACKGROUND);
		logPane.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		logPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, ModernUi.baseFont().getSize()));
		final JScrollPane logScrollPane = new JScrollPane(logPane);
		ModernUi.styleScrollPane(logScrollPane, ModernUi.LOG_BACKGROUND);
		logScrollPane.setPreferredSize(new Dimension(400, 150));
		final ModernUi.Card logCard = new ModernUi.Card(LangResources.get("logTitle"), ModernUi.IconKind.LOG, 10);
		logCard.setContent(logScrollPane);

		final JSplitPane mainSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeSplitPane, logCard);
		mainSplitPane.setResizeWeight(0.72);
		mainSplitPane.setContinuousLayout(true);
		ModernUi.styleSplitPane(mainSplitPane);
		return mainSplitPane;
	}

	private static ModernUi.Card createTreeCard(final LazyTree tree, final String title, final ModernUi.IconKind iconKind) {
		final Component treeComponent = tree;
		if (treeComponent instanceof final JComponent jComponent) {
			jComponent.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
		}
		final JScrollPane scrollPane = new JScrollPane(treeComponent);
		ModernUi.styleScrollPane(scrollPane, Color.WHITE);
		final ModernUi.Card card = new ModernUi.Card(title, iconKind, 10);
		card.setContent(scrollPane);
		return card;
	}

	/**
	 * Floating bar with progress, status text and the action buttons.
	 */
	private ModernUi.Card createActionBar() {
		progressBar.setStringPainted(true);
		progressBar.setString("");
		progressBar.setPreferredSize(new Dimension(260, 20));
		cancelButton.setEnabled(false);
		statusLabel.setForeground(ModernUi.MUTED);

		final JPanel progressPanel = new JPanel(new GridBagLayout());
		progressPanel.setOpaque(false);
		progressPanel.add(progressBar);

		final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
		buttonPanel.setOpaque(false);
		buttonPanel.add(refreshTreesButton);
		buttonPanel.add(cancelButton);
		buttonPanel.add(startButton);
		buttonPanel.add(closeButton);

		final JPanel barContent = new JPanel(new BorderLayout(14, 0));
		barContent.setOpaque(false);
		barContent.add(progressPanel, BorderLayout.WEST);
		barContent.add(statusLabel, BorderLayout.CENTER);
		barContent.add(buttonPanel, BorderLayout.EAST);

		final ModernUi.Card actionBar = new ModernUi.Card(null, null, 8);
		actionBar.setContent(barContent);
		return actionBar;
	}

	/** Transparent row of components, vertically centered */
	private static JPanel createRow(final Component... components) {
		final JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		row.setOpaque(false);
		for (final Component component : components) {
			row.add(component);
		}
		return row;
	}

	/** Transparent panel stacking the given rows from top to bottom, left aligned */
	private static JPanel createStack(final Component... rows) {
		final JPanel stack = new JPanel(new GridBagLayout());
		stack.setOpaque(false);
		final GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.anchor = GridBagConstraints.WEST;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1;
		constraints.insets = new Insets(0, -8, 10, 0);
		for (int index = 0; index < rows.length; index++) {
			if (index == rows.length - 1) {
				constraints.insets = new Insets(0, -8, 0, 0);
				// The last row takes the remaining height, so all rows stay at the top
				constraints.weighty = 1;
				constraints.anchor = GridBagConstraints.NORTHWEST;
			}
			stack.add(rows[index], constraints);
		}
		return stack;
	}

	/** Labels like "Phone:" are used as card titles without the colon */
	private static String stripColon(final String text) {
		final String trimmed = text == null ? "" : text.trim();
		return trimmed.endsWith(":") ? trimmed.substring(0, trimmed.length() - 1).trim() : trimmed;
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

	/**
	 * Called in the IO thread. Whether a folder is backed up is not stored in the entries, but decided while rendering
	 * by {@link #describeDeviceBackupFolder(TreeEntry)}, so added or removed backup folders are shown immediately.
	 */
	private static List<TreeEntry> loadDeviceFolder(final MtpFileSystem fileSystem, final TreeEntry folder) throws Exception {
		final List<TreeEntry> result = new ArrayList<>();
		for (final MtpFile file : fileSystem.list(folder.path())) {
			result.add(new TreeEntry(file.name(), file.path(), file.directory(), file.size(), file.modified(), false));
		}
		return result;
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
		deviceInfoPanel.removeAll();
		if (device == null) {
			deviceInfoPanel.add(new ModernUi.Chip(null, LangResources.get("noDeviceSelected"), ModernUi.ChipStyle.NEUTRAL));
		} else {
			addInfoChip(LangResources.get("deviceManufacturer"), device.manufacturer(), ModernUi.ChipStyle.NEUTRAL);
			addInfoChip(LangResources.get("deviceModel"), device.model(), ModernUi.ChipStyle.NEUTRAL);
			addInfoChip(LangResources.get("deviceSerialNumber"), device.serialNumber() != null ? device.serialNumber() : LangResources.get("unknownValue"), ModernUi.ChipStyle.NEUTRAL);
			if (device.powerLevel() != null) {
				final String powerLevel = String.valueOf(device.powerLevel()).trim();
				addInfoChip(LangResources.get("deviceBattery"), powerLevel + " %", getBatteryChipStyle(powerLevel));
			}
		}
		deviceInfoPanel.revalidate();
		deviceInfoPanel.repaint();
	}

	private void addInfoChip(final String label, final String value, final ModernUi.ChipStyle style) {
		if (value != null && !value.isEmpty()) {
			deviceInfoPanel.add(new ModernUi.Chip(label, value, style));
		}
	}

	/** Green for a well charged battery, orange below 50 %, red below 20 % */
	private static ModernUi.ChipStyle getBatteryChipStyle(final String powerLevel) {
		try {
			final int level = Integer.parseInt(powerLevel.replace("%", "").trim());
			if (level < 20) {
				return ModernUi.ChipStyle.DANGEROUS;
			} else if (level < 50) {
				return ModernUi.ChipStyle.ALERT;
			} else {
				return ModernUi.ChipStyle.SUCCESSFUL;
			}
		} catch (@SuppressWarnings("unused") final NumberFormatException e) {
			return ModernUi.ChipStyle.NEUTRAL;
		}
	}

	/** Shows the path of the assigned backup target, or a warning chip if the device has no target yet */
	private void updateShareInfo(final ShareDefinition share) {
		shareInfoPanel.removeAll();
		if (share != null) {
			shareInfoPanel.add(new ModernUi.Chip(null, share.getDisplayPath(), ModernUi.ChipStyle.ACCENTUATED));
		} else if (currentDevice != null) {
			shareInfoPanel.add(new ModernUi.Chip(null, noShareText, ModernUi.ChipStyle.ALERT));
		}
		shareInfoPanel.revalidate();
		shareInfoPanel.repaint();
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
		updateShareInfo(share);
		if (share == null) {
			shareTree.clear(LangResources.get("noShareAssignedTree"));
			return;
		}
		shareTree.clear(LangResources.get("connectingTo", share.getDisplayPath()));
		ioExecutor.submit(() -> {
			try {
				final BackupTarget connection = getBackupTarget(share);
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
	private static List<TreeEntry> loadShareFolder(final BackupTarget connection, final ShareDefinition share, final TreeEntry folder) throws Exception {
		final List<TreeEntry> result = new ArrayList<>();
		if (folder.path().equals(share.getBasePath()) && !share.getBasePath().isEmpty() && !connection.folderExists(share.getBasePath())) {
			// Base folder is created by the first backup
			return result;
		}
		final String prefix = folder.path().isEmpty() ? "" : folder.path() + "/";
		final DirectoryListing directory = connection.getDirectory(folder.path());
		for (final FolderInfo folderInfo : directory.folders()) {
			result.add(new TreeEntry(folderInfo.getFolderName(), prefix + folderInfo.getFolderName(), true, -1, toLocalDateTime(folderInfo.getModificationDate()), false));
		}
		for (final FileInfo fileInfo : directory.files()) {
			result.add(new TreeEntry(fileInfo.getFileName(), prefix + fileInfo.getFileName(), false, fileInfo.getFileSize(), toLocalDateTime(fileInfo.getModificationDate()), false));
		}
		return result;
	}

	private static LocalDateTime toLocalDateTime(final ZonedDateTime dateTime) {
		return dateTime == null ? null : dateTime.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
	}

	/**
	 * Returns the open access to a backup target (Samba share or local folder), one per target. Called in the IO thread.
	 */
	private BackupTarget getBackupTarget(final ShareDefinition share) throws Exception {
		final String key = share.getName().toLowerCase(Locale.ROOT);
		BackupTarget backupTarget = backupTargets.get(key);
		if (backupTarget == null) {
			backupTarget = BackupTarget.open(share);
			backupTargets.put(key, backupTarget);
		}
		return backupTarget;
	}

	private void onManageShares() {
		if (new ShareManagementDialog(this, configuration).showDialog()) {
			// Target settings may have changed: drop all connections, they are re-established on demand
			ioExecutor.submit(this::closeBackupTargets);
			fillShareCombo();
		}
	}

	/** Called in the IO thread */
	private void closeBackupTargets() {
		for (final BackupTarget backupTarget : backupTargets.values()) {
			try {
				backupTarget.close();
			} catch (@SuppressWarnings("unused") final Exception e) {
				// ignore
			}
		}
		backupTargets.clear();
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
				engine = new BackupEngine(currentFileSystem, device, getBackupTarget(share), share, configuration.getBackupFolders(device.serialNumber()), listener);
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
				final List<BackupEngine.DeletionCandidate> candidates = engine.findDeletionCandidates(days, path -> configuration.getDeleteExclusion(device.serialNumber(), path));
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
				final BackupEngine.DeletionResult result = engine.deleteFromDevice(candidates, path -> configuration.getDeleteExclusion(device.serialNumber(), path));
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

	// ---------------------------------------------------------------- folders excluded from deletion

	/**
	 * Adds "Exclude from deletion" to the context menu of a device folder. A folder protected by a parent
	 * folder shows the item checked but disabled; that protection can only be changed at the parent folder.
	 * For folders excluded by default in {@link StoragePaths}, unchecking lifts the default for this device
	 * and checking restores it.
	 * Changes are stored per device and therefore only possible for devices with an assigned share.
	 */
	private void addDeviceFolderMenuItems(final JPopupMenu menu, final TreeEntry folder) {
		final MtpDevice device = currentDevice;
		final String path = HandyBackupConfiguration.normalizeDevicePath(folder.path());
		if (device == null || path == null) {
			// The device root cannot be excluded (deletion is switched off completely with "-1" days)
			return;
		}
		final HandyBackupConfiguration.DeviceAssignment assignment = configuration.getAssignment(device.serialNumber());
		final String exclusion = configuration.getDeleteExclusion(device.serialNumber(), path);
		final String parentExclusion = configuration.getDeleteExclusion(device.serialNumber(), HandyBackupConfiguration.getParentDevicePath(path));
		final boolean isDefault = HandyBackupConfiguration.isDefaultDeleteProtected(path);

		final JCheckBoxMenuItem excludeItem = new JCheckBoxMenuItem(LangResources.get("excludeFromDeletionMenuItem"), exclusion != null);
		if (assignment == null) {
			excludeItem.setEnabled(false);
			excludeItem.setToolTipText(LangResources.get("excludeFromDeletionNoShare"));
		} else if (parentExclusion != null) {
			excludeItem.setEnabled(false);
			excludeItem.setToolTipText(LangResources.get("excludeFromDeletionInherited", parentExclusion));
		} else if (isDefault) {
			excludeItem.setToolTipText(LangResources.get(exclusion != null ? "excludeFromDeletionDefaultTooltip" : "excludeFromDeletionReleasedTooltip"));
		}
		excludeItem.addActionListener(action -> setDeleteExcluded(device, path, excludeItem.isSelected()));
		menu.add(excludeItem);

		addBackupFolderMenuItems(menu, device, assignment, path);
	}

	private void setDeleteExcluded(final MtpDevice device, final String path, final boolean excluded) {
		if (!configuration.setDeleteExcluded(device.serialNumber(), path, excluded)) {
			return;
		}
		try {
			configuration.save();
		} catch (final Exception e) {
			showErrorDialog(this, LangResources.get("configurationSaveFailed", HandyBackupConfiguration.getDevicesFile()), e);
		}
		final String logKey;
		if (HandyBackupConfiguration.isDefaultDeleteProtected(path)) {
			logKey = excluded ? "deleteDefaultExclusionRestored" : "deleteDefaultExclusionLifted";
		} else {
			logKey = excluded ? "deleteExclusionAdded" : "deleteExclusionRemoved";
		}
		log(LangResources.get(logKey, path, device.name()));
		deviceTree.repaint();
	}

	/**
	 * Marks excluded folders with a text and all protected folders and files with a color.
	 * Folders whose default exclusion was lifted are marked as well. Called while rendering.
	 */
	private LazyTree.Decoration decorateDeviceEntry(final TreeEntry entry) {
		final MtpDevice device = currentDevice;
		if (device == null || device.serialNumber() == null) {
			return null;
		}
		final String exclusion = configuration.getDeleteExclusion(device.serialNumber(), entry.path());
		if (exclusion == null) {
			if (entry.directory() && configuration.isDeleteReleased(device.serialNumber(), entry.path())) {
				return new LazyTree.Decoration(LangResources.get("treeDeleteReleased"), LangResources.get("treeDeleteReleasedTooltip"), DELETE_RELEASED_COLOR);
			}
			return null;
		} else if (entry.directory() && exclusion.equalsIgnoreCase(HandyBackupConfiguration.normalizeDevicePath(entry.path()))) {
			final String tooltipKey = HandyBackupConfiguration.isDefaultDeleteProtected(exclusion) ? "treeDeleteProtectedDefaultTooltip" : "treeDeleteProtectedTooltip";
			return new LazyTree.Decoration(LangResources.get("treeDeleteProtected"), LangResources.get(tooltipKey), DELETE_PROTECTED_COLOR);
		} else {
			return new LazyTree.Decoration(null, LangResources.get("treeDeleteProtectedBy", exclusion), DELETE_PROTECTED_COLOR);
		}
	}

	// ---------------------------------------------------------------- folders added to the backup

	/**
	 * Adds "Add to backup ..." to the context menu of a device folder, or "Change file types ..." and
	 * "Remove from backup" for a folder already added for this device.
	 * Changes are stored per device and therefore only possible for devices with an assigned share.
	 */
	private void addBackupFolderMenuItems(final JPopupMenu menu, final MtpDevice device, final HandyBackupConfiguration.DeviceAssignment assignment, final String path) {
		if (StoragePaths.getStorageOf(path) == null) {
			return;
		}
		menu.addSeparator();
		final BackupFolder customFolder = assignment == null ? null : assignment.getBackupFolder(path);
		if (customFolder == null) {
			final JMenuItem addItem = new JMenuItem(LangResources.get("addBackupFolderMenuItem"));
			if (assignment == null) {
				addItem.setEnabled(false);
				addItem.setToolTipText(LangResources.get("excludeFromDeletionNoShare"));
			} else {
				final List<BackupFolder> backupFolders = assignment.backupFolders();
				final BackupFolder effectiveFolder = BackupEngine.isScannedFolder(path, backupFolders) ? StoragePaths.findBackupFolder(path, backupFolders) : null;
				if (effectiveFolder != null) {
					addItem.setToolTipText(LangResources.get("addBackupFolderAlreadyBackedUpTooltip", describeBackupTypes(effectiveFolder.backupTypes()), effectiveFolder.devicePath()));
				}
			}
			addItem.addActionListener(action -> editBackupFolder(device, path));
			menu.add(addItem);
		} else {
			final JMenuItem changeItem = new JMenuItem(LangResources.get("changeBackupFolderMenuItem"));
			changeItem.setToolTipText(LangResources.get("backupFolderCurrentTypes", describeBackupTypes(customFolder.backupTypes())));
			changeItem.addActionListener(action -> editBackupFolder(device, path));
			menu.add(changeItem);
			final JMenuItem removeItem = new JMenuItem(LangResources.get("removeBackupFolderMenuItem"));
			removeItem.addActionListener(action -> removeBackupFolder(device, path));
			menu.add(removeItem);
		}
	}

	/**
	 * Asks for the file types of a device folder and adds the folder to the backup of the device or changes its file types.
	 */
	private void editBackupFolder(final MtpDevice device, final String path) {
		final HandyBackupConfiguration.DeviceAssignment assignment = configuration.getAssignment(device.serialNumber());
		if (assignment == null) {
			return;
		}
		final BackupFolder customFolder = assignment.getBackupFolder(path);
		final Set<BackupType> initialTypes;
		if (customFolder != null) {
			initialTypes = customFolder.backupTypes();
		} else {
			// Offer the file types that currently apply to the folder, if it is already backed up
			final BackupFolder effectiveFolder = BackupEngine.isScannedFolder(path, assignment.backupFolders()) ? StoragePaths.findBackupFolder(path, assignment.backupFolders()) : null;
			initialTypes = effectiveFolder == null ? Set.of() : effectiveFolder.backupTypes();
		}

		final Set<BackupType> backupTypes = showBackupTypesDialog(path, initialTypes);
		if (backupTypes == null || !configuration.setBackupFolder(device.serialNumber(), path, backupTypes)) {
			return;
		}
		saveDeviceConfiguration();
		log(LangResources.get(customFolder == null ? "backupFolderAdded" : "backupFolderChanged", path, describeBackupTypes(backupTypes), device.name()));
		deviceTree.repaint();
	}

	private void removeBackupFolder(final MtpDevice device, final String path) {
		if (!configuration.removeBackupFolder(device.serialNumber(), path)) {
			return;
		}
		saveDeviceConfiguration();
		final List<BackupFolder> backupFolders = configuration.getBackupFolders(device.serialNumber());
		final BackupFolder remainingFolder = BackupEngine.isScannedFolder(path, backupFolders) ? StoragePaths.findBackupFolder(path, backupFolders) : null;
		if (remainingFolder == null) {
			log(LangResources.get("backupFolderRemoved", path, device.name()));
		} else {
			log(LangResources.get("backupFolderRemovedStillBackedUp", path, device.name(), describeBackupTypes(remainingFolder.backupTypes()), remainingFolder.devicePath()));
		}
		deviceTree.repaint();
	}

	private void saveDeviceConfiguration() {
		try {
			configuration.save();
		} catch (final Exception e) {
			showErrorDialog(this, LangResources.get("configurationSaveFailed", HandyBackupConfiguration.getDevicesFile()), e);
		}
	}

	/**
	 * Dialog with one checkbox per {@link BackupType}, showing its file extensions. "All files" disables the other checkboxes.
	 *
	 * @return the selected types (not empty), null if cancelled
	 */
	private Set<BackupType> showBackupTypesDialog(final String path, final Set<BackupType> initialTypes) {
		final Map<BackupType, JCheckBox> checkBoxes = new EnumMap<>(BackupType.class);
		final JPanel typePanel = new JPanel(new GridLayout(0, 1, 0, 2));
		for (final BackupType backupType : BackupType.values()) {
			final String extensions = backupType.getExtensions().isEmpty() ? "" : " (" + String.join(", ", backupType.getExtensions().stream().sorted().toList()) + ")";
			final JCheckBox checkBox = new JCheckBox(getBackupTypeName(backupType) + extensions, initialTypes.contains(backupType));
			checkBoxes.put(backupType, checkBox);
			typePanel.add(checkBox);
		}
		final JCheckBox allCheckBox = checkBoxes.get(BackupType.ALL);
		final Runnable updateEnabled = () -> {
			for (final Map.Entry<BackupType, JCheckBox> entry : checkBoxes.entrySet()) {
				if (entry.getKey() != BackupType.ALL) {
					entry.getValue().setEnabled(!allCheckBox.isSelected());
				}
			}
		};
		allCheckBox.addActionListener(event -> updateEnabled.run());
		updateEnabled.run();

		final JPanel panel = new JPanel(new BorderLayout(0, 8));
		final JLabel infoLabel = new JLabel("<html>" + LangResources.get("backupTypesDialogText", escapeHtml(path)).replace("\n", "<br>") + "</html>");
		panel.add(infoLabel, BorderLayout.NORTH);
		panel.add(typePanel, BorderLayout.CENTER);

		while (true) {
			final int answer = JOptionPane.showConfirmDialog(this, panel, LangResources.get("backupTypesDialogTitle"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
			if (answer != JOptionPane.OK_OPTION) {
				return null;
			}
			final Set<BackupType> result = EnumSet.noneOf(BackupType.class);
			if (allCheckBox.isSelected()) {
				result.add(BackupType.ALL);
			} else {
				for (final Map.Entry<BackupType, JCheckBox> entry : checkBoxes.entrySet()) {
					if (entry.getValue().isSelected()) {
						result.add(entry.getKey());
					}
				}
			}
			if (!result.isEmpty()) {
				return result;
			}
			JOptionPane.showMessageDialog(this, LangResources.get("backupTypesNoneSelected"), LangResources.get("backupTypesDialogTitle"), JOptionPane.WARNING_MESSAGE);
		}
	}

	private static String getBackupTypeName(final BackupType backupType) {
		return LangResources.get("backupType_" + backupType.name());
	}

	private static String describeBackupTypes(final Set<BackupType> backupTypes) {
		final StringJoiner joiner = new StringJoiner(", ");
		for (final BackupType backupType : backupTypes) {
			joiner.add(getBackupTypeName(backupType));
		}
		return joiner.toString();
	}

	private static String escapeHtml(final String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	/**
	 * Decides whether a device folder is backed up (shown bold) and describes its file types for the tooltip:
	 * null if the folder is not backed up. Called while rendering.
	 */
	private String describeDeviceBackupFolder(final TreeEntry entry) {
		final MtpDevice device = currentDevice;
		if (device == null || !entry.directory()) {
			return null;
		}
		final List<BackupFolder> backupFolders = configuration.getBackupFolders(device.serialNumber());
		if (!BackupEngine.isScannedFolder(entry.path(), backupFolders)) {
			return null;
		}
		final BackupFolder backupFolder = StoragePaths.findBackupFolder(entry.path(), backupFolders);
		if (backupFolder == null) {
			return null;
		}
		final String types = describeBackupTypes(backupFolder.backupTypes());
		if (!backupFolder.custom()) {
			return LangResources.get("treeBackupTypesDefault", types, backupFolder.devicePath());
		} else if (backupFolder.devicePath().equalsIgnoreCase(HandyBackupConfiguration.normalizeDevicePath(entry.path()))) {
			return LangResources.get("treeBackupTypesCustom", types);
		} else {
			return LangResources.get("treeBackupTypesCustomBy", types, backupFolder.devicePath());
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
					index = BackupIndex.load(getBackupTarget(share), share);
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
			finishManualDeletion(null, null);
			return;
		}

		long totalBytes = 0;
		int notBackedUp = 0;
		int inProtectedFolder = 0;
		for (final ManualDeletionItem item : items) {
			totalBytes += Math.max(0, item.entry().size());
			if (Boolean.FALSE.equals(item.backedUp())) {
				notBackedUp++;
			}
			if (configuration.getDeleteExclusion(device.serialNumber(), item.entry().path()) != null) {
				inProtectedFolder++;
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
		if (inProtectedFolder > 0) {
			// Manual deletion is an explicit decision and stays possible, but the user is reminded of the exclusion
			question.append("\n").append(items.size() == 1 ? LangResources.get("fileInProtectedFolder") : LangResources.get("filesInProtectedFolder", inProtectedFolder));
		}
		question.append("\n").append(LangResources.get("phoneDeletionIrreversible"));

		final Object[] options = { LangResources.get("deleteButton"), LangResources.get("cancel") };
		setBusy(LangResources.get("waitingForConfirmation"));
		final int answer = JOptionPane.showOptionDialog(this, question.toString(), LangResources.get("deleteFromPhone"), JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE, null, options, options[1]);
		if (answer != 0) {
			finishManualDeletion(null, null);
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
				finishManualDeletion(deviceTree, items.stream().map(ManualDeletionItem::entry).toList());
			});
		});
	}

	/**
	 * @param treeToRefresh  tree to reload after files were deleted, null if nothing was deleted
	 * @param deletedEntries entries selected for deletion; only their folders are reloaded, so the rest of the tree stays as it is
	 */
	private void finishManualDeletion(final LazyTree treeToRefresh, final List<TreeEntry> deletedEntries) {
		setIdle();
		backupActive = false;
		updateControls();
		if (treeToRefresh != null) {
			treeToRefresh.refreshParentsOf(deletedEntries);
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
				index = BackupIndex.load(getBackupTarget(share), share);
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
			finishManualDeletion(null, null);
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
				share.getDisplayPath(items.get(0).relativePath())));
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
			finishManualDeletion(null, null);
			return;
		}

		setBusy(LangResources.get("deletingOnShare"));
		ioExecutor.submit(() -> {
			int deleted = 0;
			int errors = 0;
			String indexError = null;
			BackupIndex index = null;
			try {
				final BackupTarget connection = getBackupTarget(share);
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
						index.save(getBackupTarget(share), share);
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
				finishManualDeletion(shareTree, items.stream().map(ShareDeletionItem::entry).toList());
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
				final BackupTarget connection = getBackupTarget(share);
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
		log(message, false);
	}

	/**
	 * Appends a line with a grey timestamp to the log; errors are shown in red.
	 */
	private void log(final String message, final boolean error) {
		final StyledDocument document = logPane.getStyledDocument();
		try {
			document.insertString(document.getLength(), LocalDateTime.now().format(LOG_TIME_FORMAT) + "  ", LOG_TIME_STYLE);
			document.insertString(document.getLength(), message + "\n", error ? LOG_ERROR_STYLE : LOG_TEXT_STYLE);
		} catch (@SuppressWarnings("unused") final BadLocationException e) {
			// cannot happen when appending at the end
		}
		logPane.setCaretPosition(document.getLength());
	}

	/**
	 * Logs an error as "ERROR: &lt;text&gt;: &lt;error message&gt;" in the current language.
	 */
	private void logError(final String text, final Throwable error) {
		log(LangResources.get("logError", text + ": " + (error == null ? "" : error.getMessage())), true);
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
		if (StringUtilities.isNotBlank(VERSIONINFO_DOWNLOAD_URL) && dailyUpdateCheckIsPending()) {
			setDailyUpdateCheckStatus(true);
			try {
				final ApplicationConfigurationProperties applicationConfiguration = configuration.getConfigurationProperties();
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
			applicationConfiguration.set(ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK, checkboxStatus);
			applicationConfiguration.set(ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK, LocalDateTime.now().plusDays(1));
			applicationConfiguration.save();
		} catch (final Exception e) {
			log(LangResources.get("logError", LangResources.get("updateSettingSaveFailed", e.getMessage())), true);
		}
	}

	@Override
	protected Boolean isDailyUpdateCheckActivated() {
		try {
			return configuration.getConfigurationProperties().getBoolean(ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK);
		} catch (@SuppressWarnings("unused") final Exception e) {
			return false;
		}
	}

	private boolean dailyUpdateCheckIsPending() {
		try {
			final ConfigurationProperties applicationConfiguration = configuration.getConfigurationProperties();
			final LocalDateTime nextCheck = applicationConfiguration.getDate(ApplicationConfigurationProperties.CONFIG_KEY_NEXT_DAILY_UPDATE_CHECK);
			return applicationConfiguration.getBoolean(ApplicationConfigurationProperties.CONFIG_KEY_DAILY_UPDATE_CHECK) && (nextCheck == null || nextCheck.isBefore(LocalDateTime.now()));
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
	 * Transparent icon button for the gradient header; the text is shown as tooltip.
	 */
	private static JButton createHeaderButton(final ModernUi.IconKind iconKind, final String text) {
		final JButton button = new ModernUi.ModernButton(null, iconKind, ModernUi.ButtonStyle.HEADER);
		button.setToolTipText(text);
		button.getAccessibleContext().setAccessibleName(text);
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
			closeBackupTargets();
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
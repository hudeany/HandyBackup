package de.soderer.handybackup;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.soderer.handybackup.mtp.MtpDevice;
import de.soderer.handybackup.mtp.MtpFileSystem;
import de.soderer.handybackup.mtp.MtpManager;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.appupdate.ApplicationUpdateUtilities;
import de.soderer.utilities.remote.SmbConnection;

/**
 * Command line interface of HandyBackup, e.g. for scheduled backups via the Windows task scheduler.
 *
 * <pre>
 * HandyBackup.jar [gui]                              start the GUI
 * HandyBackup.jar devices                            list connected phones and their assignments
 * HandyBackup.jar backup [-serial &lt;sn&gt;] [-delete] [-v] back up all connected, assigned phones
 * HandyBackup.jar version | update | help
 * </pre>
 *
 * Commands and parameters are accepted with or without leading "-" or "--".
 * Deleting on the phone never happens without the explicit parameter "-delete", because there is
 * nobody to confirm it.
 */
public class HandyBackupCli {
	/** Success */
	public static final int EXIT_OK = 0;

	/** At least one error occurred (backup, deletion, connection) */
	public static final int EXIT_ERROR = 1;

	/** No (matching) connected and assigned phone was found */
	public static final int EXIT_NO_DEVICE = 2;

	/** Invalid command line */
	public static final int EXIT_USAGE = 3;

	private static final String HELP_RESOURCE = "/help.txt";

	/** Maximum time a Ctrl+C waits for the current file and the index to be finished */
	private static final long SHUTDOWN_WAIT_SECONDS = 60;

	private final HandyBackupConfiguration configuration;

	/** Error while loading the configuration, null if loaded successfully. Only "help" and "version" work without configuration. */
	private final Exception configurationError;

	/** Engine of the backup currently running, cancelled on Ctrl+C */
	private volatile BackupEngine runningEngine;

	/** Counted down when the command is completely finished, so a shutdown hook can wait for the index to be saved */
	private final CountDownLatch finished = new CountDownLatch(1);

	/** Progress output with -v (one updated line) */
	private boolean verbose = false;
	private int progressLineLength = 0;

	/**
	 * Checks whether the arguments ask for the command line interface. No arguments or "gui" start the GUI.
	 */
	public static boolean isCliCall(final String[] args) {
		return args != null && args.length > 0 && !"gui".equals(normalize(args[0]));
	}

	/**
	 * @param configuration      loaded configuration
	 * @param configurationError error while loading the configuration, null if loaded successfully
	 */
	public HandyBackupCli(final HandyBackupConfiguration configuration, final Exception configurationError) {
		this.configuration = configuration;
		this.configurationError = configurationError;
	}

	/**
	 * Executes the command line.
	 *
	 * @return exit code, see EXIT_* constants
	 */
	public int run(final String[] args) {
		try {
			final String command = normalize(args[0]);
			final List<String> parameters = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
			switch (command) {
				case "help":
				case "h":
				case "?":
					if (!parameters.isEmpty()) {
						return usageError(LangResources.get("cliUnknownParameter", parameters.get(0)));
					}
					printHelp();
					return EXIT_OK;
				case "version":
					if (!parameters.isEmpty()) {
						return usageError(LangResources.get("cliUnknownParameter", parameters.get(0)));
					}
					System.out.println(HandyBackup.APPLICATION_NAME + " " + HandyBackup.VERSION
						+ (HandyBackup.VERSION_BUILDTIME == null ? "" : " (" + HandyBackup.VERSION_BUILDTIME + ")"));
					return EXIT_OK;
				case "update":
				case "devices":
					if (!parameters.isEmpty()) {
						return usageError(LangResources.get("cliUnknownParameter", parameters.get(0)));
					} else if (!checkConfiguration()) {
						return EXIT_ERROR;
					}
					return "update".equals(command) ? update() : listDevices();
				case "backup":
					// Parameters are validated before the configuration, so usage errors are reported first
					return backup(parameters);
				default:
					return usageError(LangResources.get("cliUnknownCommand", args[0]));
			}
		} catch (final Throwable e) {
			printError(LangResources.get("logError", describe(e)));
			return EXIT_ERROR;
		} finally {
			finished.countDown();
		}
	}

	/**
	 * Reports a configuration that could not be loaded. Only "help" and "version" work without configuration.
	 *
	 * @return true if the configuration is available
	 */
	private boolean checkConfiguration() {
		if (configurationError != null) {
			printError(LangResources.get("configurationReadFailed", HandyBackupConfiguration.getConfigFile() + " / " + HandyBackupConfiguration.getDevicesFile()) + ": " + describe(configurationError));
			return false;
		}
		return true;
	}


	// ---------------------------------------------------------------- commands

	private static void printHelp() throws Exception {
		try (InputStream inputStream = HandyBackupCli.class.getResourceAsStream(HELP_RESOURCE)) {
			if (inputStream == null) {
				throw new Exception("Help text not available");
			}
			System.out.println(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private int update() throws Exception {
		if (HandyBackup.VERSIONINFO_DOWNLOAD_URL == null || HandyBackup.VERSIONINFO_DOWNLOAD_URL.isBlank()) {
			printError(LangResources.get("cliNoUpdateUrl"));
			return EXIT_ERROR;
		}
		// Without a parent window the update utilities use the console for questions and messages
		ApplicationUpdateUtilities.executeUpdate(null, HandyBackup.VERSIONINFO_DOWNLOAD_URL, configuration.getConfigurationProperties().getProxyConfiguration(),
			HandyBackup.APPLICATION_NAME, HandyBackup.VERSION, HandyBackup.TRUSTED_UPDATE_CA_CERTIFICATES, null, null, null, true, false);
		return EXIT_OK;
	}

	private int listDevices() throws Exception {
		try (MtpManager mtpManager = new MtpManager()) {
			final List<MtpDevice> devices = getMtpDevices(mtpManager);
			if (devices.isEmpty()) {
				printError(LangResources.get("noPhoneFound"));
				return EXIT_NO_DEVICE;
			}
			for (final MtpDevice device : devices) {
				final HandyBackupConfiguration.DeviceAssignment assignment = configuration.getAssignment(device.serialNumber());
				System.out.println(LangResources.get("cliDeviceLine",
					describeDevice(device),
					device.serialNumber() == null ? LangResources.get("unknownValue") : device.serialNumber(),
					assignment == null ? LangResources.get("noShareAssigned") : assignment.shareName(),
					assignment == null ? "-" : Integer.toString(assignment.deleteAfterDays())));
			}
			return EXIT_OK;
		}
	}

	private int backup(final List<String> parameters) throws Exception {
		String serialFilter = null;
		boolean delete = false;
		for (int i = 0; i < parameters.size(); i++) {
			final String parameter = normalize(parameters.get(i));
			switch (parameter) {
				case "serial":
					if (i + 1 >= parameters.size()) {
						return usageError(LangResources.get("cliMissingValue", parameters.get(i)));
					}
					serialFilter = parameters.get(++i);
					break;
				case "delete":
					delete = true;
					break;
				case "v":
					verbose = true;
					break;
				default:
					return usageError(LangResources.get("cliUnknownParameter", parameters.get(i)));
			}
		}

		if (!checkConfiguration()) {
			return EXIT_ERROR;
		}

		installShutdownHook();

		boolean anyBackup = false;
		boolean anyError = false;
		try (MtpManager mtpManager = new MtpManager()) {
			for (final MtpDevice device : getMtpDevices(mtpManager)) {
				if (serialFilter != null && !serialFilter.equalsIgnoreCase(device.serialNumber())) {
					continue;
				}
				if (device.serialNumber() == null) {
					printError(LangResources.get("cliSkippedNoSerial", device.name()));
					continue;
				}
				final HandyBackupConfiguration.DeviceAssignment assignment = configuration.getAssignment(device.serialNumber());
				if (assignment == null) {
					printError(LangResources.get("cliSkippedNotAssigned", device.name(), device.serialNumber()));
					continue;
				}
				final ShareDefinition share = configuration.getShare(assignment.shareName());
				if (share == null) {
					printError(LangResources.get("cliSkippedShareMissing", device.name(), assignment.shareName()));
					anyError = true;
					continue;
				}

				anyBackup = true;
				if (!backupDevice(mtpManager, device, share, assignment.deleteAfterDays(), delete)) {
					anyError = true;
				}
				if (runningEngineCancelled()) {
					break;
				}
			}
		}

		if (!anyBackup) {
			printError(serialFilter == null ? LangResources.get("noPhoneFound") : LangResources.get("cliSerialNotFound", serialFilter));
			return anyError ? EXIT_ERROR : EXIT_NO_DEVICE;
		}
		return anyError ? EXIT_ERROR : EXIT_OK;
	}

	/**
	 * Backs up one phone and, if requested, deletes backed-up files from it.
	 *
	 * @return true without errors
	 */
	private boolean backupDevice(final MtpManager mtpManager, final MtpDevice device, final ShareDefinition share, final int deleteAfterDays, final boolean delete) throws Exception {
		System.out.println(LangResources.get("backupStarted", device.name(), share.getDisplayPath()));

		final char[] password = share.getPassword();
		try (MtpFileSystem fileSystem = mtpManager.open(device);
				SmbConnection smbConnection = new SmbConnection(share.getHostname(), share.getPort(), share.getUsername(), share.getSharename(), password)) {
			Arrays.fill(password, '\0');

			final BackupEngine engine = new BackupEngine(fileSystem, device, smbConnection, share, createListener());
			runningEngine = engine;
			final BackupEngine.BackupResult result;
			try {
				result = engine.run();
			} finally {
				endProgress();
			}
			System.out.println(LangResources.get(result.cancelled() ? "backupCancelled" : "backupFinished") + ": "
				+ LangResources.get("backupSummary", result.found(), result.copied(), result.alreadySaved(), result.duplicates(), result.errors()));
			boolean success = result.errors() == 0;

			if (result.cancelled() || deleteAfterDays < 0) {
				return success;
			}

			final List<BackupEngine.DeletionCandidate> candidates;
			try {
				candidates = engine.findDeletionCandidates(deleteAfterDays, path -> configuration.getDeleteExclusion(device.serialNumber(), path));
			} finally {
				endProgress();
			}
			if (candidates.isEmpty() || runningEngineCancelled()) {
				return success;
			}

			long totalBytes = 0;
			for (final BackupEngine.DeletionCandidate candidate : candidates) {
				totalBytes += Math.max(0, candidate.file().size());
			}
			if (!delete) {
				System.out.println(LangResources.get("cliDeletionNeedsParameter", candidates.size(), LazyTree.formatSize(totalBytes)));
				return success;
			}

			final BackupEngine.DeletionResult deletionResult;
			try {
				deletionResult = engine.deleteFromDevice(candidates, path -> configuration.getDeleteExclusion(device.serialNumber(), path));
			} finally {
				endProgress();
			}
			System.out.println(LangResources.get(deletionResult.cancelled() ? "phoneDeletionCancelled" : "phoneDeletionFinished") + ": "
				+ LangResources.get("phoneDeletionSummary", deletionResult.deleted(), LazyTree.formatSize(deletionResult.deletedBytes()), deletionResult.skipped(), deletionResult.errors()));
			if (deletionResult.errors() > 0) {
				success = false;
			}
			return success;
		} catch (final Exception e) {
			endProgress();
			printError(LangResources.get("logError", LangResources.get("cliBackupFailed", device.name(), describe(e))));
			return false;
		} finally {
			Arrays.fill(password, '\0');
		}
	}

	// ---------------------------------------------------------------- helpers

	/**
	 * Connected MTP devices, as in the GUI. Devices whose properties cannot be read are reported.
	 */
	private static List<MtpDevice> getMtpDevices(final MtpManager mtpManager) throws Exception {
		final List<MtpDevice> devices = new ArrayList<>();
		for (final MtpDevice device : mtpManager.getConnectedDevices()) {
			if (device.errorMessage() != null) {
				printError(LangResources.get("deviceNotReadable", device.name(), device.errorMessage()));
			} else if (device.protocol() == null || device.protocol().toUpperCase(Locale.ROOT).startsWith("MTP")) {
				devices.add(device);
			}
		}
		return devices;
	}

	private static String describeDevice(final MtpDevice device) {
		return device.name() + (device.model() != null && !device.model().equals(device.name()) ? " (" + device.model() + ")" : "");
	}

	private BackupEngine.BackupListener createListener() {
		return new BackupEngine.BackupListener() {
			@Override
			public void onLog(final String message) {
				synchronized (HandyBackupCli.this) {
					clearProgressLine();
					System.out.println(message);
				}
			}

			@Override
			public void onProgress(final int done, final int total, final String currentFile) {
				if (!verbose || total <= 0 || currentFile == null) {
					return;
				}
				synchronized (HandyBackupCli.this) {
					String line = done + " / " + total + "  " + currentFile;
					if (line.length() > 100) {
						line = line.substring(0, 47) + "..." + line.substring(line.length() - 50);
					}
					clearProgressLine();
					System.out.print(line);
					System.out.flush();
					progressLineLength = line.length();
				}
			}
		};
	}

	private synchronized void clearProgressLine() {
		if (progressLineLength > 0) {
			System.out.print("\r" + " ".repeat(progressLineLength) + "\r");
			progressLineLength = 0;
		}
	}

	private synchronized void endProgress() {
		clearProgressLine();
		System.out.flush();
	}

	private boolean runningEngineCancelled() {
		final BackupEngine engine = runningEngine;
		return engine != null && engine.isCancelled();
	}

	/**
	 * On Ctrl+C the running backup is cancelled. The JVM waits until the current file is finished
	 * and the index is saved (at most {@link #SHUTDOWN_WAIT_SECONDS}).
	 */
	private void installShutdownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			final BackupEngine engine = runningEngine;
			if (engine != null && finished.getCount() > 0) {
				System.err.println();
				System.err.println(LangResources.get("cancelRequested"));
				engine.cancel();
				try {
					finished.await(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS);
				} catch (@SuppressWarnings("unused") final InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}, "HandyBackup-Shutdown"));
	}

	private static int usageError(final String message) throws Exception {
		printError(message);
		System.err.println();
		printHelp();
		return EXIT_USAGE;
	}

	private static void printError(final String message) {
		System.err.println(message);
	}

	private static String describe(final Throwable error) {
		return error.getMessage() != null && !error.getMessage().isBlank() ? error.getMessage() : error.getClass().getSimpleName();
	}

	/**
	 * "-Backup", "--backup", "/backup" and "backup" are the same command.
	 */
	private static String normalize(final String argument) {
		String result = argument == null ? "" : argument.trim();
		while (result.startsWith("-")) {
			result = result.substring(1);
		}
		if (result.startsWith("/") && result.length() > 1) {
			result = result.substring(1);
		}
		return result.toLowerCase(Locale.ROOT);
	}
}

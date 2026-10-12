package de.soderer.handybackup;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import de.soderer.handybackup.mtp.MtpDevice;
import de.soderer.handybackup.mtp.MtpErrorType;
import de.soderer.handybackup.mtp.MtpFile;
import de.soderer.handybackup.mtp.MtpFileSystem;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.remote.FileInfo;
import de.soderer.utilities.remote.SmbConnection;

/**
 * Backs up files of one MTP device into its assigned share. Which files are backed up is defined
 * per folder in {@link StoragePaths} (images, videos, contacts or all files) and by the folders added
 * for the device ({@link BackupFolder}); for overlapping folders the most specific one decides.
 *
 * Target layout: &lt;basePath&gt;/&lt;year&gt;/&lt;year-month&gt;/&lt;original file name&gt;
 * - all sources (camera, WhatsApp, screenshots ...) mixed by date
 * - files already in the index (same device, path, size and date) are skipped without download
 * - files with content already in the share (SHA-256) are not stored again
 * - existing files in the share are never overwritten or deleted; name collisions get a suffix "_1", "_2" ...
 *
 * After a backup, files can be deleted from the device ({@link #findDeletionCandidates(int, UnaryOperator)},
 * {@link #deleteFromDevice(List, UnaryOperator)}). Only files that are verifiably stored in the share and
 * not located in a folder excluded from deletion are deleted.
 */
public class BackupEngine {
	/** Folder names that are never scanned below a WhatsApp folder (own sent media, private folder) */
	private static final Set<String> EXCLUDED_WHATSAPP_FOLDERS = Set.of("sent", "private");

	/** The index is saved after this many new files, so an interruption loses little work */
	private static final int INDEX_SAVE_INTERVAL = 25;

	private static final String PART_SUFFIX = ".handybackup-part";

	/**
	 * Receives progress information. Called from the backup thread.
	 */
	public interface BackupListener {
		void onLog(String message);

		void onProgress(int done, int total, String currentFile);
	}

	public record BackupResult(int found, int alreadySaved, int copied, int duplicates, int errors, boolean cancelled) {
	}

	/**
	 * File on the device that may be deleted, because it is stored in the share.
	 *
	 * @param file       the file on the device, as found by the scan of the backup
	 * @param targetPath path of the stored copy, relative to the share's base path
	 */
	public record DeletionCandidate(MtpFile file, String targetPath) {
	}

	public record DeletionResult(int deleted, long deletedBytes, int skipped, int errors, boolean cancelled) {
	}

	private final MtpFileSystem mtpFileSystem;
	private final MtpDevice device;
	private final SmbConnection smbConnection;
	private final ShareDefinition share;
	private final BackupListener listener;
	private final List<BackupFolder> customBackupFolders;
	private volatile boolean cancelled = false;

	/** Results of the last {@link #run()}, needed for the deletion phase */
	private List<MtpFile> scannedFiles = null;
	private BackupIndex backupIndex = null;

	/** Progress of the current {@link #run()}, readable after an abort (e.g. lost connection) */
	private volatile boolean scanFinished = false;
	private volatile int savedCount = 0;

	/**
	 * @param customBackupFolders folders added for this device in addition to {@link StoragePaths}, may be empty;
	 *                            a copy is kept, so changes during the backup take effect with the next backup
	 */
	public BackupEngine(final MtpFileSystem mtpFileSystem, final MtpDevice device, final SmbConnection smbConnection, final ShareDefinition share, final Collection<BackupFolder> customBackupFolders, final BackupListener listener) {
		this.mtpFileSystem = mtpFileSystem;
		this.customBackupFolders = customBackupFolders == null ? List.of() : List.copyOf(customBackupFolders);
		this.device = device;
		this.smbConnection = smbConnection;
		this.share = share;
		this.listener = listener;
	}

	/**
	 * Requests cancellation. The file currently being transferred is finished first.
	 */
	public void cancel() {
		cancelled = true;
	}

	/**
	 * Whether cancellation was requested.
	 */
	public boolean isCancelled() {
		return cancelled;
	}

	/**
	 * Whether the scan of the device folders was completed in the current or last {@link #run()}.
	 */
	public boolean isScanFinished() {
		return scanFinished;
	}

	/**
	 * Number of files newly stored or registered in the index (copied or duplicates) in the current or last {@link #run()}.
	 * Also valid after {@link #run()} was aborted by an exception.
	 */
	public int getSavedCount() {
		return savedCount;
	}

	public BackupResult run() throws Exception {
		if (device.serialNumber() == null) {
			throw new Exception("Device has no serial number");
		}

		listener.onLog(LangResources.get("readingBackupIndex", share.getDisplayPath()));
		final BackupIndex index = BackupIndex.load(smbConnection, share);
		backupIndex = index;
		listener.onLog(LangResources.get("indexEntries", index.size()));

		scanFinished = false;
		savedCount = 0;
		listener.onLog(LangResources.get("scanningPhoneFolders"));
		final List<MtpFile> mediaFiles = scanMediaFiles(mtpFileSystem, customBackupFolders, () -> cancelled, listener);
		scannedFiles = mediaFiles;
		scanFinished = true;
		listener.onLog(LangResources.get("mediaFilesFound", mediaFiles.size()));

		int alreadySaved = 0;
		int copied = 0;
		int duplicates = 0;
		int errors = 0;
		int unsavedIndexChanges = 0;
		int done = 0;
		try {
			for (final MtpFile mediaFile : mediaFiles) {
				if (cancelled) {
					break;
				}
				listener.onProgress(done, mediaFiles.size(), mediaFile.path());

				final long sourceModified = toEpochMillis(mediaFile.modified());
				if (index.containsSource(device.serialNumber(), mediaFile.path(), mediaFile.size(), sourceModified)) {
					alreadySaved++;
				} else {
					try {
						if (backupFile(mediaFile, sourceModified, index)) {
							copied++;
						} else {
							duplicates++;
						}
						savedCount++;
						unsavedIndexChanges++;
					} catch (final Exception e) {
						if (MtpErrorType.isConnectionLost(e)) {
							// All further files would fail too: abort, the index is saved in the finally block
							throw e;
						}
						errors++;
						listener.onLog(LangResources.get("errorAtFile", mediaFile.path(), e.getMessage()));
					}
				}

				if (unsavedIndexChanges >= INDEX_SAVE_INTERVAL) {
					index.save(smbConnection, share);
					unsavedIndexChanges = 0;
				}
				done++;
			}
		} finally {
			if (index.isChanged()) {
				listener.onLog(LangResources.get("savingBackupIndex"));
				index.save(smbConnection, share);
			}
		}

		listener.onProgress(done, mediaFiles.size(), null);
		return new BackupResult(mediaFiles.size(), alreadySaved, copied, duplicates, errors, cancelled);
	}

	/**
	 * Decides whether a backed-up file is due for deletion from the device.
	 *
	 * @param deleteAfterDays &lt; 0 never, 0 always, &gt; 0 if the file is older than this many days
	 * @param fileDate        date of the file, null if unknown (then only deleted with deleteAfterDays = 0)
	 * @param now             reference time
	 */
	public static boolean isDeletionDue(final int deleteAfterDays, final LocalDateTime fileDate, final LocalDateTime now) {
		if (deleteAfterDays < 0) {
			return false;
		} else if (deleteAfterDays == 0) {
			return true;
		} else if (fileDate == null) {
			// Unknown age: keep the file to be safe
			return false;
		} else {
			return fileDate.isBefore(now.minusDays(deleteAfterDays));
		}
	}

	/**
	 * Determines the files that may be deleted from the device after {@link #run()}. A file qualifies only if
	 * <ul>
	 * <li>it is due by {@link #isDeletionDue(int, LocalDateTime, LocalDateTime)},</li>
	 * <li>it is not located in a folder excluded from deletion,</li>
	 * <li>the index contains it for this device with the same path, size and modification date, and</li>
	 * <li>the stored copy exists in the share with the size of the index entry.</li>
	 * </ul>
	 * Files that failed in this backup or were not reached because of a cancellation are not in the index and are kept.
	 *
	 * @param deleteAfterDays       &lt; 0 never delete, 0 delete all backed-up files, &gt; 0 delete backed-up files older than this many days
	 * @param deleteExclusionLookup returns the excluded folder protecting a device path, null if the path is not protected
	 */
	public List<DeletionCandidate> findDeletionCandidates(final int deleteAfterDays, final UnaryOperator<String> deleteExclusionLookup) throws Exception {
		if (scannedFiles == null || backupIndex == null) {
			throw new IllegalStateException("Backup has not been run");
		}
		final List<DeletionCandidate> result = new ArrayList<>();
		if (deleteAfterDays < 0) {
			return result;
		}

		final LocalDateTime now = LocalDateTime.now();
		final Map<String, Map<String, Long>> targetFolderCache = new HashMap<>();
		// Number of kept files per excluded folder, logged as one line per folder instead of one line per file
		final Map<String, Integer> protectedCounts = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		int done = 0;
		for (final MtpFile mediaFile : scannedFiles) {
			if (cancelled) {
				break;
			}
			listener.onProgress(done++, scannedFiles.size(), mediaFile.path());
			if (!isDeletionDue(deleteAfterDays, mediaFile.modified(), now)) {
				continue;
			}
			final BackupIndex.Entry entry = backupIndex.findBySource(device.serialNumber(), mediaFile.path(), mediaFile.size(), toEpochMillis(mediaFile.modified()));
			if (entry == null) {
				continue;
			}
			final String exclusion = deleteExclusionLookup.apply(mediaFile.path());
			if (exclusion != null) {
				protectedCounts.merge(exclusion, 1, Integer::sum);
				continue;
			}
			final Long targetSize = getTargetFileSize(entry.targetPath(), targetFolderCache);
			if (targetSize == null || targetSize.longValue() != entry.size()) {
				listener.onLog(LangResources.get("notDeletedBackupMissing", mediaFile.path(), entry.targetPath()));
				continue;
			}
			result.add(new DeletionCandidate(mediaFile, entry.targetPath()));
		}
		listener.onProgress(done, scannedFiles.size(), null);
		for (final Map.Entry<String, Integer> protectedCount : protectedCounts.entrySet()) {
			listener.onLog(LangResources.get("notDeletedProtectedFolder", protectedCount.getValue(), protectedCount.getKey()));
		}
		return result;
	}

	/**
	 * Deletes the given files from the device. Before each deletion the file is checked again on the device:
	 * if its size or modification date changed since the scan, it is kept. The exclusions are checked again
	 * as well, so a folder excluded after the candidates were determined is respected.
	 *
	 * @param deleteExclusionLookup returns the excluded folder protecting a device path, null if the path is not protected
	 */
	public DeletionResult deleteFromDevice(final List<DeletionCandidate> candidates, final UnaryOperator<String> deleteExclusionLookup) throws Exception {
		int deleted = 0;
		long deletedBytes = 0;
		int skipped = 0;
		int errors = 0;
		int done = 0;
		for (final DeletionCandidate candidate : candidates) {
			if (cancelled) {
				break;
			}
			final MtpFile scannedFile = candidate.file();
			listener.onProgress(done++, candidates.size(), scannedFile.path());
			final String exclusion = deleteExclusionLookup.apply(scannedFile.path());
			if (exclusion != null) {
				skipped++;
				listener.onLog(LangResources.get("notDeletedProtected", scannedFile.path(), exclusion));
				continue;
			}
			try {
				final MtpFile currentFile = mtpFileSystem.getFile(scannedFile.path());
				if (currentFile == null || currentFile.directory()) {
					skipped++;
				} else if (currentFile.size() != scannedFile.size() || toEpochMillis(currentFile.modified()) != toEpochMillis(scannedFile.modified())) {
					skipped++;
					listener.onLog(LangResources.get("notDeletedChanged", scannedFile.path()));
				} else {
					mtpFileSystem.delete(scannedFile.path(), false);
					deleted++;
					deletedBytes += Math.max(0, scannedFile.size());
					listener.onLog(LangResources.get("deletedFromPhoneBackedUpAs", scannedFile.path(), candidate.targetPath()));
				}
			} catch (final Exception e) {
				if (MtpErrorType.isConnectionLost(e)) {
					// All further deletions would fail too
					throw e;
				}
				errors++;
				listener.onLog(LangResources.get("deleteErrorAt", scannedFile.path(), e.getMessage()));
			}
		}
		listener.onProgress(done, candidates.size(), null);
		return new DeletionResult(deleted, deletedBytes, skipped, errors, cancelled);
	}

	/**
	 * Size of a stored file in the share, null if it does not exist. Folder listings are cached,
	 * because the files of one month share a folder.
	 *
	 * @param relativeTargetPath path relative to the share's base path
	 */
	private Long getTargetFileSize(final String relativeTargetPath, final Map<String, Map<String, Long>> folderCache) throws Exception {
		final String targetPath = share.resolve(relativeTargetPath);
		final int separatorIndex = targetPath.lastIndexOf('/');
		final String folderPath = separatorIndex < 0 ? "" : targetPath.substring(0, separatorIndex);
		final String fileName = targetPath.substring(separatorIndex + 1);

		Map<String, Long> folderFiles = folderCache.get(folderPath);
		if (folderFiles == null) {
			folderFiles = new HashMap<>();
			if (folderPath.isEmpty() || smbConnection.folderExists(folderPath)) {
				final var directory = smbConnection.getDirectory(folderPath);
				for (final FileInfo fileInfo : directory.getSecond()) {
					folderFiles.put(fileInfo.getFileName(), fileInfo.getFileSize());
				}
			}
			folderCache.put(folderPath, folderFiles);
		}
		return folderFiles.get(fileName);
	}

	/**
	 * Saves one file.
	 *
	 * @return true if the file was copied, false if its content already existed in the share
	 */
	private boolean backupFile(final MtpFile mediaFile, final long sourceModified, final BackupIndex index) throws Exception {
		final File tempFile = Files.createTempFile("handybackup_", "_" + mediaFile.name().replaceAll("[\\\\/:*?\"<>|]", "_")).toFile();
		try {
			mtpFileSystem.download(mediaFile.path(), tempFile);
			final String sha256 = sha256(tempFile);
			final long size = tempFile.length();
			final String backupTime = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

			final BackupIndex.Entry existingEntry = index.findByHash(sha256);
			if (existingEntry != null) {
				// Same content already saved (e.g. from the old phone): only remember the source
				index.add(new BackupIndex.Entry(sha256, size, existingEntry.targetPath(), device.serialNumber(), mediaFile.path(), sourceModified, backupTime));
				listener.onLog(LangResources.get("alreadyPresent", mediaFile.name(), existingEntry.targetPath()));
				return false;
			}

			// Date for the folder structure; MTP modification date for now (EXIF etc. may follow)
			final LocalDateTime date = mediaFile.modified() != null ? mediaFile.modified() : LocalDateTime.now();
			final String relativeFolder = String.format(Locale.ROOT, "%04d/%04d-%02d", date.getYear(), date.getYear(), date.getMonthValue());
			smbConnection.createFolderPath(share.resolve(relativeFolder));

			final String relativeTargetPath = findFreeTargetPath(relativeFolder, mediaFile.name());
			final String targetPath = share.resolve(relativeTargetPath);
			final String partPath = targetPath + PART_SUFFIX;
			if (smbConnection.fileExists(partPath)) {
				// Leftover of an interrupted earlier run
				smbConnection.deleteFile(partPath);
			}
			try (InputStream inputStream = Files.newInputStream(tempFile.toPath())) {
				smbConnection.uploadFile(partPath, inputStream);
			}
			// Rename only after a complete upload, so the share never contains half written media files
			smbConnection.renameFile(partPath, targetPath, false);
			if (sourceModified >= 0) {
				smbConnection.setLastModified(targetPath, sourceModified);
			}

			index.add(new BackupIndex.Entry(sha256, size, relativeTargetPath, device.serialNumber(), mediaFile.path(), sourceModified, backupTime));
			listener.onLog(LangResources.get("backedUpFile", mediaFile.name(), relativeTargetPath));
			return true;
		} finally {
			Files.deleteIfExists(tempFile.toPath());
		}
	}

	/**
	 * Returns "folder/name.ext", or "folder/name_1.ext", "folder/name_2.ext" ... if the name is already used.
	 */
	private String findFreeTargetPath(final String relativeFolder, final String fileName) throws Exception {
		final int dotIndex = fileName.lastIndexOf('.');
		final String baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
		final String extension = dotIndex > 0 ? fileName.substring(dotIndex) : "";
		String candidate = relativeFolder + "/" + fileName;
		for (int suffix = 1; smbConnection.fileExists(share.resolve(candidate)); suffix++) {
			candidate = relativeFolder + "/" + baseName + "_" + suffix + extension;
		}
		return candidate;
	}

	/**
	 * Collects all files to back up below the folders of {@link StoragePaths} and the folders added for the device
	 * on all storages of the device. Overlapping entries (e.g. "/DCIM" and "/DCIM/Camera") are scanned only once;
	 * the most specific entry decides which files of a folder are backed up.
	 * A folder added for the device is scanned even if its own name would exclude it (e.g. a hidden folder),
	 * because it was chosen explicitly; excluded folders below it are skipped as usual.
	 */
	public static List<MtpFile> scanMediaFiles(final MtpFileSystem mtpFileSystem, final Collection<BackupFolder> customBackupFolders, final BooleanSupplier cancelCheck, final BackupListener listener) throws IOException {
		final Map<String, MtpFile> result = new LinkedHashMap<>();
		final Set<String> visitedFolders = new HashSet<>();
		for (final MtpFile storage : mtpFileSystem.list("/")) {
			for (final String startPath : StoragePaths.getStartFolders(storage.path(), customBackupFolders)) {
				if (cancelCheck.getAsBoolean()) {
					return new ArrayList<>(result.values());
				}
				if (visitedFolders.contains(startPath.toLowerCase(Locale.ROOT))) {
					continue;
				}
				final MtpFile startFolder = mtpFileSystem.getFile(startPath);
				if (startFolder == null || !startFolder.directory()) {
					continue;
				}

				final Deque<MtpFile> foldersToScan = new ArrayDeque<>();
				foldersToScan.push(startFolder);
				visitedFolders.add(startFolder.path().toLowerCase(Locale.ROOT));
				while (!foldersToScan.isEmpty() && !cancelCheck.getAsBoolean()) {
					final MtpFile folder = foldersToScan.pop();
					if (listener != null) {
						listener.onProgress(0, 0, folder.path());
					}
					for (final MtpFile entry : mtpFileSystem.list(folder.path())) {
						if (entry.directory()) {
							if (!isExcludedFolder(entry) && visitedFolders.add(entry.path().toLowerCase(Locale.ROOT))) {
								foldersToScan.push(entry);
							}
						} else if (StoragePaths.isBackupFile(entry.path(), customBackupFolders)) {
							result.putIfAbsent(entry.path(), entry);
						}
					}
				}
			}
		}
		return new ArrayList<>(result.values());
	}

	/**
	 * Hidden folders (".thumbnails", ".trashed-...", WhatsApp ".Statuses") and WhatsApp's
	 * "Sent" and "Private" folders are not backed up.
	 */
	public static boolean isExcludedFolder(final MtpFile folder) {
		return isExcludedFolder(folder.name(), folder.path());
	}

	private static boolean isExcludedFolder(final String name, final String path) {
		if (name.startsWith(".")) {
			return true;
		}
		return EXCLUDED_WHATSAPP_FOLDERS.contains(name.toLowerCase(Locale.ROOT)) && path.toLowerCase(Locale.ROOT).contains("/whatsapp");
	}

	/**
	 * Checks whether the scan of {@link #scanMediaFiles(MtpFileSystem, Collection, BooleanSupplier, BackupListener)}
	 * reaches the given device folder: it must be a start folder or lie below one, without an excluded folder
	 * ({@link #isExcludedFolder(MtpFile)}) in between. Used to mark backed-up folders in the device tree.
	 *
	 * @param devicePath MTP path of the folder including the storage
	 */
	public static boolean isScannedFolder(final String devicePath, final Collection<BackupFolder> customBackupFolders) {
		final String path = HandyBackupConfiguration.normalizeDevicePath(devicePath);
		final String storage = StoragePaths.getStorageOf(path);
		if (path == null || storage == null) {
			return false;
		}
		final String lowerPath = path.toLowerCase(Locale.ROOT);
		for (final String startFolder : StoragePaths.getStartFolders(storage, customBackupFolders)) {
			final String lowerStart = startFolder.toLowerCase(Locale.ROOT);
			if (lowerPath.equals(lowerStart)) {
				return true;
			} else if (lowerPath.startsWith(lowerStart + "/") && !containsExcludedFolder(path, startFolder.length())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether one of the folders of the path after the given start index is excluded by {@link #isExcludedFolder(MtpFile)}.
	 */
	private static boolean containsExcludedFolder(final String path, final int startIndex) {
		int separatorIndex = path.indexOf('/', startIndex);
		while (separatorIndex >= 0) {
			final int nextSeparatorIndex = path.indexOf('/', separatorIndex + 1);
			final String folderPath = nextSeparatorIndex < 0 ? path : path.substring(0, nextSeparatorIndex);
			if (isExcludedFolder(folderPath.substring(separatorIndex + 1), folderPath)) {
				return true;
			}
			separatorIndex = nextSeparatorIndex;
		}
		return false;
	}

	/**
	 * Modification date as stored in the index (epoch millis, -1 if unknown).
	 */
	static long toEpochMillis(final LocalDateTime dateTime) {
		return dateTime == null ? -1 : dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
	}

	private static String sha256(final File file) throws Exception {
		final MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try (InputStream inputStream = new DigestInputStream(Files.newInputStream(file.toPath()), digest)) {
			inputStream.transferTo(OutputStream.nullOutputStream());
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}

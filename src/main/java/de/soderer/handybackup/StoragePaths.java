package de.soderer.handybackup;

import static de.soderer.handybackup.BackupType.ALL;
import static de.soderer.handybackup.BackupType.IMAGES;
import static de.soderer.handybackup.BackupType.MUSIC;
import static de.soderer.handybackup.BackupType.VIDEOS;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public class StoragePaths {
	// Path: "Dieser PC\HuDeanY's Phone\Interner gemeinsamer Speicher"
	// Modellname "Pixel 7 Pro"
	// Hersteller: "Google"
	// "CF7647EADF473EDAE7F7F5C8C361C64F"
	// Stromquelle: "Akku" 49%

	/**
	 * Folders to back up, relative to a storage of the device. Subfolders are included.
	 * For overlapping entries the most specific one decides which files are backed up
	 * (e.g. "WhatsApp Images" only images, although "/Android/media" also includes videos).
	 *
	 * Entries marked with {@link StoragePath#withDeleteProtected(boolean)} are excluded from the deletion
	 * after a backup by default (including subfolders). The default can be lifted per device in the device tree.
	 *
	 * Further folders can be added per device via the context menu of the device tree (see {@link BackupFolder}).
	 */
	public static final List<StoragePath> PATHS = List.of(
		new StoragePath("/DCIM", IMAGES, VIDEOS),
		new StoragePath("/DCIM/Camera", IMAGES, VIDEOS),
		new StoragePath("/DCIM/Screenshots", IMAGES, VIDEOS),
		new StoragePath("/Pictures", IMAGES),
		new StoragePath("/Pictures/Screenshots", IMAGES),
		new StoragePath("/Pictures/WhatsApp", IMAGES),
		new StoragePath("/Pictures/Telegram", IMAGES),
		new StoragePath("/Pictures/Instagram", IMAGES),
		new StoragePath("/Movies", VIDEOS),
		new StoragePath("/Movies/Telegram", VIDEOS),
		new StoragePath("/Telegram", ALL),
		new StoragePath("/Download", ALL),
		new StoragePath("/Downloads", ALL),
		new StoragePath("/Documents", ALL),
		new StoragePath("/Android/media", IMAGES, VIDEOS),
		new StoragePath("/Android/media/com.whatsapp/WhatsApp/Media", IMAGES, VIDEOS),
		new StoragePath("/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images", IMAGES),
		new StoragePath("/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video", VIDEOS),

		new StoragePath("/Music", MUSIC).withDeleteProtected(true),
		new StoragePath("/Ringtones", MUSIC).withDeleteProtected(true),
		new StoragePath("/Notifications", MUSIC).withDeleteProtected(true),
		new StoragePath("/Podcasts", ALL).withDeleteProtected(true),
		new StoragePath("/Recordings", ALL).withDeleteProtected(true),
		new StoragePath("/Audiobooks", ALL).withDeleteProtected(true)
	);

	/**
	 * Checks whether exactly this folder is excluded from the deletion by default (case-insensitive).
	 * Subfolders are not reported here; the caller checks the parent folders itself.
	 *
	 * @param relativePath folder path relative to the storage, e.g. "/Documents"
	 */
	public static boolean isDeleteProtectedByDefault(final String relativePath) {
		if (relativePath == null) {
			return false;
		}
		String path = relativePath.trim().replace('\\', '/');
		while (path.length() > 1 && path.endsWith("/")) {
			path = path.substring(0, path.length() - 1);
		}
		for (final StoragePath storagePath : PATHS) {
			if (storagePath.isDeleteProtected() && storagePath.getPath().equalsIgnoreCase(path)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Returns the most specific entry of {@link #PATHS} containing the given path (case-insensitive),
	 * or null if the path is not below any entry.
	 *
	 * @param relativePath path relative to the storage, e.g. "/DCIM/Camera/IMG_0001.jpg"
	 */
	public static StoragePath findStoragePath(final String relativePath) {
		if (relativePath == null) {
			return null;
		}
		final String lowerPath = relativePath.toLowerCase(Locale.ROOT);
		StoragePath result = null;
		for (final StoragePath storagePath : PATHS) {
			final String lowerStoragePath = storagePath.getPath().toLowerCase(Locale.ROOT);
			if ((lowerPath.equals(lowerStoragePath) || lowerPath.startsWith(lowerStoragePath + "/"))
				&& (result == null || storagePath.getPath().length() > result.getPath().length())) {
				result = storagePath;
			}
		}
		return result;
	}

	/**
	 * Checks whether a file is backed up, according to the most specific entry of {@link #PATHS} containing it.
	 *
	 * @param relativeFilePath file path relative to the storage, e.g. "/DCIM/Camera/IMG_0001.jpg"
	 */
	public static boolean isBackupFile(final String relativeFilePath) {
		final StoragePath storagePath = findStoragePath(relativeFilePath);
		if (storagePath == null) {
			return false;
		} else {
			final int separatorIndex = relativeFilePath.lastIndexOf('/');
			return storagePath.matches(relativeFilePath.substring(separatorIndex + 1));
		}
	}

	/**
	 * Storage part of a device path: "/&lt;storage&gt;" for "/&lt;storage&gt;/DCIM/Camera", null for null or the device root.
	 */
	public static String getStorageOf(final String devicePath) {
		final String path = HandyBackupConfiguration.normalizeDevicePath(devicePath);
		if (path == null) {
			return null;
		}
		final int storageEnd = path.indexOf('/', 1);
		return storageEnd < 0 ? path : path.substring(0, storageEnd);
	}

	/**
	 * Returns the most specific backup folder containing the given device path (case-insensitive),
	 * taking into account the defaults of {@link #PATHS} on every storage and the folders added for the device.
	 * For equally specific entries the folder added for the device wins, so it can change the file types of a default.
	 *
	 * @param devicePath    MTP path including the storage, e.g. "/&lt;storage&gt;/DCIM/Camera/IMG_0001.jpg"
	 * @param customFolders folders added for the device, may be empty
	 * @return the deciding folder, null if the path is not below any backup folder
	 */
	public static BackupFolder findBackupFolder(final String devicePath, final Collection<BackupFolder> customFolders) {
		final String path = HandyBackupConfiguration.normalizeDevicePath(devicePath);
		final String storage = getStorageOf(path);
		if (path == null || storage == null) {
			return null;
		}

		BackupFolder result = null;
		int resultDepth = -1;
		final StoragePath storagePath = path.length() > storage.length() ? findStoragePath(path.substring(storage.length())) : null;
		if (storagePath != null) {
			result = new BackupFolder(storage + storagePath.getPath(), storagePath.getBackupTypes(), false);
			resultDepth = storagePath.getPath().length();
		}
		if (customFolders != null) {
			for (final BackupFolder customFolder : customFolders) {
				final int depth = customFolder.devicePath().length() - storage.length();
				if (customFolder.contains(path) && depth >= resultDepth) {
					result = customFolder;
					resultDepth = depth;
				}
			}
		}
		return result;
	}

	/**
	 * Checks whether a file is backed up according to {@link #findBackupFolder(String, Collection)}.
	 *
	 * @param deviceFilePath MTP path of the file including the storage
	 */
	public static boolean isBackupFile(final String deviceFilePath, final Collection<BackupFolder> customFolders) {
		final BackupFolder backupFolder = findBackupFolder(deviceFilePath, customFolders);
		if (backupFolder == null) {
			return false;
		} else {
			return backupFolder.matches(deviceFilePath.substring(deviceFilePath.lastIndexOf('/') + 1));
		}
	}

	/**
	 * Start folders of the scan on one storage: the defaults of {@link #PATHS} followed by the folders added
	 * for the device on this storage.
	 *
	 * @param storage MTP path of the storage, e.g. "/&lt;storage&gt;"
	 */
	public static List<String> getStartFolders(final String storage, final Collection<BackupFolder> customFolders) {
		final List<String> result = new ArrayList<>();
		for (final StoragePath storagePath : PATHS) {
			result.add(storage + storagePath.getPath());
		}
		if (customFolders != null) {
			for (final BackupFolder customFolder : customFolders) {
				if (storage.equalsIgnoreCase(getStorageOf(customFolder.devicePath()))) {
					result.add(customFolder.devicePath());
				}
			}
		}
		return result;
	}
}

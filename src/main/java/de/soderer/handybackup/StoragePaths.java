package de.soderer.handybackup;

import static de.soderer.handybackup.BackupType.ALL;
import static de.soderer.handybackup.BackupType.IMAGES;
import static de.soderer.handybackup.BackupType.MUSIC;
import static de.soderer.handybackup.BackupType.VIDEOS;

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
}

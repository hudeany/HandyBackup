package de.soderer.handybackup;

import java.util.Locale;
import java.util.Set;

/**
 * Kind of files to back up from a storage path.
 */
public enum BackupType {
	/** All files, no extension filter */
	ALL(),

	/** Image files */
	IMAGES(
		"jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "bmp",
		"dng"), // RAW images from Pixel/Samsung camera apps

	/** Music files */
	MUSIC(
		"mp3", "ogg"),

	/** Video files */
	VIDEOS(
		"mp4", "mov", "3gp", "3g2", "mkv", "webm", "m4v", "avi"),

	/** Contact files (vCard) */
	CONTACTS(
		"vcf");

	private final Set<String> extensions;

	BackupType(final String... extensions) {
		this.extensions = Set.of(extensions);
	}

	/**
	 * Lowercase file extensions without leading dot. Empty for {@link #ALL}.
	 */
	public Set<String> getExtensions() {
		return extensions;
	}

	/**
	 * Checks whether the given file name belongs to this backup type.
	 */
	public boolean matches(final String fileName) {
		if (this == ALL) {
			return true;
		} else if (fileName == null) {
			return false;
		} else {
			final int dotIndex = fileName.lastIndexOf('.');
			if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
				return false;
			} else {
				return extensions.contains(fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT));
			}
		}
	}
}

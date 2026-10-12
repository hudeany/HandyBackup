package de.soderer.handybackup;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * A path on the device storage together with the kinds of files to back up from it.
 */
public class StoragePath {
	private final String path;
	private final Set<BackupType> backupTypes;
	private boolean deleteProtected = false;

	public StoragePath(final String path, final BackupType firstBackupType, final BackupType... furtherBackupTypes) {
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("Path must not be empty");
		} else if (firstBackupType == null) {
			throw new IllegalArgumentException("At least one backup type is required");
		}

		this.path = path;
		this.backupTypes = Collections.unmodifiableSet(EnumSet.of(firstBackupType, furtherBackupTypes));
	}

	public String getPath() {
		return path;
	}

	public Set<BackupType> getBackupTypes() {
		return backupTypes;
	}

	/**
	 * Whether this folder (including its subfolders) is excluded from the deletion after a backup by default.
	 * The default can be lifted per device via the context menu of the device tree.
	 */
	public boolean isDeleteProtected() {
		return deleteProtected;
	}

	public void setDeleteProtected(final boolean deleteProtected) {
		this.deleteProtected = deleteProtected;
	}

	public StoragePath withDeleteProtected(final boolean newDeleteProtected) {
		setDeleteProtected(newDeleteProtected);
		return this;
	}

	/**
	 * Checks whether a file with the given name should be backed up from this path.
	 */
	public boolean matches(final String fileName) {
		for (final BackupType backupType : backupTypes) {
			if (backupType.matches(fileName)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString() {
		return path + " " + backupTypes;
	}
}

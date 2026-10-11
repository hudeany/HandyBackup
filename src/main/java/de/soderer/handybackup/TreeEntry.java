package de.soderer.handybackup;

import java.time.LocalDateTime;

/**
 * Entry shown in the device tree or the share tree.
 *
 * @param name           display name
 * @param path           full path (MTP path on the device, or path relative to the share root)
 * @param directory      true for folders, storages and the root
 * @param size           file size in bytes, -1 for folders or if unknown
 * @param modified       modification date, null if unknown
 * @param backupRelevant true if this folder is covered by {@link StoragePaths} and therefore backed up
 */
public record TreeEntry(String name, String path, boolean directory, long size, LocalDateTime modified, boolean backupRelevant) {
	@Override
	public String toString() {
		return name;
	}
}

package de.soderer.handybackup;

import java.io.Closeable;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Arrays;

import de.soderer.remote.DirectoryListing;
import de.soderer.remote.SmbConnection;

/**
 * Access to the storage of a backup target (Samba share or local folder).
 *
 * All paths use "/" as separator and are relative to the root of the target: the share root for a
 * Samba share, the selected folder for a local folder. "" is the root itself.
 */
public interface BackupTarget extends Closeable {
	/**
	 * Opens the storage of a backup target.
	 *
	 * @param share definition of the backup target
	 * @return open access to the target, to be closed by the caller
	 * @throws Exception if the target is not reachable (server not reachable, wrong password, folder or drive missing)
	 */
	static BackupTarget open(final ShareDefinition share) throws Exception {
		if (share.isLocal()) {
			return new LocalBackupTarget(Path.of(share.getLocalPath()));
		} else {
			final char[] password = share.getPassword();
			try {
				return new SmbBackupTarget(new SmbConnection(share.getHostname(), share.getPort(), share.getUsername(), share.getSharename(), password));
			} finally {
				Arrays.fill(password, '\0');
			}
		}
	}

	/**
	 * Whether a file exists at this path (false for folders).
	 */
	boolean fileExists(String filePath) throws Exception;

	/**
	 * Whether a folder exists at this path (false for files).
	 */
	boolean folderExists(String folderPath) throws Exception;

	/**
	 * Creates a folder including all missing parent folders (like mkdir -p). Does nothing if the folder exists.
	 *
	 * @throws Exception if the path exists as file or a folder cannot be created
	 */
	void createFolderPath(String folderPath) throws Exception;

	/**
	 * Lists the sub folders and files of a folder.
	 */
	DirectoryListing getDirectory(String folderPath) throws Exception;

	/**
	 * Opens a file for reading.
	 *
	 * @return stream with the file content, the caller must close it
	 */
	InputStream downloadFile(String filePath) throws Exception;

	/**
	 * Writes a new file. Fails if the file already exists, existing files are never overwritten.
	 *
	 * @param inputStream data to write, is not closed
	 */
	void uploadFile(String filePath, InputStream inputStream) throws Exception;

	/**
	 * Deletes a file. Fails for folders, so a folder is never deleted recursively by mistake.
	 */
	void deleteFile(String filePath) throws Exception;

	/**
	 * Renames or moves a file within the target.
	 *
	 * @param replaceExisting true to replace an existing target file, false to fail if it exists
	 */
	void renameFile(String fromPath, String toPath, boolean replaceExisting) throws Exception;

	/**
	 * Sets the modification time of a file.
	 *
	 * @param epochMillis new modification time in milliseconds since 1970
	 */
	void setLastModified(String filePath, long epochMillis) throws Exception;

	/**
	 * Releases the target. Never throws.
	 */
	@Override
	void close();
}

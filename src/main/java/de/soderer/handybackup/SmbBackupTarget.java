package de.soderer.handybackup;

import java.io.InputStream;

import de.soderer.remote.DirectoryListing;
import de.soderer.remote.SmbConnection;

/**
 * Backup target on a Samba share. Paths are relative to the share root.
 */
public class SmbBackupTarget implements BackupTarget {
	private final SmbConnection connection;

	/**
	 * @param connection open connection to the share, closed by {@link #close()}
	 */
	public SmbBackupTarget(final SmbConnection connection) {
		if (connection == null) {
			throw new IllegalArgumentException("Connection must not be null");
		}
		this.connection = connection;
	}

	@Override
	public boolean fileExists(final String filePath) throws Exception {
		return connection.fileExists(filePath);
	}

	@Override
	public boolean folderExists(final String folderPath) throws Exception {
		return connection.folderExists(folderPath);
	}

	@Override
	public void createFolderPath(final String folderPath) throws Exception {
		connection.createFolderPath(folderPath);
	}

	@Override
	public DirectoryListing getDirectory(final String folderPath) throws Exception {
		return connection.getDirectory(folderPath);
	}

	@Override
	public InputStream downloadFile(final String filePath) throws Exception {
		return connection.downloadFile(filePath);
	}

	@Override
	public void uploadFile(final String filePath, final InputStream inputStream) throws Exception {
		connection.uploadFile(filePath, inputStream);
	}

	@Override
	public void deleteFile(final String filePath) throws Exception {
		connection.deleteFile(filePath);
	}

	@Override
	public void renameFile(final String fromPath, final String toPath, final boolean replaceExisting) throws Exception {
		connection.renameFile(fromPath, toPath, replaceExisting);
	}

	@Override
	public void setLastModified(final String filePath, final long epochMillis) throws Exception {
		connection.setLastModified(filePath, epochMillis);
	}

	@Override
	public void close() {
		connection.close();
	}
}

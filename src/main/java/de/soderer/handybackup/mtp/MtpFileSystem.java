package de.soderer.handybackup.mtp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * File system of an opened MTP device: listing folders, downloading, uploading and deleting files,
 * creating folders. Created by {@link MtpManager#open(MtpDevice)}; close it when done to release the device.
 *
 * Paths are absolute, use "/" as separator and start with the storage name as shown in Explorer,
 * e.g. "/Interner gemeinsamer Speicher/DCIM/Camera". The path "/" lists the device's storages.
 *
 * Instances are thread safe, but all operations of one MtpManager are executed one after another.
 */
public class MtpFileSystem implements AutoCloseable {
	private final MtpManager manager;
	private final String handle;
	private boolean closed = false;

	MtpFileSystem(final MtpManager manager, final String handle) {
		this.manager = manager;
		this.handle = handle;
	}

	/**
	 * Lists the files and folders in a folder. The path "/" lists the device's storages.
	 *
	 * @param path absolute folder path, e.g. "/Interner gemeinsamer Speicher/DCIM"
	 */
	public List<MtpFile> list(final String path) throws IOException {
		return toMtpFiles(execute("LIST", path));
	}

	/**
	 * Returns a file or folder, or null if it does not exist.
	 *
	 * @param path absolute path on the device
	 */
	public MtpFile getFile(final String path) throws IOException {
		final List<MtpFile> result = toMtpFiles(execute("STAT", path));
		return result.isEmpty() ? null : result.get(0);
	}

	/**
	 * Checks whether a file or folder exists.
	 *
	 * @param path absolute path on the device
	 */
	public boolean exists(final String path) throws IOException {
		return getFile(path) != null;
	}

	/**
	 * Downloads a file from the device. An existing local file is overwritten. The local file gets the
	 * modification date reported by the device. On failure a partially written local file is deleted.
	 *
	 * @param path       absolute file path on the device
	 * @param targetFile local file to write
	 */
	public void download(final String path, final File targetFile) throws IOException {
		final File absoluteTargetFile = targetFile.getAbsoluteFile();
		final File parentDirectory = absoluteTargetFile.getParentFile();
		if (parentDirectory != null && !parentDirectory.isDirectory() && !parentDirectory.mkdirs()) {
			throw new IOException("Cannot create local folder: " + parentDirectory);
		}
		execute("DOWNLOAD", path, absoluteTargetFile.getPath());
	}

	/**
	 * Uploads a local file into a folder on the device, keeping its file name.
	 * Fails if a file or folder with that name already exists there.
	 *
	 * @param sourceFile          local file to upload
	 * @param targetDirectoryPath absolute folder path on the device
	 * @return the created file
	 */
	public MtpFile upload(final File sourceFile, final String targetDirectoryPath) throws IOException {
		if (!sourceFile.isFile()) {
			throw new IOException("Local file not found: " + sourceFile);
		}
		final List<MtpFile> result = toMtpFiles(execute("UPLOAD", sourceFile.getAbsolutePath(), targetDirectoryPath));
		if (result.isEmpty()) {
			throw new IOException("Upload finished without result: " + sourceFile);
		}
		return result.get(0);
	}

	/**
	 * Deletes a file or folder on the device. Storages cannot be deleted.
	 *
	 * @param path      absolute path on the device
	 * @param recursive if true, folders are deleted including their contents; if false, deleting a non-empty folder fails
	 */
	public void delete(final String path, final boolean recursive) throws IOException {
		execute("DELETE", path, recursive ? "1" : "0");
	}

	/**
	 * Creates a folder on the device. The parent folder must exist.
	 *
	 * @param path absolute path of the new folder, e.g. "/Interner gemeinsamer Speicher/Backup"
	 * @return the created folder
	 */
	public MtpFile createDirectory(final String path) throws IOException {
		final List<MtpFile> result = toMtpFiles(execute("MKDIR", path));
		if (result.isEmpty()) {
			throw new IOException("Folder creation finished without result: " + path);
		}
		return result.get(0);
	}

	/**
	 * Releases the device. The manager and its helper process stay open.
	 */
	@Override
	public synchronized void close() {
		if (closed) {
			return;
		}
		closed = true;
		if (manager.isOpen()) {
			try {
				manager.execute("CLOSE", handle);
			} catch (@SuppressWarnings("unused") final IOException e) {
				// Device is released with the helper process at the latest
			}
		}
	}

	private List<String[]> execute(final String command, final String... arguments) throws IOException {
		synchronized (this) {
			if (closed) {
				throw new IOException("MTP file system is already closed");
			}
		}
		final String[] argumentsWithHandle = new String[arguments.length + 1];
		argumentsWithHandle[0] = handle;
		System.arraycopy(arguments, 0, argumentsWithHandle, 1, arguments.length);
		return manager.execute(command, argumentsWithHandle);
	}

	private static List<MtpFile> toMtpFiles(final List<String[]> lines) throws IOException {
		final List<MtpFile> result = new ArrayList<>();
		for (final String[] parts : lines) {
			result.add(MtpManager.toMtpFile(parts));
		}
		return result;
	}
}

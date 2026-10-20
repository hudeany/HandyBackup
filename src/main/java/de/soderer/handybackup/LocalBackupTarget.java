package de.soderer.handybackup;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.soderer.remote.DirectoryListing;
import de.soderer.remote.FileInfo;
import de.soderer.remote.FolderInfo;

/**
 * Backup target in a folder of the local computer (internal disk, external USB disk, mapped network drive).
 * Paths are relative to this folder; paths leading outside of it (e.g. via "..") are rejected.
 *
 * The folder must exist when the target is opened, it is never created here. So a backup to an external disk
 * that is not connected fails instead of writing into an empty mount point or a new folder on another drive.
 */
public class LocalBackupTarget implements BackupTarget {
	private final Path rootFolder;
	private volatile boolean closed = false;

	/**
	 * @param rootFolder absolute path of an existing folder
	 * @throws Exception if the path is not absolute or the folder does not exist
	 */
	public LocalBackupTarget(final Path rootFolder) throws Exception {
		if (rootFolder == null || !rootFolder.isAbsolute()) {
			throw new Exception("Backup folder must be an absolute path: " + rootFolder);
		} else if (!Files.isDirectory(rootFolder)) {
			throw new Exception("Backup folder does not exist or is not accessible (drive not connected?): " + rootFolder);
		}
		this.rootFolder = rootFolder.toRealPath();
	}

	/**
	 * The folder all paths are relative to.
	 */
	public Path getRootFolder() {
		return rootFolder;
	}

	private void checkOpen() throws Exception {
		if (closed) {
			throw new Exception("LocalBackupTarget is closed");
		}
	}

	/**
	 * Checks before writing that the root folder is still there, e.g. that the external disk was not removed meanwhile.
	 */
	private void checkRootFolderAvailable() throws Exception {
		if (!Files.isDirectory(rootFolder)) {
			throw new Exception("Backup folder is no longer available (drive disconnected?): " + rootFolder);
		}
	}

	/**
	 * Resolves a "/" separated relative path below the root folder. Every path element must be a plain name,
	 * so "..", "." and drive or root specifications cannot lead outside of the root folder.
	 */
	private Path resolve(final String path) throws Exception {
		checkOpen();
		final String normalized = ShareDefinition.normalizePath(path);
		Path result = rootFolder;
		if (!normalized.isEmpty()) {
			for (final String element : normalized.split("/", -1)) {
				if (element.isEmpty() || ".".equals(element) || "..".equals(element)) {
					throw new Exception("Invalid path: " + path);
				}
				final Path elementPath = rootFolder.getFileSystem().getPath(element);
				if (elementPath.getRoot() != null || elementPath.getNameCount() != 1) {
					// e.g. "C:name" on Windows, which would be relative to the current folder of drive C
					throw new Exception("Invalid path: " + path);
				}
				result = result.resolve(elementPath);
			}
		}
		return result;
	}

	private static ZonedDateTime toZonedDateTime(final FileTime fileTime) {
		return fileTime == null ? null : fileTime.toInstant().atZone(ZoneId.systemDefault());
	}

	@Override
	public boolean fileExists(final String filePath) throws Exception {
		return Files.isRegularFile(resolve(filePath));
	}

	@Override
	public boolean folderExists(final String folderPath) throws Exception {
		return Files.isDirectory(resolve(folderPath));
	}

	@Override
	public void createFolderPath(final String folderPath) throws Exception {
		final Path folder = resolve(folderPath);
		checkRootFolderAvailable();
		if (Files.exists(folder) && !Files.isDirectory(folder)) {
			throw new Exception("Path '" + folderPath + "' already exists as a file");
		}
		Files.createDirectories(folder);
	}

	@Override
	public DirectoryListing getDirectory(final String folderPath) throws Exception {
		final Path folder = resolve(folderPath);
		final List<FolderInfo> folders = new ArrayList<>();
		final List<FileInfo> files = new ArrayList<>();
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
			for (final Path entry : entries) {
				final BasicFileAttributes attributes;
				try {
					attributes = Files.readAttributes(entry, BasicFileAttributes.class);
				} catch (@SuppressWarnings("unused") final IOException e) {
					// e.g. a broken link or an entry deleted meanwhile
					continue;
				}
				final String name = entry.getFileName().toString();
				if (attributes.isDirectory()) {
					folders.add(new FolderInfo(name, toZonedDateTime(attributes.lastModifiedTime())));
				} else if (attributes.isRegularFile()) {
					files.add(new FileInfo(name, attributes.size(), toZonedDateTime(attributes.lastModifiedTime())));
				}
			}
		}
		folders.sort(Comparator.comparing(FolderInfo::getFolderName, String.CASE_INSENSITIVE_ORDER));
		files.sort(Comparator.comparing(FileInfo::getFileName, String.CASE_INSENSITIVE_ORDER));
		return new DirectoryListing(folders, files);
	}

	@Override
	public InputStream downloadFile(final String filePath) throws Exception {
		final Path file = resolve(filePath);
		if (!Files.isRegularFile(file)) {
			throw new NoSuchFileException(file.toString(), null, "File does not exist");
		}
		return Files.newInputStream(file);
	}

	@Override
	public void uploadFile(final String filePath, final InputStream inputStream) throws Exception {
		final Path file = resolve(filePath);
		checkRootFolderAvailable();
		// CREATE_NEW: fails if the file exists, like the exclusive create of SmbConnection
		final OutputStream outputStream = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
		boolean success = false;
		try {
			try (outputStream) {
				inputStream.transferTo(outputStream);
			}
			success = true;
		} finally {
			if (!success) {
				// The file was created by this call, so a partially written file can be removed safely
				try {
					Files.deleteIfExists(file);
				} catch (@SuppressWarnings("unused") final IOException e) {
					// Keep the original exception
				}
			}
		}
	}

	@Override
	public void deleteFile(final String filePath) throws Exception {
		final Path file = resolve(filePath);
		if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
			// Never delete a folder by mistake
			throw new Exception("Path '" + filePath + "' is a folder");
		}
		Files.delete(file);
	}

	@Override
	public void renameFile(final String fromPath, final String toPath, final boolean replaceExisting) throws Exception {
		final Path source = resolve(fromPath);
		final Path target = resolve(toPath);
		if (replaceExisting) {
			try {
				// Replaces an existing target in one step on Windows and POSIX file systems
				Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
			} catch (@SuppressWarnings("unused") final AtomicMoveNotSupportedException e) {
				Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
			}
		} else {
			if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
				throw new FileAlreadyExistsException(target.toString());
			}
			// Without REPLACE_EXISTING the move also fails if the target was created meanwhile
			Files.move(source, target);
		}
	}

	@Override
	public void setLastModified(final String filePath, final long epochMillis) throws Exception {
		Files.setLastModifiedTime(resolve(filePath), FileTime.fromMillis(epochMillis));
	}

	@Override
	public void close() {
		closed = true;
	}
}

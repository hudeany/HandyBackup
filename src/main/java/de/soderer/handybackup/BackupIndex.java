package de.soderer.handybackup;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.soderer.remote.SmbConnection;

/**
 * Index of all files backed up into one share (one index per share, shared by all devices
 * assigned to it). Stored as tab separated text file in "&lt;basePath&gt;/.handybackup/index.tsv".
 *
 * Used for:
 * - fast skip of files already saved from the same device (serial number + source path + size + modification date)
 * - deduplication by SHA-256, also across devices (e.g. after changing to a new phone)
 */
public class BackupIndex {
	public static final String INDEX_FOLDER = ".handybackup";
	public static final String INDEX_FILE = INDEX_FOLDER + "/index.tsv";

	private static final String HEADER = "#sha256\tsize\ttargetPath\tserialNumber\tsourcePath\tsourceModified\tbackupTime";

	/**
	 * Entry of the index.
	 *
	 * @param sha256         hex SHA-256 of the file content
	 * @param size           file size in bytes
	 * @param targetPath     path of the stored file, relative to the share's base path
	 * @param serialNumber   serial number of the device the file came from
	 * @param sourcePath     path of the file on the device
	 * @param sourceModified modification date on the device in epoch millis, -1 if unknown
	 * @param backupTime     time of the backup (ISO 8601)
	 */
	public record Entry(String sha256, long size, String targetPath, String serialNumber, String sourcePath, long sourceModified, String backupTime) {
	}

	private final List<Entry> entries = new ArrayList<>();
	private final Map<String, Entry> entriesByHash = new HashMap<>();
	private final Map<String, List<Entry>> entriesBySource = new HashMap<>();
	private boolean changed = false;

	/**
	 * Loads the index from the share. Falls back to the backup copy if the index file is missing
	 * (e.g. after an interrupted save). Returns an empty index if neither exists.
	 */
	public static BackupIndex load(final SmbConnection smbConnection, final ShareDefinition share) throws Exception {
		final BackupIndex index = new BackupIndex();
		String indexPath = share.resolve(INDEX_FILE);
		if (!smbConnection.fileExists(indexPath)) {
			indexPath = indexPath + ".bak";
			if (!smbConnection.fileExists(indexPath)) {
				return index;
			}
		}

		try (InputStream inputStream = smbConnection.downloadFile(indexPath);
				BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				final String[] fields = line.split("\t", -1);
				if (fields.length < 7) {
					throw new IOException("Invalid line " + lineNumber + " in backup index " + indexPath);
				}
				index.addInternal(new Entry(fields[0], Long.parseLong(fields[1]), unescape(fields[2]), unescape(fields[3]), unescape(fields[4]), Long.parseLong(fields[5]), fields[6]));
			}
		}
		return index;
	}

	/**
	 * Saves the index to the share: the new content is written to a temporary file, the old index
	 * is kept as ".bak" and the temporary file is then renamed to the index file.
	 */
	public synchronized void save(final SmbConnection smbConnection, final ShareDefinition share) throws Exception {
		final StringBuilder content = new StringBuilder(HEADER).append("\n");
		for (final Entry entry : entries) {
			content.append(entry.sha256()).append('\t')
				.append(entry.size()).append('\t')
				.append(escape(entry.targetPath())).append('\t')
				.append(escape(entry.serialNumber())).append('\t')
				.append(escape(entry.sourcePath())).append('\t')
				.append(entry.sourceModified()).append('\t')
				.append(entry.backupTime()).append('\n');
		}

		final String indexPath = share.resolve(INDEX_FILE);
		final String tempPath = indexPath + ".tmp";
		smbConnection.createFolderPath(share.resolve(INDEX_FOLDER));
		if (smbConnection.fileExists(tempPath)) {
			smbConnection.deleteFile(tempPath);
		}
		smbConnection.uploadFile(tempPath, new ByteArrayInputStream(content.toString().getBytes(StandardCharsets.UTF_8)));
		if (smbConnection.fileExists(indexPath)) {
			smbConnection.renameFile(indexPath, indexPath + ".bak", true);
		}
		smbConnection.renameFile(tempPath, indexPath, false);
		changed = false;
	}

	public synchronized boolean isChanged() {
		return changed;
	}

	public synchronized int size() {
		return entries.size();
	}

	public synchronized Entry findByHash(final String sha256) {
		return entriesByHash.get(sha256);
	}

	/**
	 * Checks whether a file with this source path, size and modification date was already saved
	 * from this device. Allows skipping files without downloading them.
	 */
	public synchronized boolean containsSource(final String serialNumber, final String sourcePath, final long size, final long sourceModified) {
		return findBySource(serialNumber, sourcePath, size, sourceModified) != null;
	}

	/**
	 * Returns the entry of a file saved from this device with this source path, size and modification date,
	 * or null if there is none.
	 */
	public synchronized Entry findBySource(final String serialNumber, final String sourcePath, final long size, final long sourceModified) {
		final List<Entry> sourceEntries = entriesBySource.get(sourceKey(serialNumber, sourcePath));
		if (sourceEntries != null) {
			for (final Entry entry : sourceEntries) {
				if (entry.size() == size && entry.sourceModified() == sourceModified) {
					return entry;
				}
			}
		}
		return null;
	}

	/**
	 * Entries whose stored file is at this path (several devices or source paths may share one stored file).
	 *
	 * @param targetPath path relative to the share's base path
	 */
	public synchronized List<Entry> findByTargetPath(final String targetPath) {
		final List<Entry> result = new ArrayList<>();
		for (final Entry entry : entries) {
			if (entry.targetPath().equals(targetPath)) {
				result.add(entry);
			}
		}
		return result;
	}

	/**
	 * Removes all entries of a stored file, e.g. after it was deleted from the share manually.
	 * Afterwards the same content is backed up again, if it is still on a device.
	 *
	 * @param targetPath path relative to the share's base path
	 * @return number of removed entries
	 */
	public synchronized int removeByTargetPath(final String targetPath) {
		final int sizeBefore = entries.size();
		entries.removeIf(entry -> entry.targetPath().equals(targetPath));
		final int removed = sizeBefore - entries.size();
		if (removed > 0) {
			final List<Entry> remaining = new ArrayList<>(entries);
			entries.clear();
			entriesByHash.clear();
			entriesBySource.clear();
			for (final Entry entry : remaining) {
				addInternal(entry);
			}
			changed = true;
		}
		return removed;
	}

	public synchronized void add(final Entry entry) {
		addInternal(entry);
		changed = true;
	}

	private void addInternal(final Entry entry) {
		entries.add(entry);
		// First stored copy of a content stays the reference for deduplication
		entriesByHash.putIfAbsent(entry.sha256(), entry);
		entriesBySource.computeIfAbsent(sourceKey(entry.serialNumber(), entry.sourcePath()), key -> new ArrayList<>()).add(entry);
	}

	private static String sourceKey(final String serialNumber, final String sourcePath) {
		return serialNumber + "\t" + sourcePath;
	}

	private static String escape(final String value) {
		return value == null ? "" : value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
	}

	private static String unescape(final String value) {
		final StringBuilder result = new StringBuilder(value.length());
		for (int i = 0; i < value.length(); i++) {
			final char c = value.charAt(i);
			if (c == '\\' && i + 1 < value.length()) {
				final char next = value.charAt(++i);
				switch (next) {
					case 't' -> result.append('\t');
					case 'n' -> result.append('\n');
					case 'r' -> result.append('\r');
					default -> result.append(next);
				}
			} else {
				result.append(c);
			}
		}
		return result.toString();
	}
}
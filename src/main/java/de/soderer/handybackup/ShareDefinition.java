package de.soderer.handybackup;

import java.io.File;

/**
 * Backup target: a Samba share or a folder on the local computer. Several devices (e.g. old and new phone
 * of the same person) may be assigned to one target.
 *
 * For a Samba share the password is only kept encrypted (Windows DPAPI, see {@link PasswordProtection}).
 * For a local folder only the name and the folder path are used, the base path is always empty.
 */
public class ShareDefinition {
	/**
	 * Kind of storage of a backup target.
	 */
	public enum TargetType {
		/** Samba share (hostname, port, sharename, basePath, username, password) */
		SMB,

		/** Folder on the local computer (localPath) */
		LOCAL
	}

	private TargetType type = TargetType.SMB;
	private String localPath = "";
	private String name;
	private String hostname;
	private int port = 445;
	private String sharename;
	private String basePath = "";
	private String username;
	private String encryptedPassword;

	public TargetType getType() {
		return type;
	}

	/**
	 * @param type kind of storage, null for {@link TargetType#SMB}
	 */
	public void setType(final TargetType type) {
		this.type = type == null ? TargetType.SMB : type;
		if (this.type == TargetType.LOCAL) {
			// The selected local folder itself is the root of the target
			basePath = "";
		}
	}

	/**
	 * Whether this target is a folder on the local computer.
	 */
	public boolean isLocal() {
		return type == TargetType.LOCAL;
	}

	/**
	 * Absolute path of the folder on the local computer, only used for {@link TargetType#LOCAL}.
	 */
	public String getLocalPath() {
		return localPath;
	}

	public void setLocalPath(final String localPath) {
		this.localPath = localPath == null ? "" : localPath.trim();
	}

	public String getName() {
		return name;
	}

	public void setName(final String name) {
		this.name = name;
	}

	public String getHostname() {
		return hostname;
	}

	public void setHostname(final String hostname) {
		this.hostname = hostname;
	}

	public int getPort() {
		return port;
	}

	public void setPort(final int port) {
		this.port = port;
	}

	public String getSharename() {
		return sharename;
	}

	public void setSharename(final String sharename) {
		this.sharename = sharename;
	}

	/**
	 * Folder inside the share where backups are stored, "" for the share root.
	 * Always uses "/" as separator, without leading or trailing separator.
	 * Always "" for a local folder.
	 */
	public String getBasePath() {
		return basePath;
	}

	public void setBasePath(final String basePath) {
		this.basePath = type == TargetType.LOCAL ? "" : normalizePath(basePath);
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(final String username) {
		this.username = username;
	}

	public String getEncryptedPassword() {
		return encryptedPassword;
	}

	public void setEncryptedPassword(final String encryptedPassword) {
		this.encryptedPassword = encryptedPassword;
	}

	/**
	 * Decrypts the stored password. The caller should wipe the returned array after use.
	 */
	public char[] getPassword() throws Exception {
		return encryptedPassword == null || encryptedPassword.isEmpty() ? new char[0] : PasswordProtection.decrypt(encryptedPassword);
	}

	/**
	 * Encrypts and stores the password.
	 */
	public void setPassword(final char[] password) throws Exception {
		encryptedPassword = password == null || password.length == 0 ? null : PasswordProtection.encrypt(password);
	}

	/**
	 * Resolves a path relative to the base path into a path relative to the share root.
	 */
	public String resolve(final String relativePath) {
		final String normalized = normalizePath(relativePath);
		if (basePath.isEmpty()) {
			return normalized;
		} else if (normalized.isEmpty()) {
			return basePath;
		} else {
			return basePath + "/" + normalized;
		}
	}

	/**
	 * Display text like "\\server\share\base\path", or the folder path for a local folder.
	 */
	public String getDisplayPath() {
		if (type == TargetType.LOCAL) {
			return localPath;
		}
		return "\\\\" + hostname + "\\" + sharename + (basePath.isEmpty() ? "" : "\\" + basePath.replace("/", "\\"));
	}

	/**
	 * Display text of a file or folder in the target, with the separator of the target.
	 *
	 * @param relativePath path relative to the base path, "/" separated
	 */
	public String getDisplayPath(final String relativePath) {
		final String normalized = normalizePath(relativePath);
		final String displayPath = getDisplayPath();
		if (normalized.isEmpty()) {
			return displayPath;
		}
		final String separator = type == TargetType.LOCAL ? File.separator : "\\";
		// A drive root like "D:\" already ends with the separator
		return (displayPath.endsWith(separator) ? displayPath : displayPath + separator) + normalized.replace("/", separator);
	}

	public ShareDefinition copy() {
		final ShareDefinition copy = new ShareDefinition();
		copy.type = type;
		copy.localPath = localPath;
		copy.name = name;
		copy.hostname = hostname;
		copy.port = port;
		copy.sharename = sharename;
		copy.basePath = basePath;
		copy.username = username;
		copy.encryptedPassword = encryptedPassword;
		return copy;
	}

	@Override
	public String toString() {
		return name;
	}

	static String normalizePath(final String path) {
		if (path == null) {
			return "";
		}
		String normalized = path.trim().replace("\\", "/");
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		while (normalized.endsWith("/")) {
			normalized = normalized.substring(0, normalized.length() - 1);
		}
		return normalized;
	}
}

package de.soderer.handybackup;

/**
 * Samba share used as backup target. Several devices (e.g. old and new phone of the same person)
 * may be assigned to one share.
 *
 * The password is only kept encrypted (Windows DPAPI, see {@link PasswordProtection}).
 */
public class ShareDefinition {
	private String name;
	private String hostname;
	private int port = 445;
	private String sharename;
	private String basePath = "";
	private String username;
	private String encryptedPassword;

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
	 */
	public String getBasePath() {
		return basePath;
	}

	public void setBasePath(final String basePath) {
		this.basePath = normalizePath(basePath);
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
	 * Display text like "\\server\share\base\path".
	 */
	public String getDisplayPath() {
		return "\\\\" + hostname + "\\" + sharename + (basePath.isEmpty() ? "" : "\\" + basePath.replace("/", "\\"));
	}

	public ShareDefinition copy() {
		final ShareDefinition copy = new ShareDefinition();
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

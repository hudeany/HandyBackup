package de.soderer.handybackup;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Encrypts passwords for the configuration file with the Windows Data Protection API (DPAPI),
 * so they can only be decrypted by the same Windows user on the same computer.
 *
 * Uses PowerShell's ConvertFrom-SecureString/ConvertTo-SecureString, no external libraries.
 * Secrets are passed via stdin (Base64 of UTF-8), never on the command line.
 *
 * PowerShell may write progress records and errors to stderr in CLIXML format when its streams
 * are redirected (e.g. "preparing modules for first use"). Therefore stdout and stderr are read
 * separately, progress output is suppressed, and the result is taken only from a line carrying
 * a fixed marker.
 */
public class PasswordProtection {
	private static final String RESULT_MARKER = "HANDYBACKUP_RESULT:";

	/** DPAPI encrypted SecureString as exported by ConvertFrom-SecureString: hex digits only */
	private static final Pattern ENCRYPTED_FORMAT = Pattern.compile("[0-9a-fA-F]{32,}");

	private static final Pattern BASE64_FORMAT = Pattern.compile("[A-Za-z0-9+/]*={0,2}");

	private static final String SCRIPT_PREFIX = String.join("\n",
		"$ErrorActionPreference = 'Stop'",
		"$ProgressPreference = 'SilentlyContinue'",
		"");

	private static final String ENCRYPT_SCRIPT = SCRIPT_PREFIX + String.join("\n",
		"$plain = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String([Console]::In.ReadLine()))",
		"$secure = ConvertTo-SecureString -String $plain -AsPlainText -Force",
		"[Console]::Out.WriteLine('" + RESULT_MARKER + "' + (ConvertFrom-SecureString -SecureString $secure))");

	private static final String DECRYPT_SCRIPT = SCRIPT_PREFIX + String.join("\n",
		"$secure = ConvertTo-SecureString -String ([Console]::In.ReadLine())",
		"$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)",
		"try { $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }",
		"[Console]::Out.WriteLine('" + RESULT_MARKER + "' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($plain)))");

	public static String encrypt(final char[] password) throws Exception {
		final ByteBuffer utf8 = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
		final byte[] bytes = new byte[utf8.remaining()];
		utf8.get(bytes);
		try {
			final String encrypted = runPowerShell(ENCRYPT_SCRIPT, Base64.getEncoder().encodeToString(bytes));
			// Never store anything that is not a DPAPI string, it could not be decrypted later
			if (!ENCRYPTED_FORMAT.matcher(encrypted).matches()) {
				throw new Exception("Unexpected result of password encryption");
			}
			return encrypted;
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	public static char[] decrypt(final String encryptedPassword) throws Exception {
		if (encryptedPassword == null || !ENCRYPTED_FORMAT.matcher(encryptedPassword.trim()).matches()) {
			throw new Exception("The stored password is invalid. Please enter the password again.");
		}

		final String result = runPowerShell(DECRYPT_SCRIPT, encryptedPassword.trim());
		if (!BASE64_FORMAT.matcher(result).matches()) {
			throw new Exception("Unexpected result of password decryption");
		}
		final byte[] bytes = Base64.getDecoder().decode(result);
		try {
			final CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes));
			final char[] password = new char[chars.remaining()];
			chars.get(password);
			return password;
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	private static String runPowerShell(final String script, final String input) throws Exception {
		if (!System.getProperty("os.name", "").toLowerCase().startsWith("windows")) {
			throw new Exception("Password encryption requires Windows (DPAPI)");
		}

		final String encodedScript = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
		final Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encodedScript).start();

		// Read stderr in parallel, so a full stderr pipe can never block the process
		final ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
		final Thread errorReader = new Thread(() -> {
			try (InputStream stderr = process.getErrorStream()) {
				stderr.transferTo(errorOutput);
			} catch (@SuppressWarnings("unused") final Exception e) {
				// ignore, error output is only used for the error message
			}
		}, "PasswordProtection-stderr");
		errorReader.setDaemon(true);
		errorReader.start();

		try (OutputStream stdin = process.getOutputStream()) {
			stdin.write((input + "\r\n").getBytes(StandardCharsets.US_ASCII));
		}

		final ByteArrayOutputStream output = new ByteArrayOutputStream();
		try (InputStream stdout = process.getInputStream()) {
			stdout.transferTo(output);
		}
		if (!process.waitFor(30, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			throw new Exception("PowerShell did not finish in time");
		}
		errorReader.join(5000);

		// All transported values are ASCII (hex or Base64), anything else is only noise
		for (final String line : output.toString(StandardCharsets.US_ASCII).split("\\R")) {
			final int markerIndex = line.indexOf(RESULT_MARKER);
			if (markerIndex >= 0 && process.exitValue() == 0) {
				return line.substring(markerIndex + RESULT_MARKER.length()).trim();
			}
		}

		final String errorText = extractErrorText(errorOutput.toString(StandardCharsets.UTF_8));
		throw new Exception("PowerShell failed (exit code " + process.exitValue() + ")" + (errorText.isEmpty() ? "" : ": " + errorText));
	}

	/**
	 * Converts PowerShell's CLIXML error output into readable text: only the error records are kept,
	 * progress records are dropped and escapes like "_x000D__x000A_" are resolved.
	 */
	static String extractErrorText(final String errorOutput) {
		if (!errorOutput.contains("CLIXML")) {
			return errorOutput.trim();
		}

		final StringBuilder text = new StringBuilder();
		final Matcher matcher = Pattern.compile("<S S=\"Error\">(.*?)</S>", Pattern.DOTALL).matcher(errorOutput);
		while (matcher.find()) {
			text.append(matcher.group(1));
		}
		String result = text.toString()
			.replace("_x000D__x000A_", "\n")
			.replace("_x000A_", "\n")
			.replace("_x000D_", "")
			.replace("&lt;", "<")
			.replace("&gt;", ">")
			.replace("&quot;", "\"")
			.replace("&apos;", "'")
			.replace("&amp;", "&");
		// The first line holds the actual message, the rest is PowerShell's position information
		result = result.strip();
		final int lineEnd = result.indexOf('\n');
		return lineEnd > 0 ? result.substring(0, lineEnd).strip() : result;
	}
}

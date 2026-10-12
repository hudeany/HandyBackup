package de.soderer.handybackup.mtp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies errors reported by the WPD helper process into categories the GUI can explain to the user.
 *
 * The helper reports .NET COMExceptions, whose message contains the HRESULT as
 * "(Ausnahme von HRESULT: 0x802A0006)" or "(Exception from HRESULT: 0x802A0006)".
 */
public enum MtpErrorType {
	/** Device stopped responding or was unplugged (loose contact, cable pulled, phone rebooted) */
	CONNECTION_LOST,

	/** Phone is locked or "File transfer" mode was not confirmed */
	ACCESS_DENIED,

	/** Anything else */
	OTHER;

	private static final Pattern HRESULT_PATTERN = Pattern.compile("HRESULT:?\\s*0x([0-9A-Fa-f]{8})");

	/**
	 * Determines the error type by the first HRESULT found in the messages of the error and its causes.
	 */
	public static MtpErrorType classify(final Throwable throwable) {
		for (Throwable t = throwable; t != null; t = t.getCause()) {
			final Integer hResult = extractHResult(t.getMessage());
			if (hResult != null) {
				return classify(hResult.intValue());
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return OTHER;
	}

	/**
	 * Determines the error type of an HRESULT.
	 */
	public static MtpErrorType classify(final int hResult) {
		switch (hResult) {
			case 0x802A0006: // E_WPD_DEVICE_IS_HUNG
			case 0x802A0002: // E_WPD_DEVICE_NOT_OPEN
			case 0x8007001F: // ERROR_GEN_FAILURE (typical after unplugging)
			case 0x80070015: // ERROR_NOT_READY
			case 0x80070079: // ERROR_SEM_TIMEOUT
			case 0x800701B1: // ERROR_DEV_NOT_EXIST
			case 0x8007048F: // ERROR_DEVICE_NOT_CONNECTED
				return CONNECTION_LOST;
			case 0x80070005: // E_ACCESSDENIED
				return ACCESS_DENIED;
			default:
				return OTHER;
		}
	}

	/**
	 * Whether the error or one of its causes means that the connection to the device was lost.
	 */
	public static boolean isConnectionLost(final Throwable throwable) {
		return classify(throwable) == CONNECTION_LOST;
	}

	private static Integer extractHResult(final String message) {
		if (message == null) {
			return null;
		}
		final Matcher matcher = HRESULT_PATTERN.matcher(message);
		return matcher.find() ? Integer.valueOf((int) Long.parseLong(matcher.group(1), 16)) : null;
	}
}

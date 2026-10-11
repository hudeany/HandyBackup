package de.soderer.handybackup;

import java.awt.Dimension;
import java.awt.GridLayout;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;

import de.soderer.utilities.ConfigurationProperties;
import de.soderer.utilities.IoUtilities;
import de.soderer.utilities.LangResources;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.VersionInfo;
import de.soderer.utilities.appupdate.ApplicationUpdateUtilities;
import de.soderer.utilities.swing.ModalDialog;
import de.soderer.utilities.swing.ShowDataDialog;

/**
 * Help dialog of the GUI with buttons for the version info, the manual and the update check
 * (same as in RestClient).
 *
 * @serial exclude
 */
public class HelpDialog extends ModalDialog<Boolean> {
	private static final long serialVersionUID = 6184217830926151842L;

	/**
	 * Extra width for the OS-drawn title bar parts (icon, window buttons, frame),
	 * added to the title text width when making sure the title is not truncated.
	 */
	private static final int TITLE_BAR_PADDING = 120;

	private final HandyBackup applicationDialog;
	private final ConfigurationProperties applicationConfiguration;

	/**
	 * Creates the dialog.
	 *
	 * @param applicationDialog main window, parent of this dialog and of the update process
	 * @param title window title
	 * @param applicationConfiguration application configuration, used for the proxy settings of the update check
	 */
	public HelpDialog(final HandyBackup applicationDialog, final String title, final ConfigurationProperties applicationConfiguration) {
		super(applicationDialog, title);

		this.applicationDialog = applicationDialog;
		this.applicationConfiguration = applicationConfiguration;

		createComponents();
	}

	private void createComponents() {
		final int margin = 5;

		// One column, all buttons equally wide and filling the dialog's width
		final JPanel buttonSection = new JPanel(new GridLayout(0, 1, margin, margin));
		buttonSection.setBorder(BorderFactory.createEmptyBorder(margin, margin, margin, margin));

		final JButton versionInfoButton = new JButton(LangResources.get("versionInfo"));
		versionInfoButton.addActionListener(event -> new ShowDataDialog(this, HandyBackup.APPLICATION_NAME + " (" + HandyBackup.VERSION + ") " + LangResources.get("versionInfo"), VersionInfo.getVersionInfoText()).withResizable(true).open());
		buttonSection.add(versionInfoButton);

		final JButton manualButton = new JButton(LangResources.get("manual"));
		manualButton.addActionListener(event -> new ShowDataDialog(this, HandyBackup.APPLICATION_NAME + " (" + HandyBackup.VERSION + ") " + LangResources.get("manual"), readManualText()).withResizable(true).withSize(800, 400).open());
		buttonSection.add(manualButton);

		final JButton checkUpdateButton = new JButton(LangResources.get("checkUpdate"));
		checkUpdateButton.addActionListener(event -> {
			try {
				ApplicationUpdateUtilities.executeUpdate(applicationDialog, HandyBackup.VERSIONINFO_DOWNLOAD_URL, applicationConfiguration.getProxyConfiguration(), HandyBackup.APPLICATION_NAME, HandyBackup.VERSION, HandyBackup.TRUSTED_UPDATE_CA_CERTIFICATES, null, null, null, true, false);
			} catch (final Exception e) {
				HandyBackup.showErrorDialog(this, LangResources.get("updateCheck"), e);
			}
		});
		checkUpdateButton.setEnabled(!Utilities.isBlank(HandyBackup.VERSIONINFO_DOWNLOAD_URL));
		buttonSection.add(checkUpdateButton);

		final JButton closeButton = new JButton(LangResources.get("close"));
		closeButton.addActionListener(event -> {
			returnValue = true;
			dispose();
		});
		buttonSection.add(closeButton);

		add(buttonSection);

		getRootPane().setDefaultButton(closeButton);

		setResizable(false);

		pack();

		widenToFitTitle();

		// Centering must happen last, once the dialog has its final size
		setLocationRelativeTo(applicationDialog);
	}

	/**
	 * pack() only considers the content, never the (often much longer) window
	 * title, so without this the title may get truncated by the window manager.
	 */
	private void widenToFitTitle() {
		final String title = getTitle();
		if (Utilities.isBlank(title)) {
			return;
		}

		final int requiredWidth = getFontMetrics(getFont()).stringWidth(title) + TITLE_BAR_PADDING;
		if (getWidth() < requiredWidth) {
			setSize(new Dimension(requiredWidth, getHeight()));
		}
	}

	private static String readManualText() {
		try (InputStream resourceStream = HandyBackup.class.getResourceAsStream("/manual_" + Locale.getDefault().getLanguage().toLowerCase(Locale.ROOT) + ".txt")) {
			if (resourceStream != null) {
				return IoUtilities.toString(resourceStream, StandardCharsets.UTF_8);
			}
		} catch (@SuppressWarnings("unused") final Exception e) {
			// Fall back to the default manual
		}

		try (InputStream resourceStreamDefault = HandyBackup.class.getResourceAsStream("/manual.txt")) {
			if (resourceStreamDefault != null) {
				return IoUtilities.toString(resourceStreamDefault, StandardCharsets.UTF_8);
			}
		} catch (@SuppressWarnings("unused") final Exception e) {
			// Handled below
		}

		return LangResources.get("manualNotAvailable");
	}
}

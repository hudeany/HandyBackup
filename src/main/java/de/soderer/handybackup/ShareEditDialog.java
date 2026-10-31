package de.soderer.handybackup;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.Arrays;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

import de.soderer.remote.SmbConnection;
import de.soderer.swing.modern.Button;
import de.soderer.swing.modern.ButtonStyle;
import de.soderer.utilities.LangResources;

/**
 * Dialog for creating or editing a share definition, including a connection test.
 */
public class ShareEditDialog extends JDialog {
	private static final long serialVersionUID = 1L;

	private final HandyBackupConfiguration configuration;
	private final ShareDefinition originalShare;
	private final JTextField nameField = new JTextField(25);
	private final JTextField hostnameField = new JTextField(25);
	private final JTextField portField = new JTextField(6);
	private final JTextField sharenameField = new JTextField(25);
	private final JTextField basePathField = new JTextField(25);
	private final JTextField usernameField = new JTextField(25);
	private final JPasswordField passwordField = new JPasswordField(25);
	private final Button testButton = new Button(LangResources.get("testConnection"), null, ButtonStyle.SECONDARY);
	private ShareDefinition result = null;

	/**
	 * @param share share to edit, null for a new share
	 */
	public ShareEditDialog(final Window owner, final HandyBackupConfiguration configuration, final ShareDefinition share) {
		super(owner, LangResources.get(share == null ? "newShareTitle" : "editShareTitle"), ModalityType.APPLICATION_MODAL);
		this.configuration = configuration;
		originalShare = share;

		final JPanel formPanel = new JPanel(new GridBagLayout());
		formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		int row = 0;
		addRow(formPanel, row++, LangResources.get("shareNameLabel"), nameField);
		addRow(formPanel, row++, LangResources.get("shareServerLabel"), hostnameField);
		addRow(formPanel, row++, LangResources.get("sharePortLabel"), portField);
		addRow(formPanel, row++, LangResources.get("shareShareLabel"), sharenameField);
		addRow(formPanel, row++, LangResources.get("shareSubfolderLabel"), basePathField);
		addRow(formPanel, row++, LangResources.get("shareUserLabel"), usernameField);
		addRow(formPanel, row++, LangResources.get("sharePasswordLabel"), passwordField);
		if (share != null) {
			addRow(formPanel, row++, "", new JLabel(LangResources.get("sharePasswordUnchanged")));
		}

		if (share != null) {
			nameField.setText(share.getName());
			hostnameField.setText(share.getHostname());
			portField.setText(Integer.toString(share.getPort()));
			sharenameField.setText(share.getSharename());
			basePathField.setText(share.getBasePath());
			usernameField.setText(share.getUsername());
		} else {
			portField.setText("445");
		}
		basePathField.setToolTipText(LangResources.get("shareSubfolderTooltip"));

		final Button okButton = new Button(LangResources.get("ok"), null, ButtonStyle.SECONDARY);
		final Button cancelButton = new Button(LangResources.get("cancel"), null, ButtonStyle.SECONDARY);
		okButton.addActionListener(event -> onOk());
		cancelButton.addActionListener(event -> dispose());
		testButton.addActionListener(event -> onTest());

		final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttonPanel.add(testButton);
		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(formPanel, BorderLayout.CENTER);
		getContentPane().add(buttonPanel, BorderLayout.SOUTH);
		getRootPane().setDefaultButton(okButton);
		pack();
		setLocationRelativeTo(owner);
	}

	/**
	 * Shows the dialog and returns the edited share, or null if cancelled.
	 */
	public ShareDefinition showDialog() {
		setVisible(true);
		return result;
	}

	private static void addRow(final JPanel panel, final int row, final String label, final JComponent field) {
		final GridBagConstraints labelConstraints = new GridBagConstraints();
		labelConstraints.gridx = 0;
		labelConstraints.gridy = row;
		labelConstraints.anchor = GridBagConstraints.WEST;
		labelConstraints.insets = new Insets(3, 3, 3, 10);
		panel.add(new JLabel(label), labelConstraints);

		final GridBagConstraints fieldConstraints = new GridBagConstraints();
		fieldConstraints.gridx = 1;
		fieldConstraints.gridy = row;
		fieldConstraints.anchor = GridBagConstraints.WEST;
		fieldConstraints.fill = field instanceof JLabel ? GridBagConstraints.NONE : GridBagConstraints.HORIZONTAL;
		fieldConstraints.weightx = 1;
		fieldConstraints.insets = new Insets(3, 3, 3, 3);
		panel.add(field, fieldConstraints);
	}

	/**
	 * Validates the input and builds a share definition. The password is encrypted here.
	 *
	 * @return the share, or null after showing an error message
	 */
	private ShareDefinition buildShare(final boolean forTest) {
		final String name = nameField.getText().trim();
		if (name.isEmpty() && !forTest) {
			showError(LangResources.get("shareNameRequired"));
			return null;
		}
		if (!forTest && (originalShare == null || !originalShare.getName().equalsIgnoreCase(name)) && configuration.getShare(name) != null) {
			showError(LangResources.get("shareNameExists", name));
			return null;
		}
		if (hostnameField.getText().isBlank() || sharenameField.getText().isBlank()) {
			showError(LangResources.get("shareServerRequired"));
			return null;
		}
		final int port;
		try {
			port = Integer.parseInt(portField.getText().trim());
			if (port <= 0 || port > 65535) {
				throw new NumberFormatException();
			}
		} catch (@SuppressWarnings("unused") final NumberFormatException e) {
			showError(LangResources.get("invalidPort"));
			return null;
		}

		final ShareDefinition share = originalShare == null ? new ShareDefinition() : originalShare.copy();
		share.setName(name);
		share.setHostname(hostnameField.getText().trim());
		share.setPort(port);
		share.setSharename(ShareDefinition.normalizePath(sharenameField.getText()));
		share.setBasePath(basePathField.getText());
		share.setUsername(usernameField.getText().trim());
		return share;
	}

	private void onOk() {
		final ShareDefinition share = buildShare(false);
		if (share == null) {
			return;
		}
		final char[] password = passwordField.getPassword();
		try {
			if (password.length > 0 || originalShare == null) {
				share.setPassword(password);
			}
		} catch (final Exception e) {
			HandyBackup.showErrorDialog(this, LangResources.get("passwordEncryptFailed"), e);
			return;
		} finally {
			Arrays.fill(password, '\0');
		}
		result = share;
		dispose();
	}

	private void onTest() {
		final ShareDefinition share = buildShare(true);
		if (share == null) {
			return;
		}
		final char[] enteredPassword = passwordField.getPassword();
		testButton.setEnabled(false);
		testButton.setText(LangResources.get("testingConnection"));
		new Thread(() -> {
			String message = null;
			Throwable error = null;
			char[] password = null;
			try {
				password = enteredPassword.length > 0 || originalShare == null ? enteredPassword : share.getPassword();
				try (SmbConnection connection = new SmbConnection(share.getHostname(), share.getPort(), share.getUsername(), share.getSharename(), password)) {
					if (share.getBasePath().isEmpty() || connection.folderExists(share.getBasePath())) {
						message = LangResources.get("connectionSuccessful");
					} else {
						message = LangResources.get("connectionSuccessfulNoSubfolder", share.getBasePath());
					}
				}
			} catch (final Throwable e) {
				error = e;
			} finally {
				Arrays.fill(enteredPassword, '\0');
				if (password != null) {
					Arrays.fill(password, '\0');
				}
			}
			final String finalMessage = message;
			final Throwable finalError = error;
			SwingUtilities.invokeLater(() -> {
				testButton.setEnabled(true);
				testButton.setText(LangResources.get("testConnection"));
				if (finalError != null) {
					HandyBackup.showErrorDialog(this, LangResources.get("connectionFailed", share.getDisplayPath()), finalError);
				} else {
					JOptionPane.showMessageDialog(this, finalMessage, LangResources.get("connectionTestTitle"), JOptionPane.INFORMATION_MESSAGE);
				}
			});
		}, "HandyBackup-ConnectionTest").start();
	}

	private void showError(final String message) {
		JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.ERROR_MESSAGE);
	}
}

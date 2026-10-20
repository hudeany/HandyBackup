package de.soderer.handybackup;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

import de.soderer.utilities.LangResources;

/**
 * Dialog for creating or editing a backup target in a folder of the local computer
 * (internal disk, external USB disk or mapped network drive).
 */
public class LocalTargetEditDialog extends JDialog {
	private static final long serialVersionUID = 1L;

	private final HandyBackupConfiguration configuration;
	private final ShareDefinition original;
	private final JTextField nameField = new JTextField(25);
	private final JTextField pathField = new JTextField(35);
	private ShareDefinition result = null;

	/**
	 * @param owner         parent window
	 * @param configuration configuration, used to check that the name is unique
	 * @param original      target to edit, null for a new target
	 */
	public LocalTargetEditDialog(final Window owner, final HandyBackupConfiguration configuration, final ShareDefinition original) {
		super(owner, LangResources.get(original == null ? "newLocalTargetTitle" : "editLocalTargetTitle"), ModalityType.APPLICATION_MODAL);
		this.configuration = configuration;
		this.original = original;

		if (original != null) {
			nameField.setText(original.getName());
			pathField.setText(original.getLocalPath());
		}
		pathField.setToolTipText(LangResources.get("localFolderTooltip"));

		final JButton browseButton = new JButton(LangResources.get("browseButton"));
		browseButton.addActionListener(event -> onBrowse());

		final JPanel formPanel = new JPanel(new GridBagLayout());
		formPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));
		final GridBagConstraints constraints = new GridBagConstraints();
		constraints.insets = new Insets(3, 3, 3, 3);
		constraints.anchor = GridBagConstraints.WEST;

		constraints.gridx = 0;
		constraints.gridy = 0;
		formPanel.add(new JLabel(LangResources.get("shareNameLabel")), constraints);
		constraints.gridx = 1;
		constraints.gridwidth = 2;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1;
		formPanel.add(nameField, constraints);

		constraints.gridx = 0;
		constraints.gridy = 1;
		constraints.gridwidth = 1;
		constraints.fill = GridBagConstraints.NONE;
		constraints.weightx = 0;
		formPanel.add(new JLabel(LangResources.get("localFolderLabel")), constraints);
		constraints.gridx = 1;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.weightx = 1;
		formPanel.add(pathField, constraints);
		constraints.gridx = 2;
		constraints.fill = GridBagConstraints.NONE;
		constraints.weightx = 0;
		formPanel.add(browseButton, constraints);

		final JButton okButton = new JButton(LangResources.get("ok"));
		final JButton cancelButton = new JButton(LangResources.get("cancel"));
		okButton.addActionListener(event -> onOk());
		cancelButton.addActionListener(event -> dispose());
		final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		buttonPanel.add(okButton);
		buttonPanel.add(cancelButton);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(formPanel, BorderLayout.CENTER);
		getContentPane().add(buttonPanel, BorderLayout.SOUTH);

		getRootPane().setDefaultButton(okButton);
		getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

		pack();
		setLocationRelativeTo(owner);
	}

	/**
	 * Shows the dialog.
	 *
	 * @return the new or changed target, null if cancelled
	 */
	public ShareDefinition showDialog() {
		setVisible(true);
		return result;
	}

	private void onBrowse() {
		final JFileChooser fileChooser = new JFileChooser();
		fileChooser.setDialogTitle(LangResources.get("localFolderChooserTitle"));
		fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		fileChooser.setAcceptAllFileFilterUsed(false);
		final File current = new File(pathField.getText().trim());
		if (!pathField.getText().isBlank() && current.isDirectory()) {
			fileChooser.setCurrentDirectory(current.getParentFile() != null ? current.getParentFile() : current);
			fileChooser.setSelectedFile(current);
		}
		if (fileChooser.showDialog(this, LangResources.get("ok")) == JFileChooser.APPROVE_OPTION && fileChooser.getSelectedFile() != null) {
			pathField.setText(fileChooser.getSelectedFile().getAbsolutePath());
			if (nameField.getText().isBlank()) {
				nameField.setText(fileChooser.getSelectedFile().getName());
			}
		}
	}

	private void onOk() {
		final String name = nameField.getText().trim();
		if (name.isEmpty()) {
			showValidationError(LangResources.get("shareNameRequired"));
			return;
		}
		final ShareDefinition existing = configuration.getShare(name);
		if (existing != null && (original == null || !original.getName().equalsIgnoreCase(name))) {
			showValidationError(LangResources.get("shareNameExists", name));
			return;
		}

		final String pathText = pathField.getText().trim();
		if (pathText.isEmpty()) {
			showValidationError(LangResources.get("localFolderRequired"));
			return;
		}
		final Path folder;
		try {
			folder = Path.of(pathText);
		} catch (@SuppressWarnings("unused") final InvalidPathException e) {
			showValidationError(LangResources.get("localFolderInvalid", pathText));
			return;
		}
		if (!folder.isAbsolute()) {
			showValidationError(LangResources.get("localFolderNotAbsolute"));
			return;
		}
		final Path normalizedFolder = folder.normalize();
		if (Files.exists(normalizedFolder) && !Files.isDirectory(normalizedFolder)) {
			showValidationError(LangResources.get("localFolderIsFile", normalizedFolder));
			return;
		} else if (!Files.exists(normalizedFolder)) {
			if (JOptionPane.showConfirmDialog(this, LangResources.get("localFolderCreateQuestion", normalizedFolder), getTitle(),
					JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.YES_OPTION) {
				return;
			}
			try {
				Files.createDirectories(normalizedFolder);
			} catch (final Exception e) {
				HandyBackup.showErrorDialog(this, LangResources.get("localFolderCreateFailed", normalizedFolder), e);
				return;
			}
		}

		final ShareDefinition share = original == null ? new ShareDefinition() : original.copy();
		share.setType(ShareDefinition.TargetType.LOCAL);
		share.setName(name);
		share.setLocalPath(normalizedFolder.toString());
		result = share;
		dispose();
	}

	private void showValidationError(final String message) {
		JOptionPane.showMessageDialog(this, message, getTitle(), JOptionPane.WARNING_MESSAGE);
	}
}

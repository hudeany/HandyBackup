package de.soderer.handybackup;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import de.soderer.utilities.LangResources;

/**
 * Dialog for managing the backup targets, Samba shares and local folders (add, edit, delete).
 * Shows which devices are assigned to the selected target.
 */
public class ShareManagementDialog extends JDialog {
	private static final long serialVersionUID = 1L;

	private final HandyBackupConfiguration configuration;
	private final DefaultListModel<ShareDefinition> listModel = new DefaultListModel<>();
	private final JList<ShareDefinition> shareList = new JList<>(listModel);
	private final JLabel detailLabel = new JLabel(" ");
	private boolean changed = false;

	public ShareManagementDialog(final Window owner, final HandyBackupConfiguration configuration) {
		super(owner, LangResources.get("sharesTitle"), ModalityType.APPLICATION_MODAL);
		this.configuration = configuration;

		shareList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		shareList.addListSelectionListener(event -> updateDetails());
		shareList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent event) {
				if (event.getClickCount() == 2) {
					onEdit();
				}
			}
		});

		final JButton addButton = new JButton(LangResources.get("newButton"));
		final JButton editButton = new JButton(LangResources.get("editButton"));
		final JButton deleteButton = new JButton(LangResources.get("deleteButton"));
		final JButton closeButton = new JButton(LangResources.get("close"));
		addButton.addActionListener(event -> onAdd());
		editButton.addActionListener(event -> onEdit());
		deleteButton.addActionListener(event -> onDelete());
		closeButton.addActionListener(event -> dispose());

		final JPanel sideButtons = new JPanel(new GridLayout(0, 1, 5, 5));
		sideButtons.add(addButton);
		sideButtons.add(editButton);
		sideButtons.add(deleteButton);
		final JPanel sidePanel = new JPanel(new BorderLayout());
		sidePanel.add(sideButtons, BorderLayout.NORTH);

		final JScrollPane listScrollPane = new JScrollPane(shareList);
		listScrollPane.setPreferredSize(new Dimension(380, 200));

		final JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
		centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 0, 10));
		centerPanel.add(listScrollPane, BorderLayout.CENTER);
		centerPanel.add(sidePanel, BorderLayout.EAST);
		centerPanel.add(detailLabel, BorderLayout.SOUTH);

		final JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
		bottomPanel.add(closeButton);

		getContentPane().setLayout(new BorderLayout());
		getContentPane().add(centerPanel, BorderLayout.CENTER);
		getContentPane().add(bottomPanel, BorderLayout.SOUTH);

		reloadList(null);
		pack();
		setLocationRelativeTo(owner);
	}

	/**
	 * Shows the dialog.
	 *
	 * @return true if the configuration was changed (and saved)
	 */
	public boolean showDialog() {
		setVisible(true);
		return changed;
	}

	private void reloadList(final String selectName) {
		listModel.clear();
		for (final ShareDefinition share : configuration.getShares()) {
			listModel.addElement(share);
			if (share.getName().equalsIgnoreCase(selectName)) {
				shareList.setSelectedIndex(listModel.size() - 1);
			}
		}
		if (shareList.getSelectedIndex() < 0 && !listModel.isEmpty()) {
			shareList.setSelectedIndex(0);
		}
		updateDetails();
	}

	private void updateDetails() {
		final ShareDefinition share = shareList.getSelectedValue();
		if (share == null) {
			detailLabel.setText(" ");
			return;
		}
		final StringBuilder text = new StringBuilder("<html>")
			.append(escapeHtml(LangResources.get(share.isLocal() ? "targetTypeLocal" : "targetTypeSmb"))).append(": ")
			.append(escapeHtml(share.getDisplayPath())).append("<br>").append(escapeHtml(LangResources.get("devicesLabel"))).append(" ");
		final List<HandyBackupConfiguration.DeviceAssignment> assignments = configuration.getAssignmentsOfShare(share.getName());
		if (assignments.isEmpty()) {
			text.append("<i>").append(escapeHtml(LangResources.get("noDevices"))).append("</i>");
		} else {
			for (int i = 0; i < assignments.size(); i++) {
				if (i > 0) {
					text.append(", ");
				}
				final HandyBackupConfiguration.DeviceAssignment assignment = assignments.get(i);
				text.append(escapeHtml(assignment.deviceName().isEmpty() ? assignment.serialNumber() : assignment.deviceName()));
			}
		}
		detailLabel.setText(text.append("</html>").toString());
	}

	private void onAdd() {
		final Object[] options = { LangResources.get("targetTypeSmb"), LangResources.get("targetTypeLocal"), LangResources.get("cancel") };
		final int choice = JOptionPane.showOptionDialog(this, LangResources.get("newTargetQuestion"), LangResources.get("newTargetTitle"),
			JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
		final ShareDefinition share;
		if (choice == 0) {
			share = new ShareEditDialog(this, configuration, null).showDialog();
		} else if (choice == 1) {
			share = new LocalTargetEditDialog(this, configuration, null).showDialog();
		} else {
			return;
		}
		if (share != null) {
			configuration.putShare(null, share);
			save();
			reloadList(share.getName());
		}
	}

	private void onEdit() {
		final ShareDefinition selected = shareList.getSelectedValue();
		if (selected == null) {
			return;
		}
		final ShareDefinition share = selected.isLocal()
			? new LocalTargetEditDialog(this, configuration, selected).showDialog()
			: new ShareEditDialog(this, configuration, selected).showDialog();
		if (share != null) {
			configuration.putShare(selected.getName(), share);
			save();
			reloadList(share.getName());
		}
	}

	private void onDelete() {
		final ShareDefinition selected = shareList.getSelectedValue();
		if (selected == null) {
			return;
		}
		final int assignedDevices = configuration.getAssignmentsOfShare(selected.getName()).size();
		final String question = LangResources.get("deleteShareQuestion", selected.getName())
			+ (assignedDevices > 0 ? "\n" + LangResources.get("deleteShareLosesAssignment", assignedDevices) : "")
			+ "\n" + LangResources.get("deleteShareFilesKept");
		if (JOptionPane.showConfirmDialog(this, question, getTitle(), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION) {
			configuration.removeShare(selected.getName());
			save();
			reloadList(null);
		}
	}

	private void save() {
		changed = true;
		try {
			configuration.save();
		} catch (final Exception e) {
			HandyBackup.showErrorDialog(this, LangResources.get("configurationSaveFailed", HandyBackupConfiguration.getDevicesFile()), e);
		}
	}

	private static String escapeHtml(final String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}

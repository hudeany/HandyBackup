package de.soderer.handybackup;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import de.soderer.swing.modern.ButtonStyle;
import de.soderer.swing.modern.DialogStyle;
import de.soderer.swing.modern.IconKind;
import de.soderer.swing.modern.ModalDialogModern;
import de.soderer.swing.modern.ModernUi;
import de.soderer.swing.modern.QuestionDialog;
import de.soderer.utilities.LangResources;

/**
 * Dialog for managing the backup targets, Samba shares and local folders (add, edit, delete).
 * Shows which devices are assigned to the selected target.
 */
public class ShareManagementDialog extends ModalDialogModern<Boolean> {
	private static final long serialVersionUID = 1L;

	private final HandyBackupConfiguration configuration;
	private final DefaultListModel<ShareDefinition> listModel = new DefaultListModel<>();
	private final JList<ShareDefinition> shareList = new JList<>(listModel);
	private final JLabel detailLabel = ModernUi.createMutedLabel(" ");
	private boolean changed = false;

	public ShareManagementDialog(final Window owner, final HandyBackupConfiguration configuration) {
		super(owner, LangResources.get("sharesTitle"), IconKind.SERVER);
		this.configuration = configuration;

		shareList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		shareList.setSelectionBackground(ModernUi.ACCENT_LIGHT);
		shareList.setSelectionForeground(ModernUi.TEXT);
		shareList.setCellRenderer(new DefaultListCellRenderer() {
			private static final long serialVersionUID = 1L;

			@Override
			public Component getListCellRendererComponent(final JList<?> list, final Object value, final int index, final boolean isSelected, final boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, index, isSelected, false);
				// More room per entry than the look and feel's default
				setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
				return this;
			}
		});
		shareList.addListSelectionListener(event -> updateDetails());
		shareList.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent event) {
				if (event.getClickCount() == 2) {
					onEdit();
				}
			}
		});

		final JButton addButton = createButton(LangResources.get("newButton"), IconKind.PLUS, ButtonStyle.SECONDARY);
		final JButton editButton = createButton(LangResources.get("editButton"), IconKind.EDIT, ButtonStyle.SECONDARY);
		final JButton deleteButton = createButton(LangResources.get("deleteButton"), IconKind.TRASH, ButtonStyle.DANGEROUS);
		addButton.addActionListener(event -> onAdd());
		editButton.addActionListener(event -> onEdit());
		deleteButton.addActionListener(event -> onDelete());

		final JPanel sideButtons = new JPanel(new GridLayout(0, 1, 0, 8));
		sideButtons.setOpaque(false);
		sideButtons.add(addButton);
		sideButtons.add(editButton);
		sideButtons.add(deleteButton);
		final JPanel sidePanel = new JPanel(new BorderLayout());
		sidePanel.setOpaque(false);
		sidePanel.add(sideButtons, BorderLayout.NORTH);

		final JScrollPane listScrollPane = new JScrollPane(shareList);
		ModernUi.styleScrollPane(listScrollPane, ModernUi.CARD);
		listScrollPane.setPreferredSize(new Dimension(380, 200));

		final JPanel centerPanel = new JPanel(new BorderLayout(12, 10));
		centerPanel.setOpaque(false);
		centerPanel.add(listScrollPane, BorderLayout.CENTER);
		centerPanel.add(sidePanel, BorderLayout.EAST);
		centerPanel.add(detailLabel, BorderLayout.SOUTH);
		getContentArea().add(centerPanel, BorderLayout.CENTER);

		final JButton closeButton = addButton(LangResources.get("close"), null, ButtonStyle.PRIMARY, event -> dispose());
		getRootPane().setDefaultButton(closeButton);

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
		final Integer answer = new QuestionDialog(this, LangResources.get("newTargetTitle"), LangResources.get("newTargetQuestion"),
			LangResources.get("targetTypeSmb"), LangResources.get("targetTypeLocal"), LangResources.get("cancel"))
			.withDialogStyle(DialogStyle.QUESTION)
			.open();
		final int choice = answer == null ? -1 : answer;
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
		// Cancel is the default answer, deleting must be chosen explicitly
		final Integer answer = new QuestionDialog(this, getTitle(), question, LangResources.get("cancel"), LangResources.get("deleteButton"))
			.withDialogStyle(DialogStyle.WARNING)
			.open();
		if (answer != null && answer == 1) {
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

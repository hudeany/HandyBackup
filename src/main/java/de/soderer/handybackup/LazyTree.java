package de.soderer.handybackup;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

import de.soderer.utilities.LangResources;

/**
 * JTree whose folder contents are loaded on demand in a background executor
 * (MTP and SMB listings are far too slow for the event dispatch thread).
 */
public class LazyTree extends JTree {
	private static final long serialVersionUID = 1L;

	/** Sort order: folders first, then by name */
	public static final Comparator<TreeEntry> ENTRY_ORDER = Comparator.comparing((final TreeEntry entry) -> !entry.directory())
		.thenComparing(entry -> entry.name().toLowerCase(Locale.ROOT));

	/**
	 * Loads the children of a folder. Called in the background executor.
	 */
	public interface ChildLoader {
		List<TreeEntry> loadChildren(TreeEntry folder) throws Exception;
	}

	/**
	 * Additional marking of an entry in the tree.
	 *
	 * @param suffix   text appended to the entry's name, null for none
	 * @param toolTip  line appended to the entry's tooltip, null for none
	 * @param color    text color when the entry is not selected, null for the default color
	 */
	public record Decoration(String suffix, String toolTip, Color color) {
	}

	private final ExecutorService executor;
	private final String emptyText;
	private ChildLoader childLoader;
	private Consumer<TreeEntry> fileOpenHandler;
	private Consumer<List<TreeEntry>> fileDeleteHandler;
	private BiConsumer<JPopupMenu, TreeEntry> folderMenuProvider;
	private Function<TreeEntry, Decoration> decorator;
	private Function<TreeEntry, String> backupInfoProvider;

	/**
	 * Paths of folders that were expanded before their parent was reloaded. They are expanded again as soon as
	 * they appear after loading, level by level, because deeper folders only exist after their parent was loaded.
	 */
	private final Set<String> pendingExpansions = new HashSet<>();

	public LazyTree(final ExecutorService executor, final String emptyText) {
		super(new DefaultTreeModel(new DefaultMutableTreeNode(emptyText)));
		this.executor = executor;
		this.emptyText = emptyText;
		setCellRenderer(new EntryRenderer());
		ToolTipManager.sharedInstance().registerComponent(this);
		addTreeWillExpandListener(new TreeWillExpandListener() {
			@Override
			public void treeWillExpand(final TreeExpansionEvent event) {
				final Object node = event.getPath().getLastPathComponent();
				if (node instanceof final EntryNode entryNode && !entryNode.loadStarted) {
					loadChildren(entryNode);
				}
			}

			@Override
			public void treeWillCollapse(final TreeExpansionEvent event) {
				// nothing to do
			}
		});

		// Double click or Enter on a file opens it (folders keep the default expand behaviour)
		addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(final MouseEvent event) {
				if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
					final TreePath path = getPathForLocation(event.getX(), event.getY());
					if (path != null) {
						openFile(getEntry(path.getLastPathComponent()));
					}
				}
			}

			// The popup trigger is reported on press (Linux) or release (Windows)
			@Override
			public void mousePressed(final MouseEvent event) {
				if (event.isPopupTrigger()) {
					showContextMenu(event);
				}
			}

			@Override
			public void mouseReleased(final MouseEvent event) {
				if (event.isPopupTrigger()) {
					showContextMenu(event);
				}
			}
		});
		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(final KeyEvent event) {
				if (event.getKeyCode() == KeyEvent.VK_ENTER) {
					final TreePath path = getSelectionPath();
					if (path != null) {
						openFile(getEntry(path.getLastPathComponent()));
					}
				} else if (event.getKeyCode() == KeyEvent.VK_DELETE) {
					deleteFiles(getSelectedFileEntries());
				}
			}
		});
	}

	/**
	 * Sets the action for deleting the selected files (context menu or Delete key), null for none.
	 * The handler is responsible for asking for confirmation. Called in the event dispatch thread.
	 */
	public void setFileDeleteHandler(final Consumer<List<TreeEntry>> handler) {
		fileDeleteHandler = handler;
	}

	/**
	 * Sets a provider that adds items to the context menu of a folder (the folder that was right-clicked), null for none.
	 * Called in the event dispatch thread.
	 */
	public void setFolderMenuProvider(final BiConsumer<JPopupMenu, TreeEntry> provider) {
		folderMenuProvider = provider;
	}

	/**
	 * Sets a function returning an additional marking for an entry (null for none), null for no markings.
	 * Called in the event dispatch thread while rendering, so it must be fast.
	 */
	public void setDecorator(final Function<TreeEntry, Decoration> decorator) {
		this.decorator = decorator;
		repaint();
	}

	/**
	 * Sets a function deciding whether a folder is backed up, instead of {@link TreeEntry#backupRelevant()}, so changes
	 * of the backed-up folders are shown without reloading the tree. The function returns null for a folder that is
	 * not backed up, otherwise a description for the tooltip (e.g. the file types). Called in the event dispatch thread
	 * while rendering, so it must be fast.
	 */
	public void setBackupInfoProvider(final Function<TreeEntry, String> backupInfoProvider) {
		this.backupInfoProvider = backupInfoProvider;
		repaint();
	}

	/**
	 * Selected file entries (folders and placeholder nodes are ignored).
	 */
	public List<TreeEntry> getSelectedFileEntries() {
		final List<TreeEntry> result = new ArrayList<>();
		final TreePath[] paths = getSelectionPaths();
		if (paths != null) {
			for (final TreePath path : paths) {
				final TreeEntry entry = getEntry(path.getLastPathComponent());
				if (entry != null && !entry.directory()) {
					result.add(entry);
				}
			}
		}
		return result;
	}

	private void showContextMenu(final MouseEvent event) {
		final TreePath path = getPathForLocation(event.getX(), event.getY());
		if (path == null || childLoader == null) {
			return;
		}
		// Right click on a not selected row selects only this row (like Explorer)
		if (!isPathSelected(path)) {
			setSelectionPath(path);
		}
		final List<TreeEntry> selectedFiles = getSelectedFileEntries();
		final TreeEntry clickedEntry = getEntry(path.getLastPathComponent());

		final JPopupMenu menu = new JPopupMenu();
		if (folderMenuProvider != null && clickedEntry != null && clickedEntry.directory()) {
			folderMenuProvider.accept(menu, clickedEntry);
		}
		if (fileOpenHandler != null && selectedFiles.size() == 1) {
			if (menu.getComponentCount() > 0) {
				menu.addSeparator();
			}
			final JMenuItem openItem = new JMenuItem(LangResources.get("openMenuItem"));
			openItem.addActionListener(action -> openFile(selectedFiles.get(0)));
			menu.add(openItem);
		}
		if (fileDeleteHandler != null && !selectedFiles.isEmpty()) {
			if (menu.getComponentCount() > 0) {
				menu.addSeparator();
			}
			final JMenuItem deleteItem = new JMenuItem(selectedFiles.size() == 1 ? LangResources.get("deleteMenuItem") : LangResources.get("deleteFilesMenuItem", selectedFiles.size()));
			deleteItem.addActionListener(action -> deleteFiles(selectedFiles));
			menu.add(deleteItem);
		}
		if (menu.getComponentCount() > 0) {
			menu.show(this, event.getX(), event.getY());
		}
	}

	private void deleteFiles(final List<TreeEntry> entries) {
		final Consumer<List<TreeEntry>> handler = fileDeleteHandler;
		if (!entries.isEmpty() && handler != null && childLoader != null) {
			handler.accept(entries);
		}
	}

	/**
	 * Sets the action for opening a file (double click or Enter on a file entry), null for none.
	 * Called in the event dispatch thread.
	 */
	public void setFileOpenHandler(final Consumer<TreeEntry> handler) {
		fileOpenHandler = handler;
	}

	private void openFile(final TreeEntry entry) {
		final Consumer<TreeEntry> handler = fileOpenHandler;
		if (entry != null && !entry.directory() && handler != null && childLoader != null) {
			handler.accept(entry);
		}
	}

	/**
	 * Shows a new root. The root is expanded immediately.
	 */
	public void setRoot(final TreeEntry rootEntry, final ChildLoader loader) {
		childLoader = loader;
		pendingExpansions.clear();
		final EntryNode root = new EntryNode(rootEntry);
		getModel().setRoot(root);
		// JTree marks a new root as expanded without firing treeWillExpand, so load its children explicitly
		loadChildren(root);
		expandPath(new TreePath(root));
	}

	/**
	 * Shows only a text instead of a tree (e.g. "no device connected").
	 */
	public void clear(final String text) {
		childLoader = null;
		pendingExpansions.clear();
		getModel().setRoot(new DefaultMutableTreeNode(text == null ? emptyText : text));
	}

	/**
	 * Reloads all currently expanded folders, keeping their expansion state.
	 */
	public void refresh() {
		if (getModel().getRoot() instanceof final EntryNode root) {
			reload(root);
		}
	}

	/**
	 * Reloads only the folders containing the given entries (e.g. after these entries were deleted), keeping
	 * the expansion state of the rest of the tree. Reloads the whole tree if none of the folders is found.
	 */
	public void refreshParentsOf(final Collection<TreeEntry> entries) {
		if (!(getModel().getRoot() instanceof final EntryNode root)) {
			return;
		}
		final Set<String> entryPaths = new HashSet<>();
		for (final TreeEntry entry : entries) {
			entryPaths.add(entry.path());
		}
		final Set<EntryNode> parents = new LinkedHashSet<>();
		final Enumeration<?> nodes = root.breadthFirstEnumeration();
		while (nodes.hasMoreElements()) {
			if (nodes.nextElement() instanceof final EntryNode node && entryPaths.contains(node.entry.path()) && node.getParent() instanceof final EntryNode parent) {
				parents.add(parent);
			}
		}
		if (parents.isEmpty()) {
			refresh();
		} else {
			for (final EntryNode parent : parents) {
				reload(parent);
			}
		}
	}

	private void reload(final EntryNode node) {
		final boolean expanded = isExpanded(new TreePath(node.getPath()));
		if (node.loadStarted && expanded) {
			loadChildren(node);
		} else if (node.loadStarted) {
			// Collapsed: discard content, it is reloaded when expanded again
			node.removeAllChildren();
			node.add(new DefaultMutableTreeNode(LangResources.get("treeLoading")));
			node.loadStarted = false;
			getModel().nodeStructureChanged(node);
		}
	}

	@Override
	public DefaultTreeModel getModel() {
		return (DefaultTreeModel) super.getModel();
	}

	private void loadChildren(final EntryNode node) {
		final ChildLoader loader = childLoader;
		if (loader == null) {
			return;
		}
		node.loadStarted = true;
		executor.submit(() -> {
			try {
				final List<TreeEntry> children = loader.loadChildren(node.entry);
				children.sort(ENTRY_ORDER);
				SwingUtilities.invokeLater(() -> showChildren(node, loader, children, null));
			} catch (final Throwable e) {
				SwingUtilities.invokeLater(() -> showChildren(node, loader, null, e));
			}
		});
	}

	private void showChildren(final EntryNode node, final ChildLoader loader, final List<TreeEntry> children, final Throwable error) {
		if (loader != childLoader) {
			// Tree got a new root in the meantime
			return;
		}

		// Remember all expanded subfolders at any depth, so a refresh keeps them open
		final TreePath nodePath = new TreePath(node.getPath());
		final Enumeration<TreePath> expandedDescendants = getExpandedDescendants(nodePath);
		if (expandedDescendants != null) {
			while (expandedDescendants.hasMoreElements()) {
				final TreePath expandedPath = expandedDescendants.nextElement();
				if (expandedPath.getLastPathComponent() != node && expandedPath.getLastPathComponent() instanceof final EntryNode expandedNode) {
					pendingExpansions.add(expandedNode.entry.path());
				}
			}
		}

		// Remember the selection below this folder, so deleting several files one after another stays convenient
		final List<String> selectedPaths = new ArrayList<>();
		int firstSelectedIndex = -1;
		final TreePath[] selectionPaths = getSelectionPaths();
		if (selectionPaths != null) {
			for (final TreePath selectionPath : selectionPaths) {
				if (selectionPath.getParentPath() != null && selectionPath.getParentPath().getLastPathComponent() == node
					&& selectionPath.getLastPathComponent() instanceof final EntryNode selectedNode) {
					selectedPaths.add(selectedNode.entry.path());
					final int index = node.getIndex(selectedNode);
					if (firstSelectedIndex < 0 || index < firstSelectedIndex) {
						firstSelectedIndex = index;
					}
				}
			}
		}

		node.removeAllChildren();
		if (error != null) {
			node.add(new DefaultMutableTreeNode(LangResources.get("treeError", error.getMessage())));
		} else if (children.isEmpty()) {
			node.add(new DefaultMutableTreeNode(LangResources.get("treeEmpty")));
		} else {
			for (final TreeEntry child : children) {
				node.add(new EntryNode(child));
			}
		}
		getModel().nodeStructureChanged(node);

		final List<TreePath> newSelection = new ArrayList<>();
		for (int i = 0; i < node.getChildCount(); i++) {
			if (node.getChildAt(i) instanceof final EntryNode child) {
				if (pendingExpansions.remove(child.entry.path())) {
					// Loads the child's content, which then expands its own remembered subfolders
					expandPath(new TreePath(child.getPath()));
				}
				if (selectedPaths.contains(child.entry.path())) {
					newSelection.add(new TreePath(child.getPath()));
				}
			}
		}

		if (!selectedPaths.isEmpty()) {
			if (newSelection.isEmpty() && node.getChildCount() > 0) {
				// The selected entries are gone (deleted): select the entry that took their place
				final int index = Math.min(firstSelectedIndex, node.getChildCount() - 1);
				if (node.getChildAt(index) instanceof final EntryNode neighbour) {
					newSelection.add(new TreePath(neighbour.getPath()));
				}
			}
			if (newSelection.isEmpty()) {
				setSelectionPath(nodePath);
			} else {
				setSelectionPaths(newSelection.toArray(new TreePath[0]));
				scrollPathToVisible(newSelection.get(0));
			}
		}
	}

	public static TreeEntry getEntry(final Object node) {
		return node instanceof final EntryNode entryNode ? entryNode.entry : null;
	}

	private static class EntryNode extends DefaultMutableTreeNode {
		private static final long serialVersionUID = 1L;

		private final TreeEntry entry;
		private boolean loadStarted = false;

		EntryNode(final TreeEntry entry) {
			super(entry, entry.directory());
			this.entry = entry;
			if (entry.directory()) {
				// Placeholder, so the folder shows an expand handle before its content is known
				add(new DefaultMutableTreeNode(LangResources.get("treeLoading")));
			}
		}

		@Override
		public boolean isLeaf() {
			return !entry.directory();
		}
	}

	private class EntryRenderer extends DefaultTreeCellRenderer {
		private static final long serialVersionUID = 1L;

		private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern(LangResources.get("treeDateTimeFormat"));

		@Override
		public Component getTreeCellRendererComponent(final JTree tree, final Object value, final boolean selectedParam, final boolean expanded, final boolean leaf, final int row, final boolean hasFocusParam) {
			super.getTreeCellRendererComponent(tree, value, selectedParam, expanded, leaf, row, hasFocusParam);
			final TreeEntry entry = getEntry(value);
			if (entry == null) {
				setFont(tree.getFont().deriveFont(Font.ITALIC));
				setIcon(null);
				setToolTipText(null);
				return this;
			}

			final Function<TreeEntry, String> currentBackupInfoProvider = backupInfoProvider;
			final String backupInfo;
			if (!entry.directory()) {
				backupInfo = null;
			} else if (currentBackupInfoProvider != null) {
				backupInfo = currentBackupInfoProvider.apply(entry);
			} else {
				backupInfo = entry.backupRelevant() ? "" : null;
			}
			setFont(tree.getFont().deriveFont(backupInfo != null ? Font.BOLD : Font.PLAIN));
			String text;
			String toolTip;
			if (entry.directory()) {
				text = entry.name();
				toolTip = entry.path() + (backupInfo != null ? "  " + LangResources.get("treeBackedUp") : "");
				if (backupInfo != null && !backupInfo.isEmpty()) {
					toolTip += "\n" + backupInfo;
				}
			} else {
				text = entry.name() + "   " + formatSize(entry.size()) + (entry.modified() != null ? "   " + entry.modified().format(dateFormat) : "");
				toolTip = entry.path();
			}

			final Function<TreeEntry, Decoration> currentDecorator = decorator;
			final Decoration decoration = currentDecorator == null ? null : currentDecorator.apply(entry);
			if (decoration != null) {
				if (decoration.suffix() != null) {
					text += "  " + decoration.suffix();
				}
				if (decoration.toolTip() != null) {
					toolTip += "\n" + decoration.toolTip();
				}
				if (decoration.color() != null && !selectedParam) {
					setForeground(decoration.color());
				}
			}
			setText(text);
			// Tooltips with line breaks need HTML
			setToolTipText(toolTip.contains("\n") ? "<html>" + escapeHtml(toolTip).replace("\n", "<br>") + "</html>" : toolTip);
			return this;
		}
	}

	private static String escapeHtml(final String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	public static String formatSize(final long size) {
		if (size < 0) {
			return "";
		} else if (size < 1024) {
			return size + " B";
		} else if (size < 1024 * 1024) {
			return String.format(Locale.getDefault(), "%.1f KB", size / 1024.0);
		} else if (size < 1024L * 1024 * 1024) {
			return String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024));
		} else {
			return String.format(Locale.getDefault(), "%.2f GB", size / (1024.0 * 1024 * 1024));
		}
	}
}

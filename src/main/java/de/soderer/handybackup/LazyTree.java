package de.soderer.handybackup;

import java.awt.Component;
import java.awt.Font;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

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

	private final ExecutorService executor;
	private final String emptyText;
	private ChildLoader childLoader;
	private Consumer<TreeEntry> fileOpenHandler;
	private Consumer<List<TreeEntry>> fileDeleteHandler;

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
		if (selectedFiles.isEmpty()) {
			return;
		}

		final JPopupMenu menu = new JPopupMenu();
		if (fileOpenHandler != null && selectedFiles.size() == 1) {
			final JMenuItem openItem = new JMenuItem(LangResources.get("openMenuItem"));
			openItem.addActionListener(action -> openFile(selectedFiles.get(0)));
			menu.add(openItem);
		}
		if (fileDeleteHandler != null) {
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

		// Remember expanded subfolders, so a refresh keeps them open
		final List<String> expandedPaths = new ArrayList<>();
		for (int i = 0; i < node.getChildCount(); i++) {
			if (node.getChildAt(i) instanceof final EntryNode child && child.loadStarted && isExpanded(new TreePath(child.getPath()))) {
				expandedPaths.add(child.entry.path());
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

		for (int i = 0; i < node.getChildCount(); i++) {
			if (node.getChildAt(i) instanceof final EntryNode child && expandedPaths.contains(child.entry.path())) {
				expandPath(new TreePath(child.getPath()));
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

	private static class EntryRenderer extends DefaultTreeCellRenderer {
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

			setFont(tree.getFont().deriveFont(entry.backupRelevant() ? Font.BOLD : Font.PLAIN));
			if (entry.directory()) {
				setText(entry.name());
				setToolTipText(entry.path() + (entry.backupRelevant() ? "  " + LangResources.get("treeBackedUp") : ""));
			} else {
				setText(entry.name() + "   " + formatSize(entry.size()) + (entry.modified() != null ? "   " + entry.modified().format(dateFormat) : ""));
				setToolTipText(entry.path());
			}
			return this;
		}
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

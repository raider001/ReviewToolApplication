package com.kalynx.serverlessreviewtool.ui.mainpanels.gitnotesdebugpanel;

import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedScrollPane;
import com.kalynx.swingtheme.themedcomponents.ThemedTextField;
import com.kalynx.swingtheme.themedcomponents.ThemedTree;
import com.kalynx.swingtheme.themedcomponents.ThemedTitledBorder;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.theme.icons.FileIcon;
import com.kalynx.swingtheme.theme.icons.FolderIcon;
import com.kalynx.swingtheme.theme.icons.RepositoryIcon;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.tree.*;
import java.awt.*;
import java.io.Serial;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * RefsListPanel displays git notes refs as a compacted tree grouped first by repository,
 * then by path-segment hierarchy with single-child directory compaction.
 * A filter field allows narrowing visible refs by path text.
 * Notifies a listener when the user selects a leaf ref node.
 */
public class RefsListPanel extends ThemedPanel {

    private static final String REF_PREFIX = "refs/notes/reviews/";
    private static final int ICON_SIZE = 16;

    private final ThemeManager themeManager = ThemeManager.getInstance();
    private final DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("Refs");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(rootNode);
    private final ThemedTree refsTree = new ThemedTree(treeModel);
    private final ThemedScrollPane scrollPane = new ThemedScrollPane(refsTree);
    private final ThemedTextField filterField = new ThemedTextField();

    private Map<String, List<String>> currentRefsByRepo = new LinkedHashMap<>();
    private BiConsumer<String, String> onRefSelected;

    /**
     * Constructs a RefsListPanel with a filter field above a compacted tree view.
     */
    public RefsListPanel() {
        setBorder(ThemedTitledBorder.create("Notes Refs"));
        refsTree.setRootVisible(false);
        refsTree.setShowsRootHandles(true);
        refsTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        refsTree.setCellRenderer(new RefTreeCellRenderer());
        configureLayout();
        setupListeners();
    }

    private void configureLayout() {
        setLayout(new MigLayout("fill, insets 5", "[][grow]", "[][grow]"));
        filterField.setToolTipText("Filter refs by path text");
        add(new ThemedLabel("Filter:"), "");
        add(filterField, "growx, wrap");
        add(scrollPane, "span, grow");
    }

    private void setupListeners() {
        refsTree.addTreeSelectionListener(_ -> {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) refsTree.getLastSelectedPathComponent();
            if (node != null && node.getUserObject() instanceof RefNodeData(String fullRef, String repoName) && onRefSelected != null) {
                onRefSelected.accept(repoName, fullRef);
            }
        });

        DocumentListener filterListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { applyFilter(); }
            public void removeUpdate(DocumentEvent e) { applyFilter(); }
            public void changedUpdate(DocumentEvent e) { applyFilter(); }
        };
        filterField.getDocument().addDocumentListener(filterListener);
    }

    /**
     * Replaces the tree contents from a map of repository name to list of full git ref paths.
     * The current filter is applied immediately.
     *
     * @param refsByRepo map from repository name to sorted list of full ref strings
     */
    public void setRefs(Map<String, List<String>> refsByRepo) {
        this.currentRefsByRepo = refsByRepo;
        SwingUtilities.invokeLater(this::applyFilter);
    }

    /**
     * Displays a plain status message (e.g. "Loading…", "Error: …") in place of the tree.
     *
     * @param message the message to show
     */
    public void setStatusMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            rootNode.removeAllChildren();
            rootNode.add(new DefaultMutableTreeNode(message));
            treeModel.reload();
        });
    }

    /**
     * Registers a callback invoked when the user selects a leaf ref node.
     * The first argument is the repository name; the second is the full git ref string.
     *
     * @param listener bi-consumer receiving (repoName, fullRef)
     */
    public void setOnRefSelected(BiConsumer<String, String> listener) {
        this.onRefSelected = listener;
    }

    private void applyFilter() {
        String filter = filterField.getText().trim().toLowerCase();
        rootNode.removeAllChildren();

        for (Map.Entry<String, List<String>> entry : currentRefsByRepo.entrySet()) {
            String repoName = entry.getKey();
            List<String> matchingRefs = entry.getValue().stream()
                .filter(ref -> {
                    String stripped = ref.startsWith(REF_PREFIX) ? ref.substring(REF_PREFIX.length()) : ref;
                    return filter.isEmpty() || stripped.toLowerCase().contains(filter);
                })
                .toList();

            if (matchingRefs.isEmpty()) {
                continue;
            }

            DefaultMutableTreeNode repoNode = new DefaultMutableTreeNode(repoName);
            rootNode.add(repoNode);

            for (String ref : matchingRefs) {
                addRefToRepoNode(repoNode, ref, repoName);
            }

            compactChildDirectories(repoNode);
        }

        treeModel.reload();
        expandAllNodes();
    }

    private void addRefToRepoNode(DefaultMutableTreeNode repoNode, String fullRef, String repoName) {
        String stripped = fullRef.startsWith(REF_PREFIX) ? fullRef.substring(REF_PREFIX.length()) : fullRef;
        String[] parts = stripped.split("/");
        DefaultMutableTreeNode current = repoNode;
        for (int i = 0; i < parts.length - 1; i++) {
            current = findOrCreateDirectoryNode(current, parts[i]);
        }
        current.add(new DefaultMutableTreeNode(new RefNodeData(fullRef, repoName)));
    }

    private DefaultMutableTreeNode findOrCreateDirectoryNode(DefaultMutableTreeNode parent, String name) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (child.getUserObject() instanceof String s && s.equals(name)) {
                return child;
            }
        }
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(name);
        parent.add(node);
        return node;
    }

    private void compactChildDirectories(DefaultMutableTreeNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            compactChildDirectories(child);
            compactSingleChildDirectories(child);
        }
    }

    private void compactSingleChildDirectories(DefaultMutableTreeNode node) {
        while (canCompactWithSingleDirectoryChild(node)) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(0);
            node.setUserObject(node.getUserObject() + "/" + child.getUserObject());
            node.removeAllChildren();
            while (child.getChildCount() > 0) {
                node.add((DefaultMutableTreeNode) child.getChildAt(0));
            }
        }
    }

    private boolean canCompactWithSingleDirectoryChild(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof String) || node.getChildCount() != 1) {
            return false;
        }
        DefaultMutableTreeNode onlyChild = (DefaultMutableTreeNode) node.getChildAt(0);
        return onlyChild.getUserObject() instanceof String;
    }

    private void expandAllNodes() {
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            expandNode((DefaultMutableTreeNode) rootNode.getChildAt(i));
        }
    }

    private void expandNode(DefaultMutableTreeNode node) {
        refsTree.expandPath(new TreePath(treeModel.getPathToRoot(node)));
        for (int i = 0; i < node.getChildCount(); i++) {
            expandNode((DefaultMutableTreeNode) node.getChildAt(i));
        }
    }

    private record RefNodeData(String fullRef, String repoName) {}

    private class RefTreeCellRenderer extends DefaultTreeCellRenderer {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                                                      boolean expanded, boolean leaf, int row,
                                                      boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            Theme theme = themeManager.getCurrentTheme();
            setBackgroundNonSelectionColor(theme.getBackgroundColor());
            setBackgroundSelectionColor(theme.getAccentColor());
            setTextNonSelectionColor(theme.getForegroundColor());
            setTextSelectionColor(Color.WHITE);
            setBorderSelectionColor(theme.getAccentColor());

            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object userObject = node.getUserObject();

            if (userObject instanceof RefNodeData refData) {
                String lastSegment = refData.fullRef().substring(refData.fullRef().lastIndexOf('/') + 1);
                setText(lastSegment);
                setFont(getFont().deriveFont(Font.PLAIN));
                setIcon(new FileIcon(ICON_SIZE));
            } else if (node.getParent() == rootNode && userObject instanceof String) {
                setText(userObject.toString());
                setFont(getFont().deriveFont(Font.BOLD));
                setIcon(new RepositoryIcon(ICON_SIZE));
            } else if (userObject instanceof String) {
                setText(userObject.toString());
                setFont(getFont().deriveFont(Font.PLAIN));
                setIcon(new FolderIcon(ICON_SIZE));
            }

            return this;
        }
    }
}


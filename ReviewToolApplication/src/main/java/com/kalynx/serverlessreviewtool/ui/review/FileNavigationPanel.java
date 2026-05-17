package com.kalynx.serverlessreviewtool.ui.review;

import java.io.Serial;

import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.*;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedScrollPane;
import com.kalynx.swingtheme.themedcomponents.ThemedTree;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.icons.FileIcon;
import com.kalynx.swingtheme.theme.icons.FolderIcon;
import com.kalynx.swingtheme.theme.icons.RepositoryIcon;
import com.kalynx.swingtheme.theme.icons.FileCommentIcon;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.CodeViewerModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * FileNavigationPanel - Tree view for navigating files across multiple repositories
 */
public class FileNavigationPanel extends ThemedPanel {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(FileNavigationPanel.class);

    private transient final ReviewContextManager reviewContextManager;
    private transient final CodeViewerModel codeViewerModel;
    private transient final ThemeManager themeManager = ThemeManager.getInstance();
    private transient final List<FileSelectionListener> listeners = new ArrayList<>();

    private ThemedTree fileTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;
    private transient ReviewContext currentReviewContext;
    private transient final java.util.Set<String> readFiles = new java.util.HashSet<>();
    private transient final java.util.Map<String, String> fileSignatures = new java.util.HashMap<>();
    private transient Consumer<ReviewFile> onFileDoubleClickListener;

    public FileNavigationPanel(ReviewContextManager reviewContextManager, CodeViewerModel codeViewerModel) {
        this.reviewContextManager = reviewContextManager;
        this.codeViewerModel = codeViewerModel;
        setLayout(new BorderLayout());

        initializeComponents();
        setupListeners();
        setupModelListeners();
    }

    private void initializeComponents() {
        rootNode = new DefaultMutableTreeNode("Review Files");
        treeModel = new DefaultTreeModel(rootNode);

        fileTree = new ThemedTree(treeModel);
        fileTree.setRootVisible(false);
        fileTree.setShowsRootHandles(true);
        fileTree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        fileTree.setCellRenderer(new FileTreeCellRenderer());

        fileTree.addTreeSelectionListener(ignored -> {
            DefaultMutableTreeNode selectedNode = (DefaultMutableTreeNode) fileTree.getLastSelectedPathComponent();
            if (selectedNode != null && selectedNode.getUserObject() instanceof ReviewFile file) {
                fireFileSelected(file);
            }
        });

        fileTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath path = fileTree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                        if (node.getUserObject() instanceof ReviewFile file) {
                            onFileDoubleClicked(file);
                        }
                    }
                }
            }
        });

        ThemedScrollPane scrollPane = new ThemedScrollPane(fileTree);
        add(scrollPane, BorderLayout.CENTER);
    }

    private void setupListeners() {
        reviewContextManager.addListener(this::onReviewContextChanged);
    }

    private void setupModelListeners() {
        codeViewerModel.availableFiles.addChangeListener(this::onFilesChanged);
        codeViewerModel.selectedFile.addChangeListener(this::onSelectedFileChanged);
    }

    private void onSelectedFileChanged(ReviewFile file) {
        if (file == null) {
            return;
        }
        readFiles.add(fileKey(file));
        fileTree.repaint();
    }

    private void onFilesChanged(List<ReviewFile> files) {
        LOGGER.info("onFilesChanged called with {} files", files != null ? files.size() : 0);

        SwingUtilities.invokeLater(() -> {
            try {
                if (files != null && !files.isEmpty()) {
                    updateReadTracking(files);
                    ReviewFile currentSelection = codeViewerModel.selectedFile.getValue();
                    buildFileTreeFromModel(files);

                    if (currentSelection != null) {
                        boolean fileStillExists = files.stream()
                            .anyMatch(f -> f.getPath().equals(currentSelection.getPath()) &&
                                           f.getRepository().equals(currentSelection.getRepository()));

                        if (fileStillExists) {
                            selectFileInTree(currentSelection);
                        } else {
                            fileTree.clearSelection();
                            codeViewerModel.selectFile(null);
                            codeViewerModel.setFileContent("", "", "");
                        }
                    }
                } else {
                    readFiles.clear();
                    fileSignatures.clear();
                    rootNode.removeAllChildren();
                    treeModel.reload();
                    fileTree.clearSelection();
                    codeViewerModel.selectFile(null);
                    codeViewerModel.setFileContent("", "", "");
                }
            } catch (Exception e) {
                LOGGER.error("Error building file tree", e);
            }
        });
    }

    private void onReviewContextChanged(ReviewContext context) {
        this.currentReviewContext = context;
    }

    private void buildFileTreeFromModel(List<ReviewFile> files) {
        rootNode.removeAllChildren();

        Map<String, DefaultMutableTreeNode> repoNodes = new HashMap<>();

        for (ReviewFile file : files) {
            String repoName = file.getRepository();
            DefaultMutableTreeNode repoNode = repoNodes.get(repoName);
            
            if (repoNode == null) {
                Repository repo = findRepository(repoName);
                repoNode = new DefaultMutableTreeNode(repo != null ? repo : repoName);
                repoNodes.put(repoName, repoNode);
                rootNode.add(repoNode);
                LOGGER.debug("Added repository node: {}", repoName);
            }

            addFileToTree(repoNode, file);
        }

        for (DefaultMutableTreeNode repoNode : repoNodes.values()) {
            compactDirectoryNodes(repoNode);
        }

        treeModel.reload();
        expandAllNodes();
        fileTree.revalidate();
        fileTree.repaint();
    }

    private void expandAllNodes() {
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            expandNode(child);
        }
    }

    private void expandNode(DefaultMutableTreeNode node) {
        try {
            TreePath path = new TreePath(treeModel.getPathToRoot(node));
            fileTree.expandPath(path);

            for (int i = 0; i < node.getChildCount(); i++) {
                DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
                expandNode(child);
            }
        } catch (Exception e) {
            LOGGER.error("Error expanding tree node: {}", node.getUserObject(), e);
        }
    }

    private Repository findRepository(String repoName) {
        if (currentReviewContext != null) {
            return currentReviewContext.getRepositories().stream()
                .filter(repo -> repo.getName().equals(repoName))
                .findFirst()
                .orElse(null);
        }
        return null;
    }

    private void addFileToTree(DefaultMutableTreeNode repoNode, ReviewFile file) {
        String[] pathParts = file.getPath().split("/");
        DefaultMutableTreeNode currentNode = repoNode;

        // Create directory nodes
        for (int i = 0; i < pathParts.length - 1; i++) {
            String dirName = pathParts[i];
            currentNode = findOrCreateChildNode(currentNode, dirName);
        }

        // Add file node
        DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(file);
        currentNode.add(fileNode);
    }

    private DefaultMutableTreeNode findOrCreateChildNode(DefaultMutableTreeNode parent, String name) {
        // Look for existing child with this name
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            Object userObject = child.getUserObject();
            String childName = userObject instanceof String ? (String) userObject : userObject.toString();
            if (childName.equals(name)) {
                return child;
            }
        }

        // Create new child
        DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(name);
        parent.add(newNode);
        return newNode;
    }

    private void compactDirectoryNodes(DefaultMutableTreeNode node) {
        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            compactDirectoryNodes(child);
            compactSingleChildDirectories(child);
        }
    }

    private void compactSingleChildDirectories(DefaultMutableTreeNode node) {
        while (canCompactWithSingleDirectoryChild(node)) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(0);
            String mergedName = node.getUserObject() + "/" + child.getUserObject();
            node.setUserObject(mergedName);
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

    private void selectFileInTree(ReviewFile file) {
        DefaultMutableTreeNode node = findNodeForFile(rootNode, file);
        if (node != null) {
            TreePath path = new TreePath(node.getPath());
            fileTree.setSelectionPath(path);
            fileTree.scrollPathToVisible(path);
        }
    }

    private DefaultMutableTreeNode findNodeForFile(DefaultMutableTreeNode node, ReviewFile targetFile) {
        Object userObject = node.getUserObject();
        if (userObject instanceof ReviewFile file) {
            if (file.getPath().equals(targetFile.getPath()) &&
                file.getRepository().equals(targetFile.getRepository())) {
                return node;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
            DefaultMutableTreeNode result = findNodeForFile(child, targetFile);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    // File tree cell renderer
    private class FileTreeCellRenderer extends DefaultTreeCellRenderer {
        @Serial
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);

            Theme theme = themeManager.getCurrentTheme();
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object userObject = node.getUserObject();

            // Apply theme colors
            setBackgroundNonSelectionColor(theme.getBackgroundColor());
            setBackgroundSelectionColor(theme.getAccentColor());
            setTextNonSelectionColor(theme.getForegroundColor());
            setTextSelectionColor(Color.WHITE);
            setBorderSelectionColor(theme.getAccentColor());

            int iconSize = 16; // Standard icon size for tree items

            switch (userObject) {
                case Repository repo -> {
                    setText(repo.getName());
                    setFont(getFont().deriveFont(Font.BOLD));
                    setIcon(new RepositoryIcon(iconSize));
                }
                case ReviewFile file -> {
                    setText(file.getFileName());
                    boolean isRead = readFiles.contains(fileKey(file));
                    setFont(getFont().deriveFont(isRead ? Font.PLAIN : Font.BOLD));

                    // Check for comments on this file
                    Icon fileIcon = new FileIcon(iconSize);
                    if (currentReviewContext != null) {
                        List<ReviewComment> comments = currentReviewContext.getCommentsForFile(file.getPath());
                        if (!comments.isEmpty()) {
                            // Determine if comments need resolution
                            boolean hasUnresolved = comments.stream()
                                    .anyMatch(c -> c.needsResolution() && !c.isResolved());
                            boolean hasResolved = comments.stream()
                                    .anyMatch(c -> c.needsResolution() && c.isResolved());

                            Color commentColor = hasUnresolved
                                    ? new Color(255, 152, 0)  // Orange for unresolved
                                    : (hasResolved ? new Color(76, 175, 80) : theme.getAccentColor());  // Green for resolved

                            int commentCount = comments.size();
                            Icon commentIcon = new FileCommentIcon(10, commentColor, commentCount, false);
                            fileIcon = new CompositeIcon(fileIcon, commentIcon);
                        }
                    }
                    setIcon(fileIcon);

                    // Color by change type (only when not selected)
                    if (!sel) {
                        switch (file.getChangeType()) {
                            case ADDED:
                                setForeground(new Color(40, 167, 69)); // Green
                                break;
                            case DELETED:
                                setForeground(new Color(220, 53, 69)); // Red
                                break;
                            case MODIFIED:
                                setForeground(theme.getAccentColor());
                                break;
                            case RENAMED:
                                setForeground(new Color(255, 193, 7)); // Yellow
                                break;
                        }
                    } else {
                        setForeground(Color.WHITE);
                    }
                }
                case String _ -> {
                    setText(value.toString());
                    setFont(getFont().deriveFont(Font.PLAIN));
                    setIcon(new FolderIcon(iconSize));
                }
                case null, default -> setText(value.toString());
            }

            return this;
        }
    }

    // Composite icon to overlay comment indicator on file icon
    private static class CompositeIcon implements Icon {
        private final Icon baseIcon;
        private final Icon overlayIcon;

        public CompositeIcon(Icon baseIcon, Icon overlayIcon) {
            this.baseIcon = baseIcon;
            this.overlayIcon = overlayIcon;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            baseIcon.paintIcon(c, g, x, y);
            // Position overlay in bottom-right corner of base icon
            int overlayX = x + baseIcon.getIconWidth() - overlayIcon.getIconWidth();
            int overlayY = y + baseIcon.getIconHeight() - overlayIcon.getIconHeight();
            overlayIcon.paintIcon(c, g, overlayX, overlayY);
        }

        @Override
        public int getIconWidth() {
            return baseIcon.getIconWidth(); // Same width as base icon
        }

        @Override
        public int getIconHeight() {
            return baseIcon.getIconHeight(); // Same height as base icon
        }
    }

    // Listener interface
    public interface FileSelectionListener {
        void onFileSelected(ReviewFile file);
    }

    private void fireFileSelected(ReviewFile file) {
        readFiles.add(fileKey(file));
        codeViewerModel.selectFile(file);
        for (FileSelectionListener listener : listeners) {
            listener.onFileSelected(file);
        }
        fileTree.repaint();
    }

    private void onFileDoubleClicked(ReviewFile file) {
        if (onFileDoubleClickListener != null) {
            onFileDoubleClickListener.accept(file);
        }
    }

    /**
     * Sets the listener invoked when the user double-clicks a file node.
     * The listener receives the double-clicked {@link ReviewFile}.
     *
     * @param listener consumer receiving the file
     */
    public void setOnFileDoubleClickListener(Consumer<ReviewFile> listener) {
        this.onFileDoubleClickListener = listener;
    }

    private void updateReadTracking(List<ReviewFile> files) {
        java.util.Map<String, String> newSignatures = new java.util.HashMap<>();
        for (ReviewFile file : files) {
            String key = fileKey(file);
            String signature = fileSignature(file);
            newSignatures.put(key, signature);
            String previousSignature = fileSignatures.get(key);
            if (previousSignature != null && !previousSignature.equals(signature)) {
                readFiles.remove(key);
            }
        }

        java.util.Set<String> removedKeys = new java.util.HashSet<>(fileSignatures.keySet());
        removedKeys.removeAll(newSignatures.keySet());
        removedKeys.forEach(readFiles::remove);

        fileSignatures.clear();
        fileSignatures.putAll(newSignatures);
    }

    private String fileKey(ReviewFile file) {
        return file.getRepository() + "|" + file.getPath();
    }

    private String fileSignature(ReviewFile file) {
        String changeType = file.getChangeType() != null ? file.getChangeType().name() : "";
        String baseBranch = file.getBaseBranch() != null ? file.getBaseBranch() : "";
        String reviewBranch = file.getReviewBranch() != null ? file.getReviewBranch() : "";
        return changeType + "|" + baseBranch + "|" + reviewBranch;
    }
}


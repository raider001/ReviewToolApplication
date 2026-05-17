package com.kalynx.serverlessreviewtool.ui.review;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.*;
import com.kalynx.swingtheme.themedcomponents.*;
import com.kalynx.swingtheme.theme.LoadingStateManager;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.theme.WindowResizeHandler;
import com.kalynx.swingtheme.theme.WindowFrameLoadingIndicator;
import com.kalynx.swingtheme.theme.icons.AlertIcon;
import com.kalynx.swingtheme.theme.icons.CheckIcon;
import net.miginfocom.swing.MigLayout;

import com.kalynx.swingtheme.themedcomponents.FocusCondition;
import com.kalynx.swingtheme.themedcomponents.ThemedRootPane;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Singleton dialog displaying a single comment thread for a specific file and line.
 * Use {@link #show} to open or reuse the singleton instance.
 * Tracks the currently open thread via {@link #getActiveKey()} so other components
 * (e.g. {@link ReviewCommentsDialog}) can highlight the corresponding row.
 */
public class InlineCommentDialog extends JDialog {

    private static InlineCommentDialog instance;
    private static String activeKey;
    private static Runnable activeKeyListener;
    private static final CopyOnWriteArrayList<Runnable> globalCommentChangedListeners = new CopyOnWriteArrayList<>();

    private final ThemeManager themeManager = ThemeManager.getInstance();
    private final LoadingStateManager loadingStateManager = LoadingStateManager.getInstance();
    private final SettingsManager settingsManager;
    private ReviewContext reviewContext;
    private ReviewContextManager reviewContextManager;
    private String filePath;
    private int lineNumber;
    private Runnable onCommentChanged;
    private String currentUser;

    private CustomTitleBar titleBar;
    private ThemedPanel commentsContainer;
    private ThemedRichTextEditor newCommentEditor;
    private ThemedButton addButton;
    private ThemedButton resolveToggleButton;
    private ThemedPanel headerPanel;
    private boolean conversationNeedsResolution = false;
    private boolean conversationResolved = false;

    /**
     * Opens (or reuses) the singleton {@link InlineCommentDialog} for the given file and line.
     *
     * @param owner                parent window
     * @param settingsManager      settings manager for user info
     * @param reviewContext        current review context
     * @param reviewContextManager manager for persisting comment changes
     * @param filePath             path of the file the thread belongs to
     * @param lineNumber           line number the thread is attached to
     * @param onCommentChanged     callback invoked after any comment or resolution change
     */
    public static void show(Window owner, SettingsManager settingsManager, ReviewContext reviewContext,
                            ReviewContextManager reviewContextManager, String filePath, int lineNumber,
                            Runnable onCommentChanged) {
        if (instance == null || !instance.isDisplayable()) {
            instance = new InlineCommentDialog(owner, settingsManager, reviewContext, reviewContextManager,
                    filePath, lineNumber, onCommentChanged);
        } else {
            instance.loadThread(reviewContext, reviewContextManager, filePath, lineNumber, onCommentChanged);
        }
        setActiveKey(makeThreadKey(filePath, lineNumber));
        if (!instance.isVisible()) instance.setVisible(true);
        instance.toFront();
    }

    /**
     * Returns the key for the currently open comment thread, or {@code null} if no thread is open.
     * The key format is {@code "filePath\u0000lineNumber"}.
     *
     * @return active thread key or null
     */
    public static String getActiveKey() {
        return activeKey;
    }

    /**
     * Sets a listener that is called whenever the active thread key changes (dialog opened, switched, or closed).
     *
     * @param listener the listener to fire on key changes, or null to remove
     */
    public static void setActiveKeyListener(Runnable listener) {
        activeKeyListener = listener;
    }

    /**
     * Adds a listener that is called whenever any comment or resolution is saved in this dialog.
     *
     * @param listener the listener to add
     */
    public static void addGlobalCommentChangedListener(Runnable listener) {
        globalCommentChangedListeners.add(listener);
    }

    /**
     * Removes a previously added global comment-changed listener.
     *
     * @param listener the listener to remove
     */
    public static void removeGlobalCommentChangedListener(Runnable listener) {
        globalCommentChangedListeners.remove(listener);
    }

    /**
     * Builds the composite thread key used by {@link #getActiveKey()}.
     *
     * @param filePath   file path
     * @param lineNumber line number
     * @return composite key
     */
    static String makeThreadKey(String filePath, int lineNumber) {
        return filePath + "\u0000" + lineNumber;
    }

    private static void setActiveKey(String key) {
        activeKey = key;
        if (activeKeyListener != null) activeKeyListener.run();
    }

    private InlineCommentDialog(Window owner, SettingsManager settingsManager, ReviewContext reviewContext,
                                ReviewContextManager reviewContextManager, String filePath, int lineNumber,
                                Runnable onCommentChanged) {
        super(owner, ModalityType.MODELESS);
        this.settingsManager = settingsManager;
        this.reviewContext = reviewContext;
        this.reviewContextManager = reviewContextManager;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.onCommentChanged = onCommentChanged;
        this.currentUser = settingsManager.getCurrentUserName();

        String loadingId = "load-comments-dialog-" + filePath + "-" + lineNumber;
        loadingStateManager.startLoading(loadingId);

        setUndecorated(true);
        initComponents();
        setupKeyboardShortcuts();
        loadExistingComments();
        applyTheme();

        loadingStateManager.stopLoading(loadingId);

        WindowFrameLoadingIndicator.install(this);

        WindowResizeHandler resizeHandler = new WindowResizeHandler(this, 5);
        addMouseListener(resizeHandler);
        addMouseMotionListener(resizeHandler);

        setMinimumSize(new Dimension(500, 350));
        pack();
        setSize(600, 450);
        setLocationRelativeTo(owner);
        setResizable(true);
    }

    @Override
    public void dispose() {
        setActiveKey(null);
        super.dispose();
    }

    @Override
    protected JRootPane createRootPane() {
        return new ThemedRootPane();
    }

    private String buildTitle() {
        String fileName = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        return fileName + " : Line " + lineNumber;
    }

    private void loadThread(ReviewContext reviewContext, ReviewContextManager reviewContextManager,
                            String filePath, int lineNumber, Runnable onCommentChanged) {
        this.reviewContext = reviewContext;
        this.reviewContextManager = reviewContextManager;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.onCommentChanged = onCommentChanged;
        this.currentUser = settingsManager.getCurrentUserName();

        titleBar.setTitle(buildTitle());
        conversationNeedsResolution = false;
        conversationResolved = false;
        newCommentEditor.setHtml("");
        addButton.setEnabled(true);
        addButton.setText("Add Comment");
        newCommentEditor.setEnabled(true);
        loadExistingComments();
    }

    private void setupKeyboardShortcuts() {
        ThemedRootPane rootPane = (ThemedRootPane) getRootPane();

        rootPane.registerKeyboardAction(
            this::dispose,
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            FocusCondition.WHEN_IN_FOCUSED_WINDOW
        );

        newCommentEditor.getEditorPane().registerKeyboardAction(
            e -> handleAddComment(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK),
            JComponent.WHEN_FOCUSED
        );
    }

    private void initComponents() {
        ThemedPanel contentPanel = new ThemedPanel();
        contentPanel.setLayout(new MigLayout("fill, insets 0, gap 0, novisualpadding", "[grow]", "[][][grow]"));

        Theme theme = themeManager.getCurrentTheme();
        contentPanel.setBorder(BorderFactory.createLineBorder(theme.getBorderColor(), 1));

        titleBar = new CustomTitleBar(this, buildTitle());
        contentPanel.add(titleBar, "growx, wrap");

        headerPanel = new ThemedPanel(new MigLayout("fill, insets 4", "[grow]", "[]"));
        headerPanel.setVisible(false);
        contentPanel.add(headerPanel, "growx, wrap");

        commentsContainer = new ThemedPanel();
        commentsContainer.setLayout(new BoxLayout(commentsContainer, BoxLayout.Y_AXIS));

        ThemedScrollPane scrollPane = new ThemedScrollPane(commentsContainer);
        scrollPane.setMinimumSize(new Dimension(200, 100));

        ThemedPanel inputPanel = createInputPanel();

        ThemedSplitPane splitPane = new ThemedSplitPane(JSplitPane.VERTICAL_SPLIT, scrollPane, inputPanel);
        splitPane.setResizeWeight(1.0);
        splitPane.setDividerSize(6);
        splitPane.setDividerLocation(300);

        contentPanel.add(splitPane, "grow");

        setContentPane(contentPanel);
    }

    private ThemedPanel createInputPanel() {
        ThemedPanel panel = new ThemedPanel(new MigLayout("", "[grow]", "[grow,fill][]"));

        newCommentEditor = new ThemedRichTextEditor();
        newCommentEditor.getEditorPane().setToolTipText("Press Ctrl+Enter to submit. Drag divider above to resize.");
        newCommentEditor.setMinimumSize(new Dimension(200, themeManager.scale(150)));

        panel.add(newCommentEditor, "cell 0 0, grow, pushy, wmin 200");

        ThemedPanel buttonRow = new ThemedPanel(new MigLayout("", "[][grow]", "[]"));

        resolveToggleButton = new ThemedButton("Mark as Needs Resolution");
        resolveToggleButton.addActionListener(this::handleResolveToggle);
        buttonRow.add(resolveToggleButton, "cell 0 0");

        addButton = new ThemedButton("Add Comment");
        addButton.addActionListener(this::handleAddComment);
        buttonRow.add(addButton, "cell 1 0, align right");

        panel.add(buttonRow, "cell 0 1");

        return panel;
    }

    private void loadExistingComments() {
        commentsContainer.removeAll();

        List<ReviewComment> allComments = reviewContext.getCommentsForFile(filePath);
        List<ReviewComment> lineComments = allComments.stream()
            .filter(c -> c.getLineNumber() == lineNumber)
            .toList();

        conversationNeedsResolution = !lineComments.isEmpty() &&
            lineComments.stream().anyMatch(ReviewComment::needsResolution);
        conversationResolved = conversationNeedsResolution &&
            lineComments.stream().filter(ReviewComment::needsResolution).allMatch(ReviewComment::isResolved);

        updateResolutionUI();

        if (lineComments.isEmpty()) {
            ThemedPanel placeholderPanel = new ThemedPanel(new MigLayout("fill, insets 10", "[center]", "[center]"));
            ThemedLabel noComments = new ThemedLabel("<html><div style='text-align: center;'>" +
                    "<b>No comments yet</b><br>" +
                    "Start the conversation below." +
                    "</div></html>");
            noComments.setFont(new Font("Segoe UI", Font.PLAIN, themeManager.scale(11)));
            placeholderPanel.add(noComments, "cell 0 0");
            commentsContainer.add(placeholderPanel);
        } else {
            for (ReviewComment comment : lineComments) {
                commentsContainer.add(new CommentCard(comment));
                commentsContainer.add(Box.createVerticalStrut(themeManager.scale(3)));
            }
        }

        commentsContainer.add(Box.createVerticalGlue());
        commentsContainer.revalidate();
        commentsContainer.repaint();
    }

    private void updateResolutionUI() {
        Theme theme = themeManager.getCurrentTheme();

        headerPanel.removeAll();
        headerPanel.setLayout(new MigLayout("fill, insets 8", "[]4[]push", "[]"));

        if (conversationNeedsResolution) {
            headerPanel.setVisible(true);

            Color bgColor;
            Color borderColor;
            Color textColor;
            Icon icon;
            String status;

            if (conversationResolved) {
                bgColor = new Color(76, 175, 80, 30);
                borderColor = new Color(76, 175, 80);
                textColor = new Color(76, 175, 80);
                icon = new CheckIcon(themeManager.scale(16), borderColor);
                status = "Resolved";
            } else {
                bgColor = new Color(255, 152, 0, 30);
                borderColor = new Color(255, 152, 0);
                textColor = new Color(255, 152, 0);
                icon = new AlertIcon(themeManager.scale(16), borderColor);
                status = "Unresolved";
            }

            headerPanel.setBackground(bgColor);
            headerPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor));

            ThemedLabel iconLabel = new ThemedLabel();
            iconLabel.setIcon(icon);
            headerPanel.add(iconLabel, "align left");

            ThemedLabel statusLabel = new ThemedLabel(status);
            statusLabel.setFont(new Font("Segoe UI", Font.BOLD, themeManager.scale(12)));
            statusLabel.setForeground(textColor);
            headerPanel.add(statusLabel, "align left");

            if (conversationResolved) {
                String resolvedByUser = currentUser;
                List<ReviewComment> allComments = reviewContext.getCommentsForFile(filePath);
                List<ReviewComment> lineComments = allComments.stream()
                    .filter(c -> c.getLineNumber() == lineNumber && c.needsResolution() && c.isResolved())
                    .toList();

                if (!lineComments.isEmpty() && lineComments.getFirst().getResolvedBy() != null) {
                    resolvedByUser = lineComments.getFirst().getResolvedBy();
                }

                ThemedLabel resolvedByLabel = new ThemedLabel("• Marked resolved by " + resolvedByUser);
                resolvedByLabel.setFont(new Font("Segoe UI", Font.PLAIN, themeManager.scale(10)));
                resolvedByLabel.setForeground(theme.getSecondaryTextColor());
                headerPanel.add(resolvedByLabel, "gapleft 6");
            }
        } else {
            headerPanel.setVisible(false);
        }

        if (!conversationNeedsResolution) {
            resolveToggleButton.setText("Mark as Needs Resolution");
        } else if (conversationResolved) {
            resolveToggleButton.setText("Mark as Unresolved");
        } else {
            resolveToggleButton.setText("Mark as Resolved");
        }

        headerPanel.revalidate();
        headerPanel.repaint();
    }

    private void handleResolveToggle() {
        List<ReviewComment> lineComments = reviewContext.getCommentsForFile(filePath).stream()
            .filter(c -> c.getLineNumber() == lineNumber)
            .collect(Collectors.toList());

        if (lineComments.isEmpty()) {
            return;
        }

        if (!conversationNeedsResolution) {
            for (ReviewComment comment : lineComments) {
                comment.setNeedsResolution(true);
                comment.markUnresolved();
            }
        } else if (conversationResolved) {
            for (ReviewComment comment : lineComments) {
                comment.markUnresolved();
            }
        } else {
            for (ReviewComment comment : lineComments) {
                comment.markResolved(currentUser);
            }
        }

        String operationId = "save-resolution-" + java.util.UUID.randomUUID();
        loadingStateManager.startLoading(operationId);

        resolveToggleButton.setEnabled(false);
        addButton.setEnabled(false);
        resolveToggleButton.setText("Saving...");

        reviewContextManager.saveAllComments(reviewContext.reviewId, lineComments)
            .thenRun(() -> SwingUtilities.invokeLater(() -> {
                loadingStateManager.stopLoading(operationId);
                resolveToggleButton.setEnabled(true);
                addButton.setEnabled(true);
                loadExistingComments();
                notifyCommentChanged();
            }))
            .exceptionally(error -> {
                SwingUtilities.invokeLater(() -> {
                    loadingStateManager.stopLoading(operationId);
                    resolveToggleButton.setEnabled(true);
                    addButton.setEnabled(true);
                    loadExistingComments();
                    ThemedConfirmDialog.showMessage(this, "Save Error",
                        "Failed to save resolution status: " + error.getMessage());
                });
                return null;
            });
    }

    private void handleAddComment() {
        String commentText = newCommentEditor.getHtml().trim();

        if (commentText.isEmpty()) {
            ThemedConfirmDialog.showMessage(this, "Error", "Please enter a comment");
            return;
        }

        String commentId = com.kalynx.serverlessreviewtool.utils.UuidV7Generator.generate();

        ReviewComment newComment = new ReviewComment(
            commentId,
            filePath,
            lineNumber,
            currentUser,
            commentText,
            "just now",
            null,
            false
        );

        reviewContext.addComment(newComment);

        String operationId = "save-comment-" + commentId;
        loadingStateManager.startLoading(operationId);

        addButton.setEnabled(false);
        newCommentEditor.setEnabled(false);
        addButton.setText("Saving...");

        reviewContextManager.saveComment(reviewContext.reviewId, newComment)
            .thenRun(() -> SwingUtilities.invokeLater(() -> {
                loadingStateManager.stopLoading(operationId);
                addButton.setEnabled(true);
                newCommentEditor.setEnabled(true);
                addButton.setText("Add Comment");
                newCommentEditor.setHtml("");
                loadExistingComments();
                notifyCommentChanged();

                JScrollBar vertical = ((JScrollPane) commentsContainer.getParent().getParent()).getVerticalScrollBar();
                vertical.setValue(vertical.getMaximum());
            }))
            .exceptionally(error -> {
                SwingUtilities.invokeLater(() -> {
                    loadingStateManager.stopLoading(operationId);
                    addButton.setEnabled(true);
                    newCommentEditor.setEnabled(true);
                    addButton.setText("Add Comment");

                    ThemedConfirmDialog.showMessage(this, "Save Error",
                        "Failed to save comment: " + error.getMessage());

                    reviewContext.getComments().remove(newComment);
                });
                return null;
            });
    }

    private void notifyCommentChanged() {
        if (onCommentChanged != null) onCommentChanged.run();
        globalCommentChangedListeners.forEach(Runnable::run);
    }

    private void applyTheme() {
        Theme theme = themeManager.getCurrentTheme();
        getContentPane().setBackground(theme.getBackgroundColor());
    }
}


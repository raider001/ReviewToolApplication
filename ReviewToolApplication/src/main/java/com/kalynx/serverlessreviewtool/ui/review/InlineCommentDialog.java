package com.kalynx.serverlessreviewtool.ui.review;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.*;
import com.kalynx.swingtheme.themedcomponents.*;
import com.kalynx.swingtheme.theme.LoadingStateManager;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.theme.WindowResizeHandler;
import com.kalynx.swingtheme.theme.icons.AlertIcon;
import com.kalynx.swingtheme.theme.icons.CheckIcon;
import net.miginfocom.swing.MigLayout;

import com.kalynx.swingtheme.themedcomponents.FocusCondition;
import com.kalynx.swingtheme.themedcomponents.ThemedRootPane;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.stream.Collectors;

public class InlineCommentDialog extends JDialog {

    private final ThemeManager themeManager = ThemeManager.getInstance();
    private final LoadingStateManager loadingStateManager = LoadingStateManager.getInstance();
    private final ReviewContext reviewContext;
    private final ReviewContextManager reviewContextManager;
    private final ReviewFile file;
    private final int lineNumber;
    private final Runnable onCommentAdded;
    private final String currentUser;

    private ThemedPanel commentsContainer;
    private ThemedTextArea newCommentArea;
    private ThemedButton addButton;
    private ThemedButton resolveToggleButton;
    private ThemedPanel headerPanel;
    private boolean conversationNeedsResolution = false;
    private boolean conversationResolved = false;

    public InlineCommentDialog(Window owner, SettingsManager settingsManager, ReviewContext reviewContext, ReviewContextManager reviewContextManager,
                               ReviewFile file, int lineNumber, Runnable onCommentAdded) {
        super(owner, ModalityType.MODELESS);
        this.reviewContext = reviewContext;
        this.reviewContextManager = reviewContextManager;
        this.file = file;
        this.lineNumber = lineNumber;
        this.onCommentAdded = onCommentAdded;

        this.currentUser = settingsManager.getCurrentUserName();

        // Start loading indicator for dialog initialization
        String loadingId = "load-comments-dialog-" + file.getPath() + "-" + lineNumber;
        loadingStateManager.startLoading(loadingId);

        setUndecorated(true);
        initComponents();
        setupKeyboardShortcuts();
        loadExistingComments();
        applyTheme();

        // Stop loading indicator after dialog is ready
        loadingStateManager.stopLoading(loadingId);

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
    protected JRootPane createRootPane() {
        return new ThemedRootPane();
    }

    private void setupKeyboardShortcuts() {
        ThemedRootPane rootPane = (ThemedRootPane) getRootPane();

        rootPane.registerKeyboardAction(
            this::dispose,
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            FocusCondition.WHEN_IN_FOCUSED_WINDOW
        );

        newCommentArea.registerKeyboardAction(
            e -> handleAddComment(),
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK),
            JComponent.WHEN_FOCUSED
        );
    }

    private void initComponents() {
        ThemedPanel contentPanel = new ThemedPanel();
        contentPanel.setLayout(new MigLayout("fill, insets 0", "[grow]", "[][][grow]"));

        Theme theme = themeManager.getCurrentTheme();
        contentPanel.setBorder(BorderFactory.createLineBorder(theme.getBorderColor(), 2));

        String fileName = file.getPath().substring(file.getPath().lastIndexOf('/') + 1);
        CustomTitleBar titleBar = new CustomTitleBar(this, fileName + " : Line " + lineNumber);
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

        newCommentArea = new ThemedTextArea(3, 40);
        newCommentArea.setLineWrap(true);
        newCommentArea.setWrapStyleWord(true);
        newCommentArea.setToolTipText("Press Ctrl+Enter to submit. Use ```code``` for code snippets. Drag divider above to resize.");
        newCommentArea.setMinimumSize(new Dimension(200, themeManager.scale(70)));

        ThemedScrollPane textScrollPane = new ThemedScrollPane(newCommentArea);
        panel.add(textScrollPane, "cell 0 0, grow, pushy, wmin 200");


        ThemedPanel buttonRow = new ThemedPanel(new MigLayout("", "[][][grow]", "[]"));

        resolveToggleButton = new ThemedButton("Mark as Needs Resolution");
        resolveToggleButton.addActionListener(this::handleResolveToggle);
        buttonRow.add(resolveToggleButton, "cell 0 0");

        ThemedButton codeButton = new ThemedButton("<>");
        codeButton.setToolTipText("Insert code snippet");
        codeButton.addActionListener(this::handleInsertCode);
        buttonRow.add(codeButton, "cell 1 0");

        addButton = new ThemedButton("Add Comment");
        addButton.addActionListener(this::handleAddComment);
        buttonRow.add(addButton, "cell 2 0, align right");

        panel.add(buttonRow, "cell 0 1");

        return panel;
    }

    private void handleInsertCode() {
        String currentText = newCommentArea.getText();
        int caretPos = newCommentArea.getCaretPosition();

        String codeBlock = "```\n\n```";
        String newText = currentText.substring(0, caretPos) + codeBlock + currentText.substring(caretPos);

        newCommentArea.setText(newText);
        newCommentArea.setCaretPosition(caretPos + 4);
        newCommentArea.requestFocus();
    }

    private void loadExistingComments() {
        commentsContainer.removeAll();

        List<ReviewComment> allComments = reviewContext.getCommentsForFile(file.getPath());
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
            // Show banner for both Unresolved and Resolved states
            headerPanel.setVisible(true);

            Color bgColor;
            Color borderColor;
            Color textColor;
            Icon icon;
            String status;

            if (conversationResolved) {
                // State 3: Resolved (Green)
                bgColor = new Color(76, 175, 80, 30);
                borderColor = new Color(76, 175, 80);
                textColor = new Color(76, 175, 80);
                icon = new CheckIcon(themeManager.scale(16), borderColor);
                status = "Resolved";
            } else {
                // State 2: Unresolved (Orange)
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
                // Find the user who resolved the comment (get from actual comment data)
                String resolvedByUser = currentUser; // Default fallback
                List<ReviewComment> allComments = reviewContext.getCommentsForFile(file.getPath());
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
            // State 1: Observation (No banner)
            headerPanel.setVisible(false);
        }

        // Update button text based on three states
        if (!conversationNeedsResolution) {
            // State 1: Observation → can mark as Unresolved
            resolveToggleButton.setText("Mark as Needs Resolution");
        } else if (conversationResolved) {
            // State 3: Resolved → can mark as Unresolved
            resolveToggleButton.setText("Mark as Unresolved");
        } else {
            // State 2: Unresolved → can mark as Resolved
            resolveToggleButton.setText("Mark as Resolved");
        }

        headerPanel.revalidate();
        headerPanel.repaint();
    }

    private void handleResolveToggle() {
        List<ReviewComment> lineComments = reviewContext.getCommentsForFile(file.getPath()).stream()
            .filter(c -> c.getLineNumber() == lineNumber)
            .collect(Collectors.toList());

        if (lineComments.isEmpty()) {
            return;
        }

        // Three-state system with one-way gate:
        // 1. Observation (needsResolution=false) → Mark as Unresolved → State 2 [ONE WAY - can't go back]
        // 2. Unresolved (needsResolution=true, resolved=false) ↔ Resolved (needsResolution=true, resolved=true)

        if (!conversationNeedsResolution) {
            // State 1 → State 2: Mark as Unresolved (one-way transition)
            // Once marked, needsResolution stays true forever
            for (ReviewComment comment : lineComments) {
                comment.setNeedsResolution(true);
                // Ensure resolved is false (Unresolved state)
                comment.markUnresolved();
            }
        } else if (conversationResolved) {
            // State 3 → State 2: Mark as Unresolved
            // needsResolution stays true, just toggle resolved flag
            for (ReviewComment comment : lineComments) {
                comment.markUnresolved();
            }
        } else {
            // State 2 → State 3: Mark as Resolved
            // needsResolution stays true, set resolved to true
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

                if (onCommentAdded != null) {
                    onCommentAdded.run();
                }
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
        String commentText = newCommentArea.getText().trim();

        if (commentText.isEmpty()) {
            ThemedConfirmDialog.showMessage(this, "Error", "Please enter a comment");
            return;
        }

        String commentId = com.kalynx.serverlessreviewtool.utils.UuidV7Generator.generate();

        // Create comment in default state: "just a comment" (not needing resolution)
        ReviewComment newComment = new ReviewComment(
            commentId,
            file.getPath(),
            lineNumber,
            currentUser,
            commentText,
            "just now",
            null,
            false  // Default state: just a comment (not needing resolution)
        );

        reviewContext.addComment(newComment);

        String operationId = "save-comment-" + commentId;
        loadingStateManager.startLoading(operationId);

        addButton.setEnabled(false);
        newCommentArea.setEnabled(false);
        addButton.setText("Saving...");

        reviewContextManager.saveComment(reviewContext.reviewId, newComment)
            .thenRun(() -> SwingUtilities.invokeLater(() -> {
                loadingStateManager.stopLoading(operationId);
                addButton.setEnabled(true);
                newCommentArea.setEnabled(true);
                addButton.setText("Add Comment");
                newCommentArea.setText("");
                loadExistingComments();

                if (onCommentAdded != null) {
                    onCommentAdded.run();
                }

                JScrollBar vertical = ((JScrollPane) commentsContainer.getParent().getParent()).getVerticalScrollBar();
                vertical.setValue(vertical.getMaximum());
            }))
            .exceptionally(error -> {
                SwingUtilities.invokeLater(() -> {
                    loadingStateManager.stopLoading(operationId);
                    addButton.setEnabled(true);
                    newCommentArea.setEnabled(true);
                    addButton.setText("Add Comment");

                    ThemedConfirmDialog.showMessage(this, "Save Error",
                        "Failed to save comment: " + error.getMessage());

                    reviewContext.getComments().remove(newComment);
                });
                return null;
            });
    }

    private void applyTheme() {
        Theme theme = themeManager.getCurrentTheme();
        getContentPane().setBackground(theme.getBackgroundColor());
    }
}
































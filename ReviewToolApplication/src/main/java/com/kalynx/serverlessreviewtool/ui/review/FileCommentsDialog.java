package com.kalynx.serverlessreviewtool.ui.review;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.swingtheme.themedcomponents.*;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.theme.WindowResizeHandler;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Dialog listing all comment threads for a specific file, grouped by line number.
 * Each thread row opens the {@link InlineCommentDialog} for that line when clicked.
 * Provides access to comments even when the associated lines have been deleted.
 */
public class FileCommentsDialog extends JDialog {

    private final ThemeManager themeManager = ThemeManager.getInstance();
    private final SettingsManager settingsManager;
    private final ReviewContext reviewContext;
    private final ReviewContextManager reviewContextManager;
    private final ReviewFile file;
    private final Runnable onCommentRefreshed;

    private ThemedPanel threadsContainer;

    /**
     * Creates a file comments dialog for the given file.
     *
     * @param owner               parent window
     * @param settingsManager     settings manager for user info
     * @param reviewContext       current review context
     * @param reviewContextManager manager for persisting comment changes
     * @param file                file whose comments to display
     * @param onCommentRefreshed  callback invoked after any comment change
     */
    public FileCommentsDialog(Window owner, SettingsManager settingsManager, ReviewContext reviewContext,
                              ReviewContextManager reviewContextManager, ReviewFile file, Runnable onCommentRefreshed) {
        super(owner, ModalityType.MODELESS);
        this.settingsManager = settingsManager;
        this.reviewContext = reviewContext;
        this.reviewContextManager = reviewContextManager;
        this.file = file;
        this.onCommentRefreshed = onCommentRefreshed;

        setUndecorated(true);
        initComponents();
        setupKeyboardShortcuts();
        loadThreads();
        applyTheme();

        WindowResizeHandler resizeHandler = new WindowResizeHandler(this, 5);
        addMouseListener(resizeHandler);
        addMouseMotionListener(resizeHandler);

        setMinimumSize(new Dimension(480, 300));
        pack();
        setSize(560, 480);
        setLocationRelativeTo(owner);
        setResizable(true);
    }

    @Override
    protected JRootPane createRootPane() {
        return new ThemedRootPane();
    }

    private void initComponents() {
        ThemedPanel contentPanel = new ThemedPanel();
        contentPanel.setLayout(new MigLayout("fill, insets 0", "[grow]", "[][grow]"));

        Theme theme = themeManager.getCurrentTheme();
        contentPanel.setBorder(BorderFactory.createLineBorder(theme.getBorderColor(), 2));

        String fileName = file.getPath().contains("/")
            ? file.getPath().substring(file.getPath().lastIndexOf('/') + 1)
            : file.getPath();
        CustomTitleBar titleBar = new CustomTitleBar(this, "Comments — " + fileName);
        contentPanel.add(titleBar, "growx, wrap");

        threadsContainer = new ThemedPanel();
        threadsContainer.setLayout(new BoxLayout(threadsContainer, BoxLayout.Y_AXIS));

        ThemedScrollPane scrollPane = new ThemedScrollPane(threadsContainer);
        contentPanel.add(scrollPane, "grow");

        setContentPane(contentPanel);
    }

    private void setupKeyboardShortcuts() {
        ThemedRootPane rootPane = (ThemedRootPane) getRootPane();
        rootPane.registerKeyboardAction(
            this::dispose,
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            FocusCondition.WHEN_IN_FOCUSED_WINDOW
        );
    }

    private void loadThreads() {
        threadsContainer.removeAll();

        List<ReviewComment> allComments = reviewContext.getCommentsForFile(file.getPath());

        if (allComments.isEmpty()) {
            showEmptyState();
        } else {
            Map<Integer, List<ReviewComment>> byLine = allComments.stream()
                .collect(Collectors.groupingBy(ReviewComment::getLineNumber, TreeMap::new, Collectors.toList()));

            byLine.forEach((lineNumber, comments) -> {
                ThreadSummaryRow row = new ThreadSummaryRow(lineNumber, comments);
                row.setOnOpenListener(() -> openThreadDialog(lineNumber));
                threadsContainer.add(row);
                threadsContainer.add(Box.createVerticalStrut(1));
            });
        }

        threadsContainer.add(Box.createVerticalGlue());
        threadsContainer.revalidate();
        threadsContainer.repaint();
    }

    private void showEmptyState() {
        ThemedPanel placeholder = new ThemedPanel(new MigLayout("fill, insets 20", "[center]", "[center]"));
        ThemedLabel label = new ThemedLabel("<html><div style='text-align:center'><b>No comments</b><br>This file has no comment threads.</div></html>");
        label.setFont(new Font(themeManager.getBaseFontFamily(), Font.PLAIN, themeManager.scale(11)));
        placeholder.add(label, "cell 0 0");
        threadsContainer.add(placeholder);
    }

    private void openThreadDialog(int lineNumber) {
        InlineCommentDialog dialog = new InlineCommentDialog(
            getOwner(),
            settingsManager,
            reviewContext,
            reviewContextManager,
            file,
            lineNumber,
            this::onThreadCommentChanged
        );
        dialog.setVisible(true);
    }

    private void onThreadCommentChanged() {
        loadThreads();
        if (onCommentRefreshed != null) {
            onCommentRefreshed.run();
        }
    }

    private void applyTheme() {
        Theme theme = themeManager.getCurrentTheme();
        getContentPane().setBackground(theme.getBackgroundColor());
    }

    /**
     * A single row representing one comment thread (all comments at the same line number).
     * Clicking the row opens the thread in an {@link InlineCommentDialog}.
     */
    private class ThreadSummaryRow extends ThemedPanel {

        private Runnable onOpenListener;

        /**
         * Creates a summary row for the given line's comment thread.
         *
         * @param lineNumber line number this thread belongs to
         * @param comments   all comments at this line
         */
        ThreadSummaryRow(int lineNumber, List<ReviewComment> comments) {
            configureLayout(lineNumber, comments);
            setupHoverAndClick();
        }

        /**
         * Sets the action invoked when the row is clicked.
         *
         * @param listener open action
         */
        void setOnOpenListener(Runnable listener) {
            this.onOpenListener = listener;
        }

        private void configureLayout(int lineNumber, List<ReviewComment> comments) {
            Theme theme = themeManager.getCurrentTheme();
            setLayout(new MigLayout("insets 8 12 8 12", "[]12[][]push[]", "[]2[]"));
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, theme.getBorderColor()));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

            ThemedLabel lineLabel = new ThemedLabel("Line " + lineNumber);
            lineLabel.setFont(new Font(themeManager.getBaseFontFamily(), Font.BOLD, themeManager.scale(11)));
            lineLabel.setForeground(theme.getAccentColor());
            add(lineLabel, "cell 0 0");

            int count = comments.size();
            ThemedLabel countLabel = new ThemedLabel(count + (count == 1 ? " comment" : " comments"));
            countLabel.setFont(new Font(themeManager.getBaseFontFamily(), Font.PLAIN, themeManager.scale(10)));
            countLabel.setForeground(theme.getSecondaryTextColor());
            add(countLabel, "cell 1 0");

            boolean hasUnresolved = comments.stream().anyMatch(c -> c.needsResolution() && !c.isResolved());
            boolean hasResolved = comments.stream().anyMatch(c -> c.needsResolution() && c.isResolved());

            if (hasUnresolved) {
                add(createResolutionBadge("Unresolved", new Color(255, 152, 0)), "cell 2 0");
            } else if (hasResolved) {
                add(createResolutionBadge("Resolved", new Color(76, 175, 80)), "cell 2 0");
            }

            ThemedLabel openLabel = new ThemedLabel("Open →");
            openLabel.setFont(new Font(themeManager.getBaseFontFamily(), Font.PLAIN, themeManager.scale(10)));
            openLabel.setForeground(theme.getAccentColor());
            add(openLabel, "cell 3 0");

            ReviewComment first = comments.getFirst();
            String preview = buildPreview(first);
            ThemedLabel previewLabel = new ThemedLabel(preview);
            previewLabel.setFont(new Font(themeManager.getBaseFontFamily(), Font.PLAIN, themeManager.scale(10)));
            previewLabel.setForeground(theme.getSecondaryTextColor());
            add(previewLabel, "cell 0 1 4 1, growx");
        }

        private ThemedLabel createResolutionBadge(String text, Color color) {
            ThemedLabel badge = new ThemedLabel(text);
            badge.setFont(new Font(themeManager.getBaseFontFamily(), Font.BOLD, themeManager.scale(9)));
            badge.setForeground(color);
            badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(color, 1),
                BorderFactory.createEmptyBorder(0, 4, 0, 4)
            ));
            return badge;
        }

        private String buildPreview(ReviewComment comment) {
            String author = comment.getAuthor();
            String raw = comment.getText();
            if (raw == null || raw.isBlank()) {
                return author + ": (empty)";
            }
            String stripped = raw.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
            String snippet = stripped.length() > 80 ? stripped.substring(0, 80) + "…" : stripped;
            return author + ": " + snippet;
        }

        private void setupHoverAndClick() {
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (onOpenListener != null) {
                        onOpenListener.run();
                    }
                }

                @Override
                public void mouseEntered(MouseEvent e) {
                    setBackground(themeManager.getCurrentTheme().getButtonBackground());
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    setBackground(themeManager.getCurrentTheme().getBackgroundColor());
                    repaint();
                }
            });
        }
    }
}


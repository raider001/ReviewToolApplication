package com.kalynx.serverlessreviewtool.ui.review;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.swingtheme.themedcomponents.*;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.theme.WindowFrameLoadingIndicator;
import com.kalynx.swingtheme.theme.WindowResizeHandler;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiConsumer;

/**
 * Singleton dialog listing all comment threads across the entire current review.
 * Threads are grouped by file and line number. Clicking a thread opens the
 * {@link InlineCommentDialog} singleton for that thread.
 * The row corresponding to the currently open thread is highlighted.
 * Use {@link #show} to open or reuse the singleton instance.
 */
public class ReviewCommentsDialog extends JDialog {

    private enum CommentFilter {
        ALL("All"),
        UNRESOLVED("Unresolved"),
        RESOLVED("Resolved");

        private final String label;

        CommentFilter(String label) {
            this.label = label;
        }

        /**
         * Returns the display label for the filter.
         *
         * @return display label
         */
        String getLabel() {
            return label;
        }
    }

    private static ReviewCommentsDialog instance;

    private final ThemeManager themeManager = ThemeManager.getInstance();
    private final SettingsManager settingsManager;
    private ReviewContext reviewContext;
    private ReviewContextManager reviewContextManager;
    private Runnable onCommentRefreshed;
    private BiConsumer<String, Integer> onNavigate;
    private CommentFilter currentFilter = CommentFilter.ALL;

    private ThemedPanel threadsContainer;
    private final Map<String, ThreadSummaryRow> threadRows = new LinkedHashMap<>();
    private final Map<CommentFilter, ThemedButton> filterButtons = new LinkedHashMap<>();

    private final Runnable onCommentChangedGlobal = this::onThreadCommentChanged;

    /**
     * Opens (or reuses) the singleton {@link ReviewCommentsDialog} showing all comments for the review.
     *
     * @param owner                parent window
     * @param settingsManager      settings manager for user info
     * @param reviewContext        current review context containing all comments
     * @param reviewContextManager manager for persisting comment changes
     * @param onCommentRefreshed   callback invoked after any comment change (e.g. to refresh diff markers)
     * @param onNavigate           callback invoked when a row is selected to navigate the code review
     *                             panel to the (filePath, lineNumber); when {@code null} the dialog falls
     *                             back to opening {@link InlineCommentDialog} directly
     */
    public static void show(Window owner, SettingsManager settingsManager, ReviewContext reviewContext,
                            ReviewContextManager reviewContextManager, Runnable onCommentRefreshed,
                            BiConsumer<String, Integer> onNavigate) {
        if (instance == null || !instance.isDisplayable()) {
            instance = new ReviewCommentsDialog(owner, settingsManager, reviewContext,
                    reviewContextManager, onCommentRefreshed, onNavigate);
        } else {
            instance.updateContext(reviewContext, reviewContextManager, onCommentRefreshed, onNavigate);
        }
        if (!instance.isVisible()) instance.setVisible(true);
        instance.toFront();
    }

    private ReviewCommentsDialog(Window owner, SettingsManager settingsManager, ReviewContext reviewContext,
                                 ReviewContextManager reviewContextManager, Runnable onCommentRefreshed,
                                 BiConsumer<String, Integer> onNavigate) {
        super(owner, ModalityType.MODELESS);
        this.settingsManager = settingsManager;
        this.reviewContext = reviewContext;
        this.reviewContextManager = reviewContextManager;
        this.onCommentRefreshed = onCommentRefreshed;
        this.onNavigate = onNavigate;

        setUndecorated(true);
        initComponents();
        setupKeyboardShortcuts();

        InlineCommentDialog.setActiveKeyListener(this::refreshHighlights);
        InlineCommentDialog.addGlobalCommentChangedListener(onCommentChangedGlobal);

        loadThreads();
        applyTheme();

        WindowFrameLoadingIndicator.install(this);

        WindowResizeHandler resizeHandler = new WindowResizeHandler(this, 5);
        addMouseListener(resizeHandler);
        addMouseMotionListener(resizeHandler);

        setMinimumSize(new Dimension(520, 350));
        pack();
        setSize(620, 540);
        setLocationRelativeTo(owner);
        setResizable(true);
    }

    @Override
    public void dispose() {
        InlineCommentDialog.removeGlobalCommentChangedListener(onCommentChangedGlobal);
        InlineCommentDialog.setActiveKeyListener(null);
        super.dispose();
    }

    @Override
    protected JRootPane createRootPane() {
        return new ThemedRootPane();
    }

    private void updateContext(ReviewContext reviewContext, ReviewContextManager reviewContextManager,
                               Runnable onCommentRefreshed, BiConsumer<String, Integer> onNavigate) {
        this.reviewContext = reviewContext;
        this.reviewContextManager = reviewContextManager;
        this.onCommentRefreshed = onCommentRefreshed;
        this.onNavigate = onNavigate;
        loadThreads();
    }

    private void initComponents() {
        ThemedPanel contentPanel = new ThemedPanel();
        contentPanel.setLayout(new MigLayout("fill, insets 0, gapy 0", "[grow]", "[][][grow]"));

        Theme theme = themeManager.getCurrentTheme();
        contentPanel.setBorder(BorderFactory.createLineBorder(theme.getBorderColor(), 1));

        CustomTitleBar titleBar = new CustomTitleBar(this, "Review Comments");
        contentPanel.add(titleBar, "growx, wrap");

        contentPanel.add(createFilterPanel(), "growx, wrap");

        threadsContainer = new ThemedPanel(new MigLayout("insets 0, fillx", "[grow]", "[]"));

        ThemedScrollPane scrollPane = new ThemedScrollPane(threadsContainer);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        contentPanel.add(scrollPane, "grow");

        setContentPane(contentPanel);
    }

    private ThemedPanel createFilterPanel() {
        ThemedPanel panel = new ThemedPanel(new MigLayout("insets 4 12 4 12", "[][][]push", "[]"));

        for (CommentFilter filter : CommentFilter.values()) {
            ThemedButton button = new ThemedButton(filter.getLabel());
            button.addActionListener(ignored -> onFilterSelected(filter));
            filterButtons.put(filter, button);
            panel.add(button);
        }

        updateFilterButtonStates();
        return panel;
    }

    private void onFilterSelected(CommentFilter filter) {
        currentFilter = filter;
        updateFilterButtonStates();
        loadThreads();
    }

    private void updateFilterButtonStates() {
        Theme theme = themeManager.getCurrentTheme();
        filterButtons.forEach((filter, button) -> {
            if (filter == currentFilter) {
                button.setBackground(theme.getAccentColor());
                button.setForeground(Color.WHITE);
            } else {
                button.setBackground(theme.getButtonBackground());
                button.setForeground(theme.getForegroundColor());
            }
        });
    }

    private void loadThreads() {
        threadsContainer.removeAll();
        threadRows.clear();

        List<ReviewComment> allComments = reviewContext.getComments();

        Map<String, Map<Integer, List<ReviewComment>>> byFileAndLine = new TreeMap<>();
        for (ReviewComment comment : allComments) {
            byFileAndLine
                .computeIfAbsent(comment.getFilePath(), ignored -> new TreeMap<>())
                .computeIfAbsent(comment.getLineNumber(), ignored -> new ArrayList<>())
                .add(comment);
        }

        boolean hasAny = false;
        for (Map.Entry<String, Map<Integer, List<ReviewComment>>> fileEntry : byFileAndLine.entrySet()) {
            String filePath = fileEntry.getKey();
            for (Map.Entry<Integer, List<ReviewComment>> lineEntry : fileEntry.getValue().entrySet()) {
                int lineNumber = lineEntry.getKey();
                List<ReviewComment> comments = lineEntry.getValue();

                if (!matchesFilter(comments)) {
                    continue;
                }

                hasAny = true;
                String key = InlineCommentDialog.makeThreadKey(filePath, lineNumber);
                ThreadSummaryRow row = new ThreadSummaryRow(filePath, lineNumber, comments);
                row.setOnOpenListener(() -> openThread(filePath, lineNumber));
                threadRows.put(key, row);
                threadsContainer.add(row, "growx, wrap");
            }
        }

        if (!hasAny) {
            showEmptyState();
        }

        threadsContainer.revalidate();
        threadsContainer.repaint();
    }

    private boolean matchesFilter(List<ReviewComment> comments) {
        return switch (currentFilter) {
            case ALL -> true;
            case UNRESOLVED -> comments.stream().anyMatch(c -> c.needsResolution() && !c.isResolved());
            case RESOLVED -> comments.stream().anyMatch(c -> c.needsResolution() && c.isResolved()) &&
                    comments.stream().filter(ReviewComment::needsResolution).allMatch(ReviewComment::isResolved);
        };
    }

    private void openThread(String filePath, int lineNumber) {
        if (onNavigate != null) {
            onNavigate.accept(filePath, lineNumber);
            return;
        }
        InlineCommentDialog.show(getOwner(), settingsManager, reviewContext, reviewContextManager,
                filePath, lineNumber, this::onThreadCommentChanged);
    }

    private void onThreadCommentChanged() {
        loadThreads();
        if (onCommentRefreshed != null) {
            onCommentRefreshed.run();
        }
    }

    private void refreshHighlights() {
        threadRows.forEach((ignored, row) -> row.updateActiveState());
        threadsContainer.repaint();
    }

    private void showEmptyState() {
        String message = currentFilter == CommentFilter.ALL
                ? "This review has no comments yet."
                : "No " + currentFilter.getLabel().toLowerCase() + " comment threads.";
        ThemedPanel placeholder = new ThemedPanel(new MigLayout("fill, insets 20", "[center]", "[center]"));
        ThemedLabel label = new ThemedLabel(
                "<html><div style='text-align:center'><b>No comments</b><br>" + message + "</div></html>");
        label.setFont(new Font(themeManager.getBaseFontFamily(), Font.PLAIN, themeManager.scale(11)));
        placeholder.add(label, "cell 0 0");
        threadsContainer.add(placeholder, "growx, wrap");
    }

    private void setupKeyboardShortcuts() {
        ThemedRootPane rootPane = (ThemedRootPane) getRootPane();
        rootPane.registerKeyboardAction(
            this::dispose,
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            FocusCondition.WHEN_IN_FOCUSED_WINDOW
        );
    }

    private void applyTheme() {
        Theme theme = themeManager.getCurrentTheme();
        getContentPane().setBackground(theme.getBackgroundColor());
    }

    /**
     * A single row representing all comments at one (file, line) location.
     * Shows the file name, line number, comment count, resolution badge, and a preview.
     * Clicking opens the thread in {@link InlineCommentDialog}.
     * Highlighted when this thread is the currently open one.
     */
    private static class ThreadSummaryRow extends ThemedPanel {

        private final ThemeManager themeManager = ThemeManager.getInstance();
        private final String filePath;
        private final int lineNumber;
        private Runnable onOpenListener;

        /**
         * Creates a summary row for the given (file, line) thread.
         *
         * @param filePath   path of the file this thread belongs to
         * @param lineNumber line number this thread is attached to
         * @param comments   all comments in this thread
         */
        ThreadSummaryRow(String filePath, int lineNumber, List<ReviewComment> comments) {
            this.filePath = filePath;
            this.lineNumber = lineNumber;
            configureLayout(comments);
            setupHoverAndClick();
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }

        /**
         * Sets the listener invoked when this row is clicked.
         *
         * @param listener open action
         */
        void setOnOpenListener(Runnable listener) {
            this.onOpenListener = listener;
        }

        /**
         * Updates the visual highlight state based on whether this thread is currently open.
         */
        void updateActiveState() {
            String myKey = InlineCommentDialog.makeThreadKey(filePath, lineNumber);
            boolean isActive = myKey.equals(InlineCommentDialog.getActiveKey());
            Theme theme = themeManager.getCurrentTheme();
            if (isActive) {
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 3, 1, 0, theme.getAccentColor()),
                    BorderFactory.createEmptyBorder(0, 9, 0, 12)
                ));
                setBackground(new Color(
                    theme.getAccentColor().getRed(),
                    theme.getAccentColor().getGreen(),
                    theme.getAccentColor().getBlue(),
                    25));
            } else {
                setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, theme.getBorderColor()),
                    BorderFactory.createEmptyBorder(0, 12, 0, 12)
                ));
                setBackground(theme.getBackgroundColor());
            }
        }

        private void configureLayout(List<ReviewComment> comments) {
            Theme theme = themeManager.getCurrentTheme();
            setLayout(new MigLayout("insets 8 0 8 0", "[]12[][]push[]", "[]2[]"));
            setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, theme.getBorderColor()),
                BorderFactory.createEmptyBorder(0, 12, 0, 12)
            ));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            String fileName = filePath.contains("/")
                    ? filePath.substring(filePath.lastIndexOf('/') + 1)
                    : filePath;

            ThemedLabel fileLabel = new ThemedLabel(fileName);
            fileLabel.setFont(new Font(themeManager.getBaseFontFamily(), Font.BOLD, themeManager.scale(11)));
            fileLabel.setForeground(theme.getAccentColor());
            add(fileLabel, "cell 0 0");

            ThemedLabel lineLabel = new ThemedLabel("Line " + lineNumber);
            lineLabel.setFont(new Font(themeManager.getBaseFontFamily(), Font.PLAIN, themeManager.scale(10)));
            lineLabel.setForeground(theme.getSecondaryTextColor());
            add(lineLabel, "cell 1 0");

            int count = comments.size();
            ThemedLabel countLabel = new ThemedLabel(count + (count == 1 ? " comment" : " comments"));
            countLabel.setFont(new Font(themeManager.getBaseFontFamily(), Font.PLAIN, themeManager.scale(10)));
            countLabel.setForeground(theme.getSecondaryTextColor());
            add(countLabel, "cell 2 0");

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
                    String myKey = InlineCommentDialog.makeThreadKey(filePath, lineNumber);
                    if (!myKey.equals(InlineCommentDialog.getActiveKey())) {
                        setBackground(themeManager.getCurrentTheme().getButtonBackground());
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    updateActiveState();
                    repaint();
                }
            });
        }
    }
}


















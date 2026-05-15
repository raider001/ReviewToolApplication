package com.kalynx.serverlessreviewtool.ui.review.diffviewerpanel;

import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.swingtheme.themedcomponents.AnnotatedScrollPane;
import com.kalynx.swingtheme.themedcomponents.LineNumberedTextPane;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * UnifiedDiffPanel renders a file diff in unified format.
 * The full after-content is shown and added lines are highlighted.
 */
public class UnifiedDiffPanel extends ThemedPanel {

    private final LineNumberedTextPane unifiedPane = new LineNumberedTextPane();
    private final AnnotatedScrollPane unifiedScrollPane = new AnnotatedScrollPane(unifiedPane);

    private final ThemeManager themeManager = ThemeManager.getInstance();
    private final SyntaxHighlightApplier syntaxHighlightApplier;

    private List<ReviewComment> currentComments = List.of();
    private ReviewFile currentFile;
    private Theme lastRenderedTheme;
    private String lastRight;
    private String lastUnified;

    /**
     * @param pluginManager the plugin manager used to resolve syntax highlighters
     */
    public UnifiedDiffPanel(PluginManager pluginManager) {
        this.syntaxHighlightApplier = new SyntaxHighlightApplier(pluginManager);
        configureLayout();
    }

    private void configureLayout() {
        setLayout(new BorderLayout());
        unifiedScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        add(unifiedScrollPane, BorderLayout.CENTER);
    }

    /**
     * Renders the unified diff from the provided content strings.
     *
     * @param right   after-content to display
     * @param unified unified diff used to determine which lines were added
     * @param file    the file being viewed (used for syntax highlighting)
     */
    public void render(String right, String unified, ReviewFile file) {
        this.lastRight = right;
        this.lastUnified = unified;
        this.currentFile = file;
        this.lastRenderedTheme = themeManager.getCurrentTheme();
        applyHighlighting(right, unified);
    }

    /**
     * Updates the comments shown in the scroll-pane annotations.
     *
     * @param comments comments for the current file
     */
    public void setComments(List<ReviewComment> comments) {
        currentComments = new ArrayList<>(comments);
        unifiedPane.setComments(comments);
        if (unifiedScrollPane != null) unifiedScrollPane.setCommentAnnotations(currentComments);
    }

    /**
     * Registers a listener that fires with the line number when a line is double-clicked.
     *
     * @param listener consumer receiving 1-based line numbers
     */
    public void setOnLineDoubleClickListener(Consumer<Integer> listener) {
        unifiedPane.setOnLineDoubleClickListener(listener);
    }

    /**
     * Returns the first visible line number in the unified pane.
     *
     * @return 1-based line number, or -1 when unavailable
     */
    public int getTopVisibleLine() {
        return computeTopVisibleLine(unifiedPane.getTextPane());
    }

    /**
     * Scrolls the unified pane so the given line is at the top of the viewport.
     *
     * @param lineNumber 1-based line number
     */
    public void scrollToLine(int lineNumber) {
        applyScrollToLine(unifiedPane.getTextPane(), lineNumber);
    }

    /**
     * Clears the pane and all annotations.
     */
    public void clear() {
        unifiedPane.setText("");
        currentComments = List.of();
        if (unifiedScrollPane != null) unifiedScrollPane.clearAnnotations();
        lastRight = null;
        lastUnified = null;
        currentFile = null;
    }

    @Override
    public void paint(Graphics g) {
        if (lastRight != null && themeManager.getCurrentTheme() != lastRenderedTheme) {
            lastRenderedTheme = themeManager.getCurrentTheme();
            applyHighlighting(lastRight, lastUnified);
        }
        super.paint(g);
    }

    private void applyHighlighting(String right, String unified) {
        DiffAligner.CleanedDiff cleaned = DiffAligner.buildFullFileDiff(right, unified);
        unifiedPane.setText(cleaned.content());

        Theme theme = themeManager.getCurrentTheme();
        JTextPane textPane = unifiedPane.getTextPane();
        StyledDocument doc = textPane.getStyledDocument();

        Style addedStyle = textPane.addStyle("added", null);
        StyleConstants.setForeground(addedStyle, theme.getForegroundColor());
        StyleConstants.setBackground(addedStyle, theme.getAddedLineColor());

        Style defaultStyle = textPane.addStyle("default", null);
        StyleConstants.setForeground(defaultStyle, theme.getForegroundColor());

        String[] lines = cleaned.content().split("\n", -1);
        int offset = 0;
        int lineNumber = 1;

        for (int i = 0; i < lines.length; i++) {
            int lineLength = lines[i].length() + 1;

            if (cleaned.addedLines().contains(i)) {
                if (offset + lineLength <= doc.getLength()) {
                    doc.setCharacterAttributes(offset, lineLength, addedStyle, true);
                }
                unifiedPane.markLineAdded(lineNumber);
            } else {
                if (offset + lineLength <= doc.getLength()) {
                    doc.setCharacterAttributes(offset, lineLength, defaultStyle, true);
                }
            }

            offset += lineLength;
            lineNumber++;
        }

        syntaxHighlightApplier.apply(textPane, cleaned.content(), currentFile);
        pushAnnotations(cleaned);
    }

    private void pushAnnotations(DiffAligner.CleanedDiff cleaned) {
        if (unifiedScrollPane == null) return;
        int totalLines = cleaned.content().split("\n", -1).length;
        unifiedScrollPane.setChangeAnnotations(totalLines,
                DiffAligner.toOneBased(cleaned.addedLines()),
                DiffAligner.toOneBased(cleaned.removedLines()),
                Set.of());
        unifiedScrollPane.setCommentAnnotations(currentComments);
    }

    private static int computeTopVisibleLine(JTextPane pane) {
        if (pane == null || pane.getDocument() == null || pane.getDocument().getLength() == 0) return -1;
        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, pane);
        if (viewport == null) return -1;
        Point viewPoint = viewport.getViewPosition();
        int offset = pane.viewToModel2D(new Point(0, Math.max(0, viewPoint.y)));
        if (offset < 0) return -1;
        return pane.getDocument().getDefaultRootElement().getElementIndex(offset) + 1;
    }

    private static void applyScrollToLine(JTextPane pane, int lineNumber) {
        if (pane == null || lineNumber <= 0) return;
        Element root = pane.getDocument().getDefaultRootElement();
        int clamped = Math.min(lineNumber, Math.max(root.getElementCount(), 1));
        Element lineEl = root.getElement(clamped - 1);
        if (lineEl == null) return;
        try {
            Rectangle rect = pane.modelToView2D(lineEl.getStartOffset()).getBounds();
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, pane);
            if (viewport != null) {
                viewport.setViewPosition(new Point(0, Math.max(0, rect.y)));
            }
        } catch (BadLocationException ignored) {
        }
    }
}


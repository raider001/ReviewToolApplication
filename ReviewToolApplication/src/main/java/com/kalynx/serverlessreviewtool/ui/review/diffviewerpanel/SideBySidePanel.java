package com.kalynx.serverlessreviewtool.ui.review.diffviewerpanel;

import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.AnnotatedScrollPane;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.LineNumberedTextPane;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedPanel;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedSplitPane;
import com.kalynx.serverlessreviewtool.theme.Theme;
import com.kalynx.serverlessreviewtool.theme.ThemeManager;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.AdjustmentListener;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * SideBySidePanel renders file diffs in a split left/right layout.
 * The left pane shows the before content and the right pane shows the after content,
 * with removed and added lines highlighted respectively.
 */
public class SideBySidePanel extends ThemedPanel {

    private final LineNumberedTextPane leftPane = new LineNumberedTextPane();
    private final LineNumberedTextPane rightPane = new LineNumberedTextPane();
    private final AnnotatedScrollPane leftScrollPane = new AnnotatedScrollPane(leftPane);
    private final AnnotatedScrollPane rightScrollPane = new AnnotatedScrollPane(rightPane);

    private final ThemeManager themeManager = ThemeManager.getInstance();
    private final SyntaxHighlightApplier syntaxHighlightApplier;

    private List<ReviewComment> currentComments = List.of();
    private ReviewFile currentFile;
    private Theme lastRenderedTheme;
    private String lastLeft;
    private String lastRight;
    private String lastUnified;
    private volatile boolean syncingScrollBars;

    /**
     * @param pluginManager the plugin manager used to resolve syntax highlighters
     */
    public SideBySidePanel(PluginManager pluginManager) {
        this.syntaxHighlightApplier = new SyntaxHighlightApplier(pluginManager);
        configureLayout();
    }

    private void configureLayout() {
        setLayout(new BorderLayout());

        leftScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        rightScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        synchronizeScrollPanes(leftScrollPane, rightScrollPane);

        ThemedSplitPane splitPane = new ThemedSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightScrollPane);
        splitPane.setResizeWeight(0.5);
        add(splitPane, BorderLayout.CENTER);
    }

    /**
     * Renders the side-by-side diff from the provided content strings.
     *
     * @param left    before-content to display in the left pane
     * @param right   after-content to display in the right pane
     * @param unified unified diff used for alignment
     * @param file    the file being viewed (used for syntax highlighting)
     */
    public void render(String left, String right, String unified, ReviewFile file) {
        this.lastLeft = left;
        this.lastRight = right;
        this.lastUnified = unified;
        this.currentFile = file;
        this.lastRenderedTheme = themeManager.getCurrentTheme();
        applyHighlighting(left, right, unified);
    }

    /**
     * Updates the comments shown in the scroll-pane annotations.
     *
     * @param comments comments for the current file
     */
    public void setComments(List<ReviewComment> comments) {
        currentComments = new ArrayList<>(comments);
        leftPane.setComments(comments);
        rightPane.setComments(comments);
        if (leftScrollPane != null) leftScrollPane.setCommentAnnotations(currentComments);
        if (rightScrollPane != null) rightScrollPane.setCommentAnnotations(currentComments);
    }

    /**
     * Registers a listener that fires with the line number when a line is double-clicked.
     *
     * @param listener consumer receiving 1-based line numbers
     */
    public void setOnLineDoubleClickListener(Consumer<Integer> listener) {
        leftPane.setOnLineDoubleClickListener(listener);
        rightPane.setOnLineDoubleClickListener(listener);
    }

    /**
     * Returns the first visible line number in the left pane.
     *
     * @return 1-based line number, or -1 when unavailable
     */
    public int getTopVisibleLine() {
        return computeTopVisibleLine(leftPane.getTextPane());
    }

    /**
     * Scrolls the left pane so the given line is at the top of the viewport.
     * The right pane follows via scroll synchronisation.
     *
     * @param lineNumber 1-based line number
     */
    public void scrollToLine(int lineNumber) {
        applyScrollToLine(leftPane.getTextPane(), lineNumber);
    }

    /**
     * Clears both panes and all annotations.
     */
    public void clear() {
        leftPane.setText("");
        rightPane.setText("");
        currentComments = List.of();
        if (leftScrollPane != null) leftScrollPane.clearAnnotations();
        if (rightScrollPane != null) rightScrollPane.clearAnnotations();
        lastLeft = null;
        lastRight = null;
        lastUnified = null;
        currentFile = null;
    }

    @Override
    public void paint(Graphics g) {
        if (lastLeft != null && themeManager.getCurrentTheme() != lastRenderedTheme) {
            lastRenderedTheme = themeManager.getCurrentTheme();
            applyHighlighting(lastLeft, lastRight, lastUnified);
        }
        super.paint(g);
    }

    private void applyHighlighting(String left, String right, String unified) {
        DiffAligner.AlignedContent aligned = DiffAligner.alignContentUsingDiff(left, right, unified);

        leftPane.setText(aligned.leftContent());
        rightPane.setText(aligned.rightContent());

        Theme theme = themeManager.getCurrentTheme();
        JTextPane leftTextPane = leftPane.getTextPane();
        JTextPane rightTextPane = rightPane.getTextPane();
        StyledDocument leftDoc = leftTextPane.getStyledDocument();
        StyledDocument rightDoc = rightTextPane.getStyledDocument();

        Style removedStyle = buildStyle(leftTextPane, "removed", theme.getForegroundColor(), theme.getRemovedLineColor());
        Style addedStyle = buildStyle(rightTextPane, "added", theme.getForegroundColor(), theme.getAddedLineColor());
        Style defaultLeft = buildStyle(leftTextPane, "default", theme.getForegroundColor(), null);
        Style defaultRight = buildStyle(rightTextPane, "default", theme.getForegroundColor(), null);
        Style emptyLeft = buildStyle(leftTextPane, "empty", theme.getSecondaryTextColor(), theme.getBackgroundColor());
        Style emptyRight = buildStyle(rightTextPane, "emptyRight", theme.getSecondaryTextColor(), theme.getBackgroundColor());

        String[] leftLines = aligned.leftContent().split("\n", -1);
        String[] rightLines = aligned.rightContent().split("\n", -1);

        int leftOffset = 0;
        int rightOffset = 0;

        for (int i = 0; i < leftLines.length && i < rightLines.length; i++) {
            int leftLen = leftLines[i].length() + 1;
            int rightLen = rightLines[i].length() + 1;

            boolean leftIsEmpty = aligned.leftEmptyLines().contains(i);
            boolean rightIsEmpty = aligned.rightEmptyLines().contains(i);

            if (leftIsEmpty) {
                applyStyle(leftDoc, leftOffset, leftLen, emptyLeft);
                applyStyle(rightDoc, rightOffset, rightLen, addedStyle);
            } else if (rightIsEmpty) {
                applyStyle(leftDoc, leftOffset, leftLen, removedStyle);
                applyStyle(rightDoc, rightOffset, rightLen, emptyRight);
            } else {
                applyStyle(leftDoc, leftOffset, leftLen, defaultLeft);
                applyStyle(rightDoc, rightOffset, rightLen, defaultRight);
            }

            leftOffset += leftLen;
            rightOffset += rightLen;
        }

        syntaxHighlightApplier.apply(leftTextPane, aligned.leftContent(), currentFile);
        syntaxHighlightApplier.apply(rightTextPane, aligned.rightContent(), currentFile);

        pushAnnotations(aligned);
    }

    private void pushAnnotations(DiffAligner.AlignedContent aligned) {
        int totalLines = aligned.leftContent().split("\n", -1).length;
        Set<Integer> leftRemoved = DiffAligner.toOneBased(aligned.rightEmptyLines());
        Set<Integer> rightAdded = DiffAligner.toOneBased(aligned.leftEmptyLines());

        if (leftScrollPane != null) {
            leftScrollPane.setChangeAnnotations(totalLines, Set.of(), leftRemoved, Set.of());
            leftScrollPane.setCommentAnnotations(currentComments);
        }
        if (rightScrollPane != null) {
            rightScrollPane.setChangeAnnotations(totalLines, rightAdded, Set.of(), Set.of());
            rightScrollPane.setCommentAnnotations(currentComments);
        }
    }

    private static Style buildStyle(JTextPane pane, String name, Color fg, Color bg) {
        Style style = pane.addStyle(name, null);
        StyleConstants.setForeground(style, fg);
        if (bg != null) {
            StyleConstants.setBackground(style, bg);
        }
        return style;
    }

    private static void applyStyle(StyledDocument doc, int offset, int length, Style style) {
        if (offset + length <= doc.getLength()) {
            doc.setCharacterAttributes(offset, length, style, true);
        }
    }

    private void synchronizeScrollPanes(JScrollPane left, JScrollPane right) {
        linkScrollBars(left.getVerticalScrollBar(), right.getVerticalScrollBar());
        linkScrollBars(left.getHorizontalScrollBar(), right.getHorizontalScrollBar());
    }

    private void linkScrollBars(JScrollBar a, JScrollBar b) {
        if (a == null || b == null) return;
        a.addAdjustmentListener(new AdjustmentListenerWrapper(b));
        b.addAdjustmentListener(new AdjustmentListenerWrapper(a));
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

    private class AdjustmentListenerWrapper implements AdjustmentListener {
        private final JScrollBar targetBar;

        public AdjustmentListenerWrapper(JScrollBar targetBar) {
            this.targetBar = targetBar;
        }

        @Override
        public void adjustmentValueChanged(java.awt.event.AdjustmentEvent e) {
            if (syncingScrollBars) return;
            syncingScrollBars = true;
            try {
                targetBar.setValue(e.getValue());
            } finally {
                syncingScrollBars = false;
            }
        }
    }
}


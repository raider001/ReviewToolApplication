package com.kalynx.serverlessreviewtool.swingextensions.themedcomponents;

import com.kalynx.serverlessreviewtool.theme.Theme;
import com.kalynx.serverlessreviewtool.theme.ThemeManager;

import java.awt.*;
import java.util.Map;
import java.util.Set;

/**
 * AnnotatedScrollBar extends ThemedScrollBar with an overview ruler painted directly
 * on the scrollbar track. Change indicators appear on the left edge and comment
 * indicators on the right edge, each proportional to document line position.
 */
public class AnnotatedScrollBar extends ThemedScrollBar {

    private static final int MARK_WIDTH = 3;
    private static final int MARK_HEIGHT = 3;
    private static final int MARK_ALPHA = 210;

    /**
     * Classifies a comment line's indicator state.
     */
    public enum CommentIndicatorType {
        OBSERVATION,
        NEEDS_RESOLUTION,
        RESOLVED
    }

    private int totalLines = 0;
    private Set<Integer> addedLines = Set.of();
    private Set<Integer> removedLines = Set.of();
    private Set<Integer> modifiedLines = Set.of();
    private Map<Integer, CommentIndicatorType> commentLines = Map.of();

    private final ThemeManager themeManager = ThemeManager.getInstance();

    /**
     * Creates a vertical annotated scroll bar.
     */
    public AnnotatedScrollBar() {
        super(ScrollBarOrientation.VERTICAL);
        setPreferredSize(new Dimension(themeManager.scale(16), themeManager.scale(16)));
    }

    /**
     * Updates all change-type annotation data and repaints.
     *
     * @param totalLines   total line count in the document
     * @param addedLines   1-based line numbers of added lines
     * @param removedLines 1-based line numbers of removed lines
     * @param modifiedLines 1-based line numbers of modified lines
     */
    public void setChangeAnnotations(int totalLines, Set<Integer> addedLines,
                                     Set<Integer> removedLines, Set<Integer> modifiedLines) {
        this.totalLines = totalLines;
        this.addedLines = Set.copyOf(addedLines);
        this.removedLines = Set.copyOf(removedLines);
        this.modifiedLines = Set.copyOf(modifiedLines);
        repaint();
    }

    /**
     * Updates comment indicator data and repaints.
     *
     * @param commentLines map of 1-based line number to comment indicator type
     */
    public void setCommentAnnotations(Map<Integer, CommentIndicatorType> commentLines) {
        this.commentLines = Map.copyOf(commentLines);
        repaint();
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        if (totalLines > 0) {
            paintIndicators(g);
        }
    }

    private void paintIndicators(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        Theme theme = themeManager.getCurrentTheme();
        int height = getHeight();
        int rightX = getWidth() - MARK_WIDTH;

        for (int line : addedLines) {
            paintMark(g2, line, height, theme.getAddedLineColor(), 0);
        }
        for (int line : removedLines) {
            paintMark(g2, line, height, theme.getRemovedLineColor(), 0);
        }
        for (int line : modifiedLines) {
            paintMark(g2, line, height, theme.getModifiedLineColor(), 0);
        }

        for (Map.Entry<Integer, CommentIndicatorType> entry : commentLines.entrySet()) {
            paintMark(g2, entry.getKey(), height, resolveCommentColor(entry.getValue()), rightX);
        }

        g2.dispose();
    }

    private void paintMark(Graphics2D g2, int lineNumber, int trackHeight, Color base, int x) {
        int y = lineToY(lineNumber, trackHeight);
        g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), MARK_ALPHA));
        g2.fillRect(x, y, MARK_WIDTH, MARK_HEIGHT);
    }

    private int lineToY(int lineNumber, int trackHeight) {
        if (totalLines <= 0 || lineNumber <= 0) return 0;
        return (int) ((double) (lineNumber - 1) / totalLines * trackHeight);
    }

    private Color resolveCommentColor(CommentIndicatorType type) {
        return switch (type) {
            case OBSERVATION -> new Color(33, 150, 243);
            case NEEDS_RESOLUTION -> new Color(255, 152, 0);
            case RESOLVED -> new Color(76, 175, 80);
        };
    }
}


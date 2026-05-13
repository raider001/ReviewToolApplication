package com.kalynx.serverlessreviewtool.swingextensions.themedcomponents;

  import com.kalynx.serverlessreviewtool.theme.ThemeManager;

import java.awt.*;
import java.util.List;

/**
 * AnnotatedScrollBar extends ThemedScrollBar with an overview ruler painted directly
 * on the scrollbar track. Change blocks store raw 1-based line numbers; fractions are
 * computed at paint time from {@code totalLines} so alignment is never affected by
 * layout-timing issues. Comment marks carry pre-computed fractions (they are always
 * set after the pane is fully laid out).
 */
public class AnnotatedScrollBar extends ThemedScrollBar {

    private static final int CHANGE_MARK_WIDTH = 3;
    private static final int COMMENT_MARK_WIDTH = 3;
    private static final int MIN_MARK_HEIGHT = 2;
    private static final int MARK_ALPHA = 210;

    /**
     * Classifies a comment line's indicator state.
     */
    public enum CommentIndicatorType {
        OBSERVATION,
        NEEDS_RESOLUTION,
        RESOLVED
    }

    /**
     * A contiguous block of changed lines described by 1-based line numbers.
     *
     * @param lineStart 1-based first line of the block (inclusive)
     * @param lineEnd   1-based line after the last line of the block (exclusive)
     * @param color     fill color for the block
     */
    public record ChangeBlock(int lineStart, int lineEnd, Color color) {}

    /**
     * A single comment indicator at a fractional track position.
     *
     * @param yFrac fractional position (0.0 = top, 1.0 = bottom)
     * @param type  comment state
     */
    public record CommentMark(double yFrac, CommentIndicatorType type) {}

    private int cachedTotalLines = 0;
    private List<ChangeBlock> changeBlocks = List.of();
    private List<CommentMark> commentMarks = List.of();

    /**
     * Creates a vertical annotated scroll bar.
     */
    public AnnotatedScrollBar() {
        super(ScrollBarOrientation.VERTICAL);
        setPreferredSize(new Dimension(ThemeManager.getInstance().scale(16),
                ThemeManager.getInstance().scale(16)));
    }

    /**
     * Replaces change block data and schedules a repaint.
     *
     * @param totalLines total number of lines in the displayed document
     * @param blocks     change blocks with 1-based line numbers
     */
    public void setChangeBlocks(int totalLines, List<ChangeBlock> blocks) {
        this.cachedTotalLines = totalLines;
        this.changeBlocks = List.copyOf(blocks);
        repaint();
    }

    /**
     * Replaces comment mark data and schedules a repaint.
     *
     * @param marks precomputed comment marks with fractional positions
     */
    public void setCommentMarks(List<CommentMark> marks) {
        this.commentMarks = List.copyOf(marks);
        repaint();
    }

    @Override
    public void paint(Graphics g) {
        super.paint(g);
        if (!changeBlocks.isEmpty() || !commentMarks.isEmpty()) {
            paintIndicators(g);
        }
    }

    private void paintIndicators(Graphics g) {
        Rectangle track = resolveTrackBounds();
        if (track.height <= 0) return;

        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        if (cachedTotalLines > 0) {
            for (ChangeBlock block : changeBlocks) {
                double startFrac = (block.lineStart() - 1.0) / cachedTotalLines;
                double endFrac = Math.min(1.0, block.lineEnd() / (double) cachedTotalLines);
                int y = track.y + (int) (startFrac * track.height);
                int h = Math.max(MIN_MARK_HEIGHT, (int) ((endFrac - startFrac) * track.height));
                g2.setColor(withAlpha(block.color()));
                g2.fillRect(track.x, y, CHANGE_MARK_WIDTH, h);
            }
        }

        int rightX = track.x + track.width - COMMENT_MARK_WIDTH;
        for (CommentMark mark : commentMarks) {
            int y = track.y + (int) (mark.yFrac() * track.height);
            g2.setColor(withAlpha(resolveCommentColor(mark.type())));
            g2.fillRect(rightX, y, COMMENT_MARK_WIDTH, MIN_MARK_HEIGHT + 2);
        }

        g2.dispose();
    }

    private Rectangle resolveTrackBounds() {
        Insets i = getInsets();
        if (i == null) i = new Insets(0, 0, 0, 0);
        return new Rectangle(i.left, i.top,
                getWidth() - i.left - i.right,
                getHeight() - i.top - i.bottom);
    }

    private Color withAlpha(Color color) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), MARK_ALPHA);
    }

    private Color resolveCommentColor(CommentIndicatorType type) {
        return switch (type) {
            case OBSERVATION -> new Color(33, 150, 243);
            case NEEDS_RESOLUTION -> new Color(255, 152, 0);
            case RESOLVED -> new Color(76, 175, 80);
        };
    }
}

package com.kalynx.serverlessreviewtool.swingextensions.themedcomponents;

import java.io.Serial;

import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.theme.Theme;
import com.kalynx.serverlessreviewtool.theme.ThemeManager;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * LineNumberedTextPane - A text pane with line numbers, line change indicators, and comment support
 * Displays added/removed/modified line indicators with proper theming support
 * Integrates with ReviewComment system for inline code review comments
 * This component should be wrapped in a scroll pane by the parent container
 */
public class LineNumberedTextPane extends ThemedPanel {
    @Serial
    private static final long serialVersionUID = 1L;

    private transient final ThemeManager themeManager;
    private final ThemedTextPane textPane;
    private final LineNumberPanel lineNumberPanel;

    private static final String FONT_NAME = "Consolas";
    private static final int FONT_SIZE_BASE = 14;
    private final int scaledFontSize;

    private transient final Set<Integer> addedLines = new HashSet<>();
    private transient final Set<Integer> removedLines = new HashSet<>();
    private transient final Set<Integer> modifiedLines = new HashSet<>();
    private transient final Map<Integer, List<ReviewComment>> lineComments = new HashMap<>();

    private transient Consumer<Integer> onLineDoubleClick;

    public LineNumberedTextPane() {
        this.themeManager = ThemeManager.getInstance();
        this.textPane = new ThemedTextPane();
        this.lineNumberPanel = new LineNumberPanel();
        this.scaledFontSize = themeManager.scale(FONT_SIZE_BASE);

        ((DefaultCaret) textPane.getCaret()).setUpdatePolicy(DefaultCaret.NEVER_UPDATE);

        Font synchronizedFont = new Font(FONT_NAME, Font.PLAIN, scaledFontSize);
        textPane.setFont(synchronizedFont);
        textPane.setMargin(new Insets(0, 0, 0, 0));

        setLayout(new BorderLayout());
        add(textPane, BorderLayout.CENTER);

        textPane.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                lineNumberPanel.repaint();
                lineNumberPanel.revalidate();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                lineNumberPanel.repaint();
                lineNumberPanel.revalidate();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                lineNumberPanel.repaint();
                lineNumberPanel.revalidate();
            }
        });

        textPane.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    handleDoubleClick(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    handleRightClick(e);
                }
            }
        });
    }

    private void handleDoubleClick(MouseEvent e) {
        int lineNumber = getLineNumberFromPoint(e.getPoint());
        if (lineNumber > 0 && onLineDoubleClick != null) {
            onLineDoubleClick.accept(lineNumber);
        }
    }

    private void handleRightClick(MouseEvent e) {
        int lineNumber = getLineNumberFromPoint(e.getPoint());
        if (lineNumber > 0) {
            showCommentTooltip(e.getPoint(), lineNumber);
        }
    }

    private int getLineNumberFromPoint(Point point) {
        int offset = textPane.viewToModel2D(point);
        String text = textPane.getText();

        int lineNumber = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lineNumber++;
            }
        }
        return lineNumber;
    }

    private void showCommentTooltip(Point point, int lineNumber) {
        List<ReviewComment> comments = lineComments.get(lineNumber);
        if (comments == null || comments.isEmpty()) {
            return;
        }

        StringBuilder tooltip = new StringBuilder("<html>");
        for (ReviewComment comment : comments) {
            tooltip.append("<b>").append(comment.getAuthor()).append(":</b> ")
                   .append(comment.getText().replace("\n", "<br>"))
                   .append("<br><i>").append(comment.getTimestamp()).append("</i>");

            if (comment.needsResolution()) {
                tooltip.append(" <font color='red'>[Needs Resolution]</font>");
            }
            if (comment.isResolved()) {
                tooltip.append(" <font color='green'>[Resolved]</font>");
            }
            tooltip.append("<br><br>");
        }
        tooltip.append("</html>");

        JToolTip tip = textPane.createToolTip();
        tip.setTipText(tooltip.toString());

        Point screenPoint = new Point(point);
        SwingUtilities.convertPointToScreen(screenPoint, textPane);

        Popup popup = PopupFactory.getSharedInstance().getPopup(textPane, tip,
            screenPoint.x, screenPoint.y + 20);
        popup.show();

        Timer timer = new Timer(5000, _ -> popup.hide());
        timer.setRepeats(false);
        timer.start();
    }

    public void setOnLineDoubleClickListener(Consumer<Integer> listener) {
        this.onLineDoubleClick = listener;
    }

    private void applyTheme() {
        Theme theme = themeManager.getCurrentTheme();
        textPane.setBackground(theme.getBackgroundColor());
        textPane.setForeground(theme.getForegroundColor());
        textPane.setFont(new Font(FONT_NAME, Font.PLAIN, scaledFontSize));
        textPane.setCaretColor(theme.getAccentColor());
        setBackground(theme.getBackgroundColor());
    }

    @Override
    protected void paintComponent(Graphics g) {
        applyTheme();
        super.paintComponent(g);

    }

    /**
     * Set text content
     */
    public void setText(String text) {
        textPane.setText(text);
        textPane.setCaretPosition(0);
        clearLineIndicators();
    }

    /**
     * Get text content
     */
    public String getText() {
        return textPane.getText();
    }

    /**
     * Mark a line as added
     */
    public void markLineAdded(int lineNumber) {
        addedLines.add(lineNumber);
        removedLines.remove(lineNumber);
        modifiedLines.remove(lineNumber);
        lineNumberPanel.repaint();
    }

    public void clearLineIndicators() {
        addedLines.clear();
        removedLines.clear();
        modifiedLines.clear();
        lineNumberPanel.repaint();
    }

    public JTextPane getTextPane() {
        return textPane;
    }

    @SuppressWarnings("unused")
    public LineNumberPanel getLineNumberPanel() {
        return lineNumberPanel;
    }

    public void setComments(List<ReviewComment> comments) {
        lineComments.clear();
        for (ReviewComment comment : comments) {
            lineComments.computeIfAbsent(comment.getLineNumber(), _ -> new ArrayList<>()).add(comment);
        }
        lineNumberPanel.repaint();
    }


    public List<ReviewComment> getCommentsForLine(int lineNumber) {
        return lineComments.getOrDefault(lineNumber, new ArrayList<>());
    }

    public boolean hasComments(int lineNumber) {
        List<ReviewComment> comments = lineComments.get(lineNumber);
        return comments != null && !comments.isEmpty();
    }

    public boolean hasUnresolvedComments(int lineNumber) {
        List<ReviewComment> comments = lineComments.get(lineNumber);
        if (comments == null) return false;

        return comments.stream().anyMatch(c -> c.needsResolution() && !c.isResolved());
    }

    /**
     * Inner class for rendering line numbers with background color indicators
     * Visible for use as a row header in external scroll panes
     */
    public class LineNumberPanel extends JPanel {
        @Serial
        private static final long serialVersionUID = 1L;
        private static final int RIGHT_MARGIN = 8;
        private static final int ICON_SPACE = 20;

        LineNumberPanel() {
            setOpaque(true);
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(new Font(FONT_NAME, Font.PLAIN, scaledFontSize));
            String text = textPane.getText();
            int lineCount = text.isEmpty() ? 1 : text.split("\n", -1).length;
            int maxLineWidth = fm.stringWidth(String.valueOf(lineCount));
            int width = ICON_SPACE + maxLineWidth + RIGHT_MARGIN + 5;
            int height = textPane.getPreferredSize().height;
            return new Dimension(width, height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            // Improve text rendering consistency with text pane
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2d.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            Theme theme = themeManager.getCurrentTheme();

            // Background
            g2d.setColor(theme.getBackgroundColor());
            g2d.fillRect(0, 0, getWidth(), getHeight());

            // Border on right side
            g2d.setColor(theme.getBorderColor());
            g2d.drawLine(getWidth() - 1, 0, getWidth() - 1, getHeight());

            // Use the SAME synchronized font as the text pane
            Font font = new Font(FONT_NAME, Font.PLAIN, scaledFontSize);
            FontMetrics fm = g2d.getFontMetrics(font);
            int lineHeight = fm.getHeight();
            int ascent = fm.getAscent();

            // Get text from pane
            String text = textPane.getText();
            if (text.isEmpty()) {
                updatePreferredWidth();
                return;
            }

            // Use clip bounds so only visible lines are drawn (efficient for large files)
            Rectangle clip = g2d.getClipBounds();
            int firstVisibleY = clip != null ? clip.y : 0;
            int lastVisibleY = clip != null ? clip.y + clip.height : getHeight();

            // Start drawing from top with padding
            int lineNumber = 1;
            int y = ascent;

            // Draw line numbers and background colors
            String[] lines = text.split("\n", -1);
            for (String ignored : lines) {
                if (y > lastVisibleY) break;
                if (y + lineHeight <= firstVisibleY) {
                    y += lineHeight;
                    lineNumber++;
                    continue;
                }

                // Draw background color for line indicator
                drawLineBackground(g2d, lineNumber, y - ascent, lineHeight);

                // Draw line number with the same font as text pane
                g2d.setColor(theme.getForegroundColor());
                g2d.setFont(font);
                String lineStr = String.valueOf(lineNumber);
                int numberX = ICON_SPACE + 2;
                g2d.drawString(lineStr, numberX, y + 2);

                y += lineHeight;
                lineNumber++;
            }

            // Notify layout of any width change
            updatePreferredWidth();
        }

        private void updatePreferredWidth() {
            revalidate();
        }

        private void drawLineBackground(Graphics2D g2d, int lineNumber, int y, int lineHeight) {
            Theme theme = themeManager.getCurrentTheme();

            if (y < 0 || y > getHeight()) {
                return;
            }

            if (addedLines.contains(lineNumber)) {
                g2d.setColor(theme.getAddedLineColor());
                g2d.fillRect(0, y, getWidth() - 1, lineHeight);
            } else if (removedLines.contains(lineNumber)) {
                g2d.setColor(theme.getRemovedLineColor());
                g2d.fillRect(0, y, getWidth() - 1, lineHeight);
            } else if (modifiedLines.contains(lineNumber)) {
                g2d.setColor(theme.getModifiedLineColor());
                g2d.fillRect(0, y, getWidth() - 1, lineHeight);
            }

            if (hasComments(lineNumber)) {
                List<ReviewComment> comments = getCommentsForLine(lineNumber);
                int commentCount = comments.size();

                // Three-state indicator color:
                // 1. Blue: Observation (no comments marked as needs resolution)
                // 2. Orange: Unresolved (has comments marked as needs resolution but not resolved)
                // 3. Green: Resolved (all comments marked as needs resolution are resolved)
                Color indicatorColor;
                boolean hasAnyNeedingResolution = comments.stream().anyMatch(ReviewComment::needsResolution);

                if (!hasAnyNeedingResolution) {
                    // State 1: All comments are observations
                    indicatorColor = new Color(33, 150, 243); // Blue
                } else if (hasUnresolvedComments(lineNumber)) {
                    // State 2: Has unresolved comments
                    indicatorColor = new Color(255, 152, 0); // Orange
                } else {
                    // State 3: All comments needing resolution are resolved
                    indicatorColor = new Color(76, 175, 80); // Green
                }

                int iconSize = themeManager.scale(12);
                int iconX = (ICON_SPACE - iconSize) / 2;
                int iconY = y + (lineHeight - iconSize) / 2;

                g2d.setColor(indicatorColor);
                g2d.fillOval(iconX, iconY, iconSize, iconSize);

                if (commentCount > 1) {
                    g2d.setColor(Color.WHITE);
                    g2d.setFont(new Font("Segoe UI", Font.BOLD, themeManager.scale(8)));
                    String countStr = String.valueOf(commentCount);
                    FontMetrics fm = g2d.getFontMetrics();
                    int textWidth = fm.stringWidth(countStr);
                    int textHeight = fm.getAscent();
                    g2d.drawString(countStr,
                        iconX + (iconSize - textWidth) / 2,
                        iconY + (iconSize + textHeight) / 2 - 1);
                }
            }
        }
    }
}



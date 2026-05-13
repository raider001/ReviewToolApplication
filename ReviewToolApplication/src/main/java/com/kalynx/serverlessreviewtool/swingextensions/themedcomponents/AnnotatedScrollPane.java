package com.kalynx.serverlessreviewtool.swingextensions.themedcomponents;

import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.theme.Theme;
import com.kalynx.serverlessreviewtool.theme.ThemeManager;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import javax.swing.text.Element;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Rectangle2D;
import java.util.*;
import java.util.List;

/**
 * AnnotatedScrollPane extends ThemedScrollPane with an AnnotatedScrollBar on the
 * vertical axis.
 *
 * <p>Change annotations are expressed as raw 1-based line numbers; fractions are
 * computed at paint time inside {@link AnnotatedScrollBar} so they are never affected
 * by layout-timing issues. Comment annotations continue to use document pixel positions
 * (via {@link JTextPane#modelToView2D}) because they are always pushed after the pane
 * is fully laid out.</p>
 *
 * <p>Change and comment annotation data are cached so each update method does not
 * discard the other's state.</p>
 */
public class AnnotatedScrollPane extends ThemedScrollPane {

    private final AnnotatedScrollBar annotatedScrollBar = new AnnotatedScrollBar();
    private final ThemeManager themeManager = ThemeManager.getInstance();

    private LineNumberedTextPane referencePaneForAnnotations;

    private int cachedTotalLines = 0;
    private Set<Integer> cachedAdded = Set.of();
    private Set<Integer> cachedRemoved = Set.of();
    private Set<Integer> cachedModified = Set.of();
    private List<ReviewComment> cachedComments = List.of();

    /**
     * Creates an annotated scroll pane wrapping the supplied view component.
     *
     * @param view the component to scroll
     */
    public AnnotatedScrollPane(Component view) {
        super(view);
        setVerticalScrollBar(annotatedScrollBar);
        setVerticalScrollBarPolicy(VERTICAL_SCROLLBAR_ALWAYS);
    }

    /**
     * Registers the text pane whose document is queried when computing accurate comment
     * positions via {@link JTextPane#modelToView2D}. Also triggers a change-annotation
     * refresh whenever the pane is resized (e.g. after content changes).
     *
     * @param pane the {@link LineNumberedTextPane} displayed inside this scroll pane
     */
    public void setReferencePaneForAnnotations(LineNumberedTextPane pane) {
        this.referencePaneForAnnotations = pane;
        pane.getTextPane().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                if (!cachedAdded.isEmpty() || !cachedRemoved.isEmpty() || !cachedModified.isEmpty()) {
                    pushChangeAnnotations();
                }
            }
        });
    }

    /**
     * Updates the change-type indicators. Comment indicators are preserved.
     *
     * @param totalLines    total line count of the aligned document
     * @param addedLines    1-based line numbers of added lines
     * @param removedLines  1-based line numbers of removed lines
     * @param modifiedLines 1-based line numbers of modified lines
     */
    public void setChangeAnnotations(int totalLines, Set<Integer> addedLines,
                                     Set<Integer> removedLines, Set<Integer> modifiedLines) {
        cachedTotalLines = totalLines;
        cachedAdded = Set.copyOf(addedLines);
        cachedRemoved = Set.copyOf(removedLines);
        cachedModified = Set.copyOf(modifiedLines);
        pushChangeAnnotations();
    }

    /**
     * Updates the comment indicators. Change indicators are preserved.
     *
     * @param comments flat list of all comments for the current file
     */
    public void setCommentAnnotations(List<ReviewComment> comments) {
        cachedComments = new ArrayList<>(comments);
        pushCommentAnnotations();
    }

    /**
     * Clears all change and comment annotations.
     */
    public void clearAnnotations() {
        cachedTotalLines = 0;
        cachedAdded = Set.of();
        cachedRemoved = Set.of();
        cachedModified = Set.of();
        cachedComments = List.of();
        annotatedScrollBar.setChangeBlocks(0, List.of());
        annotatedScrollBar.setCommentMarks(List.of());
    }

    private void pushChangeAnnotations() {
        Theme theme = themeManager.getCurrentTheme();
        List<AnnotatedScrollBar.ChangeBlock> blocks = new ArrayList<>();
        addChangeBlocks(blocks, cachedAdded, theme.getAddedLineColor());
        addChangeBlocks(blocks, cachedRemoved, theme.getRemovedLineColor());
        addChangeBlocks(blocks, cachedModified, theme.getModifiedLineColor());
        annotatedScrollBar.setChangeBlocks(cachedTotalLines, blocks);
    }

    private void addChangeBlocks(List<AnnotatedScrollBar.ChangeBlock> blocks,
                                 Set<Integer> lineNumbers, Color color) {
        if (lineNumbers.isEmpty()) return;
        List<Integer> sorted = lineNumbers.stream().sorted().toList();
        int runStart = sorted.getFirst();
        int runEnd = sorted.getFirst();
        for (int i = 1; i < sorted.size(); i++) {
            int line = sorted.get(i);
            if (line == runEnd + 1) {
                runEnd = line;
            } else {
                blocks.add(new AnnotatedScrollBar.ChangeBlock(runStart, runEnd + 1, color));
                runStart = line;
                runEnd = line;
            }
        }
        blocks.add(new AnnotatedScrollBar.ChangeBlock(runStart, runEnd + 1, color));
    }

    private void pushCommentAnnotations() {
        Map<Integer, List<ReviewComment>> byLine = new HashMap<>();
        for (ReviewComment comment : cachedComments) {
            byLine.computeIfAbsent(comment.getLineNumber(), _ -> new ArrayList<>()).add(comment);
        }
        List<AnnotatedScrollBar.CommentMark> marks = new ArrayList<>();
        for (Map.Entry<Integer, List<ReviewComment>> entry : byLine.entrySet()) {
            marks.add(new AnnotatedScrollBar.CommentMark(
                    lineToFraction(entry.getKey()),
                    classifyLine(entry.getValue())));
        }
        annotatedScrollBar.setCommentMarks(marks);
    }

    private AnnotatedScrollBar.CommentIndicatorType classifyLine(List<ReviewComment> comments) {
        boolean anyNeedsResolution = comments.stream().anyMatch(ReviewComment::needsResolution);
        if (!anyNeedsResolution) {
            return AnnotatedScrollBar.CommentIndicatorType.OBSERVATION;
        }
        boolean allResolved = comments.stream()
                .filter(ReviewComment::needsResolution)
                .allMatch(ReviewComment::isResolved);
        return allResolved
                ? AnnotatedScrollBar.CommentIndicatorType.RESOLVED
                : AnnotatedScrollBar.CommentIndicatorType.NEEDS_RESOLUTION;
    }

    /**
     * Converts a 1-based line number to a fractional track position (0.0–1.0) by
     * querying the reference text pane's document for the actual pixel Y of that line.
     * Used only for comment marks, which are always pushed after the pane is laid out.
     * Falls back to a proportional estimate when the pane is unavailable or not yet painted.
     */
    private double lineToFraction(int lineNumber) {
        if (referencePaneForAnnotations != null) {
            JTextPane pane = referencePaneForAnnotations.getTextPane();
            int paneHeight = pane.getPreferredSize().height;
            if (paneHeight > 0) {
                Document doc = pane.getDocument();
                Element root = doc.getDefaultRootElement();
                int lineIdx = lineNumber - 1;
                if (lineIdx >= 0 && lineIdx < root.getElementCount()) {
                    try {
                        Element lineEl = root.getElement(lineIdx);
                        Rectangle2D r = pane.modelToView2D(lineEl.getStartOffset());
                        if (r != null) {
                            return Math.max(0.0, Math.min(1.0, r.getY() / paneHeight));
                        }
                    } catch (BadLocationException ignored) {
                    }
                } else if (lineIdx >= root.getElementCount()) {
                    return 1.0;
                }
            }
        }
        return cachedTotalLines > 0 ? (double) (lineNumber - 1) / cachedTotalLines : 0.0;
    }
}

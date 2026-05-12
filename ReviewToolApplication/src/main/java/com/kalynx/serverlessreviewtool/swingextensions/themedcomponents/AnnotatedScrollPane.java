package com.kalynx.serverlessreviewtool.swingextensions.themedcomponents;

import com.kalynx.serverlessreviewtool.models.ReviewComment;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * AnnotatedScrollPane extends ThemedScrollPane with an AnnotatedScrollBar on the
 * vertical axis. Change and comment annotation data can be updated independently
 * and are cached so whichever updates last does not lose the other's state.
 */
public class AnnotatedScrollPane extends ThemedScrollPane {

    private final AnnotatedScrollBar annotatedScrollBar = new AnnotatedScrollBar();

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
     * Updates the change-type indicators. Comment indicators are preserved.
     *
     * @param totalLines    total line count of the document
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
        annotatedScrollBar.setCommentAnnotations(buildCommentMap(cachedComments));
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
        pushChangeAnnotations();
        annotatedScrollBar.setCommentAnnotations(Map.of());
    }

    private void pushChangeAnnotations() {
        annotatedScrollBar.setChangeAnnotations(cachedTotalLines, cachedAdded, cachedRemoved, cachedModified);
    }

    private Map<Integer, AnnotatedScrollBar.CommentIndicatorType> buildCommentMap(List<ReviewComment> comments) {
        Map<Integer, List<ReviewComment>> byLine = new HashMap<>();
        for (ReviewComment comment : comments) {
            byLine.computeIfAbsent(comment.getLineNumber(), _ -> new ArrayList<>()).add(comment);
        }
        Map<Integer, AnnotatedScrollBar.CommentIndicatorType> result = new HashMap<>();
        for (Map.Entry<Integer, List<ReviewComment>> entry : byLine.entrySet()) {
            result.put(entry.getKey(), classifyLine(entry.getValue()));
        }
        return result;
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
}


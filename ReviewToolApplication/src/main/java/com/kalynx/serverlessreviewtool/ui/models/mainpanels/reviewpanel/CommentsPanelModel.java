package com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.swingtheme.ComponentModel;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CommentsPanelModel {

    public enum CommentFilter {
        ALL,
        UNRESOLVED,
        RESOLVED,
        MY_COMMENTS
    }

    public final ComponentModel<List<ReviewComment>> allComments = new ComponentModel<>();
    public final ComponentModel<List<ReviewComment>> filteredComments = new ComponentModel<>();
    public final ComponentModel<CommentFilter> currentFilter = new ComponentModel<>();
    public final ComponentModel<String> selectedCommentId = new ComponentModel<>();

    public final ComponentModel<Boolean> isComposingComment = new ComponentModel<>();
    public final ComponentModel<String> newCommentText = new ComponentModel<>();
    public final ComponentModel<String> newCommentFilePath = new ComponentModel<>();
    public final ComponentModel<Integer> newCommentLineNumber = new ComponentModel<>();
    public final ComponentModel<String> newCommentParentId = new ComponentModel<>();

    public final ComponentModel<String> currentUserName = new ComponentModel<>();

    public CommentsPanelModel() {
        initializeDefaults();
        setupFilterListener();
    }

    private void initializeDefaults() {
        allComments.setValue(new ArrayList<>());
        filteredComments.setValue(new ArrayList<>());
        currentFilter.setValue(CommentFilter.ALL);
        selectedCommentId.setValue(null);
        isComposingComment.setValue(false);
        newCommentText.setValue("");
        newCommentFilePath.setValue(null);
        newCommentLineNumber.setValue(-1);
        newCommentParentId.setValue(null);
        currentUserName.setValue("");
    }

    private void setupFilterListener() {
        currentFilter.addChangeListener(filter -> applyFilter());
        allComments.addChangeListener(comments -> applyFilter());
    }

    public void clear() {
        initializeDefaults();
    }

    public void setComments(List<ReviewComment> comments) {
        allComments.setValue(comments != null ? new ArrayList<>(comments) : new ArrayList<>());
    }

    public void addComment(ReviewComment comment) {
        List<ReviewComment> comments = new ArrayList<>(allComments.getValue());
        comments.add(comment);
        allComments.setValue(comments);
    }

    public void updateComment(ReviewComment updatedComment) {
        List<ReviewComment> comments = new ArrayList<>(allComments.getValue());
        for (int i = 0; i < comments.size(); i++) {
            if (comments.get(i).getId().equals(updatedComment.getId())) {
                comments.set(i, updatedComment);
                break;
            }
        }
        allComments.setValue(comments);
    }

    public void removeComment(String commentId) {
        List<ReviewComment> comments = allComments.getValue().stream()
            .filter(c -> !c.getId().equals(commentId))
            .collect(Collectors.toList());
        allComments.setValue(comments);
    }

    public void setFilter(CommentFilter filter) {
        currentFilter.setValue(filter);
    }

    public void selectComment(String commentId) {
        selectedCommentId.setValue(commentId);
    }

    public void startComposingComment(String filePath, int lineNumber, String parentId) {
        isComposingComment.setValue(true);
        newCommentFilePath.setValue(filePath);
        newCommentLineNumber.setValue(lineNumber);
        newCommentParentId.setValue(parentId);
        newCommentText.setValue("");
    }

    public void cancelComposingComment() {
        isComposingComment.setValue(false);
        newCommentText.setValue("");
        newCommentFilePath.setValue(null);
        newCommentLineNumber.setValue(-1);
        newCommentParentId.setValue(null);
    }

    public boolean isReplyToComment() {
        String parentId = newCommentParentId.getValue();
        return parentId != null && !parentId.isEmpty();
    }

    private void applyFilter() {
        List<ReviewComment> comments = allComments.getValue();
        if (comments == null) {
            filteredComments.setValue(new ArrayList<>());
            return;
        }

        CommentFilter filter = currentFilter.getValue();
        String userName = currentUserName.getValue();

        List<ReviewComment> filtered = switch (filter) {
            case ALL -> new ArrayList<>(comments);
            case UNRESOLVED -> comments.stream()
                .filter(c -> !c.isResolved())
                .collect(Collectors.toList());
            case RESOLVED -> comments.stream()
                .filter(ReviewComment::isResolved)
                .collect(Collectors.toList());
            case MY_COMMENTS -> comments.stream()
                .filter(c -> userName != null && userName.equals(c.getAuthor()))
                .collect(Collectors.toList());
        };

        filteredComments.setValue(filtered);
    }

    public void setCurrentUser(String userName) {
        currentUserName.setValue(userName != null ? userName : "");
        applyFilter();
    }

    public List<ReviewComment> getCommentsForFile(String filePath) {
        if (filePath == null) {
            return new ArrayList<>();
        }
        return allComments.getValue().stream()
            .filter(c -> filePath.equals(c.getFilePath()))
            .collect(Collectors.toList());
    }

    public List<ReviewComment> getCommentsForLine(String filePath, int lineNumber) {
        if (filePath == null) {
            return new ArrayList<>();
        }
        return allComments.getValue().stream()
            .filter(c -> filePath.equals(c.getFilePath()) && c.getLineNumber() == lineNumber)
            .collect(Collectors.toList());
    }

    public int getUnresolvedCount() {
        return (int) allComments.getValue().stream()
            .filter(c -> !c.isResolved())
            .count();
    }
}


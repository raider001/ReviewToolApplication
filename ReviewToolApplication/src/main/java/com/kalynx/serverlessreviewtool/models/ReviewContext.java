package com.kalynx.serverlessreviewtool.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class ReviewContext {

    public final String reviewId;
    public String title;
    public String summary;
    public String author;
    public ReviewStatus status;
    public final List<ReviewerInfo> reviewers;
    public final List<Repository> repositories;
    public final List<ReviewComment> comments;
    public final String branch;
    public final String baseBranch;
    public final boolean hasClosedHistory;

    public ReviewContext(String reviewId, String title, String summary, String author, ReviewStatus status,
                         List<ReviewerInfo> reviewers, List<Repository> repositories, List<ReviewComment> comments,
                         String branch, String baseBranch, boolean hasClosedHistory) {
        this.reviewId = reviewId;
        this.title = title;
        this.summary = summary;
        this.author = author;
        this.status = status;
        this.reviewers = new ArrayList<>(reviewers);
        this.repositories = new ArrayList<>(repositories);
        this.comments = new ArrayList<>(comments);
        this.branch = branch;
        this.baseBranch = baseBranch;
        this.hasClosedHistory = hasClosedHistory;
    }

    /**
     * Returns the review branch name (e.g., "feature/my-feature").
     *
     * @return the branch being reviewed, or null if not yet loaded
     */
    public String getBranch() {
        return branch;
    }

    /**
     * Returns the base branch name (e.g., "origin/main").
     *
     * @return the base branch to compare against, or null if not yet loaded
     */
    public String getBaseBranch() {
        return baseBranch;
    }

    public boolean hasClosedHistory() {
        return hasClosedHistory;
    }

    public String getReviewId() {
        return reviewId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public List<ReviewerInfo> getReviewers() {
        return new ArrayList<>(reviewers);
    }

    public List<Repository> getRepositories() {
        return new ArrayList<>(repositories);
    }

    public List<ReviewComment> getComments() {
        return new ArrayList<>(comments);
    }

    public void addComment(ReviewComment comment) {
        comments.add(comment);
    }

    public void updateComment(ReviewComment updated) {
        for (int i = 0; i < comments.size(); i++) {
            if (comments.get(i).getId().equals(updated.getId())) {
                comments.set(i, updated);
                return;
            }
        }
    }

    public List<ReviewComment> getCommentsForFile(String filePath) {
        return comments.stream()
            .filter(c -> c.getFilePath().equals(filePath))
            .collect(java.util.stream.Collectors.toList());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReviewContext that = (ReviewContext) o;
        return Objects.equals(reviewId, that.reviewId) &&
               Objects.equals(title, that.title) &&
               Objects.equals(summary, that.summary) &&
               Objects.equals(author, that.author) &&
               status == that.status &&
               Objects.equals(reviewers, that.reviewers) &&
               Objects.equals(repositories, that.repositories) &&
               Objects.equals(comments, that.comments) &&
               Objects.equals(branch, that.branch) &&
               Objects.equals(baseBranch, that.baseBranch) &&
               hasClosedHistory == that.hasClosedHistory;
    }

    @Override
    public int hashCode() {
        return Objects.hash(reviewId, title, summary, author, status, reviewers, repositories, comments, branch, baseBranch,
            hasClosedHistory);
    }
}

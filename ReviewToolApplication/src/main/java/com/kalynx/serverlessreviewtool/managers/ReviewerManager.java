package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.OrphanBranchReviewManager;
import com.kalynx.serverlessreviewtool.git.ReviewBranchManagerFactory;
import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Manages reviewer membership and status changes for a review.
 * Writes reviewer entries via the orphan branch store.
 */
public class ReviewerManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewerManager.class);

    private final ReviewBranchManagerFactory branchManagerFactory;

    /**
     * Constructs a ReviewerManager.
     *
     * @param branchManagerFactory factory for creating per-repository orphan branch managers
     */
    public ReviewerManager(ReviewBranchManagerFactory branchManagerFactory) {
        this.branchManagerFactory = branchManagerFactory;
    }

    /**
     * Add a reviewer to a review in the specified repositories.
     *
     * @param reviewId the review identifier
     * @param reviewerName the name of the reviewer to add
     * @param repositoryNames the repositories containing the review notes
     * @return future completing when the reviewer is written
     */
    public CompletableFuture<Void> addReviewer(String reviewId, String reviewerName, List<String> repositoryNames) {
        if (repositoryNames == null || repositoryNames.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Repository names cannot be null or empty"));
        }

        String primaryRepoName = repositoryNames.getFirst();
        OrphanBranchReviewManager notesManager = branchManagerFactory.create(primaryRepoName);

        com.kalynx.serverlessreviewtool.models.review.ReviewerData reviewerData =
            new com.kalynx.serverlessreviewtool.models.review.ReviewerData("REVIEWING", null);

        LOGGER.debug("Adding reviewer {} to review {} in repository {}", reviewerName, reviewId, primaryRepoName);

        return notesManager.writeReviewer(reviewId, reviewerName, reviewerData);
    }

    /**
     * Update a reviewer's decision status for a review.
     *
     * @param reviewId the review identifier
     * @param reviewerName reviewer/editor name
     * @param reviewerStatus target decision status
     * @param repositoryNames repositories containing the review notes
     * @return future completing when reviewer status is written
     */
    public CompletableFuture<Void> updateReviewerStatus(
            String reviewId, String reviewerName, ReviewerStatus reviewerStatus, List<String> repositoryNames) {
        if (repositoryNames == null || repositoryNames.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Repository names cannot be null or empty"));
        }
        if (reviewerStatus == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Reviewer status cannot be null"));
        }

        String primaryRepoName = repositoryNames.getFirst();
        OrphanBranchReviewManager notesManager = branchManagerFactory.create(primaryRepoName);
        String statusValue = mapReviewerStatus(reviewerStatus);

        com.kalynx.serverlessreviewtool.models.review.ReviewerData reviewerData =
            new com.kalynx.serverlessreviewtool.models.review.ReviewerData(statusValue, null);

        LOGGER.debug("Updating reviewer {} status to {} for review {} in repository {}",
            reviewerName, reviewerStatus, reviewId, primaryRepoName);

        return notesManager.writeReviewer(reviewId, reviewerName, reviewerData);
    }

    /**
     * Remove a reviewer from a review.
     *
     * @param reviewId the review identifier
     * @param reviewerName the name of the reviewer to remove
     * @param repositoryNames the repositories containing the review notes
     * @return future completing when the reviewer removal is written
     */
    public CompletableFuture<Void> removeReviewer(String reviewId, String reviewerName, List<String> repositoryNames) {
        if (repositoryNames == null || repositoryNames.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Repository names cannot be null or empty"));
        }

        String primaryRepoName = repositoryNames.getFirst();
        OrphanBranchReviewManager notesManager = branchManagerFactory.create(primaryRepoName);

        com.kalynx.serverlessreviewtool.models.review.ReviewerData reviewerData =
            new com.kalynx.serverlessreviewtool.models.review.ReviewerData("LEFT", null);

        LOGGER.debug("Removing reviewer {} from review {} in repository {}", reviewerName, reviewId, primaryRepoName);

        return notesManager.writeReviewer(reviewId, reviewerName, reviewerData);
    }

    private String mapReviewerStatus(ReviewerStatus reviewerStatus) {
        return switch (reviewerStatus) {
            case APPROVED -> "approved";
            case CHANGES_REQUESTED -> "changes_requested";
            case REVIEWING -> "reviewing";
        };
    }
}






package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.theme.LoadingStateManager;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewPanelModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handles author-exclusive review state transitions: closing, cancelling, and marking in progress.
 */
public class ReviewAuthorActionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewAuthorActionHandler.class);

    private final ReviewContextManager reviewContextManager;
    private final ReviewPanelModel model;
    private final SettingsManager settingsManager;
    private final Supplier<ReviewContext> contextSupplier;
    private final Consumer<ReviewContext> contextConsumer;

    /**
     * @param reviewContextManager manager for review metadata and snapshot operations
     * @param model                shared panel model
     * @param settingsManager      settings access
     * @param contextSupplier      supplier for the currently loaded {@link ReviewContext}
     * @param contextConsumer      callback invoked when the context is updated after an action
     */
    public ReviewAuthorActionHandler(ReviewContextManager reviewContextManager,
                                     ReviewPanelModel model,
                                     SettingsManager settingsManager,
                                     Supplier<ReviewContext> contextSupplier,
                                     Consumer<ReviewContext> contextConsumer) {
        this.reviewContextManager = reviewContextManager;
        this.model = model;
        this.settingsManager = settingsManager;
        this.contextSupplier = contextSupplier;
        this.contextConsumer = contextConsumer;
    }

    /**
     * Closes (completes) the currently loaded review. Only available to the review author.
     */
    public void handleCloseReview() {
        applyAuthorStatusChange(ReviewStatus.COMPLETED, "Closing review...", "close review");
    }

    /**
     * Marks the currently loaded review as in progress. Only available to the review author.
     */
    public void handleMarkInProgress() {
        applyAuthorStatusChange(ReviewStatus.IN_PROGRESS, "Updating review...", "mark review in progress");
    }

    /**
     * Cancels the currently loaded review. Only available to the review author.
     */
    public void handleCancelReview() {
        applyAuthorStatusChange(ReviewStatus.CANCELLED, "Cancelling review...", "cancel review");
    }

    private void applyAuthorStatusChange(ReviewStatus targetStatus,
                                         String loadingMessage,
                                         String actionDescription) {
        ReviewContext current = contextSupplier.get();
        if (current == null) {
            LOGGER.warn("Cannot {} - no review context loaded", actionDescription);
            return;
        }

        String currentUser = settingsManager.getCurrentUserName();
        if (currentUser == null || currentUser.isBlank()) {
            LOGGER.warn("Cannot {} - current user is not set", actionDescription);
            return;
        }

        if (!currentUser.trim().equals(current.author != null ? current.author.trim() : "")) {
            LOGGER.warn("Cannot {} - current user is not the review author", actionDescription);
            return;
        }

        if (current.status == targetStatus) {
            LOGGER.debug("Review {} is already {}", current.reviewId, targetStatus);
            return;
        }

        LOGGER.debug("Applying status {} to review {} by author {}", targetStatus, current.reviewId, currentUser);
        LoadingStateManager.getInstance().startLoading(loadingMessage);

        ReviewContext updatedContext = new ReviewContext(
            current.reviewId, current.title, current.summary, current.author, targetStatus,
            new ArrayList<>(current.reviewers),
            new ArrayList<>(current.repositories),
            new ArrayList<>(current.comments),
            current.getBranch(), current.getBaseBranch(),
            current.hasClosedHistory() || isTerminalStatus(targetStatus)
        );

        CompletableFuture<Map<String, List<String>>> snapshotFuture = isTerminalStatus(targetStatus)
            ? reviewContextManager.captureReviewCommitSnapshots(
                updatedContext.reviewId, updatedContext.getRepositories(),
                updatedContext.getBranch(), updatedContext.getBaseBranch(), currentUser)
            : CompletableFuture.completedFuture(Map.of());

        List<String> repoNames = current.repositories.stream().map(Repository::getName).toList();
        String primaryRepo = current.repositories.getFirst().getName();

        snapshotFuture
            .thenCompose(ignored -> reviewContextManager.saveReviewMetadata(updatedContext))
            .thenCompose(ignored -> reviewContextManager.loadReviewMetadataOnly(
                current.reviewId, repoNames, primaryRepo))
            .thenAccept(reloaded -> {
                LoadingStateManager.getInstance().stopLoading(loadingMessage);
                if (reloaded != null) {
                    contextConsumer.accept(reloaded);
                    SwingUtilities.invokeLater(() -> model.reviewDetailModel.setReviewData(
                        reloaded.reviewId, reloaded.title, reloaded.author,
                        reloaded.summary, reloaded.status, reloaded.reviewers));
                }
            })
            .exceptionally(error -> {
                LoadingStateManager.getInstance().stopLoading(loadingMessage);
                LOGGER.error("Failed to {}", actionDescription, error);
                return null;
            });
    }

    private boolean isTerminalStatus(ReviewStatus status) {
        return status == ReviewStatus.COMPLETED || status == ReviewStatus.CANCELLED;
    }
}


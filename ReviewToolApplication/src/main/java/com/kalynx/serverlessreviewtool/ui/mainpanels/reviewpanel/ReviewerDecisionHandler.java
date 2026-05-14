package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import com.kalynx.serverlessreviewtool.theme.LoadingStateManager;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewPanelModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handles approve and request-changes decisions made by a reviewer, including automatic
 * overall-status synchronisation based on all reviewer decisions.
 */
public class ReviewerDecisionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewerDecisionHandler.class);

    private final ReviewContextManager reviewContextManager;
    private final ReviewPanelModel model;
    private final SettingsManager settingsManager;
    private final Supplier<ReviewContext> contextSupplier;
    private final Consumer<ReviewContext> contextConsumer;

    /**
     * @param reviewContextManager manager for reviewer operations
     * @param model                shared panel model
     * @param settingsManager      settings access
     * @param contextSupplier      supplier for the currently loaded {@link ReviewContext}
     * @param contextConsumer      callback invoked when the context is updated after an action
     */
    public ReviewerDecisionHandler(ReviewContextManager reviewContextManager,
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
     * Approves the currently loaded review on behalf of the current user.
     */
    public void handleApprove() {
        if (contextSupplier.get() == null) {
            LOGGER.warn("Cannot approve - no review context loaded");
            return;
        }
        applyReviewerDecision(ReviewerStatus.APPROVED, "Approving review...");
    }

    /**
     * Requests changes on the currently loaded review on behalf of the current user.
     */
    public void handleRequestChanges() {
        if (contextSupplier.get() == null) {
            LOGGER.warn("Cannot request changes - no review context loaded");
            return;
        }
        applyReviewerDecision(ReviewerStatus.CHANGES_REQUESTED, "Requesting changes...");
    }

    /**
     * Resets the given reviewer's status back to {@link ReviewerStatus#REVIEWING},
     * prompting them to review the code again.
     *
     * @param reviewerName name of the reviewer to reset
     */
    public void handleReRequestReview(String reviewerName) {
        ReviewContext current = contextSupplier.get();
        if (current == null) {
            LOGGER.warn("Cannot request re-review - no review context loaded");
            return;
        }

        List<String> repositoryNames = current.repositories.stream()
            .map(Repository::getName)
            .toList();

        LOGGER.debug("Requesting re-review from {} on review {}", reviewerName, current.reviewId);
        LoadingStateManager.getInstance().startLoading("Requesting re-review...");

        reviewContextManager.updateReviewerStatus(current.reviewId, reviewerName, ReviewerStatus.REVIEWING, repositoryNames)
            .thenCompose(ignored -> reviewContextManager.loadReviewMetadataOnly(
                current.reviewId, repositoryNames, current.repositories.getFirst().getName()))
            .thenAccept(updatedContext -> {
                LoadingStateManager.getInstance().stopLoading("Requesting re-review...");
                if (updatedContext != null) {
                    contextConsumer.accept(updatedContext);
                    SwingUtilities.invokeLater(() -> model.reviewDetailModel.setReviewData(
                        updatedContext.reviewId, updatedContext.title, updatedContext.author,
                        updatedContext.summary, updatedContext.status, updatedContext.reviewers));
                }
            })
            .exceptionally(error -> {
                LoadingStateManager.getInstance().stopLoading("Requesting re-review...");
                LOGGER.error("Failed to request re-review from {}", reviewerName, error);
                return null;
            });
    }

    private void applyReviewerDecision(ReviewerStatus status, String loadingMessage) {
        ReviewContext current = contextSupplier.get();
        if (current == null) {
            LOGGER.warn("Cannot apply reviewer decision - no review context loaded");
            return;
        }

        String currentUser = settingsManager.getCurrentUserName();
        if (currentUser == null || currentUser.isBlank()) {
            LOGGER.warn("Cannot apply reviewer decision - current user is not set");
            return;
        }

        List<String> repositoryNames = current.repositories.stream()
            .map(Repository::getName)
            .toList();

        LOGGER.debug("Applying reviewer decision {} for user {} on review {}",
            status, currentUser, current.reviewId);
        LoadingStateManager.getInstance().startLoading(loadingMessage);

        reviewContextManager.updateReviewerStatus(current.reviewId, currentUser, status, repositoryNames)
            .thenCompose(ignored -> reviewContextManager.loadReviewMetadataOnly(
                current.reviewId, repositoryNames, current.repositories.getFirst().getName()))
            .thenCompose(updatedContext -> {
                if (updatedContext == null) {
                    return CompletableFuture.completedFuture(null);
                }
                contextConsumer.accept(updatedContext);
                ReviewStatus desired = computeOverallStatus(updatedContext);
                if (desired != updatedContext.status && !isTerminalStatus(updatedContext.status)) {
                    LOGGER.debug("Syncing overall status to {} based on reviewer decisions", desired);
                    ReviewContext synced = new ReviewContext(
                        updatedContext.reviewId, updatedContext.title, updatedContext.summary,
                        updatedContext.author, desired,
                        new ArrayList<>(updatedContext.reviewers),
                        new ArrayList<>(updatedContext.repositories),
                        new ArrayList<>(updatedContext.comments),
                        updatedContext.getBranch(), updatedContext.getBaseBranch(),
                        updatedContext.hasClosedHistory()
                    );
                    return reviewContextManager.saveReviewMetadata(synced)
                        .thenCompose(ignored2 -> reviewContextManager.loadReviewMetadataOnly(
                            updatedContext.reviewId, repositoryNames,
                            updatedContext.repositories.getFirst().getName()));
                }
                return CompletableFuture.completedFuture(updatedContext);
            })
            .thenAccept(finalContext -> {
                LoadingStateManager.getInstance().stopLoading(loadingMessage);
                if (finalContext != null) {
                    contextConsumer.accept(finalContext);
                    SwingUtilities.invokeLater(() -> model.reviewDetailModel.setReviewData(
                        finalContext.reviewId, finalContext.title, finalContext.author,
                        finalContext.summary, finalContext.status, finalContext.reviewers));
                }
            })
            .exceptionally(error -> {
                LoadingStateManager.getInstance().stopLoading(loadingMessage);
                LOGGER.error("Failed to apply reviewer decision {}", status, error);
                return null;
            });
    }

    private ReviewStatus computeOverallStatus(ReviewContext context) {
        boolean anyChangesRequested = context.reviewers.stream()
            .anyMatch(r -> r.getStatus() == ReviewerStatus.CHANGES_REQUESTED);
        return anyChangesRequested ? ReviewStatus.CHANGES_REQUESTED : ReviewStatus.IN_PROGRESS;
    }

    private boolean isTerminalStatus(ReviewStatus status) {
        return status == ReviewStatus.COMPLETED || status == ReviewStatus.CANCELLED;
    }
}


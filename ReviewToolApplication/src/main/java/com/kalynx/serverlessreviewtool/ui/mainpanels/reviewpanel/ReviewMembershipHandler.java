package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.theme.LoadingStateManager;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewPanelModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Handles joining and leaving a review as a reviewer.
 */
public class ReviewMembershipHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewMembershipHandler.class);

    private final ReviewContextManager reviewContextManager;
    private final ReviewPanelModel model;
    private final SettingsManager settingsManager;
    private final Supplier<ReviewContext> contextSupplier;
    private final Consumer<ReviewContext> contextConsumer;

    /**
     * @param reviewContextManager manager for reviewer membership operations
     * @param model                shared panel model
     * @param settingsManager      settings access
     * @param contextSupplier      supplier for the currently loaded {@link ReviewContext}
     * @param contextConsumer      callback invoked when the context is updated after an action
     */
    public ReviewMembershipHandler(ReviewContextManager reviewContextManager,
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
     * Adds the current user as a reviewer on the currently loaded review.
     */
    public void handleJoinReview() {
        ReviewContext context = contextSupplier.get();
        if (context == null) {
            LOGGER.warn("Cannot join review - no review context loaded");
            return;
        }
        String currentUser = settingsManager.getCurrentUserName();
        LOGGER.debug("Adding {} as reviewer to review: {}", currentUser, context.reviewId);
        LoadingStateManager.getInstance().startLoading("Joining review...");

        reviewContextManager.addReviewer(context.reviewId, currentUser, repoNames(context))
            .thenCompose(ignored -> refreshMetadata(context))
            .thenAccept(updated -> {
                LoadingStateManager.getInstance().stopLoading("Joining review...");
                applyUpdatedContext(updated);
            })
            .exceptionally(error -> {
                LoadingStateManager.getInstance().stopLoading("Joining review...");
                LOGGER.error("Failed to join review", error);
                return null;
            });
    }

    /**
     * Removes the current user from the reviewer list of the currently loaded review.
     */
    public void handleLeaveReview() {
        ReviewContext context = contextSupplier.get();
        if (context == null) {
            LOGGER.warn("Cannot leave review - no review context loaded");
            return;
        }
        String currentUser = settingsManager.getCurrentUserName();
        LOGGER.debug("Removing {} from review: {}", currentUser, context.reviewId);
        LoadingStateManager.getInstance().startLoading("Leaving review...");

        reviewContextManager.removeReviewer(context.reviewId, currentUser, repoNames(context))
            .thenCompose(ignored -> refreshMetadata(context))
            .thenAccept(updated -> {
                LoadingStateManager.getInstance().stopLoading("Leaving review...");
                applyUpdatedContext(updated);
            })
            .exceptionally(error -> {
                LoadingStateManager.getInstance().stopLoading("Leaving review...");
                LOGGER.error("Failed to leave review", error);
                return null;
            });
    }

    private CompletableFuture<ReviewContext> refreshMetadata(ReviewContext context) {
        return reviewContextManager.loadReviewMetadata(
            context.reviewId, repoNames(context), context.repositories.getFirst().getName());
    }

    private void applyUpdatedContext(ReviewContext updatedContext) {
        if (updatedContext != null) {
            contextConsumer.accept(updatedContext);
            SwingUtilities.invokeLater(() -> model.reviewDetailModel.setReviewData(
                updatedContext.reviewId, updatedContext.title, updatedContext.author,
                updatedContext.summary, updatedContext.status, updatedContext.reviewers));
        }
    }

    private List<String> repoNames(ReviewContext context) {
        return context.repositories.stream().map(Repository::getName).toList();
    }
}


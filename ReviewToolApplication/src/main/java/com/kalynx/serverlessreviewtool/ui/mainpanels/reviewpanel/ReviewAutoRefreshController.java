package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.serverlessreviewtool.plugin.ReviewListUpdate;
import com.kalynx.serverlessreviewtool.plugin.ReviewUpdateType;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewPanelModel;

import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Listens for plugin-driven review update notifications and triggers a background reload of the
 * currently open review, preserving the user's viewport position.
 */
public class ReviewAutoRefreshController {

    private final Supplier<ReviewContext> contextSupplier;
    private final ReviewPanelModel model;
    private final CodePanel codePanel;
    private final BiFunction<ReviewItem, ViewportRestoreState, CompletableFuture<Void>> reloadCallback;

    private volatile boolean autoRefreshInProgress;
    private volatile boolean autoRefreshPending;

    /**
     * @param contextSupplier supplier for the currently loaded {@link ReviewContext}
     * @param model           shared panel model
     * @param codePanel       code viewer, used to capture the current scroll position
     * @param reloadCallback  invoked with the review item and restore state to trigger a reload
     */
    public ReviewAutoRefreshController(Supplier<ReviewContext> contextSupplier,
                                       ReviewPanelModel model,
                                       CodePanel codePanel,
                                       BiFunction<ReviewItem, ViewportRestoreState, CompletableFuture<Void>> reloadCallback) {
        this.contextSupplier = contextSupplier;
        this.model = model;
        this.codePanel = codePanel;
        this.reloadCallback = reloadCallback;
    }

    /**
     * Handles incoming review update notifications from plugins. Triggers an auto-refresh
     * if any update is relevant to the currently open review.
     *
     * @param updates array of received update events
     */
    public void onReviewUpdatesReceived(ReviewListUpdate[] updates) {
        if (updates == null || updates.length == 0) {
            return;
        }
        ReviewContext context = contextSupplier.get();
        if (context == null || context.getReviewId() == null || context.getReviewId().isBlank()) {
            return;
        }

        String activeReviewId = context.getReviewId();
        Set<String> activeRepositories = context.getRepositories().stream()
            .map(Repository::getName)
            .collect(Collectors.toSet());

        boolean hasRelevantUpdate = Arrays.stream(updates)
            .filter(Objects::nonNull)
            .filter(update -> update.updateType() == ReviewUpdateType.UPDATED)
            .anyMatch(update -> isRelevantToCurrentReview(update, activeReviewId, activeRepositories));

        if (hasRelevantUpdate) {
            triggerAutoRefreshForOpenReview();
        }
    }

    private boolean isRelevantToCurrentReview(ReviewListUpdate update,
                                              String activeReviewId,
                                              Set<String> activeRepositories) {
        if (activeReviewId.equals(update.reviewId())) {
            return true;
        }
        if (update.primaryRepository() != null && activeRepositories.contains(update.primaryRepository())) {
            return true;
        }
        if (update.repositories() == null || update.repositories().isEmpty()) {
            return false;
        }
        return update.repositories().stream().anyMatch(activeRepositories::contains);
    }

    private void triggerAutoRefreshForOpenReview() {
        synchronized (this) {
            if (autoRefreshInProgress) {
                autoRefreshPending = true;
                return;
            }
            autoRefreshInProgress = true;
        }

        SwingUtilities.invokeLater(() -> {
            ReviewContext context = contextSupplier.get();
            if (context == null || context.getReviewId() == null || context.getReviewId().isBlank()) {
                completeAutoRefreshCycle();
                return;
            }

            ViewportRestoreState restoreState = captureViewportState();
            ReviewItem reviewItem = buildReviewItem(context);
            reloadCallback.apply(reviewItem, restoreState)
                .whenComplete((_, ignored) -> completeAutoRefreshCycle());
        });
    }

    private void completeAutoRefreshCycle() {
        boolean shouldRunAgain;
        synchronized (this) {
            shouldRunAgain = autoRefreshPending;
            autoRefreshPending = false;
            autoRefreshInProgress = false;
        }
        if (shouldRunAgain) {
            triggerAutoRefreshForOpenReview();
        }
    }

    private ViewportRestoreState captureViewportState() {
        var selectedFile = model.codeViewerModel.selectedFile.getValue();
        return new ViewportRestoreState(
            selectedFile != null ? selectedFile.getRepository() : null,
            selectedFile != null ? selectedFile.getPath() : null,
            codePanel.getTopVisibleLine()
        );
    }

    private ReviewItem buildReviewItem(ReviewContext context) {
        return new ReviewItem(
            context.getReviewId(),
            context.getTitle(),
            context.getAuthor(),
            context.getRepositories().isEmpty() ? null : context.getRepositories().getFirst().getName(),
            context.getRepositories().stream().map(Repository::getName).toList(),
            context.status,
            System.currentTimeMillis(),
            context.getReviewers().stream().map(ReviewerInfo::getName).toList(),
            context.getBranch(),
            context.getBaseBranch()
        );
    }
}


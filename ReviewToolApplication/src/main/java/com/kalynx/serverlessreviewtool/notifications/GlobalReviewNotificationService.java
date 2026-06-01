package com.kalynx.serverlessreviewtool.notifications;

import com.kalynx.serverlessreviewtool.models.ReviewItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fires desktop notifications for review changes that affect the current user,
 * regardless of which panel is currently open.
 *
 * <p>Register via
 * {@link com.kalynx.serverlessreviewtool.managers.ReviewItemManager#addListener} and
 * {@link com.kalynx.serverlessreviewtool.managers.ReviewItemManager#addUpsertListener}.
 * The first full-list call establishes the baseline; all subsequent updates are compared
 * against it to detect status changes and new reviewer assignments.
 */
public class GlobalReviewNotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalReviewNotificationService.class);

    private final SystemNotifier notifier;
    private final Supplier<String> currentUserSupplier;
    private final Map<String, ReviewItem> previousReviews = new HashMap<>();
    private boolean startupMode = true;

    /**
     * @param notifier            used to dispatch desktop notifications
     * @param currentUserSupplier supplies the currently logged-in user name
     */
    public GlobalReviewNotificationService(SystemNotifier notifier, Supplier<String> currentUserSupplier) {
        this.notifier = notifier;
        this.currentUserSupplier = currentUserSupplier;
    }

    /**
     * Signals that the initial data load is complete.
     *
     * <p>Until this is called every incoming update silently builds the baseline without
     * dispatching any notifications. After this call all subsequent updates are compared
     * against the baseline and notifications are dispatched where appropriate.
     */
    public synchronized void markInitialLoadComplete() {
        startupMode = false;
        LOGGER.debug("Initial load complete — notifications enabled");
    }

    /**
     * Handles a full review-list snapshot.
     *
     * <p>While in startup mode the snapshot is merged into the silent baseline.
     * After startup mode ends each item is compared against the baseline and
     * notifications are dispatched for any changes that affect the current user.
     *
     * @param reviews current full snapshot of all known review items
     */
    public synchronized void onReviewListUpdated(List<ReviewItem> reviews) {
        if (startupMode) {
            reviews.forEach(r -> previousReviews.put(r.getReviewId(), r));
            return;
        }
        reviews.forEach(this::evaluateAndNotify);
        previousReviews.clear();
        reviews.forEach(r -> previousReviews.put(r.getReviewId(), r));
    }

    /**
     * Handles a single targeted upsert.
     *
     * <p>While in startup mode the item is merged into the silent baseline.
     * After startup mode ends the item is compared against its previously known state
     * and a notification is dispatched if the current user is the author or a reviewer.
     *
     * @param item the review item that was added or updated
     */
    public synchronized void onReviewUpserted(ReviewItem item) {
        if (startupMode) {
            previousReviews.put(item.getReviewId(), item);
            return;
        }
        evaluateAndNotify(item);
        previousReviews.put(item.getReviewId(), item);
    }

    private void evaluateAndNotify(ReviewItem next) {
        String currentUser = currentUserSupplier.get();
        if (currentUser == null || currentUser.isBlank()) return;
        ReviewItem previous = previousReviews.get(next.getReviewId());
        checkReviewerAdded(previous, next, currentUser);
        checkStatusChanged(previous, next, currentUser);
    }

    private void checkReviewerAdded(ReviewItem previous, ReviewItem next, String currentUser) {
        boolean wasReviewer = previous != null && previous.getReviewers().contains(currentUser);
        boolean isReviewer = next.getReviewers().contains(currentUser);
        if (!wasReviewer && isReviewer) {
            String title = resolveTitle(next);
            LOGGER.debug("Notifying user added as reviewer: reviewId={}", next.getReviewId());
            notifier.sendNotification("Added as Reviewer", "You were added as a reviewer to: " + title);
        }
    }

    private void checkStatusChanged(ReviewItem previous, ReviewItem next, String currentUser) {
        if (previous == null || previous.getStatus() == next.getStatus()) return;
        if (!isUserInvolved(next, currentUser)) return;
        LOGGER.debug("Notifying status change: reviewId={} status={}", next.getReviewId(), next.getStatus());
        notifier.sendNotification("Review Status Changed", buildStatusMessage(next));
    }

    private boolean isUserInvolved(ReviewItem item, String currentUser) {
        return currentUser.equals(item.getAuthor()) || item.getReviewers().contains(currentUser);
    }

    private String buildStatusMessage(ReviewItem item) {
        String displayTitle = resolveTitle(item);
        String statusName = item.getStatus() != null ? item.getStatus().getDisplayName() : "Unknown";
        return "'" + displayTitle + "' is now " + statusName;
    }

    private String resolveTitle(ReviewItem item) {
        return item.getTitle() != null && !item.getTitle().isBlank() ? item.getTitle() : item.getReviewId();
    }
}

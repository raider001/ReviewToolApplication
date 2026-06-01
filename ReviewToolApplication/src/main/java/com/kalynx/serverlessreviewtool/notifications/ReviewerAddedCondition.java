package com.kalynx.serverlessreviewtool.notifications;

import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;

import java.util.Optional;

/**
 * Fires a notification when the current user has been added as a reviewer to a review.
 *
 * <p>The condition compares the reviewer list of the previous and next {@link ReviewContext}.
 * A notification is sent only when both contexts are non-null, the current user is present
 * in {@code next}'s reviewer list, and was absent from {@code previous}'s reviewer list.
 * Requiring a non-null {@code previous} ensures that simply opening a review the user already
 * belongs to does not trigger a spurious notification.
 */
public class ReviewerAddedCondition implements ReviewNotificationCondition {

    /**
     * {@inheritDoc}
     *
     * <p>Returns a notification only when:
     * <ul>
     *   <li>Both {@code previous} and {@code next} are non-null</li>
     *   <li>The {@code currentUser} is present in {@code next}'s reviewer list</li>
     *   <li>The {@code currentUser} was absent from {@code previous}'s reviewer list</li>
     * </ul>
     */
    @Override
    public Optional<Notification> evaluate(ReviewContext previous, ReviewContext next, String currentUser) {
        if (previous == null || next == null || currentUser == null || currentUser.isBlank()) {
            return Optional.empty();
        }
        if (!isReviewer(next, currentUser)) {
            return Optional.empty();
        }
        if (isReviewer(previous, currentUser)) {
            return Optional.empty();
        }
        String title = "Added as Reviewer";
        String message = buildMessage(next);
        return Optional.of(new Notification(title, message));
    }

    private boolean isReviewer(ReviewContext context, String currentUser) {
        return context.reviewers.stream()
            .map(ReviewerInfo::getName)
            .anyMatch(currentUser::equals);
    }

    private String buildMessage(ReviewContext context) {
        String reviewTitle = context.title != null && !context.title.isBlank()
            ? "'" + context.title + "'"
            : "a review";
        return "You have been added as a reviewer to " + reviewTitle;
    }
}


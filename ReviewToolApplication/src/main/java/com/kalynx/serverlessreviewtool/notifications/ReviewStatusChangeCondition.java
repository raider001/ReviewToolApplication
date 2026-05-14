package com.kalynx.serverlessreviewtool.notifications;

import com.kalynx.serverlessreviewtool.models.ReviewContext;

import java.util.Optional;

/**
 * Fires a notification when the overall review status changes and the current user
 * is either the author or a reviewer of the review.
 */
public class ReviewStatusChangeCondition implements ReviewNotificationCondition {

    /**
     * {@inheritDoc}
     *
     * <p>Returns a notification only when:
     * <ul>
     *   <li>Both {@code previous} and {@code next} are non-null</li>
     *   <li>The {@link com.kalynx.serverlessreviewtool.models.ReviewStatus} has changed</li>
     *   <li>The {@code currentUser} is the author or one of the reviewers</li>
     * </ul>
     */
    @Override
    public Optional<Notification> evaluate(ReviewContext previous, ReviewContext next, String currentUser) {
        if (previous == null || next == null) {
            return Optional.empty();
        }
        if (previous.status == next.status) {
            return Optional.empty();
        }
        if (!isUserInvolved(next, currentUser)) {
            return Optional.empty();
        }
        String title = "Review Status Changed";
        String message = buildMessage(next);
        return Optional.of(new Notification(title, message));
    }

    private boolean isUserInvolved(ReviewContext context, String currentUser) {
        if (currentUser == null || currentUser.isBlank()) {
            return false;
        }
        if (currentUser.equals(context.author)) {
            return true;
        }
        return context.reviewers.stream().anyMatch(r -> currentUser.equals(r.getName()));
    }

    private String buildMessage(ReviewContext context) {
        String reviewTitle = context.title != null && !context.title.isBlank()
            ? "'" + context.title + "'"
            : "A review";
        return reviewTitle + " changed to " + context.status.getDisplayName();
    }
}


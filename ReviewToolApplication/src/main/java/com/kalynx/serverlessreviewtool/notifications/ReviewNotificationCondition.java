package com.kalynx.serverlessreviewtool.notifications;

import com.kalynx.serverlessreviewtool.models.ReviewContext;

import java.util.Optional;

/**
 * A condition that determines whether a desktop notification should be sent when
 * a {@link ReviewContext} changes.
 *
 * <p>Implementations are evaluated on every context change. Returning a non-empty
 * {@link Optional} causes the contained {@link Notification} to be dispatched.
 */
@FunctionalInterface
public interface ReviewNotificationCondition {

    /**
     * Evaluates whether a notification should be sent for this particular context transition.
     *
     * @param previous    the context before the change, or {@code null} if none was loaded
     * @param next        the context after the change, or {@code null} if the review was cleared
     * @param currentUser the logged-in user name used to determine involvement
     * @return a non-empty {@link Optional} containing the notification to send, or empty if none
     */
    Optional<Notification> evaluate(ReviewContext previous, ReviewContext next, String currentUser);
}

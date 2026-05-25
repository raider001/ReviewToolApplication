package com.kalynx.serverlessreviewtool.plugin;

import java.time.Instant;
import java.util.List;

/**
 * Carries a minimal review update signal and repository query hints.
 *
 * @param eventId unique id for this update event
 * @param occurredAt timestamp when the event occurred
 * @param updateType semantic type of this update
 * @param reviewId unique review id
 * @param primaryRepository primary repository name for this review
 * @param repositories full repository snapshot for this review
 * @param repositoryUrl canonical git URL for fetching review content directly; may be {@code null}
 *                      if the URL is not known at notification time
 * @param branchName branch name; non-{@code null} only for {@code branch.*} events
 */
public record ReviewListUpdate(
        String eventId,
        Instant occurredAt,
        ReviewUpdateType updateType,
        String reviewId,
        String primaryRepository,
        List<String> repositories,
        String repositoryUrl,
        String branchName) implements NotificationPayload {
}



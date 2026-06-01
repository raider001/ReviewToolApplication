package com.kalynx.serverlessreviewtool.plugin.dataobjects;

import java.time.Instant;
import java.util.List;

/**
 * Carries repository list update signals emitted by notification plugins.
 *
 * @param eventId unique id for this update event
 * @param occurredAt timestamp when the event occurred
 * @param repositories tracked repository descriptors
 */
public record RepositoryListUpdate(
    String eventId,
    Instant occurredAt,
    List<RepositoryDescriptor> repositories) implements NotificationPayload {
}



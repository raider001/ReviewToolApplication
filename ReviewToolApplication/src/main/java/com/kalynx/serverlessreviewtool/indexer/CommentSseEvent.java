package com.kalynx.serverlessreviewtool.indexer;

/**
 * Carries the parsed fields of a {@code REVIEW_COMMENT_ADDED} or {@code REVIEW_COMMENT_UPDATED}
 * SSE event. Delivered to listeners registered via
 * {@link IndexerEventSource#addCommentEventListener}.
 */
public record CommentSseEvent(String eventType, String reviewId, String repositoryUrl, String commentId) {}

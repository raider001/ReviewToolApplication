package com.kalynx.serverlessreviewtool.plugin.dataobjects;

public record CommentIndex(String reviewId, String commentId, String repositoryUrl) implements NotificationPayload {}

package com.kalynx.serverlessreviewtool.plugin.dataobjects;

public record BranchIndex(String branchName, String repositoryUrl) implements NotificationPayload {}

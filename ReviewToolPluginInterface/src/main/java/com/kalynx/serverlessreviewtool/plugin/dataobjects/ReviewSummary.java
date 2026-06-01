package com.kalynx.serverlessreviewtool.plugin.dataobjects;

import java.util.List;


/**
 * A single review entry returned by {@code GET /reviews}.
 */
public record ReviewSummary(
        String reviewId,
        String status,
        String reviewBranch,
        String baseBranch,
        List<RepositoryDescriptor> repositories) {}
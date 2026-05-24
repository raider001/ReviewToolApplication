package com.kalynx.serverlessreviewtool.git;

/**
 * Single-argument factory for {@link OrphanBranchReviewManager}.
 *
 * <p>Callers supply only the repository name; the factory resolves the remote URL
 * and manages the underlying {@link OrphanBranchStore} internally.
 */
@FunctionalInterface
public interface ReviewBranchManagerFactory {
    OrphanBranchReviewManager create(String repositoryName);
}

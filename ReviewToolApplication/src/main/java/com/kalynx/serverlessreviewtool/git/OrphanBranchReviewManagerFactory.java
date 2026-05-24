package com.kalynx.serverlessreviewtool.git;

/**
 * Factory for creating {@link OrphanBranchReviewManager} instances scoped to a
 * specific repository name and remote URL.
 *
 * <p>Injecting this factory keeps call-sites decoupled from {@link OrphanBranchStore}
 * construction details and makes unit testing straightforward.
 */
@FunctionalInterface
public interface OrphanBranchReviewManagerFactory {

    /**
     * Returns an {@link OrphanBranchReviewManager} for the given repository.
     *
     * @param repositoryName local repository identifier (e.g. {@code "owner/repo"})
     * @param remoteUrl      canonical git remote URL
     * @return manager scoped to that repository
     */
    OrphanBranchReviewManager create(String repositoryName, String remoteUrl);
}

package com.kalynx.serverlessreviewtool.git;

/**
 * Factory for creating GitReviewNotesManager instances for a given repository.
 * Injecting this factory instead of constructing GitReviewNotesManager directly
 * allows managers to be unit-tested without a real git process.
 */
@FunctionalInterface
public interface ReviewNotesManagerFactory {

    /**
     * Creates a GitReviewNotesManager scoped to the given repository.
     *
     * @param repositoryName the local repository name
     * @return a GitReviewNotesManager for that repository
     */
    GitReviewNotesManager create(String repositoryName);
}


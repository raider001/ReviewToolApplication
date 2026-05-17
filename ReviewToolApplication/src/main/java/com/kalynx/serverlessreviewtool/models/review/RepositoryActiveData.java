package com.kalynx.serverlessreviewtool.models.review;

/**
 * Data payload recording whether a repository is an active participant in a review.
 * Stored in the {@code metadata/repositoryActive} stream. The {@code editor} field
 * of the enclosing {@code StreamEntry} identifies who made the change, while
 * {@code repositoryName} identifies the affected repository.
 * A missing entry for a repository implies that repository is active.
 */
public record RepositoryActiveData(String repositoryName, boolean active) {}

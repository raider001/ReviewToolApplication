package com.kalynx.serverlessreviewtool.git;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Manages per-repository blobless git clones used for code-review diff operations.
 *
 * <p>Implementations provide a {@code --filter=blob:none --no-checkout} clone per repository
 * so that all remote-tracking refs are immediately available but blobs are fetched lazily
 * on demand (e.g. when {@code git show sha:path} is run).
 */
public interface ReviewCloneManager {

    /**
     * Ensures a blobless clone of {@code repoName} exists locally. Idempotent.
     */
    CompletableFuture<Void> ensureClone(String repoName);

    /**
     * Runs a git sub-command inside the clone for {@code repoName}.
     * Ensures the clone exists first.
     */
    CompletableFuture<String> execute(String repoName, String... args);

    /**
     * Returns the default branch name (e.g. {@code main}) for {@code repoName}.
     */
    CompletableFuture<String> getDefaultBranch(String repoName);

    /**
     * Lists commits reachable from {@code ref} in the clone.
     * Returns raw log lines in {@code "%H|%an|%ai|%s"} format, newest first.
     * Plain branch names are resolved to {@code origin/<branch>} automatically.
     */
    CompletableFuture<List<String>> listCommits(String repoName, String ref, int maxCount);

    /**
     * Returns files changed between {@code fromCommit} and {@code toCommit} with status
     * letters (e.g. {@code M src/Foo.java}).
     */
    CompletableFuture<List<String>> listChangedFiles(String repoName, String fromCommit, String toCommit);

    /**
     * Fetches the latest refs from origin without downloading new blobs.
     * Ensures the clone exists first, so safe to call before any other method.
     */
    CompletableFuture<Void> refresh(String repoName);
}

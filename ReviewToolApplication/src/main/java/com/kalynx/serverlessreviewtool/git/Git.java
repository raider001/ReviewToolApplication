package com.kalynx.serverlessreviewtool.git;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Git provides asynchronous operations for managing local repository clones.
 *
 * <p>All operations are non-blocking and return {@link CompletableFuture} for composability.
 */
public interface Git {

    /**
     * Clones a remote repository locally, or reconnects it if the directory already exists.
     *
     * <p>Creates the local directory if needed, runs {@code git clone}, configures the remote,
     * and fetches all branches.
     *
     * @param remoteUrl git remote URL
     * @return future that completes when initialization finishes
     */
    CompletableFuture<Void> cloneRepository(String remoteUrl);

    /**
     * Ensures a repository is cloned locally, cloning it first if it does not exist.
     *
     * <p>If the local repository directory already exists this is a no-op.
     * If it does not exist, {@link #cloneRepository(String)} is called with the provided URL.
     *
     * @param repoName  local repository directory name
     * @param remoteUrl remote URL to clone from if not already present
     * @return future that completes when the repository is guaranteed to be present locally
     */
    CompletableFuture<Void> ensureCloned(String repoName, String remoteUrl);

    /**
     * Removes a local repository directory.
     *
     * @param repository local repository path to delete
     * @return future that completes when deletion finishes
     */
    CompletableFuture<Void> removeRepository(String repository);

    CompletableFuture<Void> fetch(String repository);

    /**
     * Fetch only the provided branch refs for a repository.
     *
     * @param repository local repository name
     * @param branches branch names or refs to fetch
     * @return future that completes when branch refs are fetched
     */
    CompletableFuture<Void> fetchBranches(String repository, List<String> branches);

    CompletableFuture<Void> pull(String repository);

    /**
     * List all branches in the repository.
     *
     * <p><b>Performance Note:</b> This method queries a locally cloned repository.
     * For UI operations that only need branch metadata, consider using
     * {@link #listBranchesRemote(String)} instead to avoid sync issues with locally cached branches.</p>
     *
     * @param repository local repository name
     * @return future containing list of branch names
     */
    CompletableFuture<List<String>> listBranches(String repository);

    /**
     * List all branches in a remote repository without cloning.
     * Uses git ls-remote to fetch remote refs.
     *
     * <p><b>Design Rationale:</b> This method is preferred for UI operations because:
     * <ul>
     *   <li><b>Always in sync:</b> Always reflects current remote state (~600ms)</li>
     *   <li><b>No storage:</b> Doesn't require local cloning or storage</li>
     *   <li><b>No maintenance:</b> No sync issues if local repo isn't fetched regularly</li>
     * </ul>
     * Local cloning is only necessary for operations requiring full git history or file analysis.</p>
     *
     * @param remoteUrl remote repository URL
     * @return future containing list of branch names
     */
    CompletableFuture<List<String>> listBranchesRemote(String remoteUrl);

    /**
     * Get the default branch name for the repository.
     * Typically "main" or "master".
     *
     * @param repository local repository name
     * @return future containing the default branch name
     */
    CompletableFuture<String> getDefaultBranch(String repository);

    /**
     * List commits for a specific branch or ref.
     * Returns commits in format: "hash|author|date|message"
     *
     * @param repository local repository name
     * @param ref branch name or commit reference (e.g., "main", "HEAD~10")
     * @param maxCount maximum number of commits to return
     * @return future containing list of commit info strings
     */
    CompletableFuture<List<String>> listCommits(String repository, String ref, int maxCount);

    /**
     * List commits for a branch directly from a remote repository without requiring a local clone.
     * Creates a temporary bare clone, reads the log, then removes the clone.
     * Returns commits in format: "hash|author|date|message", newest first.
     *
     * @param remoteUrl remote repository URL
     * @param ref       branch name (e.g., "main", "feature/my-branch")
     * @param maxCount  maximum number of commits to return
     * @return future containing list of commit info strings
     */
    CompletableFuture<List<String>> listCommitsRemote(String remoteUrl, String ref, int maxCount);

    /**
     * List files changed between two commits.
     * Returns files in format: "status path" (e.g., "M src/Main.java", "A newfile.txt")
     *
     * @param repository local repository name
     * @param fromCommit starting commit hash or ref
     * @param toCommit ending commit hash or ref
     * @return future containing list of changed files with status
     */
    CompletableFuture<List<String>> listChangedFiles(String repository, String fromCommit, String toCommit);

    /**
     * Execute an arbitrary git command in the specified repository.
     *
     * @param repository local repository name
     * @param args git command arguments (e.g., "notes", "--ref=myref", "add", ...)
     * @return future containing the command output
     */
    CompletableFuture<String> executeAsync(String repository, String... args);
}


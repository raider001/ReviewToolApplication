package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.ReviewCloneManager;
import com.kalynx.serverlessreviewtool.models.Commit;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.CodeViewerModel;
import com.kalynx.swingtheme.theme.LoadingStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * FileDiffManager manages loading file diffs and content for code review.
 * Handles async operations for loading commits, changed files, and diff content.
 * Updates CodeViewerModel which triggers UI updates.
 */
public class FileDiffManager {
    private static final Logger logger = LoggerFactory.getLogger(FileDiffManager.class);

    private final ReviewCloneManager cloneManager;
    private final CodeViewerModel codeViewerModel;
    private final AtomicLong diffGeneration = new AtomicLong();

    public FileDiffManager(ReviewCloneManager cloneManager, CodeViewerModel codeViewerModel) {
        this.cloneManager = cloneManager;
        this.codeViewerModel = codeViewerModel;
    }

    /**
     * Loads commits for a review directly from the remote repository.
     * Requests maxCommits+1 commits so the final entry serves as the baseline
     * (parent of the oldest visible branch commit) without needing a local clone.
     *
     * @param repositoryName name of the repository (for logging)
     * @param remoteUrl      remote git URL to query
     * @param branch         branch name to load commits from
     * @param maxCommits     maximum number of branch commits to show
     * @return future that completes when commits are loaded
     */
    public CompletableFuture<Void> loadCommitsForReview(String repositoryName,
                                                         String branch, int maxCommits) {
        if (branch == null || branch.isBlank()) {
            logger.warn("Cannot load commits for repository {}: branch ref is blank", repositoryName);
            codeViewerModel.setAvailableCommits(new ArrayList<>());
            return CompletableFuture.completedFuture(null);
        }
        logger.info("Loading commits for repository: {}, branch: {}, max: {}", repositoryName, branch, maxCommits);
        long start = System.nanoTime();

        return cloneManager.listCommits(repositoryName, branch, maxCommits + 1)
            .thenApply(rawLines -> {
                logger.info("TIMING loadCommitsForReview git.listCommits (repo={}, branch={}): {}ms",
                    repositoryName, branch, elapsedMs(start));
                return rawLines;
            })
            .thenApply(this::parseCommits)
            .thenCompose(allCommits -> {
                logger.info("Loaded {} commits (including potential baseline)", allCommits.size());
                if (allCommits.isEmpty()) {
                    logger.warn("No commits found for repository: {}, branch: {}", repositoryName, branch);
                    codeViewerModel.setAvailableCommits(new ArrayList<>());
                    return CompletableFuture.<Void>completedFuture(null);
                }

                // If we received maxCommits+1 entries, the last one is the baseline
                // (parent of the branch); otherwise the oldest branch commit is the baseline.
                boolean hasSeparateBaseline = allCommits.size() > maxCommits;
                List<Commit> branchCommits = hasSeparateBaseline
                        ? new ArrayList<>(allCommits.subList(0, maxCommits))
                        : new ArrayList<>(allCommits);
                Commit baselineCommit = allCommits.getLast();
                Commit endCommit = branchCommits.getFirst();

                List<Commit> commitsForModel = new ArrayList<>(branchCommits);
                if (hasSeparateBaseline) {
                    commitsForModel.add(baselineCommit);
                }
                codeViewerModel.setAvailableCommits(commitsForModel);

                if (codeViewerModel.startCommit.getValue() == null || codeViewerModel.endCommit.getValue() == null) {
                    logger.info("Setting initial commit range: start={} (baseline), end={} (latest)",
                        baselineCommit.getShortHash(), endCommit.getShortHash());
                    codeViewerModel.setCommitRange(baselineCommit, endCommit);
                } else {
                    logger.info("Preserving existing commit range: start={}, end={}",
                        codeViewerModel.startCommit.getValue().getShortHash(),
                        codeViewerModel.endCommit.getValue().getShortHash());
                }

                return CompletableFuture.<Void>completedFuture(null);
            })
            .exceptionally(error -> {
                logger.error("Failed to load commits: {}", error.getMessage(), error);
                codeViewerModel.setAvailableCommits(new ArrayList<>());
                return null;
            });
    }

    /**
     * Loads commits directly from a stored commit-hash snapshot.
     * Snapshot order is expected newest -> oldest.
     */
    public CompletableFuture<Void> loadCommitsForSnapshot(String repositoryName, List<String> commitHashes) {
        if (commitHashes == null || commitHashes.isEmpty()) {
            codeViewerModel.setAvailableCommits(new ArrayList<>());
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<Commit>> commitFutures = commitHashes.stream()
            .map(hash -> cloneManager.execute(repositoryName,
                    "show", "-s", "--format=%H|%an|%ad|%s", "--date=short", hash)
                .thenApply(this::parseSingleCommit)
                .exceptionally(error -> {
                    logger.warn("Failed to load stored commit {} in {}: {}", hash, repositoryName, error.getMessage());
                    return null;
                }))
            .toList();

        return CompletableFuture.allOf(commitFutures.toArray(new CompletableFuture[0]))
            .thenCompose(ignored -> {
                List<Commit> commits = commitFutures.stream()
                    .map(CompletableFuture::join)
                    .filter(java.util.Objects::nonNull)
                    .toList();

                if (commits.isEmpty()) {
                    codeViewerModel.setAvailableCommits(new ArrayList<>());
                    return CompletableFuture.completedFuture(null);
                }

                Commit endCommit = commits.getFirst();
                return resolveBaselineCommit(repositoryName, commits)
                    .thenAccept(startCommit -> {
                        List<Commit> commitsForModel = new ArrayList<>(commits);
                        if (startCommit != null && commitsForModel.stream().noneMatch(c -> c.getHash().equals(startCommit.getHash()))) {
                            commitsForModel.add(startCommit);
                        }
                        codeViewerModel.setAvailableCommits(commitsForModel);

                        if (codeViewerModel.startCommit.getValue() == null || codeViewerModel.endCommit.getValue() == null) {
                            Commit baselineCommit = startCommit != null ? startCommit : commits.getLast();
                            codeViewerModel.setCommitRange(baselineCommit, endCommit);
                        } else {
                            logger.info("Preserving existing commit range during snapshot load: start={}, end={}",
                                codeViewerModel.startCommit.getValue().getShortHash(),
                                codeViewerModel.endCommit.getValue().getShortHash());
                        }
                    });
            })
            .exceptionally(error -> {
                logger.error("Failed to load commits from snapshot: {}", error.getMessage(), error);
                codeViewerModel.setAvailableCommits(new ArrayList<>());
                return null;
            });
    }

    /**
     * Loads diff content for a specific file between two commits.
     * Loads both side-by-side content (left/right) and unified diff format.
     * Updates the model with file content.
     * Handles ADDED and DELETED files gracefully with appropriate placeholder messages.
     *
     * @param repositoryName name of the repository
     * @param file file to load diff for
     * @param startCommit starting commit for comparison
     * @param endCommit ending commit for comparison
     * @return future that completes when diff is loaded
     */
    public CompletableFuture<Void> loadDiffForFile(String repositoryName, ReviewFile file,
                                                     Commit startCommit, Commit endCommit) {
        if (file == null || startCommit == null || endCommit == null) {
            return CompletableFuture.completedFuture(null);
        }

        logger.info("Loading diff for file {} between commits {} and {}",
            file.getPath(), startCommit.getShortHash(), endCommit.getShortHash());

        // Guard against out-of-order async completions: only the most-recently-started
        // request is allowed to update the model.
        final long myGeneration = diffGeneration.incrementAndGet();

        String operationId = "load-diff-" + file.getPath() + "@" + startCommit.getShortHash() + ".." + endCommit.getShortHash();
        LoadingStateManager.getInstance().startLoading(operationId);

        // When comparing specific commits, always try to load file content
        // The file's changeType (ADDED/DELETED/MODIFIED) is relative to branch comparison
        // A file marked ADDED (not in master) might still exist in both commits we're comparing
        CompletableFuture<String> leftContentFuture = cloneManager.execute(repositoryName, "show",
            startCommit.getHash() + ":" + file.getPath())
            .exceptionally(error -> contentFallback(file.getPath(), startCommit.getShortHash(), error));

        CompletableFuture<String> rightContentFuture = cloneManager.execute(repositoryName, "show",
            endCommit.getHash() + ":" + file.getPath())
            .exceptionally(error -> contentFallback(file.getPath(), endCommit.getShortHash(), error));

        CompletableFuture<String> unifiedDiffFuture = loadUnifiedDiff(repositoryName,
            file.getPath(), startCommit.getHash(), endCommit.getHash());

        return CompletableFuture.allOf(leftContentFuture, rightContentFuture, unifiedDiffFuture)
            .thenAccept(ignored -> {
                if (diffGeneration.get() != myGeneration) {
                    logger.debug("Discarding stale diff result for {} (generation mismatch)", file.getPath());
                    return;
                }
                String leftContent = leftContentFuture.join();
                String rightContent = rightContentFuture.join();
                String unifiedDiff = unifiedDiffFuture.join();

                logger.info("Loaded content - Left: {} chars, Right: {} chars, Diff: {} chars",
                    leftContent.length(), rightContent.length(), unifiedDiff.length());

                codeViewerModel.setFileContent(leftContent, rightContent, unifiedDiff);
            })
            .exceptionally(error -> {
                logger.error("Failed to load diff for file {}: {}", file.getPath(), error.getMessage());
                codeViewerModel.setFileContent(
                    "// Error loading content: " + error.getMessage(),
                    "// Error loading content: " + error.getMessage(),
                    "// Error loading diff: " + error.getMessage()
                );
                return null;
            })
            .whenComplete((ignoredResult, ignoredError) ->
                LoadingStateManager.getInstance().stopLoading(operationId));
    }

    private CompletableFuture<String> loadUnifiedDiff(String repositoryName, String filePath,
                                                        String fromCommit, String toCommit) {
        return cloneManager.execute(repositoryName, "diff", fromCommit, toCommit, "--", filePath)
            .exceptionally(error -> {
                String errorMsg = error.getMessage();
                logger.warn("Failed to generate unified diff for {}: {}", filePath, errorMsg);

                // Return a descriptive message for the diff pane
                return "# Unable to generate diff\n" +
                       "# File: " + filePath + "\n" +
                       "# Error: " + errorMsg;
            });
    }

    private CompletableFuture<Commit> resolveBaselineCommit(String repositoryName, List<Commit> commits) {
        if (commits.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        Commit oldestBranchCommit = commits.getLast();
        String parentRef = oldestBranchCommit.getHash() + "^";
        return cloneManager.execute(repositoryName, "rev-parse", parentRef)
            .thenApply(String::trim)
            .thenCompose(parentHash -> cloneManager.execute(repositoryName,
                "show", "-s", "--format=%H|%an|%ad|%s", "--date=short", parentHash)
                .thenApply(this::parseSingleCommit)
                .exceptionally(ignored -> new Commit(
                    parentHash,
                    "Baseline (parent of " + oldestBranchCommit.getShortHash() + ")",
                    oldestBranchCommit.getAuthor(),
                    oldestBranchCommit.getDate()
                )))
            .exceptionally(ignored -> oldestBranchCommit);
    }

    private Commit parseSingleCommit(String output) {
        String[] lines = output.split("\\R");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 4);
            if (parts.length >= 4) {
                return new Commit(parts[0], parts[3], parts[1], parts[2]);
            }
        }
        throw new IllegalArgumentException("Unable to parse commit output: " + output);
    }

    private List<Commit> parseCommits(List<String> commitStrings) {
        List<Commit> commits = new ArrayList<>();
        for (String commitString : commitStrings) {
            try {
                String[] parts = commitString.split("\\|", 4);
                if (parts.length >= 4) {
                    commits.add(new Commit(parts[0], parts[3], parts[1], parts[2]));
                } else {
                    logger.warn("Skipping malformed commit line: '{}' (only {} parts)", commitString, parts.length);
                }
            } catch (Exception e) {
                logger.error("Error parsing commit line '{}': {}", commitString, e.getMessage(), e);
            }
        }
        return commits;
    }

    private String contentFallback(String filePath, String shortHash, Throwable error) {
        String msg = error.getMessage() != null ? error.getMessage() : "";
        if (msg.contains("not our ref") || msg.contains("upload-pack")) {
            logger.warn("Commit {} not available on remote for file {} (push the branch to view)",
                shortHash, filePath);
            return "// Commit " + shortHash + " is not available on the remote.\n" +
                   "// Push your branch and reload the review to view this file's content.";
        }
        logger.warn("File {} not found in commit {}: {}", filePath, shortHash, msg);
        return "// File does not exist in commit " + shortHash + "\n// Path: " + filePath;
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}


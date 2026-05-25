package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.OrphanBranchReviewManager;
import com.kalynx.serverlessreviewtool.git.ReviewCloneManager;
import com.kalynx.serverlessreviewtool.git.ReviewBranchManagerFactory;
import com.kalynx.serverlessreviewtool.models.FileChangeType;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Manages the set of files changed in a review and their associated commit snapshots.
 * Handles git ref resolution, file diff listing, commit snapshot capture and retrieval.
 * All operations are stateless and operate purely via git and git notes.
 */
public class ReviewChangeSetManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewChangeSetManager.class);

    private final ReviewCloneManager cloneManager;
    private final ReviewBranchManagerFactory branchManagerFactory;

    /**
     * Constructs a ReviewChangeSetManager with the given clone manager and branch manager factory.
     *
     * @param cloneManager         manages blobless git clones for diff operations
     * @param branchManagerFactory factory for creating per-repository orphan branch managers
     */
    public ReviewChangeSetManager(ReviewCloneManager cloneManager, ReviewBranchManagerFactory branchManagerFactory) {
        this.cloneManager = cloneManager;
        this.branchManagerFactory = branchManagerFactory;
    }

    /**
     * Load files changed in a review by comparing the review branch against the base branch.
     *
     * @param repositoryName the repository containing the branches
     * @param reviewBranch the branch being reviewed
     * @param baseBranch the base branch to compare against
     * @return future completing with the list of changed ReviewFile objects
     */
    public CompletableFuture<List<ReviewFile>> loadFilesForReview(
            String repositoryName,
            String reviewBranch,
            String baseBranch) {

        if (repositoryName == null || reviewBranch == null || baseBranch == null) {
            LOGGER.warn("Invalid parameters for loading files: repo={}, reviewBranch={}, baseBranch={}",
                repositoryName, reviewBranch, baseBranch);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        return resolveComparisonRefs(repositoryName, baseBranch, reviewBranch)
            .thenCompose(resolved -> {
                LOGGER.debug("Loading files for review in repository '{}': {} -> {} (resolved: {} -> {})",
                    repositoryName, baseBranch, reviewBranch, resolved.baseRef(), resolved.reviewRef());

                if (resolved.baseRef() == null || resolved.baseRef().isBlank() ||
                    resolved.reviewRef() == null || resolved.reviewRef().isBlank()) {
                    LOGGER.warn("After resolution, refs are invalid: baseRef='{}', reviewRef='{}'",
                        resolved.baseRef(), resolved.reviewRef());
                    return CompletableFuture.completedFuture(new ArrayList<ReviewFile>());
                }

                boolean reviewRefResolved = !resolved.reviewRef().equals(reviewBranch);
                if (!reviewRefResolved) {
                    return refExistsInRepository(repositoryName, resolved.reviewRef())
                        .thenCompose(exists -> {
                            if (!exists) {
                                LOGGER.debug("Review branch '{}' does not exist in repository '{}', skipping file diff",
                                    reviewBranch, repositoryName);
                                return CompletableFuture.completedFuture(new ArrayList<ReviewFile>());
                            }
                            return cloneManager.listChangedFiles(repositoryName, resolved.baseRef(), resolved.reviewRef())
                                .thenApply(paths -> toReviewFiles(paths, repositoryName, baseBranch, reviewBranch));
                        });
                }

                return cloneManager.listChangedFiles(repositoryName, resolved.baseRef(), resolved.reviewRef())
                    .thenApply(paths -> toReviewFiles(paths, repositoryName, baseBranch, reviewBranch));
            })
            .exceptionally(error -> {
                LOGGER.warn("Failed to load files for review in repository '{}': {}",
                    repositoryName, error.getMessage());
                return new ArrayList<>();
            });
    }

    /**
     * Load all files changed in a review by comparing branches across multiple repositories.
     *
     * @param repositories list of repositories to check
     * @param branch the review branch
     * @param baseBranch the base branch to compare against
     * @return future completing with the combined list of ReviewFile objects across all repositories
     */
    public CompletableFuture<List<ReviewFile>> loadFilesFromReviewCommits(
            List<Repository> repositories, String branch, String baseBranch) {
        if (repositories == null || repositories.isEmpty()) {
            LOGGER.warn("No repositories provided for loading files");
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        if (branch == null || baseBranch == null) {
            LOGGER.warn("Missing branch or baseBranch for loading files");
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        LOGGER.debug("Loading files from review branches for {} repositories", repositories.size());
        LOGGER.debug("Branch: {}, BaseBranch: {}", branch, baseBranch);

        List<CompletableFuture<List<ReviewFile>>> fileFutures = repositories.stream()
            .map(repo -> {
                LOGGER.debug("Loading files from repository: {}", repo.getName());
                return loadFilesForReview(repo.getName(), branch, baseBranch);
            })
            .toList();

        return CompletableFuture.allOf(fileFutures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                List<ReviewFile> allFiles = new ArrayList<>();
                for (int i = 0; i < fileFutures.size(); i++) {
                    List<ReviewFile> repoFiles = fileFutures.get(i).join();
                    LOGGER.debug("Repository '{}' returned {} files", repositories.get(i).getName(), repoFiles.size());
                    allFiles.addAll(repoFiles);
                }
                LOGGER.debug("Total {} files loaded from review branches across all repositories", allFiles.size());
                return allFiles;
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to load files from review branches: {}", error.getMessage(), error);
                return new ArrayList<>();
            });
    }

    /**
     * Load files changed in a review using stored commit snapshots instead of live branch diffs.
     * Falls back to branch diff when no stored commits are available for a repository.
     *
     * @param reviewId the review identifier
     * @param repositories list of repositories containing the review
     * @param branch the review branch
     * @param baseBranch the base branch
     * @param commitsByRepository map of repository name to stored commit hashes
     * @return future completing with the combined list of ReviewFile objects
     */
    public CompletableFuture<List<ReviewFile>> loadFilesFromStoredReviewCommits(
            String reviewId,
            List<Repository> repositories,
            String branch,
            String baseBranch,
            Map<String, List<String>> commitsByRepository) {
        if (reviewId == null || reviewId.isBlank() || repositories == null || repositories.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        Map<String, List<String>> safeCommits = commitsByRepository != null ? commitsByRepository : Map.of();

        LOGGER.debug("Loading files from preloaded stored commit snapshots for review {} across {} repositories",
            reviewId, repositories.size());

        List<CompletableFuture<List<ReviewFile>>> fileFutures = repositories.stream()
            .map(repo -> loadFilesForRepositoryFromStoredCommits(
                reviewId, repo.getName(), branch, baseBranch, safeCommits.get(repo.getName())))
            .toList();

        return CompletableFuture.allOf(fileFutures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                List<ReviewFile> allFiles = new ArrayList<>();
                for (CompletableFuture<List<ReviewFile>> future : fileFutures) {
                    allFiles.addAll(future.join());
                }
                LOGGER.debug("Loaded {} files from preloaded stored commit snapshots for review {}", allFiles.size(), reviewId);
                return allFiles;
            })
            .exceptionally(error -> {
                LOGGER.error("Failed loading files from preloaded stored commits for review {}: {}",
                    reviewId, error.getMessage(), error);
                return new ArrayList<>();
            });
    }

    /**
     * Capture commit snapshots for each repository in a review.
     * Used when transitioning a review to a terminal state so reopened reviews can replay historical changes.
     *
     * @param reviewId the review identifier
     * @param repositories list of repositories participating in the review
     * @param reviewBranch the review branch
     * @param baseBranch the base branch
     * @param editor the user triggering the capture
     * @return future completing with a map of repository name to captured commit hashes
     */
    public CompletableFuture<Map<String, List<String>>> captureReviewCommitSnapshots(
            String reviewId,
            List<Repository> repositories,
            String reviewBranch,
            String baseBranch,
            String editor) {
        if (reviewId == null || reviewId.isBlank() || repositories == null || repositories.isEmpty()) {
            return CompletableFuture.completedFuture(new LinkedHashMap<>());
        }
        if (reviewBranch == null || reviewBranch.isBlank() || baseBranch == null || baseBranch.isBlank()) {
            LOGGER.warn("Skipping commit snapshot capture for review {} - review/base branch is empty", reviewId);
            return CompletableFuture.completedFuture(new LinkedHashMap<>());
        }

        List<CompletableFuture<Map.Entry<String, List<String>>>> futures = repositories.stream()
            .map(repo -> captureCommitSnapshotForRepository(reviewId, repo, reviewBranch, baseBranch, editor))
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                Map<String, List<String>> result = new LinkedHashMap<>();
                for (CompletableFuture<Map.Entry<String, List<String>>> future : futures) {
                    Map.Entry<String, List<String>> entry = future.join();
                    result.put(entry.getKey(), entry.getValue());
                }
                return result;
            });
    }

    /**
     * Load the latest stored commit snapshot for a review in a given repository.
     *
     * @param reviewId the review identifier
     * @param repositoryName the name of the repository
     * @return future completing with the list of stored commit hashes, or an empty list if none exist
     */
    public CompletableFuture<List<String>> loadLatestReviewCommits(String reviewId, String repositoryName) {
        if (reviewId == null || reviewId.isBlank() || repositoryName == null || repositoryName.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        OrphanBranchReviewManager notesManager = branchManagerFactory.create(repositoryName);
        return notesManager.readCommits(reviewId)
            .thenApply(this::getLatestValue)
            .thenApply(commits -> commits != null ? commits : List.<String>of())
            .exceptionally(error -> {
                LOGGER.warn("Unable to load stored commits for review {} in repo {}: {}",
                    reviewId, repositoryName, error.getMessage());
                return List.of();
            });
    }

    /**
     * Read-only load of stored commit snapshots across multiple repositories.
     * Unlike {@link #captureReviewCommitSnapshots}, this never writes new snapshots; it
     * only returns whatever was previously captured for the review. Used when opening a
     * review (especially closed reviews) so the file list reflects the historical scope
     * rather than the current branch state.
     *
     * @param reviewId     the review identifier
     * @param repositories repositories participating in the review
     * @return future completing with a map of repository name to stored commit hashes
     *         (empty list when a repository has no snapshot)
     */
    public CompletableFuture<Map<String, List<String>>> loadStoredReviewCommitsForAllRepositories(
            String reviewId, List<Repository> repositories) {
        if (reviewId == null || reviewId.isBlank() || repositories == null || repositories.isEmpty()) {
            return CompletableFuture.completedFuture(new LinkedHashMap<>());
        }
        List<CompletableFuture<Map.Entry<String, List<String>>>> futures = repositories.stream()
            .map(repo -> loadLatestReviewCommits(reviewId, repo.getName())
                .thenApply(commits -> Map.entry(repo.getName(), commits)))
            .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                Map<String, List<String>> result = new LinkedHashMap<>();
                for (CompletableFuture<Map.Entry<String, List<String>>> future : futures) {
                    Map.Entry<String, List<String>> entry = future.join();
                    result.put(entry.getKey(), entry.getValue());
                }
                return result;
            });
    }

    private CompletableFuture<Map.Entry<String, List<String>>> captureCommitSnapshotForRepository(
            String reviewId, Repository repo, String reviewBranch, String baseBranch, String editor) {
        OrphanBranchReviewManager notesManager = branchManagerFactory.create(repo.getName());
        return resolveComparisonRefs(repo.getName(), baseBranch, reviewBranch)
            .thenCompose(resolved -> {
                String commitRange = resolved.baseRef() + ".." + resolved.reviewRef();
                return loadLatestReviewCommits(reviewId, repo.getName())
                    .thenCompose(existingCommits -> cloneManager.listCommits(repo.getName(), commitRange, 1000)
                        .thenApply(this::extractCommitHashes)
                        .thenCompose(commitHashes -> {
                            if (commitHashes.isEmpty()) {
                                if (existingCommits != null && !existingCommits.isEmpty()) {
                                    LOGGER.debug("Scoped review range {} is empty for review {} in repo {}; keeping existing stored snapshot of {} commits",
                                        commitRange, reviewId, repo.getName(), existingCommits.size());
                                } else {
                                    LOGGER.warn("No scoped review commits resolved for review {} in repo {} using range {}",
                                        reviewId, repo.getName(), commitRange);
                                }
                                List<String> fallback = existingCommits != null ? existingCommits : List.of();
                                return CompletableFuture.completedFuture(Map.entry(repo.getName(), fallback));
                            }

                            if (existingCommits != null && existingCommits.equals(commitHashes)) {
                                LOGGER.debug("Stored commit snapshot for review {} in repo {} is already correct ({} commits)",
                                    reviewId, repo.getName(), commitHashes.size());
                                return CompletableFuture.completedFuture(Map.entry(repo.getName(), commitHashes));
                            }

                            LOGGER.debug("Capturing {} scoped review commits for review {} in repo {}",
                                commitHashes.size(), reviewId, repo.getName());
                            return notesManager.writeReviewCommits(reviewId, editor, commitHashes)
                                .thenApply(ignored -> Map.entry(repo.getName(), commitHashes));
                        }));
            })
            .exceptionally(error -> {
                LOGGER.warn("Failed to capture commits for review {} in repo {}: {}",
                    reviewId, repo.getName(), error.getMessage());
                return Map.entry(repo.getName(), List.of());
            });
    }

    private CompletableFuture<List<ReviewFile>> loadFilesForRepositoryFromStoredCommits(
            String reviewId, String repositoryName, String branch, String baseBranch, List<String> commits) {
        if (commits == null || commits.isEmpty()) {
            LOGGER.warn("No stored commit snapshot for review {} in repo {}; returning empty file list to avoid showing unrelated files",
                reviewId, repositoryName);
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        String newestCommit = commits.getFirst();
        String oldestCommit = commits.getLast();
        String baseRef = oldestCommit + "^";

        LOGGER.debug("Computing review file diff for review {} in repo {} using range {}...{}",
            reviewId, repositoryName, baseRef, newestCommit);

        return cloneManager.execute(repositoryName, "rev-parse", "--verify", baseRef)
            .thenCompose(ignored -> cloneManager.listChangedFiles(repositoryName, baseRef, newestCommit))
            .thenApply(paths -> toReviewFiles(paths, repositoryName, baseBranch, branch))
            .exceptionally(error -> {
                LOGGER.warn("Failed to compute diff range {}...{} for review {} in repo {} (oldest commit may be a root): {}",
                    baseRef, newestCommit, reviewId, repositoryName, error.getMessage());
                return new ArrayList<>();
            });
    }

    private List<ReviewFile> toReviewFiles(List<String> changedFilePaths, String repositoryName,
                                           String baseBranch, String reviewBranch) {
        LOGGER.debug("Git diff returned {} changed file paths for repository '{}'",
            changedFilePaths.size(), repositoryName);
        List<ReviewFile> reviewFiles = changedFilePaths.stream()
            .map(filePath -> parseChangedFileLine(filePath, repositoryName, baseBranch, reviewBranch))
            .toList();
        LOGGER.debug("Loaded {} files for review in repository '{}'", reviewFiles.size(), repositoryName);
        return reviewFiles;
    }

    private ReviewFile parseChangedFileLine(String line, String repositoryName,
                                            String baseBranch, String reviewBranch) {
        String trimmed = line.trim();
        String[] parts = trimmed.split("\\s+", 2);

        if (parts.length >= 2) {
            return new ReviewFile(parts[1], repositoryName, parseFileChangeType(parts[0]), baseBranch, reviewBranch);
        } else {
            LOGGER.warn("Malformed file line: '{}', defaulting to MODIFIED", line);
            return new ReviewFile(trimmed, repositoryName, FileChangeType.MODIFIED, baseBranch, reviewBranch);
        }
    }

    private FileChangeType parseFileChangeType(String status) {
        if (status == null || status.isEmpty()) {
            return FileChangeType.MODIFIED;
        }
        return switch (status.toUpperCase().charAt(0)) {
            case 'A' -> FileChangeType.ADDED;
            case 'M' -> FileChangeType.MODIFIED;
            case 'D' -> FileChangeType.DELETED;
            case 'R' -> FileChangeType.RENAMED;
            default -> FileChangeType.MODIFIED;
        };
    }

    private List<String> extractCommitHashes(List<String> commitRows) {
        if (commitRows == null || commitRows.isEmpty()) {
            return List.of();
        }
        return commitRows.stream()
            .map(row -> row == null ? "" : row)
            .map(row -> row.split("\\|", 2))
            .filter(parts -> parts.length > 0 && !parts[0].isBlank())
            .map(parts -> parts[0])
            .toList();
    }

    private CompletableFuture<ResolvedRefs> resolveComparisonRefs(String repositoryName, String baseRef, String reviewRef) {
        CompletableFuture<String> resolvedBase = resolveBaseRefForRepository(repositoryName, baseRef);
        CompletableFuture<String> resolvedReview = resolveReviewRefForRepository(repositoryName, reviewRef);

        return resolvedBase.thenCombine(resolvedReview, ResolvedRefs::new)
            .thenApply(resolved -> {
                if (!Objects.equals(baseRef, resolved.baseRef()) || !Objects.equals(reviewRef, resolved.reviewRef())) {
                    LOGGER.info("Resolved comparison refs for repo {}: base '{}' -> '{}', review '{}' -> '{}'",
                        repositoryName, baseRef, resolved.baseRef(), reviewRef, resolved.reviewRef());
                }
                return resolved;
            });
    }

    private CompletableFuture<String> resolveBaseRefForRepository(String repositoryName, String baseRef) {
        List<String> baseCandidates = candidateRefs(baseRef);
        return cloneManager.getDefaultBranch(repositoryName)
            .exceptionally(_ -> "main")
            .thenCompose(defaultBranch -> {
                for (String candidate : candidateRefs(defaultBranch)) {
                    if (!baseCandidates.contains(candidate)) {
                        baseCandidates.add(candidate);
                    }
                }
                if (!baseCandidates.contains("HEAD")) {
                    baseCandidates.add("HEAD");
                }
                return resolveFirstExistingRef(repositoryName, baseCandidates, baseRef, "base");
            });
    }

    private CompletableFuture<String> resolveReviewRefForRepository(String repositoryName, String reviewRef) {
        return resolveFirstExistingRef(repositoryName, candidateRefs(reviewRef), reviewRef, "review");
    }

    private CompletableFuture<String> resolveFirstExistingRef(
            String repositoryName, List<String> candidates, String fallbackRef, String refLabel) {
        CompletableFuture<String> chain = CompletableFuture.completedFuture(null);
        for (String candidate : candidates.stream().filter(Objects::nonNull).filter(c -> !c.isBlank()).distinct().toList()) {
            chain = chain.thenCompose(found -> {
                if (found != null) return CompletableFuture.completedFuture(found);
                return refExistsInRepository(repositoryName, candidate)
                    .thenApply(exists -> exists ? candidate : null);
            });
        }
        return chain.thenApply(found -> {
            if (found != null) return found;
            LOGGER.warn("Unable to resolve {} ref '{}' in repo {}, using fallback '{}'",
                refLabel, fallbackRef, repositoryName, fallbackRef);
            return fallbackRef;
        });
    }

    private CompletableFuture<Boolean> refExistsInRepository(String repositoryName, String ref) {
        return cloneManager.execute(repositoryName, "rev-parse", "--verify", ref)
            .thenApply(_ -> true)
            .exceptionally(_ -> false);
    }

    private List<String> candidateRefs(String ref) {
        List<String> candidates = new ArrayList<>();
        if (ref == null || ref.isBlank()) return candidates;
        if (!ref.startsWith("origin/")) {
            candidates.add("origin/" + ref);
        }
        candidates.add(ref);
        return candidates;
    }

    private <T> T getLatestValue(List<StreamEntry<T>> entries) {
        if (entries == null || entries.isEmpty()) return null;
        return entries.getLast().data();
    }

    private record ResolvedRefs(String baseRef, String reviewRef) {}
}
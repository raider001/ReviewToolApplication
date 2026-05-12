package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.git.GitReviewNotesManager;
import com.kalynx.serverlessreviewtool.models.FileChangeType;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * ReviewContextManager manages the lifecycle of a ReviewContext.
 * Responsibilities:
 * - Search across all repositories for review notes
 * - Load review metadata from git notes
 * - Create and maintain current ReviewContext
 * - Notify listeners when context changes
 * Does NOT handle:
 * - Repository fetching (caller's responsibility)
 * - Commit loading (FileDiffManager's responsibility)
 * - File loading (FileDiffManager's responsibility)
 */
public class ReviewContextManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewContextManager.class);

    private ReviewContext currentReviewContext;
    private final Git git;
    private final RepositoryManager repositoryManager;
    private final Set<Consumer<ReviewContext>> listeners = new HashSet<>();

    public ReviewContextManager(Git git, RepositoryManager repositoryManager) {
        this.git = git;
        this.repositoryManager = repositoryManager;
    }

    private CompletableFuture<List<com.kalynx.serverlessreviewtool.models.ReviewComment>> loadCommentsFromKnownRepository(
            String reviewId, String primaryRepoName) {
        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, primaryRepoName);
        long listCommentsStart = System.nanoTime();

        return notesManager.listCommentIds(reviewId)
            .thenCompose(commentIds -> {
                LOGGER.info("TIMING [{}] listCommentIds (repo={}): {}ms",
                    reviewId, primaryRepoName, elapsedMs(listCommentsStart));

                if (commentIds.isEmpty()) {
                    LOGGER.debug("No comments found for review: {}", reviewId);
                    return CompletableFuture.completedFuture(new ArrayList<com.kalynx.serverlessreviewtool.models.ReviewComment>());
                }

                LOGGER.debug("Found {} comment threads for review: {}", commentIds.size(), reviewId);

                long loadCommentsStart = System.nanoTime();
                List<CompletableFuture<com.kalynx.serverlessreviewtool.models.ReviewComment>> commentFutures =
                    commentIds.stream()
                        .map(commentId -> loadSingleComment(notesManager, reviewId, commentId))
                        .toList();

                return CompletableFuture.allOf(commentFutures.toArray(new CompletableFuture[0]))
                    .thenApply(ignored -> {
                        List<com.kalynx.serverlessreviewtool.models.ReviewComment> comments =
                            commentFutures.stream()
                                .map(CompletableFuture::join)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList());
                        LOGGER.info("TIMING [{}] loadComments ({} comments, parallel): {}ms",
                            reviewId, comments.size(), elapsedMs(loadCommentsStart));
                        return comments;
                    });
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to load comments from repo {} for review {}: {}", primaryRepoName, reviewId, error.getMessage());
                return new ArrayList<>();
            });
    }

    private CompletableFuture<com.kalynx.serverlessreviewtool.models.ReviewComment> loadSingleComment(
            GitReviewNotesManager notesManager, String reviewId, String commentId) {

        CompletableFuture<List<StreamEntry<GitReviewNotesManager.CommentMetadata>>> metadataFuture =
            notesManager.readCommentMetadata(reviewId, commentId);
        CompletableFuture<List<StreamEntry<GitReviewNotesManager.CommentTextData>>> textFuture =
            notesManager.readCommentText(reviewId, commentId);
        CompletableFuture<List<StreamEntry<GitReviewNotesManager.CommentStatusData>>> statusFuture =
            notesManager.readCommentStatus(reviewId, commentId);

        return CompletableFuture.allOf(metadataFuture, textFuture, statusFuture)
            .thenApply(ignored -> {
                List<StreamEntry<GitReviewNotesManager.CommentMetadata>> metadata = metadataFuture.join();
                List<StreamEntry<GitReviewNotesManager.CommentTextData>> textEntries = textFuture.join();
                List<StreamEntry<GitReviewNotesManager.CommentStatusData>> statusEntries = statusFuture.join();

                if (metadata.isEmpty() || textEntries.isEmpty()) {
                    return null;
                }

                StreamEntry<GitReviewNotesManager.CommentMetadata> latestMetadata = metadata.getLast();
                StreamEntry<GitReviewNotesManager.CommentTextData> firstText = textEntries.getFirst();

                GitReviewNotesManager.CommentMetadata metaData = latestMetadata.data();
                GitReviewNotesManager.CommentTextData textData = firstText.data();

                com.kalynx.serverlessreviewtool.models.ReviewComment comment =
                    new com.kalynx.serverlessreviewtool.models.ReviewComment(
                        commentId,
                        metaData.file(),
                        metaData.line(),
                        firstText.editor(),
                        textData.text(),
                        firstText.timestamp().toString(),
                        textData.replyTo(),
                        "review".equals(textData.type())
                    );

                if (!statusEntries.isEmpty()) {
                    StreamEntry<GitReviewNotesManager.CommentStatusData> latestStatus = statusEntries.getLast();
                    GitReviewNotesManager.CommentStatusData statusData = latestStatus.data();

                    if (statusData.needsResolution() != null) {
                        comment.setNeedsResolution(statusData.needsResolution());
                    }

                    if (statusData.resolved() != null) {
                        if (statusData.resolved()) {
                            comment.markResolved(latestStatus.editor());
                        } else {
                            comment.markUnresolved();
                        }
                    }
                }

                return comment;
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to load comment {}: {}", commentId, error.getMessage());
                return null;
            });
    }

    /**
     * Save a comment to git notes.
     *
     * @param reviewId the review identifier
     * @param comment the comment to save
     * @return future that completes when comment is saved
     */
    public CompletableFuture<Void> saveComment(String reviewId, com.kalynx.serverlessreviewtool.models.ReviewComment comment) {
        if (reviewId == null || reviewId.isEmpty() || comment == null) {
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.debug("Saving comment for review: {} (id: {})", reviewId, comment.getId());

        ReviewContext context = currentReviewContext;
        if (context == null || context.repositories.isEmpty()) {
            LOGGER.warn("No review context or repositories, cannot save comment");
            return CompletableFuture.completedFuture(null);
        }

        Repository primaryRepo = context.repositories.getFirst();
        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, primaryRepo.getName());

        String commentType = comment.needsResolution() ? "review" : "comment";

        CompletableFuture<Void> saveFuture = notesManager.writeCommentMetadata(
            reviewId, comment.getId(), comment.getAuthor(),
            comment.getFilePath(), comment.getLineNumber(), comment.getLineNumber(), null
        ).thenCompose(ignored ->
            notesManager.writeCommentText(
                reviewId, comment.getId(), comment.getAuthor(),
                comment.getText(), comment.getParentId(), commentType
            )
        );

        if (comment.needsResolution() || comment.isResolved()) {
            saveFuture = saveFuture.thenCompose(ignored ->
                notesManager.writeCommentStatus(
                    reviewId, comment.getId(), comment.getAuthor(),
                    comment.needsResolution(), comment.isResolved()
                )
            );
        }

        return saveFuture
            .thenRun(() -> LOGGER.debug("Comment saved successfully for review: {} (id: {})", reviewId, comment.getId()))
            .exceptionally(error -> {
                LOGGER.error("Failed to save comment for review: {} (id: {})", reviewId, comment.getId(), error);
                return null;
            });
    }

    /**
     * Save all comments for a review to git notes.
     * This is useful when multiple comments have been updated (e.g., resolution status changes).
     * Uses a single atomic git operation to avoid race conditions.
     *
     * @param reviewId the review identifier
     * @param comments the comments to save
     * @return future that completes when all comments are saved
     */
    public CompletableFuture<Void> saveAllComments(String reviewId, List<com.kalynx.serverlessreviewtool.models.ReviewComment> comments) {
        if (reviewId == null || reviewId.isEmpty() || comments == null || comments.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.debug("Batch saving {} comments for review: {}", comments.size(), reviewId);

        List<CompletableFuture<Void>> saveFutures = comments.stream()
            .map(comment -> saveComment(reviewId, comment))
            .toList();

        return CompletableFuture.allOf(saveFutures.toArray(new CompletableFuture[0]))
            .thenRun(() -> LOGGER.debug("All {} comments batch-saved successfully for review: {}", comments.size(), reviewId))
            .exceptionally(error -> {
                LOGGER.error("Failed to batch-save comments for review: {}", reviewId, error);
                return null;
            });
    }

    /**
     * Load review metadata by searching across all known repositories.
     * Uses an optimized batch read operation to minimize Git operations.
     * Does NOT handle repository syncing or file/commit loading.
     *
     * @param reviewId the review identifier
     * @return future that completes when ReviewContext is created and set
     */
    public CompletableFuture<ReviewContext> loadReviewMetadata(String reviewId) {
        if (reviewId == null || reviewId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.debug("Searching for review {} across all repositories", reviewId);
        List<Repository> allRepositories = repositoryManager.getRepositories();

        if (allRepositories.isEmpty()) {
            LOGGER.warn("No repositories configured, cannot load review {}", reviewId);
            return CompletableFuture.completedFuture(null);
        }

        return findAllRepositoriesContainingReview(reviewId, allRepositories)
            .thenCompose(reviewRepositories -> {
                if (reviewRepositories.isEmpty()) {
                    LOGGER.warn("Review {} not found in any repository", reviewId);
                    return CompletableFuture.completedFuture(null);
                }
                String primaryRepoName = reviewRepositories.getFirst().getName();
                LOGGER.debug("Found review {} in repository: {}", reviewId, primaryRepoName);
                return loadReviewFromRepositories(reviewId, primaryRepoName, reviewRepositories, true);
            });
    }

    /**
     * Load review metadata without reloading comments.
     *
     * @param reviewId the review identifier
     * @return future that completes when ReviewContext metadata is refreshed
     */
    public CompletableFuture<ReviewContext> loadReviewMetadataOnly(String reviewId) {
        if (reviewId == null || reviewId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.debug("Searching for review {} across all repositories (metadata only)", reviewId);
        List<Repository> allRepositories = repositoryManager.getRepositories();

        if (allRepositories.isEmpty()) {
            LOGGER.warn("No repositories configured, cannot load review {}", reviewId);
            return CompletableFuture.completedFuture(null);
        }

        return findAllRepositoriesContainingReview(reviewId, allRepositories)
            .thenCompose(reviewRepositories -> {
                if (reviewRepositories.isEmpty()) {
                    LOGGER.warn("Review {} not found in any repository", reviewId);
                    return CompletableFuture.completedFuture(null);
                }
                String primaryRepoName = reviewRepositories.getFirst().getName();
                LOGGER.debug("Found review {} in repository: {}", reviewId, primaryRepoName);
                return loadReviewFromRepositories(reviewId, primaryRepoName, reviewRepositories, false);
            });
    }

    /**
     * Load review metadata from specific repositories.
     * Skips repository discovery since the provided repositories are already validated.
     * Does NOT handle repository syncing or file/commit loading.
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @return future that completes when ReviewContext is created and set
     */
    public CompletableFuture<ReviewContext> loadReviewMetadata(String reviewId, List<String> repositoryNames) {
        if (reviewId == null || reviewId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        if (repositoryNames == null || repositoryNames.isEmpty()) {
            LOGGER.debug("No specific repositories provided, searching all repositories for review {}", reviewId);
            return loadReviewMetadata(reviewId);
        }

        List<Repository> specificRepositories = resolveRepositories(repositoryNames);

        if (specificRepositories.isEmpty()) {
            LOGGER.warn("None of the specified repositories found, falling back to search all repositories");
            return loadReviewMetadata(reviewId);
        }

        return resolvePrimaryRepositoryForReview(reviewId, specificRepositories)
            .thenCompose(primaryRepoName -> {
                LOGGER.debug("Loading review {} from {} specified repositories, primary={} (resolved from metadata)",
                    reviewId, specificRepositories.size(), primaryRepoName);
                return loadReviewFromRepositories(reviewId, primaryRepoName, specificRepositories, true);
            });
    }

    /**
     * Load review metadata from specific repositories without reloading comments.
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @return future that completes when ReviewContext metadata is refreshed
     */
    public CompletableFuture<ReviewContext> loadReviewMetadataOnly(String reviewId, List<String> repositoryNames) {
        if (reviewId == null || reviewId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        if (repositoryNames == null || repositoryNames.isEmpty()) {
            LOGGER.debug("No specific repositories provided, searching all repositories for review {} (metadata only)", reviewId);
            return loadReviewMetadataOnly(reviewId);
        }

        List<Repository> specificRepositories = resolveRepositories(repositoryNames);

        if (specificRepositories.isEmpty()) {
            LOGGER.warn("None of the specified repositories found, falling back to search all repositories");
            return loadReviewMetadataOnly(reviewId);
        }

        return resolvePrimaryRepositoryForReview(reviewId, specificRepositories)
            .thenCompose(primaryRepoName -> {
                LOGGER.debug("Loading review {} from {} specified repositories, primary={} (metadata only, resolved from metadata)",
                    reviewId, specificRepositories.size(), primaryRepoName);
                return loadReviewFromRepositories(reviewId, primaryRepoName, specificRepositories, false);
            });
    }

    /**
     * Load review metadata from specific repositories with a pre-known primary repository.
     * Skips primary repository resolution, going directly to metadata loading.
     * Use this when opening a review from a cached ReviewItem that already has primaryRepository set.
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @param knownPrimaryRepoName primary repository name from the cached ReviewItem
     * @return future that completes when ReviewContext is created and set
     */
    public CompletableFuture<ReviewContext> loadReviewMetadata(String reviewId, List<String> repositoryNames, String knownPrimaryRepoName) {
        if (reviewId == null || reviewId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        if (repositoryNames == null || repositoryNames.isEmpty() || knownPrimaryRepoName == null || knownPrimaryRepoName.isBlank()) {
            return loadReviewMetadata(reviewId, repositoryNames);
        }

        List<Repository> specificRepositories = resolveRepositories(repositoryNames);

        if (specificRepositories.isEmpty()) {
            LOGGER.warn("None of the specified repositories found for review {}, falling back to search all", reviewId);
            return loadReviewMetadata(reviewId);
        }

        LOGGER.debug("Loading review {} from {} specified repositories, primary={} (from cached ReviewItem, skipping resolution)",
            reviewId, specificRepositories.size(), knownPrimaryRepoName);
        return loadReviewFromRepositories(reviewId, knownPrimaryRepoName, specificRepositories, true);
    }

    /**
     * Load review metadata from specific repositories with a pre-known primary repository, without reloading comments.
     * Skips primary repository resolution, going directly to metadata loading.
     * Use this when refreshing a review that is already open and its primary repository is known.
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @param knownPrimaryRepoName primary repository name
     * @return future that completes when ReviewContext metadata is refreshed
     */
    public CompletableFuture<ReviewContext> loadReviewMetadataOnly(String reviewId, List<String> repositoryNames, String knownPrimaryRepoName) {
        if (reviewId == null || reviewId.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        if (repositoryNames == null || repositoryNames.isEmpty() || knownPrimaryRepoName == null || knownPrimaryRepoName.isBlank()) {
            return loadReviewMetadataOnly(reviewId, repositoryNames);
        }

        List<Repository> specificRepositories = resolveRepositories(repositoryNames);

        if (specificRepositories.isEmpty()) {
            LOGGER.warn("None of the specified repositories found for review {}, falling back to search all", reviewId);
            return loadReviewMetadataOnly(reviewId);
        }

        LOGGER.debug("Loading review {} from {} specified repositories, primary={} (from context, skipping resolution, metadata only)",
            reviewId, specificRepositories.size(), knownPrimaryRepoName);
        return loadReviewFromRepositories(reviewId, knownPrimaryRepoName, specificRepositories, false);
    }

    private CompletableFuture<String> resolvePrimaryRepositoryForReview(String reviewId, List<Repository> candidateRepositories) {
        if (candidateRepositories == null || candidateRepositories.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<PrimaryRepositoryCandidate>> futures = candidateRepositories.stream()
            .map(repository -> {
                GitReviewNotesManager notesManager = new GitReviewNotesManager(git, repository.getName());
                return notesManager.readAllMetadata(reviewId)
                    .thenApply(metadata -> buildPrimaryRepositoryCandidate(repository, metadata))
                    .exceptionally(_ -> new PrimaryRepositoryCandidate(repository.getName(), null, 0L, false));
            })
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> Objects.requireNonNull(futures.stream()
                    .map(CompletableFuture::join)
                    .filter(PrimaryRepositoryCandidate::isExplicitPrimary)
                    .max(Comparator.comparingLong(PrimaryRepositoryCandidate::timestamp)
                            .thenComparing(PrimaryRepositoryCandidate::repositoryName))
                    .map(PrimaryRepositoryCandidate::claimedPrimaryRepository)
                    .orElse(null)))
            .thenCompose(explicitPrimary -> {
                if (!explicitPrimary.isBlank()) {
                    return CompletableFuture.completedFuture(explicitPrimary);
                }
                return findAllRepositoriesContainingReview(reviewId, candidateRepositories)
                    .thenApply(foundRepositories -> {
                        if (!foundRepositories.isEmpty()) {
                            return foundRepositories.getFirst().getName();
                        }
                        String fallback = candidateRepositories.getFirst().getName();
                        LOGGER.warn("Could not resolve primary repository for review {} from metadata; falling back to {}",
                            reviewId, fallback);
                        return fallback;
                    });
            });
    }

    private PrimaryRepositoryCandidate buildPrimaryRepositoryCandidate(
            Repository repository,
            GitReviewNotesManager.ReviewMetadata metadata) {
        String rawValue = getLatestValue(metadata.primaryRepository());
        if (rawValue == null || rawValue.isBlank() || "false".equalsIgnoreCase(rawValue.trim())) {
            return new PrimaryRepositoryCandidate(repository.getName(), null, 0L, false);
        }

        String claimedPrimary = "true".equalsIgnoreCase(rawValue.trim())
            ? repository.getName()
            : rawValue;
        long timestamp = getLatestTimestamp(metadata.primaryRepository());
        return new PrimaryRepositoryCandidate(repository.getName(), claimedPrimary, timestamp, true);
    }

    private List<Repository> resolveRepositories(List<String> repositoryNames) {
        List<Repository> resolved = new ArrayList<>();
        for (String repoName : repositoryNames) {
            Repository repo = repositoryManager.getRepositoryByName(repoName);
            if (repo != null) {
                resolved.add(repo);
            } else {
                LOGGER.warn("Repository '{}' not found in RepositoryManager", repoName);
            }
        }
        return resolved;
    }

    private CompletableFuture<List<Repository>> findAllRepositoriesContainingReview(
            String reviewId, List<Repository> candidateRepositories) {
        long start = System.nanoTime();
        List<CompletableFuture<Repository>> futures = candidateRepositories.stream()
            .map(repo -> {
                GitReviewNotesManager notesManager = new GitReviewNotesManager(git, repo.getName());
                return notesManager.readTitles(reviewId)
                    .thenApply(titles -> (titles != null && !titles.isEmpty()) ? repo : null)
                    .exceptionally(ignored -> null);
            })
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                List<Repository> found = futures.stream()
                    .map(CompletableFuture::join)
                    .filter(Objects::nonNull)
                    .toList();
                LOGGER.info("TIMING [{}] findAllRepositoriesContainingReview ({} candidates, {} found, parallel): {}ms",
                    reviewId, candidateRepositories.size(), found.size(), elapsedMs(start));
                return found;
            });
    }

    private CompletableFuture<ReviewContext> loadReviewFromRepositories(
            String reviewId,
            String primaryRepoName,
            List<Repository> reviewRepositories,
            boolean includeComments) {
        List<Repository> orderedRepositories = orderRepositoriesWithPrimaryFirst(reviewRepositories, primaryRepoName);
        LOGGER.debug("Loading review metadata: reviewId={}, primaryRepo={}, repos={}",
            reviewId, primaryRepoName, orderedRepositories.size());

        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, primaryRepoName);
        List<com.kalynx.serverlessreviewtool.models.ReviewComment> existingComments =
            currentReviewContext != null && reviewId.equals(currentReviewContext.reviewId)
                ? new ArrayList<>(currentReviewContext.getComments())
                : new ArrayList<>();

        CompletableFuture<List<com.kalynx.serverlessreviewtool.models.ReviewComment>> commentsFuture = includeComments
            ? loadCommentsFromKnownRepository(reviewId, primaryRepoName)
            : CompletableFuture.completedFuture(existingComments);

        long readAllMetadataStart = System.nanoTime();
        return notesManager.readAllMetadata(reviewId)
            .thenCombine(commentsFuture, (metadata, comments) -> {
                LOGGER.info("TIMING [{}] readAllMetadata (repo={}): {}ms",
                    reviewId, primaryRepoName, elapsedMs(readAllMetadataStart));
                String title = getLatestValue(metadata.titles());
                String description = getLatestValue(metadata.descriptions());
                String author = getLatestValue(metadata.authors());
                String statusStr = getLatestValue(metadata.statuses());
                String branch = getLatestValue(metadata.branches());
                String baseBranch = getLatestValue(metadata.baseBranches());
                boolean hasClosedHistory = metadata.statuses().stream()
                    .map(StreamEntry::data)
                    .filter(Objects::nonNull)
                    .map(this::parseStatus)
                    .anyMatch(this::isClosedStatus);

                List<StreamEntry<com.kalynx.serverlessreviewtool.models.review.ReviewerData>> latestReviewerEntries = metadata.reviewers().stream()
                    .collect(Collectors.groupingBy(
                        StreamEntry::editor,
                        Collectors.maxBy(Comparator.comparing(StreamEntry::timestamp))
                    ))
                    .values().stream()
                    .flatMap(java.util.Optional::stream)
                    .filter(entry -> !isLeftReviewerStatus(entry.data().getStatus()))
                    .toList();

                String resolvedTitle = title != null ? title : "Untitled Review";
                String resolvedDescription = description != null ? description : "";
                String resolvedAuthor = author != null ? author : "Unknown";
                String resolvedStatus = statusStr != null ? statusStr : "OPEN";

                LOGGER.debug("Loaded review metadata: reviewId={}, title={}, author={}, reviewers={}",
                    reviewId, resolvedTitle, resolvedAuthor, latestReviewerEntries.size());
                LOGGER.debug("Review {} found in {} repositories", reviewId, reviewRepositories.size());

                ReviewStatus status = parseStatus(resolvedStatus);
                List<ReviewerInfo> reviewers = latestReviewerEntries.stream()
                    .map(entry -> {
                        ReviewerInfo reviewerInfo = new ReviewerInfo(entry.editor());
                        reviewerInfo.setStatus(parseReviewerStatus(entry.data().getStatus()));
                        return reviewerInfo;
                    })
                    .toList();

                if (includeComments) {
                    LOGGER.debug("Loaded {} comments for review {}", comments.size(), reviewId);
                }

                ReviewContext reviewContext = new ReviewContext(
                    reviewId, resolvedTitle, resolvedDescription, resolvedAuthor,
                    status, reviewers, orderedRepositories, comments,
                    branch, baseBranch, hasClosedHistory
                );
                setReviewContext(reviewContext);
                return reviewContext;
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to load review metadata for {}: {}", reviewId, error.getMessage(), error);
                return null;
            });
    }

    private List<Repository> orderRepositoriesWithPrimaryFirst(List<Repository> repositories, String primaryRepoName) {
        if (repositories == null || repositories.isEmpty() || primaryRepoName == null || primaryRepoName.isBlank()) {
            return repositories != null ? new ArrayList<>(repositories) : new ArrayList<>();
        }

        List<Repository> ordered = new ArrayList<>();
        repositories.stream()
            .filter(Objects::nonNull)
            .filter(repository -> primaryRepoName.equals(repository.getName()))
            .findFirst()
            .ifPresent(ordered::add);

        repositories.stream()
            .filter(Objects::nonNull)
            .filter(repository -> !primaryRepoName.equals(repository.getName()))
            .forEach(ordered::add);

        return ordered;
    }

    /**
     * Load files changed in a review by comparing branch against base branch.
     * Returns a list of ReviewFile objects representing all changed files in the review.
     *
     * @param repositoryName the repository containing the review
     * @param reviewBranch the branch being reviewed (e.g., "feature/my-feature" or "origin/feature/my-feature")
     * @param baseBranch the base branch to compare against (e.g., "main" or "origin/main")
     * @return future that completes with list of ReviewFile objects
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
                            return git.listChangedFiles(repositoryName, resolved.baseRef(), resolved.reviewRef())
                                .thenApply(paths -> toReviewFiles(paths, repositoryName, baseBranch, reviewBranch));
                        });
                }

                return git.listChangedFiles(repositoryName, resolved.baseRef(), resolved.reviewRef())
                    .thenApply(paths -> toReviewFiles(paths, repositoryName, baseBranch, reviewBranch));
            })
            .exceptionally(error -> {
                LOGGER.warn("Failed to load files for review in repository '{}': {}",
                    repositoryName, error.getMessage());
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
            String status = parts[0];
            String path = parts[1];
            FileChangeType changeType = parseFileChangeType(status);
            return new ReviewFile(path, repositoryName, changeType, baseBranch, reviewBranch);
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

    /**
     * Load files changed in a review by comparing the already-known branches across all given repositories.
     * Uses branch and baseBranch from the loaded ReviewContext, avoiding a redundant metadata fetch.
     *
     * @param repositories list of repositories containing the review
     * @param branch the review branch (e.g., "feature/my-feature")
     * @param baseBranch the base branch to compare against (e.g., "origin/main")
     * @return future that completes with list of ReviewFile objects across all repositories
     */
    public CompletableFuture<List<ReviewFile>> loadFilesFromReviewCommits(List<Repository> repositories, String branch, String baseBranch) {
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


    public CompletableFuture<List<ReviewFile>> loadFilesFromStoredReviewCommits(
            String reviewId,
            List<Repository> repositories,
            String branch,
            String baseBranch,
            Map<String, List<String>> commitsByRepository) {
        if (reviewId == null || reviewId.isBlank() || repositories == null || repositories.isEmpty()) {
            return CompletableFuture.completedFuture(new ArrayList<>());
        }

        Map<String, List<String>> safeCommitsByRepository =
            commitsByRepository != null ? commitsByRepository : Map.of();

        LOGGER.debug("Loading files from preloaded stored commit snapshots for review {} across {} repositories",
            reviewId, repositories.size());

        List<CompletableFuture<List<ReviewFile>>> fileFutures = repositories.stream()
            .map(repo -> loadFilesForRepositoryFromStoredCommits(
                reviewId,
                repo.getName(),
                branch,
                baseBranch,
                safeCommitsByRepository.get(repo.getName())))
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

    public CompletableFuture<List<String>> loadLatestReviewCommits(String reviewId, String repositoryName) {
        if (reviewId == null || reviewId.isBlank() || repositoryName == null || repositoryName.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, repositoryName);
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
     * Capture commit snapshots for each repository participating in a review.
     * Used when transitioning a review to a terminal state so reopened reviews can replay historical changes.
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
            .map(repo -> {
                GitReviewNotesManager notesManager = new GitReviewNotesManager(git, repo.getName());
                return resolveComparisonRefs(repo.getName(), baseBranch, reviewBranch)
                    .thenCompose(resolved -> {
                        String commitRange = resolved.baseRef() + ".." + resolved.reviewRef();
                        return loadLatestReviewCommits(reviewId, repo.getName())
                            .thenCompose(existingCommits -> git.listCommits(repo.getName(), commitRange, 1000)
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
                                        List<String> fallbackCommits = existingCommits != null ? existingCommits : List.of();
                                        return CompletableFuture.completedFuture(Map.entry(repo.getName(), fallbackCommits));
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
            })
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                Map<String, List<String>> commitsByRepository = new LinkedHashMap<>();
                for (CompletableFuture<Map.Entry<String, List<String>>> future : futures) {
                    Map.Entry<String, List<String>> entry = future.join();
                    commitsByRepository.put(entry.getKey(), entry.getValue());
                }
                return commitsByRepository;
            });
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

    private CompletableFuture<List<ReviewFile>> loadFilesForRepositoryFromStoredCommits(
            String reviewId,
            String repositoryName,
            String branch,
            String baseBranch,
            List<String> commits) {
        if (commits == null || commits.isEmpty()) {
            LOGGER.warn("No stored commit snapshot for review {} in repo {}; falling back to branch diff",
                reviewId, repositoryName);
            return loadFilesForReview(repositoryName, branch, baseBranch);
        }

        List<CompletableFuture<List<String>>> perCommitFutures = commits.stream()
            .map(commitHash -> loadChangedFilesForCommit(repositoryName, commitHash))
            .toList();

        return CompletableFuture.allOf(perCommitFutures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> {
                Map<String, String> statusByPath = new LinkedHashMap<>();
                for (CompletableFuture<List<String>> future : perCommitFutures) {
                    for (String line : future.join()) {
                        String[] parts = line.trim().split("\\s+", 2);
                        if (parts.length < 2) {
                            continue;
                        }
                        String status = parts[0];
                        String path = parts[1];
                        statusByPath.put(path, status);
                    }
                }

                List<ReviewFile> files = new ArrayList<>();
                for (Map.Entry<String, String> entry : statusByPath.entrySet()) {
                    files.add(parseChangedFileLine(entry.getValue() + " " + entry.getKey(),
                        repositoryName, baseBranch, branch));
                }
                return files;
            });
    }

    private CompletableFuture<List<String>> loadChangedFilesForCommit(String repositoryName, String commitHash) {
        if (commitHash == null || commitHash.isBlank()) {
            return CompletableFuture.completedFuture(List.of());
        }
        return git.executeAsync(repositoryName,
                "show", "--name-status", "--pretty=format:", "--root", commitHash)
            .thenApply(output -> Arrays.stream(output.split("\\R"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList())
            .exceptionally(error -> {
                LOGGER.warn("Failed to load changed files for commit {} in repo {}: {}",
                    commitHash, repositoryName, error.getMessage());
                return List.of();
            });
    }

    private CompletableFuture<ResolvedRefs> resolveComparisonRefs(String repositoryName, String baseRef, String reviewRef) {
        CompletableFuture<String> resolvedBaseFuture = resolveBaseRefForRepository(repositoryName, baseRef);
        CompletableFuture<String> resolvedReviewFuture = resolveReviewRefForRepository(repositoryName, reviewRef);

        return resolvedBaseFuture.thenCombine(resolvedReviewFuture, ResolvedRefs::new)
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
        return git.getDefaultBranch(repositoryName)
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
        List<String> reviewCandidates = candidateRefs(reviewRef);
        return resolveFirstExistingRef(repositoryName, reviewCandidates, reviewRef, "review");
    }

    private CompletableFuture<String> resolveFirstExistingRef(
            String repositoryName,
            List<String> candidates,
            String fallbackRef,
            String refLabel) {
        CompletableFuture<String> chain = CompletableFuture.completedFuture(null);
        for (String candidate : candidates.stream().filter(Objects::nonNull).filter(c -> !c.isBlank()).distinct().toList()) {
            chain = chain.thenCompose(found -> {
                if (found != null) {
                    return CompletableFuture.completedFuture(found);
                }
                return refExistsInRepository(repositoryName, candidate)
                    .thenApply(exists -> exists ? candidate : null);
            });
        }

        return chain.thenApply(found -> {
            if (found != null) {
                return found;
            }
            LOGGER.warn("Unable to resolve {} ref '{}' in repo {}, using fallback '{}'",
                refLabel, fallbackRef, repositoryName, fallbackRef);
            return fallbackRef;
        });
    }

    private CompletableFuture<Boolean> refExistsInRepository(String repositoryName, String ref) {
        return git.executeAsync(repositoryName, "rev-parse", "--verify", ref)
            .thenApply(_ -> true)
            .exceptionally(_ -> false);
    }

    private List<String> candidateRefs(String ref) {
        List<String> candidates = new ArrayList<>();
        if (ref == null || ref.isBlank()) {
            return candidates;
        }
        candidates.add(ref);
        if (!ref.startsWith("origin/")) {
            candidates.add("origin/" + ref);
        }
        return candidates;
    }

    private long getLatestTimestamp(List<? extends StreamEntry<?>> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        return entries.stream()
            .filter(Objects::nonNull)
            .filter(entry -> entry.timestamp() != null)
            .mapToLong(entry -> entry.timestamp().toEpochMilli())
            .max()
            .orElse(0);
    }

    private record ResolvedRefs(String baseRef, String reviewRef) {}

    private record PrimaryRepositoryCandidate(
        String repositoryName,
        String claimedPrimaryRepository,
        long timestamp,
        boolean isExplicitPrimary) {}

    /**
     * Set the current ReviewContext and notify all listeners.
     */
    public void setReviewContext(ReviewContext reviewContext) {
        this.currentReviewContext = reviewContext;
        LOGGER.debug("ReviewContext set: {}", reviewContext != null ? reviewContext.getReviewId() : "null");
        notifyListeners();
    }

    /**
     * Save review metadata to git notes using a parallel batch write.
     * All metadata streams (title, description, author, status, reviewers) are fetched in parallel,
     * written locally, and pushed in a single git push command — replacing the previous sequential
     * per-field fetch-write-push chain.
     *
     * @param reviewContext the updated review context to persist
     * @return future that completes when all metadata has been written and pushed
     */
    public CompletableFuture<Void> saveReviewMetadata(ReviewContext reviewContext) {
        if (reviewContext == null || reviewContext.reviewId == null || reviewContext.reviewId.isEmpty()) {
            LOGGER.warn("Cannot save review - invalid review context");
            return CompletableFuture.completedFuture(null);
        }

        LOGGER.debug("Saving review metadata for review: {}", reviewContext.reviewId);

        if (reviewContext.repositories.isEmpty()) {
            LOGGER.warn("No repositories in review context, cannot save");
            return CompletableFuture.completedFuture(null);
        }

        Repository primaryRepo = reviewContext.repositories.getFirst();
        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, primaryRepo.getName());
        String editor = reviewContext.author != null ? reviewContext.author : "system";

        List<Map.Entry<String, com.kalynx.serverlessreviewtool.models.review.ReviewerData>> reviewerEntries = new ArrayList<>();

        for (ReviewerInfo reviewer : reviewContext.reviewers) {
            reviewerEntries.add(Map.entry(
                reviewer.getName(),
                new com.kalynx.serverlessreviewtool.models.review.ReviewerData(
                    reviewer.getStatus().name().toLowerCase(), "")));
        }

        if (currentReviewContext != null) {
            Set<String> newReviewerNames = reviewContext.reviewers.stream()
                .map(ReviewerInfo::getName)
                .collect(Collectors.toSet());

            for (ReviewerInfo previousReviewer : currentReviewContext.reviewers) {
                if (!newReviewerNames.contains(previousReviewer.getName())) {
                    LOGGER.debug("Writing LEFT status for removed reviewer {} on review {}",
                        previousReviewer.getName(), reviewContext.reviewId);
                    reviewerEntries.add(Map.entry(
                        previousReviewer.getName(),
                        new com.kalynx.serverlessreviewtool.models.review.ReviewerData("left", "")));
                }
            }
        }

        return notesManager.saveAllMetadataBatch(
                reviewContext.reviewId,
                editor,
                reviewContext.title,
                reviewContext.summary,
                reviewContext.author,
                reviewContext.status.name(),
                reviewerEntries)
            .thenRun(() -> {
                LOGGER.debug("Review metadata saved successfully for review: {}", reviewContext.reviewId);
                setReviewContext(reviewContext);
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to save review metadata for review: " + reviewContext.reviewId, error);
                throw new RuntimeException("Failed to save review metadata", error);
            });
    }

    /**
     * Get the current ReviewContext.
     */
    public ReviewContext getReviewContext() {
        return currentReviewContext;
    }

    /**
     * Add a listener for ReviewContext changes.
     * Listener is immediately called with current context.
     */
    public void addListener(Consumer<ReviewContext> listener) {
        listeners.add(listener);
        listener.accept(currentReviewContext);
    }

    public CompletableFuture<Void> addReviewer(String reviewId, String reviewerName, List<String> repositoryNames) {
        if (repositoryNames == null || repositoryNames.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Repository names cannot be null or empty"));
        }

        String primaryRepoName = repositoryNames.getFirst();
        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, primaryRepoName);

        com.kalynx.serverlessreviewtool.models.review.ReviewerData reviewerData =
            new com.kalynx.serverlessreviewtool.models.review.ReviewerData("REVIEWING", null);

        LOGGER.debug("Adding reviewer {} to review {} in repository {}", reviewerName, reviewId, primaryRepoName);

        return notesManager.writeReviewer(reviewId, reviewerName, reviewerData);
    }

    /**
     * Update a reviewer's decision status for a review.
     *
     * @param reviewId the review identifier
     * @param reviewerName reviewer/editor name
     * @param reviewerStatus target decision status
     * @param repositoryNames repositories containing the review notes
     * @return future completing when reviewer status is written
     */
    public CompletableFuture<Void> updateReviewerStatus(
            String reviewId,
            String reviewerName,
            ReviewerStatus reviewerStatus,
            List<String> repositoryNames) {
        if (repositoryNames == null || repositoryNames.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Repository names cannot be null or empty"));
        }
        if (reviewerStatus == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Reviewer status cannot be null"));
        }

        String primaryRepoName = repositoryNames.getFirst();
        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, primaryRepoName);
        String statusValue = mapReviewerStatus(reviewerStatus);

        com.kalynx.serverlessreviewtool.models.review.ReviewerData reviewerData =
            new com.kalynx.serverlessreviewtool.models.review.ReviewerData(statusValue, null);

        LOGGER.debug("Updating reviewer {} status to {} for review {} in repository {}",
            reviewerName, reviewerStatus, reviewId, primaryRepoName);

        return notesManager.writeReviewer(reviewId, reviewerName, reviewerData);
    }

    public CompletableFuture<Void> removeReviewer(String reviewId, String reviewerName, List<String> repositoryNames) {
        if (repositoryNames == null || repositoryNames.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Repository names cannot be null or empty"));
        }

        String primaryRepoName = repositoryNames.getFirst();
        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, primaryRepoName);

        com.kalynx.serverlessreviewtool.models.review.ReviewerData reviewerData =
            new com.kalynx.serverlessreviewtool.models.review.ReviewerData("LEFT", null);

        LOGGER.debug("Removing reviewer {} from review {} in repository {}", reviewerName, reviewId, primaryRepoName);

        return notesManager.writeReviewer(reviewId, reviewerName, reviewerData);
    }

    /**
     * Remove a listener.
     */
    @SuppressWarnings("unused")
    public void removeListener(Consumer<ReviewContext> listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        LOGGER.debug("Notifying {} listeners of ReviewContext change", listeners.size());
        listeners.forEach(listener -> listener.accept(currentReviewContext));
    }

    private <T> T getLatestValue(List<StreamEntry<T>> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.getLast().data();
    }

    private ReviewStatus parseStatus(String statusStr) {
        if (statusStr == null) {
            return ReviewStatus.OPEN;
        }
        try {
            return ReviewStatus.valueOf(statusStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Unknown review status: {}, defaulting to OPEN", statusStr);
            return ReviewStatus.OPEN;
        }
    }

    private boolean isClosedStatus(ReviewStatus status) {
        return status == ReviewStatus.COMPLETED || status == ReviewStatus.CANCELLED;
    }

    private boolean isLeftReviewerStatus(String status) {
        return status != null && "left".equalsIgnoreCase(status.trim());
    }

    private ReviewerStatus parseReviewerStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return ReviewerStatus.REVIEWING;
        }

        String normalized = status.trim().toLowerCase().replace(' ', '_');
        return switch (normalized) {
            case "approved" -> ReviewerStatus.APPROVED;
            case "changes_requested", "rejected" -> ReviewerStatus.CHANGES_REQUESTED;
            case "reviewing", "pending" -> ReviewerStatus.REVIEWING;
            default -> {
                LOGGER.warn("Unknown reviewer status '{}', defaulting to REVIEWING", status);
                yield ReviewerStatus.REVIEWING;
            }
        };
    }

    private String mapReviewerStatus(ReviewerStatus reviewerStatus) {
        return switch (reviewerStatus) {
            case APPROVED -> "approved";
            case CHANGES_REQUESTED -> "changes_requested";
            case REVIEWING -> "reviewing";
        };
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }
}


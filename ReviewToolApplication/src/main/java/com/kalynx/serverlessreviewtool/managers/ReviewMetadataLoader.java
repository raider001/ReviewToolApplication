package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.GitReviewNotesManager;
import com.kalynx.serverlessreviewtool.git.ReviewNotesManagerFactory;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Loads review metadata from git notes across one or more repositories.
 * Handles repository discovery, primary repository resolution, metadata parsing,
 * and ReviewContext assembly. Does not mutate application state — callers are
 * responsible for acting on the returned ReviewContext.
 */
public class ReviewMetadataLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewMetadataLoader.class);

    private final ReviewNotesManagerFactory notesManagerFactory;
    private final RepositoryManager repositoryManager;
    private final ReviewCommentManager commentManager;
    private final Supplier<ReviewContext> contextSupplier;

    /**
     * Constructs a ReviewMetadataLoader.
     *
     * @param notesManagerFactory factory for creating per-repository git notes managers
     * @param repositoryManager the repository manager
     * @param commentManager the comment manager for loading comments alongside metadata
     * @param contextSupplier supplier for the current ReviewContext, used to preserve existing comments on reload
     */
    public ReviewMetadataLoader(ReviewNotesManagerFactory notesManagerFactory,
                                RepositoryManager repositoryManager,
                                ReviewCommentManager commentManager,
                                Supplier<ReviewContext> contextSupplier) {
        this.notesManagerFactory = notesManagerFactory;
        this.repositoryManager = repositoryManager;
        this.commentManager = commentManager;
        this.contextSupplier = contextSupplier;
    }

    /**
     * Load review metadata by searching across all known repositories.
     *
     * @param reviewId the review identifier
     * @return future completing with the assembled ReviewContext, or null if not found
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
     * @return future completing with the refreshed ReviewContext, or null if not found
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
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @return future completing with the assembled ReviewContext, or null if not found
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
     * @return future completing with the refreshed ReviewContext, or null if not found
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
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @param knownPrimaryRepoName primary repository name from the cached ReviewItem
     * @return future completing with the assembled ReviewContext, or null if not found
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
     *
     * @param reviewId the review identifier
     * @param repositoryNames list of repository names that contain the review
     * @param knownPrimaryRepoName primary repository name
     * @return future completing with the refreshed ReviewContext, or null if not found
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

    private CompletableFuture<ReviewContext> loadReviewFromRepositories(
            String reviewId, String primaryRepoName, List<Repository> reviewRepositories, boolean includeComments) {
        List<Repository> orderedRepositories = orderRepositoriesWithPrimaryFirst(reviewRepositories, primaryRepoName);
        LOGGER.debug("Loading review metadata: reviewId={}, primaryRepo={}, repos={}",
            reviewId, primaryRepoName, orderedRepositories.size());

        GitReviewNotesManager notesManager = notesManagerFactory.create(primaryRepoName);
        ReviewContext currentContext = contextSupplier.get();
        List<com.kalynx.serverlessreviewtool.models.ReviewComment> existingComments =
            currentContext != null && reviewId.equals(currentContext.reviewId)
                ? new ArrayList<>(currentContext.getComments())
                : new ArrayList<>();

        CompletableFuture<List<com.kalynx.serverlessreviewtool.models.ReviewComment>> commentsFuture = includeComments
            ? commentManager.loadCommentsFromKnownRepository(reviewId, primaryRepoName)
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

                List<StreamEntry<com.kalynx.serverlessreviewtool.models.review.ReviewerData>> latestReviewerEntries =
                    metadata.reviewers().stream()
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

                Map<String, Boolean> activeByRepo = metadata.repositoryActiveEntries().stream()
                    .collect(Collectors.groupingBy(
                        entry -> entry.data().repositoryName(),
                        Collectors.collectingAndThen(
                            Collectors.maxBy(Comparator.comparing(StreamEntry::timestamp)),
                            opt -> opt.map(e -> e.data().active()).orElse(true)
                        )
                    ));

                List<Repository> activeRepositories = orderedRepositories.stream()
                    .filter(repo -> activeByRepo.getOrDefault(repo.getName(), true))
                    .collect(Collectors.toList());

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

                return new ReviewContext(
                    reviewId, resolvedTitle, resolvedDescription, resolvedAuthor,
                    status, reviewers, activeRepositories, comments,
                    branch, baseBranch, hasClosedHistory
                );
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to load review metadata for {}: {}", reviewId, error.getMessage(), error);
                return null;
            });
    }

    private CompletableFuture<String> resolvePrimaryRepositoryForReview(String reviewId, List<Repository> candidateRepositories) {
        if (candidateRepositories == null || candidateRepositories.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        List<CompletableFuture<PrimaryRepositoryCandidate>> futures = candidateRepositories.stream()
            .map(repository -> {
                GitReviewNotesManager notesManager = notesManagerFactory.create(repository.getName());
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
            Repository repository, GitReviewNotesManager.ReviewMetadata metadata) {
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

    private CompletableFuture<List<Repository>> findAllRepositoriesContainingReview(
            String reviewId, List<Repository> candidateRepositories) {
        long start = System.nanoTime();
        List<CompletableFuture<Repository>> futures = candidateRepositories.stream()
            .map(repo -> {
                GitReviewNotesManager notesManager = notesManagerFactory.create(repo.getName());
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

    private <T> T getLatestValue(List<StreamEntry<T>> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.getLast().data();
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

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private record PrimaryRepositoryCandidate(
        String repositoryName,
        String claimedPrimaryRepository,
        long timestamp,
        boolean isExplicitPrimary) {}
}







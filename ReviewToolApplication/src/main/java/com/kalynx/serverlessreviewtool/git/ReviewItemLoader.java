package com.kalynx.serverlessreviewtool.git;

import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.review.ReviewerData;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Loads review item projections from the {@code refs/heads/kalynx-reviews} orphan branch
 * via {@link OrphanBranchReviewManager}.
 *
 * <p>Each public entry point accepts a {@code remoteUrl} so the factory can route to the
 * correct {@link OrphanBranchStore} for that remote. Reads are handled entirely by JGit
 * plumbing inside the store — no subprocess git commands, no temp files.
 */
public class ReviewItemLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewItemLoader.class);

    private final OrphanBranchReviewManagerFactory managerFactory;
    private static final int GIT_LOAD_BATCH_SIZE = 16;

    /**
     * Constructs a ReviewItemLoader backed by the orphan-branch storage.
     *
     * @param managerFactory factory that produces a manager for a given (repositoryName, remoteUrl) pair
     */
    public ReviewItemLoader(OrphanBranchReviewManagerFactory managerFactory) {
        this.managerFactory = managerFactory;
    }

    public CompletableFuture<List<ReviewItem>> loadReviewsFromRepository(String repositoryName, String remoteUrl) {
        return listReviewIds(repositoryName, remoteUrl)
            .thenCompose(reviewIds -> loadReviewItems(repositoryName, remoteUrl, reviewIds));
    }

    /**
     * Loads review items from a repository in bounded batches and emits each item as soon as
     * its batch completes. At most {@value #GIT_LOAD_BATCH_SIZE} concurrent reads are in-flight.
     *
     * @param repositoryName repository to load from
     * @param remoteUrl      canonical git remote URL for the repository
     * @param onReviewLoaded callback invoked for each loaded review item
     * @return future completed when all review items have been attempted
     */
    public CompletableFuture<Void> loadReviewsFromRepositoryLazy(String repositoryName,
                                                                   String remoteUrl,
                                                                   Consumer<ReviewItem> onReviewLoaded) {
        return listReviewIds(repositoryName, remoteUrl)
            .thenCompose(reviewIds -> loadInBatches(repositoryName, remoteUrl, reviewIds, onReviewLoaded));
    }

    /**
     * Loads a single review item for use in targeted notification-driven refreshes.
     *
     * @param repositoryName repository containing the review
     * @param remoteUrl      canonical git remote URL
     * @param reviewId       review identifier
     * @return future containing the review item, or {@code null} if the review cannot be projected
     */
    public CompletableFuture<ReviewItem> loadSingleReviewItem(String repositoryName,
                                                               String remoteUrl,
                                                               String reviewId) {
        return loadReviewItem(repositoryName, remoteUrl, reviewId);
    }

    // -------------------------------------------------------------------------
    // Private batch helpers
    // -------------------------------------------------------------------------

    private CompletableFuture<Void> loadInBatches(String repositoryName,
                                                   String remoteUrl,
                                                   List<String> reviewIds,
                                                   Consumer<ReviewItem> onReviewLoaded) {
        List<List<String>> batches = partition(reviewIds);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (List<String> batch : batches) {
            chain = chain.thenCompose(ignored -> loadBatch(repositoryName, remoteUrl, batch, onReviewLoaded));
        }
        return chain;
    }

    private CompletableFuture<Void> loadBatch(String repositoryName,
                                               String remoteUrl,
                                               List<String> reviewIds,
                                               Consumer<ReviewItem> onReviewLoaded) {
        List<CompletableFuture<Void>> futures = reviewIds.stream()
            .map(reviewId -> loadReviewItem(repositoryName, remoteUrl, reviewId)
                .thenAccept(reviewItem -> {
                    if (reviewItem != null) {
                        onReviewLoaded.accept(reviewItem);
                    }
                }))
            .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private static <T> List<List<T>> partition(List<T> list) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += ReviewItemLoader.GIT_LOAD_BATCH_SIZE) {
            partitions.add(list.subList(i, Math.min(i + ReviewItemLoader.GIT_LOAD_BATCH_SIZE, list.size())));
        }
        return partitions;
    }

    // -------------------------------------------------------------------------
    // Core read operations
    // -------------------------------------------------------------------------

    private CompletableFuture<List<String>> listReviewIds(String repositoryName, String remoteUrl) {
        return managerFactory.create(repositoryName, remoteUrl).listReviewIds()
            .exceptionally(ex -> {
                LOGGER.warn("Failed to list review IDs for {} ({}): {}", repositoryName, remoteUrl, ex.getMessage());
                return new ArrayList<>();
            });
    }

    private CompletableFuture<List<ReviewItem>> loadReviewItems(String repositoryName,
                                                                 String remoteUrl,
                                                                 List<String> reviewIds) {
        List<CompletableFuture<ReviewItem>> reviewFutures = reviewIds.stream()
            .map(reviewId -> loadReviewItem(repositoryName, remoteUrl, reviewId))
            .toList();

        return CompletableFuture.allOf(reviewFutures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> reviewFutures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }

    private CompletableFuture<ReviewItem> loadReviewItem(String repositoryName,
                                                          String remoteUrl,
                                                          String reviewId) {
        OrphanBranchReviewManager manager = managerFactory.create(repositoryName, remoteUrl);

        return manager.readAllMetadata(reviewId)
            .thenApply(metadata -> {
                List<StreamEntry<String>> titleEntries = metadata.titles();
                List<StreamEntry<String>> authorEntries = metadata.authors();
                List<StreamEntry<String>> statusEntries = metadata.statuses();
                List<StreamEntry<ReviewerData>> reviewerEntries = metadata.reviewers();

                String primaryRepositoryValue = getLatestValue(metadata.primaryRepository());
                boolean isSecondaryReference = "false".equalsIgnoreCase(primaryRepositoryValue);

                String title = getLatestValue(titleEntries);
                if (title == null) {
                    if (isSecondaryReference) {
                        long secondaryLastUpdate = getMostRecentTimestamp(
                            titleEntries,
                            metadata.descriptions(),
                            authorEntries,
                            metadata.primaryRepository(),
                            metadata.branches(),
                            metadata.baseBranches(),
                            statusEntries,
                            reviewerEntries
                        );
                        return new ReviewItem(reviewId, null, null, null,
                            List.of(repositoryName), null, secondaryLastUpdate, List.of(),
                            getLatestValue(metadata.branches()), getLatestValue(metadata.baseBranches()));
                    }
                    LOGGER.debug("Skipping review {} in {} — no title written yet (review is partially created)", reviewId, repositoryName);
                    return null;
                }

                String author = getLatestValue(authorEntries);
                if (author == null) author = "Unknown";

                String primaryRepo = normalizePrimaryRepository(getLatestValue(metadata.primaryRepository()), repositoryName);

                String statusStr = getLatestValue(statusEntries);
                if (statusStr == null) statusStr = "OPEN";

                String branch = getLatestValue(metadata.branches());
                String baseBranch = getLatestValue(metadata.baseBranches());

                List<String> reviewers = reviewerEntries.stream()
                    .map(StreamEntry::editor)
                    .distinct()
                    .collect(java.util.stream.Collectors.toList());

                ReviewStatus status = parseStatus(statusStr);

                long lastUpdate = getMostRecentTimestamp(
                    titleEntries,
                    metadata.descriptions(),
                    authorEntries,
                    metadata.primaryRepository(),
                    metadata.branches(),
                    metadata.baseBranches(),
                    statusEntries,
                    reviewerEntries
                );

                return new ReviewItem(reviewId, title, author, primaryRepo, List.of(repositoryName), status, lastUpdate, reviewers, branch, baseBranch);
            })
            .exceptionally(ex -> {
                LOGGER.warn("Failed to load review {} from {}", reviewId, repositoryName, ex);
                return null;
            });
    }

    // -------------------------------------------------------------------------
    // Projection helpers
    // -------------------------------------------------------------------------

    private String normalizePrimaryRepository(String primaryRepositoryValue, String repositoryName) {
        if (primaryRepositoryValue == null || primaryRepositoryValue.isBlank()) {
            return repositoryName;
        }
        if ("true".equalsIgnoreCase(primaryRepositoryValue)) {
            return repositoryName;
        }
        if ("false".equalsIgnoreCase(primaryRepositoryValue)) {
            return null;
        }
        return primaryRepositoryValue;
    }

    private long getMostRecentTimestamp(List<StreamEntry<String>> titleEntries,
                                        List<StreamEntry<String>> descriptionEntries,
                                        List<StreamEntry<String>> authorEntries,
                                        List<StreamEntry<String>> primaryRepositoryEntries,
                                        List<StreamEntry<String>> branchEntries,
                                        List<StreamEntry<String>> baseBranchEntries,
                                        List<StreamEntry<String>> statusEntries,
                                        List<StreamEntry<com.kalynx.serverlessreviewtool.models.review.ReviewerData>> reviewerEntries) {
        long mostRecent = 0;
        mostRecent = Math.max(mostRecent, getLatestTimestamp(titleEntries));
        mostRecent = Math.max(mostRecent, getLatestTimestamp(descriptionEntries));
        mostRecent = Math.max(mostRecent, getLatestTimestamp(authorEntries));
        mostRecent = Math.max(mostRecent, getLatestTimestamp(primaryRepositoryEntries));
        mostRecent = Math.max(mostRecent, getLatestTimestamp(branchEntries));
        mostRecent = Math.max(mostRecent, getLatestTimestamp(baseBranchEntries));
        mostRecent = Math.max(mostRecent, getLatestTimestamp(statusEntries));
        mostRecent = Math.max(mostRecent, getLatestTimestamp(reviewerEntries));
        return mostRecent;
    }

    private <T> long getLatestTimestamp(List<StreamEntry<T>> entries) {
        if (entries == null || entries.isEmpty()) {
            return 0;
        }
        return entries.stream()
            .filter(entry -> entry != null && entry.timestamp() != null)
            .mapToLong(entry -> entry.timestamp().toEpochMilli())
            .max()
            .orElse(0);
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
            return ReviewStatus.OPEN;
        }
    }
}

package com.kalynx.serverlessreviewtool.git;

import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads review item projections from repository review notes.
 */
public class ReviewItemLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewItemLoader.class);

    private final Git git;
    private static final String NOTES_REF_PREFIX = "refs/notes/reviews/";
    private static final Pattern REVIEW_ID_PATTERN = Pattern.compile("^" + Pattern.quote(NOTES_REF_PREFIX) + "([^/]+)/");
    private static final int GIT_LOAD_BATCH_SIZE = 16;

    public ReviewItemLoader(Git git) {
        this.git = git;
    }

    public CompletableFuture<List<ReviewItem>> loadReviewsFromRepository(String repositoryName) {
        return synchronizeRepository(repositoryName)
            .thenCompose(ignored -> listReviewIds(repositoryName))
            .thenCompose(reviewIds -> loadReviewItems(repositoryName, reviewIds));
    }

    /**
     * Loads review items from a repository in bounded batches and emits each item as soon as its batch completes.
     * At most {@value #GIT_LOAD_BATCH_SIZE} concurrent git operations are in-flight at any time.
     *
     * @param repositoryName repository to load from
     * @param onReviewLoaded callback invoked for each loaded review item
     * @return future completed when all review items have been attempted
     */
    public CompletableFuture<Void> loadReviewsFromRepositoryLazy(String repositoryName, Consumer<ReviewItem> onReviewLoaded) {
        return synchronizeRepository(repositoryName)
            .thenCompose(ignored -> listReviewIds(repositoryName))
            .thenCompose(reviewIds -> loadInBatches(repositoryName, reviewIds, onReviewLoaded));
    }

    private CompletableFuture<Void> loadInBatches(String repositoryName,
                                                   List<String> reviewIds,
                                                   Consumer<ReviewItem> onReviewLoaded) {
        List<List<String>> batches = partition(reviewIds);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (List<String> batch : batches) {
            chain = chain.thenCompose(ignored -> loadBatch(repositoryName, batch, onReviewLoaded));
        }
        return chain;
    }

    private CompletableFuture<Void> loadBatch(String repositoryName,
                                               List<String> reviewIds,
                                               Consumer<ReviewItem> onReviewLoaded) {
        List<CompletableFuture<Void>> futures = reviewIds.stream()
            .map(reviewId -> loadReviewItem(repositoryName, reviewId)
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

    private CompletableFuture<Void> synchronizeRepository(String repositoryName) {
        long start = System.nanoTime();
        return git.fetch(repositoryName)
            .thenRun(() -> LOGGER.info("TIMING [{}] synchronizeRepository fetch: {}ms", repositoryName,
                (System.nanoTime() - start) / 1_000_000))
            .exceptionally(ex -> {
                LOGGER.warn("Failed to synchronize repository {} before reading reviews", repositoryName, ex);
                return null;
            });
    }

    private CompletableFuture<List<String>> listReviewIds(String repositoryName) {
        return git.executeAsync(repositoryName, "show-ref")
            .thenApply(output -> {
                LinkedHashSet<String> reviewIdSet = new LinkedHashSet<>();
                String[] lines = output.split("\n");

                for (String line : lines) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) {
                        String ref = parts[1];
                        if (ref.startsWith(NOTES_REF_PREFIX)) {
                            Matcher matcher = REVIEW_ID_PATTERN.matcher(ref);
                            if (matcher.find()) {
                                reviewIdSet.add(matcher.group(1));
                            }
                        }
                    }
                }

                return (List<String>) new ArrayList<>(reviewIdSet);
            })
            .exceptionally(_ -> new ArrayList<>());
    }

    private CompletableFuture<List<ReviewItem>> loadReviewItems(String repositoryName, List<String> reviewIds) {
        List<CompletableFuture<ReviewItem>> reviewFutures = reviewIds.stream()
            .map(reviewId -> loadReviewItem(repositoryName, reviewId))
            .toList();

        return CompletableFuture.allOf(reviewFutures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> reviewFutures.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .collect(Collectors.toList()));
    }

    private CompletableFuture<ReviewItem> loadReviewItem(String repositoryName, String reviewId) {
        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, repositoryName);

        return notesManager.readAllMetadataFromLocal(reviewId)
            .thenApply(metadata -> {
                List<StreamEntry<String>> titleEntries = metadata.titles();
                List<StreamEntry<String>> authorEntries = metadata.authors();
                List<StreamEntry<String>> statusEntries = metadata.statuses();
                List<StreamEntry<com.kalynx.serverlessreviewtool.models.review.ReviewerData>> reviewerEntries = metadata.reviewers();

                String title = getLatestValue(titleEntries);
                if (title == null) title = "Untitled Review";

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


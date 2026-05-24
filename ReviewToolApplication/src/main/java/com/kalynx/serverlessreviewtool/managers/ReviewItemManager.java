package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.git.ReviewItemLoader;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.plugin.RepositoryDescriptor;
import com.kalynx.serverlessreviewtool.plugin.ReviewListUpdate;
import com.kalynx.serverlessreviewtool.plugin.ReviewUpdateType;
import com.kalynx.swingtheme.theme.LoadingStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Maintains review item state and emits incremental updates to listeners.
 */
public class ReviewItemManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewItemManager.class);

    private final Object lock = new Object();
    private List<ReviewItem> reviewItems = new ArrayList<>();
    private final Map<String, Map<String, ReviewItem>> repositoryReviewIndex = new HashMap<>();
    private final Map<String, ReviewItem> mergedReviewIndex = new HashMap<>();

    private final Set<Consumer<List<ReviewItem>>> listeners = new HashSet<>();
    private final Git git;
    private final ReviewItemLoader reviewItemLoader;
    private volatile List<RepositoryDescriptor> notificationPluginRepositories = List.of();
    private final Map<String, CompletableFuture<Void>> inFlightRepositoryRefreshes = new ConcurrentHashMap<>();

    /**
     * Creates a new review item manager.
     *
     * @param git git client
     * @param reviewItemLoader review item loader
     */
    public ReviewItemManager(
        Git git,
        ReviewItemLoader reviewItemLoader) {
        this.git = git;
        this.reviewItemLoader = reviewItemLoader;
    }

    /**
     * Refreshes reviews for all configured repositories.
     *
     * @return completion future for the refresh operation
     */
    public CompletableFuture<Void> refresh() {
        LoadingStateManager.getInstance().startLoading("refresh-review-items");
        List<CompletableFuture<Void>> futures = resolveRepositoryNames().stream()
            .map(repositoryName -> refreshRepository(repositoryName)
                .exceptionally(error -> {
                    LOGGER.warn("Skipping repository '{}' during refresh due to error: {}",
                        repositoryName, error.getMessage());
                    return null;
                }))
            .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .whenComplete((_, ignored) -> LoadingStateManager.getInstance().stopLoading("refresh-review-items"));
    }

    /**
     * Sets repositories discovered from notification plugins.
     *
     * @param repositories repository descriptors from notification plugins
     */
    public void setNotificationPluginRepositories(List<RepositoryDescriptor> repositories) {
        List<RepositoryDescriptor> normalized = repositories == null
            ? List.of()
            : repositories.stream()
                .filter(descriptor -> descriptor != null && descriptor.name() != null && !descriptor.name().isBlank())
                .collect(java.util.stream.Collectors.collectingAndThen(
                    java.util.stream.Collectors.toMap(
                        RepositoryDescriptor::name,
                        descriptor -> descriptor,
                        (_, second) -> second,
                        java.util.LinkedHashMap::new),
                    map -> new ArrayList<>(map.values())));

        List<RepositoryDescriptor> previous = notificationPluginRepositories;
        notificationPluginRepositories = normalized;

        Set<String> previousSet = previous.stream().map(RepositoryDescriptor::name).collect(java.util.stream.Collectors.toSet());
        Set<String> currentSet = normalized.stream().map(RepositoryDescriptor::name).collect(java.util.stream.Collectors.toSet());

        previousSet.stream()
            .filter(repo -> !currentSet.contains(repo))
            .forEach(this::removeRepositorySnapshot);

        currentSet.stream()
            .filter(repo -> !previousSet.contains(repo))
            .forEach(this::refreshRepository);
    }

    private List<String> resolveRepositoryNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();

        notificationPluginRepositories.stream()
            .map(RepositoryDescriptor::name)
            .filter(name -> name != null && !name.isBlank())
            .forEach(names::add);

        return new ArrayList<>(names);
    }

    /**
     * Refreshes reviews for one repository and emits incremental updates as each item is loaded.
     *
     * @param repositoryName repository to refresh
     * @return completion future for the repository refresh
     */
    public CompletableFuture<Void> refreshRepository(String repositoryName) {
        if (repositoryName == null || repositoryName.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }

        return inFlightRepositoryRefreshes.compute(repositoryName, (name, inFlight) -> {
            if (inFlight != null && !inFlight.isDone()) {
                return inFlight;
            }

            RepositoryDescriptor descriptor = findRepositoryDescriptor(repositoryName);
            if (descriptor == null || descriptor.location() == null || descriptor.location().isBlank()) {
                LOGGER.warn("Repository descriptor not found for {}", repositoryName);
                return CompletableFuture.completedFuture(null);
            }

            Map<String, ReviewItem> refreshedRepositorySnapshot = new HashMap<>();
            CompletableFuture<Void> refreshFuture = git.ensureCloned(repositoryName, descriptor.location())
                .thenCompose(ignored -> reviewItemLoader.loadReviewsFromRepositoryLazy(
                    repositoryName, descriptor.location(), review ->
                    refreshedRepositorySnapshot.put(review.getReviewId(), review)))
                .thenRun(() -> replaceRepositorySnapshot(repositoryName, refreshedRepositorySnapshot))
                .exceptionally(ex -> {
                    LOGGER.warn("Failed to refresh repository {}", repositoryName, ex);
                    return null;
                });

            refreshFuture.whenComplete((ignored, _) ->
                inFlightRepositoryRefreshes.remove(name, refreshFuture));
            return refreshFuture;
        });
    }

    private RepositoryDescriptor findRepositoryDescriptor(String repositoryName) {
        return notificationPluginRepositories.stream()
            .filter(desc -> repositoryName.equals(desc.name()))
            .findFirst()
            .orElse(null);
    }

    /**
     * Applies plugin notification updates.
     *
     * <p>When an update carries both a {@code reviewId} and a {@code repositoryUrl} a targeted
     * single-review fetch is performed via {@link ReviewItemLoader#loadSingleReviewItem} so only
     * the affected blob is retrieved from the orphan branch. When either field is absent the
     * affected repository is refreshed in full as a fallback.
     *
     * @param updates plugin review list updates
     */
    public void applyNotificationUpdates(ReviewListUpdate[] updates) {
        if (updates == null) {
            return;
        }


        Arrays.stream(updates).parallel().forEach( update -> {
            if (update == null) return;
            LOGGER.debug("Received notification update: {}", update);
            if (update.reviewId() != null && update.repositoryUrl() != null) {
                // Targeted path: fetch only the changed review from the orphan branch.
                fetchSingleReview(update.reviewId(), update.repositoryUrl(),
                        update.primaryRepository(), update.updateType())
                        .exceptionally(ex -> {
                            LOGGER.warn("Targeted fetch failed for review '{}': {}", update.reviewId(), ex.getMessage());
                            return null;
                        });
            } else {
                // Fallback: refresh the whole repository (no repositoryUrl in payload).
                List<String> repos = new ArrayList<>();
                if (update.primaryRepository() != null) repos.add(update.primaryRepository());
                if (update.repositories() != null) repos.addAll(update.repositories());

                repos.stream()
                        .filter(r -> r != null && !r.isBlank())
                        .distinct()
                        .forEach(repo -> refreshRepository(repo).exceptionally(ex -> {
                            LOGGER.warn("Repository refresh failed for '{}': {}", repo, ex.getMessage());
                            return null;
                        }));

                if (repos.isEmpty()) {
                    refresh();
                }
            }
        });
    }

    /**
     * Fetches or removes a single review in response to a targeted notification event.
     */
    private CompletableFuture<Void> fetchSingleReview(String reviewId,
                                                        String remoteUrl,
                                                        String repositoryName,
                                                        ReviewUpdateType type) {
        if (type == ReviewUpdateType.DELETED) {
            removeSingleReviewFromSnapshot(repositoryName, reviewId);
            return CompletableFuture.completedFuture(null);
        }
        if (repositoryName == null) {
            LOGGER.debug("Skipping targeted fetch for review '{}' — no primaryRepository", reviewId);
            return CompletableFuture.completedFuture(null);
        }
        return reviewItemLoader.loadSingleReviewItem(repositoryName, remoteUrl, reviewId)
            .thenAccept(item -> {
                if (item != null) {
                    upsertReview(repositoryName, item);
                }
            });
    }

    /** Inserts or replaces a single review item in the snapshot and notifies listeners. */
    private void upsertReview(String repositoryName, ReviewItem item) {
        List<ReviewItem> snapshot;
        synchronized (lock) {
            repositoryReviewIndex
                .computeIfAbsent(repositoryName, k -> new HashMap<>())
                .put(item.getReviewId(), item);
            recomputeMergedReview(item.getReviewId());
            snapshot = rebuildSnapshot();
        }
        notifyListeners(snapshot);
    }

    /** Removes a single review from one repository's snapshot and notifies listeners. */
    private void removeSingleReviewFromSnapshot(String repositoryName, String reviewId) {
        List<ReviewItem> snapshot;
        synchronized (lock) {
            Map<String, ReviewItem> repoMap = repositoryReviewIndex.get(repositoryName);
            if (repoMap == null || !repoMap.containsKey(reviewId)) {
                return;
            }
            repoMap.remove(reviewId);
            recomputeMergedReview(reviewId);
            snapshot = rebuildSnapshot();
        }
        notifyListeners(snapshot);
    }

    /**
     * Adds a review list listener.
     *
     * @param listener listener to add
     */
    public void addListener(Consumer<List<ReviewItem>> listener) {
        List<ReviewItem> snapshot;
        synchronized (lock) {
            listeners.add(listener);
            snapshot = List.copyOf(reviewItems);
        }
        listener.accept(snapshot);
    }

    private void replaceRepositorySnapshot(String repositoryName, Map<String, ReviewItem> refreshedSnapshot) {
        List<ReviewItem> snapshot;
        synchronized (lock) {
            Map<String, ReviewItem> previous = repositoryReviewIndex.getOrDefault(repositoryName, Map.of());
            Set<String> impactedReviewIds = new HashSet<>(previous.keySet());
            impactedReviewIds.addAll(refreshedSnapshot.keySet());

            repositoryReviewIndex.put(repositoryName, new HashMap<>(refreshedSnapshot));
            impactedReviewIds.forEach(this::recomputeMergedReview);

            snapshot = rebuildSnapshot();
        }
        notifyListeners(snapshot);
    }

    private void removeRepositorySnapshot(String repositoryName) {
        List<ReviewItem> snapshot;
        synchronized (lock) {
            Map<String, ReviewItem> removed = repositoryReviewIndex.remove(repositoryName);
            if (removed == null || removed.isEmpty()) {
                return;
            }
            removed.keySet().forEach(this::recomputeMergedReview);
            snapshot = rebuildSnapshot();
        }
        notifyListeners(snapshot);
    }

    private void recomputeMergedReview(String reviewId) {
        List<ReviewItem> copies = repositoryReviewIndex.values().stream()
            .map(repositoryItems -> repositoryItems.get(reviewId))
            .filter(Objects::nonNull)
            .toList();

        if (copies.isEmpty()) {
            mergedReviewIndex.remove(reviewId);
            return;
        }

        boolean hasPrimaryCopy = copies.stream().anyMatch(c -> c.getTitle() != null);
        if (!hasPrimaryCopy) {
            mergedReviewIndex.remove(reviewId);
            return;
        }

        mergedReviewIndex.put(reviewId, mergeReviewCopies(reviewId, copies));
    }

    private ReviewItem mergeReviewCopies(String reviewId, List<ReviewItem> copies) {
        ReviewItem latest = copies.stream()
            .filter(c -> c.getTitle() != null)
            .max(Comparator.comparingLong(ReviewItem::getLastUpdate))
            .orElseGet(() -> copies.stream()
                .max(Comparator.comparingLong(ReviewItem::getLastUpdate))
                .orElse(copies.getFirst()));

        long mergedLastUpdate = copies.stream()
            .mapToLong(ReviewItem::getLastUpdate)
            .max()
            .orElse(latest.getLastUpdate());

        String primaryRepository = determinePrimaryRepository(reviewId, copies);
        String mergedTitle = resolveLatestValue(copies, ReviewItem::getTitle, this::isMeaningfulTitle, latest.getTitle());
        String mergedAuthor = resolveLatestValue(copies, ReviewItem::getAuthor, this::isMeaningfulAuthor, latest.getAuthor());
        LinkedHashSet<String> repositories = new LinkedHashSet<>();
        LinkedHashSet<String> reviewers = new LinkedHashSet<>();
        String branch = latest.getBranch();
        String baseBranch = latest.getBaseBranch();

        for (ReviewItem copy : copies) {
            repositories.addAll(copy.getRepositories());
            reviewers.addAll(copy.getReviewers());
            if (branch == null && copy.getBranch() != null) {
                branch = copy.getBranch();
            }
            if (baseBranch == null && copy.getBaseBranch() != null) {
                baseBranch = copy.getBaseBranch();
            }
        }

        return new ReviewItem(
            latest.getReviewId(),
            mergedTitle,
            mergedAuthor,
            primaryRepository,
            new ArrayList<>(repositories),
            latest.getStatus(),
            mergedLastUpdate,
            new ArrayList<>(reviewers),
            branch,
            baseBranch
        );
    }

    private String resolveLatestValue(List<ReviewItem> copies,
                                      Function<ReviewItem, String> valueExtractor,
                                      Predicate<String> isMeaningful,
                                      String fallback) {
        return copies.stream()
            .sorted(Comparator.comparingLong(ReviewItem::getLastUpdate).reversed())
            .map(valueExtractor)
            .filter(Objects::nonNull)
            .filter(isMeaningful)
            .findFirst()
            .orElse(fallback);
    }

    private boolean isMeaningfulTitle(String title) {
        return !title.isBlank() && !"Untitled Review".equalsIgnoreCase(title.trim());
    }

    private boolean isMeaningfulAuthor(String author) {
        return !author.isBlank() && !"Unknown".equalsIgnoreCase(author.trim());
    }

    private String determinePrimaryRepository(String reviewId, List<ReviewItem> copies) {
        List<ReviewItem> primaryCandidates = copies.stream()
            .filter(copy -> copy.getPrimaryRepository() != null && !copy.getPrimaryRepository().isBlank())
            .toList();

        if (!primaryCandidates.isEmpty()) {
            String selected = primaryCandidates.stream()
                .max(Comparator.comparingLong(ReviewItem::getLastUpdate)
                    .thenComparing(ReviewItem::getPrimaryRepository))
                .map(ReviewItem::getPrimaryRepository)
                .orElse(null);

            Set<String> uniqueCandidates = primaryCandidates.stream()
                .map(ReviewItem::getPrimaryRepository)
                .collect(Collectors.toCollection(LinkedHashSet::new));

            if (uniqueCandidates.size() > 1) {
                LOGGER.warn("Review '{}' has conflicting primary repositories {}. Auto-resolved to '{}' from latest metadata update.",
                    reviewId, uniqueCandidates, selected);
            }

            return selected;
        }

        return copies.stream()
            .map(ReviewItem::getRepositories)
            .filter(repos -> repos != null && !repos.isEmpty())
            .map(List::getFirst)
            .findFirst()
            .orElse(null);
    }

    private List<ReviewItem> rebuildSnapshot() {
        reviewItems = mergedReviewIndex.values().stream()
            .sorted(Comparator.comparingLong(ReviewItem::getLastUpdate).reversed())
            .toList();
        return List.copyOf(reviewItems);
    }

    private void notifyListeners(List<ReviewItem> snapshot) {
        LOGGER.debug("Notifying listeners of review item list update: {} items", snapshot.size());
        listeners.forEach(listener -> listener.accept(snapshot));
    }

}

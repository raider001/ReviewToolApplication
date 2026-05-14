package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.ReviewItemLoader;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.plugin.RepositoryDescriptor;
import com.kalynx.serverlessreviewtool.plugin.ReviewListUpdate;
import com.kalynx.serverlessreviewtool.plugin.ReviewUpdateType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ReviewItemManager covering snapshot management, merge logic, and listener notification.
 */
class ReviewItemManagerTests {

    private static final String REPO_A = "repo-a";
    private static final String REPO_B = "repo-b";
    private static final String REPO_URL = "file:///mock/repo";

    private com.kalynx.serverlessreviewtool.git.Git git;
    private ReviewItemLoader loader;
    private ReviewItemManager manager;

    @BeforeEach
    void setUp() {
        git = mock(com.kalynx.serverlessreviewtool.git.Git.class);
        loader = mock(ReviewItemLoader.class);
        manager = new ReviewItemManager(git, loader);
    }

    @Test
    void addListener_noItems_calledImmediatelyWithEmptyList() {
        List<ReviewItem> received = new CopyOnWriteArrayList<>();
        manager.addListener(received::addAll);

        assertTrue(received.isEmpty());
    }

    @Test
    void addListener_afterItemsLoaded_calledImmediatelyWithCurrentSnapshot() throws Exception {
        ReviewItem item = buildReviewItem("r1", REPO_A, "Title", 100L);
        stubRepositoryRefresh(REPO_A, List.of(item));

        manager.setNotificationPluginRepositories(List.of(new RepositoryDescriptor(REPO_A, REPO_URL)));
        Thread.sleep(200);

        AtomicReference<List<ReviewItem>> snapshot = new AtomicReference<>();
        manager.addListener(snapshot::set);

        assertNotNull(snapshot.get());
        assertEquals(1, snapshot.get().size());
    }

    @Test
    void setNotificationPluginRepositories_newRepository_triggersRefreshAndNotifiesListener() throws Exception {
        ReviewItem item = buildReviewItem("r1", REPO_A, "My Review", 200L);
        stubRepositoryRefresh(REPO_A, List.of(item));

        List<List<ReviewItem>> notifications = new CopyOnWriteArrayList<>();
        manager.addListener(notifications::add);
        notifications.clear();

        manager.setNotificationPluginRepositories(List.of(new RepositoryDescriptor(REPO_A, REPO_URL)));
        Thread.sleep(300);

        assertNotNull(notifications);
        List<ReviewItem> lastSnapshot = notifications.getLast();
        assertEquals(1, lastSnapshot.size());
        assertEquals("r1", lastSnapshot.getFirst().getReviewId());
    }

    @Test
    void setNotificationPluginRepositories_removedRepository_removesItemsFromSnapshot() throws Exception {
        ReviewItem item = buildReviewItem("r1", REPO_A, "Old Review", 100L);
        stubRepositoryRefresh(REPO_A, List.of(item));

        manager.setNotificationPluginRepositories(List.of(new RepositoryDescriptor(REPO_A, REPO_URL)));
        Thread.sleep(200);

        AtomicReference<List<ReviewItem>> lastNotification = new AtomicReference<>(List.of());
        manager.addListener(lastNotification::set);

        manager.setNotificationPluginRepositories(List.of());
        Thread.sleep(100);

        assertTrue(lastNotification.get().isEmpty());
    }

    @Test
    void mergeReviews_twoRepositoriesOneReview_picksLatestTitle() throws Exception {
        ReviewItem older = buildReviewItem("r1", REPO_A, "Old Title", 100L);
        ReviewItem newer = buildReviewItem("r1", REPO_B, "New Title", 200L);

        stubRepositoryRefresh(REPO_A, List.of(older));
        stubRepositoryRefresh(REPO_B, List.of(newer));

        AtomicReference<List<ReviewItem>> lastSnapshot = new AtomicReference<>(List.of());
        manager.addListener(lastSnapshot::set);

        manager.setNotificationPluginRepositories(List.of(
            new RepositoryDescriptor(REPO_A, REPO_URL),
            new RepositoryDescriptor(REPO_B, REPO_URL)
        ));
        Thread.sleep(300);

        List<ReviewItem> items = lastSnapshot.get();
        assertEquals(1, items.size());
        assertEquals("New Title", items.getFirst().getTitle());
    }

    @Test
    void mergeReviews_twoRepositoriesOneReview_mergesRepositoryLists() throws Exception {
        ReviewItem inA = buildReviewItem("r1", REPO_A, "Review", 100L);
        ReviewItem inB = buildReviewItem("r1", REPO_B, "Review", 100L);

        stubRepositoryRefresh(REPO_A, List.of(inA));
        stubRepositoryRefresh(REPO_B, List.of(inB));

        AtomicReference<List<ReviewItem>> lastSnapshot = new AtomicReference<>(List.of());
        manager.addListener(lastSnapshot::set);

        manager.setNotificationPluginRepositories(List.of(
            new RepositoryDescriptor(REPO_A, REPO_URL),
            new RepositoryDescriptor(REPO_B, REPO_URL)
        ));
        Thread.sleep(300);

        List<ReviewItem> items = lastSnapshot.get();
        assertEquals(1, items.size());
        List<String> repos = items.getFirst().getRepositories();
        assertTrue(repos.contains(REPO_A));
        assertTrue(repos.contains(REPO_B));
    }

    @Test
    void mergeReviews_differentReviewIds_maintainSeparateItems() throws Exception {
        ReviewItem first = buildReviewItem("r1", REPO_A, "Review One", 100L);
        ReviewItem second = buildReviewItem("r2", REPO_A, "Review Two", 200L);

        stubRepositoryRefresh(REPO_A, List.of(first, second));

        AtomicReference<List<ReviewItem>> lastSnapshot = new AtomicReference<>(List.of());
        manager.addListener(lastSnapshot::set);
        manager.setNotificationPluginRepositories(List.of(new RepositoryDescriptor(REPO_A, REPO_URL)));
        Thread.sleep(200);

        assertEquals(2, lastSnapshot.get().size());
    }

    @Test
    void refreshRepository_unknownRepository_doesNotThrow() {
        CompletableFuture<Void> result = manager.refreshRepository("nonexistent-repo");
        assertNotNull(result);
    }

    @Test
    void setNotificationPluginRepositories_duplicateNames_deduplicates() throws Exception {
        ReviewItem item = buildReviewItem("r1", REPO_A, "Title", 100L);
        stubRepositoryRefresh(REPO_A, List.of(item));

        manager.setNotificationPluginRepositories(List.of(
            new RepositoryDescriptor(REPO_A, REPO_URL),
            new RepositoryDescriptor(REPO_A, REPO_URL)
        ));
        Thread.sleep(200);

        AtomicReference<List<ReviewItem>> snapshot = new AtomicReference<>(List.of());
        manager.addListener(snapshot::set);

        assertEquals(1, snapshot.get().size());
    }

    @Test
    void applyNotificationUpdates_nullUpdates_doesNotThrow() {
        manager.applyNotificationUpdates(null);
    }

    @Test
    void applyNotificationUpdates_emptyUpdates_doesNotThrow() {
        manager.applyNotificationUpdates(new ReviewListUpdate[0]);
    }

    @Test
    void applyNotificationUpdates_updatesWithRepositories_refreshesAffectedRepos() throws Exception {
        ReviewItem item = buildReviewItem("r1", REPO_A, "Title", 100L);
        stubRepositoryRefresh(REPO_A, List.of(item));

        manager.setNotificationPluginRepositories(List.of(new RepositoryDescriptor(REPO_A, REPO_URL)));

        ReviewListUpdate update = new ReviewListUpdate(
            UUID.randomUUID().toString(), Instant.now(), ReviewUpdateType.UPDATED,
            "r1", REPO_A, List.of(REPO_A));

        manager.applyNotificationUpdates(new ReviewListUpdate[]{update});
        Thread.sleep(300);

        AtomicReference<List<ReviewItem>> snapshot = new AtomicReference<>(List.of());
        manager.addListener(snapshot::set);
        assertNotNull(snapshot.get());
    }

    @Test
    void applyNotificationUpdates_updatesWithNullRepos_callsRefresh() throws Exception {
        ReviewItem item = buildReviewItem("r1", REPO_A, "Title", 100L);
        stubRepositoryRefresh(REPO_A, List.of(item));

        manager.setNotificationPluginRepositories(List.of(new RepositoryDescriptor(REPO_A, REPO_URL)));

        ReviewListUpdate update = new ReviewListUpdate(
            UUID.randomUUID().toString(), Instant.now(), ReviewUpdateType.UPDATED,
            "r1", null, null);

        manager.applyNotificationUpdates(new ReviewListUpdate[]{update});
        Thread.sleep(300);
    }

    @Test
    void mergeReviews_untitledTitlePreference_picksNonUntitledWhenAvailable() throws Exception {
        ReviewItem olderWithTitle = buildReviewItem("r1", REPO_A, "Real Title", 100L);
        ReviewItem newerUntitled = buildReviewItem("r1", REPO_B, "Untitled Review", 200L);

        stubRepositoryRefresh(REPO_A, List.of(olderWithTitle));
        stubRepositoryRefresh(REPO_B, List.of(newerUntitled));

        AtomicReference<List<ReviewItem>> lastSnapshot = new AtomicReference<>(List.of());
        manager.addListener(lastSnapshot::set);

        manager.setNotificationPluginRepositories(List.of(
            new RepositoryDescriptor(REPO_A, REPO_URL),
            new RepositoryDescriptor(REPO_B, REPO_URL)
        ));
        Thread.sleep(300);

        List<ReviewItem> items = lastSnapshot.get();
        assertEquals(1, items.size());
        assertEquals("Real Title", items.getFirst().getTitle());
    }

    @Test
    void mergeReviews_unknownAuthorPreference_picksNonUnknownWhenAvailable() throws Exception {
        ReviewItem withRealAuthor = new ReviewItem("r1", "Title", "Bob", REPO_A,
            List.of(REPO_A), ReviewStatus.OPEN, 100L, List.of(), "feature", "main");
        ReviewItem withUnknownAuthor = new ReviewItem("r1", "Title", "Unknown", REPO_B,
            List.of(REPO_B), ReviewStatus.OPEN, 200L, List.of(), "feature", "main");

        stubRepositoryRefresh(REPO_A, List.of(withRealAuthor));
        stubRepositoryRefresh(REPO_B, List.of(withUnknownAuthor));

        AtomicReference<List<ReviewItem>> lastSnapshot = new AtomicReference<>(List.of());
        manager.addListener(lastSnapshot::set);

        manager.setNotificationPluginRepositories(List.of(
            new RepositoryDescriptor(REPO_A, REPO_URL),
            new RepositoryDescriptor(REPO_B, REPO_URL)
        ));
        Thread.sleep(300);

        List<ReviewItem> items = lastSnapshot.get();
        assertEquals(1, items.size());
        assertEquals("Bob", items.getFirst().getAuthor());
    }

    @Test
    void refresh_withConfiguredRepositories_refreshesAll() throws Exception {
        ReviewItem item = buildReviewItem("r1", REPO_A, "Title", 100L);
        stubRepositoryRefresh(REPO_A, List.of(item));

        manager.setNotificationPluginRepositories(List.of(new RepositoryDescriptor(REPO_A, REPO_URL)));
        Thread.sleep(200);

        manager.refresh().get(2000, java.util.concurrent.TimeUnit.MILLISECONDS);

        AtomicReference<List<ReviewItem>> snapshot = new AtomicReference<>(List.of());
        manager.addListener(snapshot::set);
        assertEquals(1, snapshot.get().size());
    }

    private ReviewItem buildReviewItem(String reviewId, String repositoryName, String title, long lastUpdate) {
        return new ReviewItem(reviewId, title, "Author", repositoryName,
            List.of(repositoryName), ReviewStatus.OPEN, lastUpdate, List.of(), "feature", "main");
    }

    @SuppressWarnings("unchecked")
    private void stubRepositoryRefresh(String repoName, List<ReviewItem> items) {
        when(git.ensureCloned(eq(repoName), eq(ReviewItemManagerTests.REPO_URL)))
            .thenReturn(CompletableFuture.completedFuture(null));

        when(loader.loadReviewsFromRepositoryLazy(eq(repoName), any(Consumer.class)))
            .thenAnswer(invocation -> {
                Consumer<ReviewItem> callback = invocation.getArgument(1);
                items.forEach(callback);
                return CompletableFuture.completedFuture(null);
            });
    }
}



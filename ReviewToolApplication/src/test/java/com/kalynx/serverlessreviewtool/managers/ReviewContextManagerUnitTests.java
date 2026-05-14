package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.GitReviewNotesManager;
import com.kalynx.serverlessreviewtool.git.ReviewNotesManagerFactory;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;/**
 * Unit tests for ReviewContextManager using mocked collaborators.
 * Covers delegation paths, guard-clause branches, listener management, and reviewer operations.
 */
class ReviewContextManagerUnitTests {

    private ReviewCommentManager commentManager;
    private ReviewChangeSetManager changeSetManager;
    private ReviewMetadataLoader metadataLoader;
    private ReviewerManager reviewerManager;
    private GitReviewNotesManager notesManager;
    private ReviewContextManager manager;

    @BeforeEach
    void setUp() {
        commentManager = mock(ReviewCommentManager.class);
        changeSetManager = mock(ReviewChangeSetManager.class);
        metadataLoader = mock(ReviewMetadataLoader.class);
        reviewerManager = mock(ReviewerManager.class);
        notesManager = mock(GitReviewNotesManager.class);
        ReviewNotesManagerFactory factory = _ -> notesManager;
        manager = new ReviewContextManager(factory, commentManager, changeSetManager, metadataLoader, reviewerManager);
    }

    @Test
    void getReviewContext_initiallyNull() {
        assertNull(manager.getReviewContext());
    }

    @Test
    void setReviewContext_null_setsCurrentContextToNull() {
        manager.setReviewContext(buildContext("r1"));
        manager.setReviewContext(null);
        assertNull(manager.getReviewContext());
    }

    @Test
    void setReviewContext_nonNull_updatesCurrentContext() {
        ReviewContext ctx = buildContext("r1");
        manager.setReviewContext(ctx);
        assertEquals(ctx, manager.getReviewContext());
    }

    // -------------------- saveComment --------------------

    @Test
    void saveComment_nullReviewId_returnsFutureWithoutCallingCommentManager() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "file.java", 1, "author", "text", "ts");
        manager.saveComment(null, comment).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveComment_emptyReviewId_returnsFutureWithoutCallingCommentManager() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "file.java", 1, "author", "text", "ts");
        manager.saveComment("", comment).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveComment_nullComment_returnsFutureWithoutCallingCommentManager() throws Exception {
        manager.saveComment("review-1", null).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveComment_noCurrentContext_returnsFutureWithoutCallingCommentManager() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "file.java", 1, "author", "text", "ts");
        manager.saveComment("review-1", comment).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveComment_contextWithEmptyRepositories_returnsFutureWithoutCallingCommentManager() throws Exception {
        ReviewContext ctx = new ReviewContext("r1", "T", "S", "A", ReviewStatus.OPEN,
                List.of(), List.of(), List.of(), "branch", "main", false);
        manager.setReviewContext(ctx);
        ReviewComment comment = new ReviewComment("c1", "file.java", 1, "author", "text", "ts");
        manager.saveComment("review-1", comment).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveComment_validContextWithRepository_delegatesToCommentManager() throws Exception {
        when(commentManager.saveComment(anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Repository repo = new Repository("repo1", "", "url");
        ReviewContext ctx = new ReviewContext("r1", "T", "S", "A", ReviewStatus.OPEN,
                List.of(), List.of(repo), List.of(), "branch", "main", false);
        manager.setReviewContext(ctx);
        ReviewComment comment = new ReviewComment("c1", "file.java", 1, "author", "text", "ts");
        manager.saveComment("review-1", comment).get(1, TimeUnit.SECONDS);
        verify(commentManager).saveComment("review-1", "repo1", comment);
    }

    // -------------------- saveAllComments --------------------

    @Test
    void saveAllComments_nullReviewId_returnsFutureWithoutCallingCommentManager() throws Exception {
        manager.saveAllComments(null, List.of()).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveAllComments_emptyReviewId_returnsFutureWithoutCallingCommentManager() throws Exception {
        manager.saveAllComments("", List.of()).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveAllComments_nullComments_returnsFutureWithoutCallingCommentManager() throws Exception {
        manager.saveAllComments("review-1", null).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveAllComments_emptyComments_returnsFutureWithoutCallingCommentManager() throws Exception {
        manager.saveAllComments("review-1", List.of()).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveAllComments_noCurrentContext_returnsFutureWithoutCallingCommentManager() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "file.java", 1, "author", "text", "ts");
        manager.saveAllComments("review-1", List.of(comment)).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveAllComments_contextWithEmptyRepositories_returnsFutureWithoutCallingCommentManager() throws Exception {
        ReviewContext ctx = new ReviewContext("r1", "T", "S", "A", ReviewStatus.OPEN,
                List.of(), List.of(), List.of(), "branch", "main", false);
        manager.setReviewContext(ctx);
        ReviewComment comment = new ReviewComment("c1", "file.java", 1, "author", "text", "ts");
        manager.saveAllComments("review-1", List.of(comment)).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(commentManager);
    }

    @Test
    void saveAllComments_validContextWithRepository_delegatesToCommentManager() throws Exception {
        when(commentManager.saveAllComments(anyString(), anyString(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Repository repo = new Repository("repo1", "", "url");
        ReviewContext ctx = new ReviewContext("r1", "T", "S", "A", ReviewStatus.OPEN,
                List.of(), List.of(repo), List.of(), "branch", "main", false);
        manager.setReviewContext(ctx);
        ReviewComment comment = new ReviewComment("c1", "file.java", 1, "author", "text", "ts");
        manager.saveAllComments("review-1", List.of(comment)).get(1, TimeUnit.SECONDS);
        verify(commentManager).saveAllComments(eq("review-1"), eq("repo1"), anyList());
    }

    // -------------------- changeSetManager delegation --------------------

    @Test
    void loadFilesForReview_delegatesToChangeSetManager() throws Exception {
        when(changeSetManager.loadFilesForReview(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        manager.loadFilesForReview("repo", "branch", "main").get(1, TimeUnit.SECONDS);
        verify(changeSetManager).loadFilesForReview("repo", "branch", "main");
    }

    @Test
    void loadFilesFromReviewCommits_delegatesToChangeSetManager() throws Exception {
        when(changeSetManager.loadFilesFromReviewCommits(anyList(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        List<Repository> repos = List.of(new Repository("repo1", "", "url"));
        manager.loadFilesFromReviewCommits(repos, "branch", "main").get(1, TimeUnit.SECONDS);
        verify(changeSetManager).loadFilesFromReviewCommits(repos, "branch", "main");
    }

    @Test
    void loadFilesFromStoredReviewCommits_delegatesToChangeSetManager() throws Exception {
        when(changeSetManager.loadFilesFromStoredReviewCommits(anyString(), anyList(), anyString(), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        List<Repository> repos = List.of(new Repository("r1", "", "url"));
        Map<String, List<String>> commits = Map.of("r1", List.of("abc"));
        manager.loadFilesFromStoredReviewCommits("review-1", repos, "branch", "main", commits).get(1, TimeUnit.SECONDS);
        verify(changeSetManager).loadFilesFromStoredReviewCommits("review-1", repos, "branch", "main", commits);
    }

    @Test
    void captureReviewCommitSnapshots_delegatesToChangeSetManager() throws Exception {
        when(changeSetManager.captureReviewCommitSnapshots(anyString(), anyList(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(Map.of()));
        List<Repository> repos = List.of(new Repository("r1", "", "url"));
        manager.captureReviewCommitSnapshots("review-1", repos, "branch", "main", "editor").get(1, TimeUnit.SECONDS);
        verify(changeSetManager).captureReviewCommitSnapshots("review-1", repos, "branch", "main", "editor");
    }

    @Test
    void loadLatestReviewCommits_delegatesToChangeSetManager() throws Exception {
        when(changeSetManager.loadLatestReviewCommits(anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(List.of()));
        manager.loadLatestReviewCommits("review-1", "repo").get(1, TimeUnit.SECONDS);
        verify(changeSetManager).loadLatestReviewCommits("review-1", "repo");
    }

    // -------------------- metadataLoader delegation --------------------

    @Test
    void loadReviewMetadata_singleArg_delegatesToMetadataLoader() throws Exception {
        ReviewContext ctx = buildContext("review-1");
        when(metadataLoader.loadReviewMetadata("review-1"))
                .thenReturn(CompletableFuture.completedFuture(ctx));
        ReviewContext result = manager.loadReviewMetadata("review-1").get(1, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(ctx, result);
        assertEquals(ctx, manager.getReviewContext());
    }

    @Test
    void loadReviewMetadataOnly_singleArg_delegatesToMetadataLoader() throws Exception {
        ReviewContext ctx = buildContext("review-1");
        when(metadataLoader.loadReviewMetadataOnly("review-1"))
                .thenReturn(CompletableFuture.completedFuture(ctx));
        ReviewContext result = manager.loadReviewMetadataOnly("review-1").get(1, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(ctx, result);
    }

    @Test
    void loadReviewMetadata_threeArgs_delegatesToMetadataLoader() throws Exception {
        ReviewContext ctx = buildContext("review-1");
        when(metadataLoader.loadReviewMetadata("review-1", List.of("repo1"), "repo1"))
                .thenReturn(CompletableFuture.completedFuture(ctx));
        ReviewContext result = manager.loadReviewMetadata("review-1", List.of("repo1"), "repo1")
                .get(1, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(ctx, result);
    }

    @Test
    void loadReviewMetadataOnly_threeArgs_delegatesToMetadataLoader() throws Exception {
        ReviewContext ctx = buildContext("review-1");
        when(metadataLoader.loadReviewMetadataOnly("review-1", List.of("repo1"), "repo1"))
                .thenReturn(CompletableFuture.completedFuture(ctx));
        ReviewContext result = manager.loadReviewMetadataOnly("review-1", List.of("repo1"), "repo1")
                .get(1, TimeUnit.SECONDS);
        assertNotNull(result);
        assertEquals(ctx, result);
    }

    @Test
    void loadReviewMetadata_returnsNullContext_doesNotSetCurrentContext() throws Exception {
        when(metadataLoader.loadReviewMetadata("review-1"))
                .thenReturn(CompletableFuture.completedFuture(null));
        ReviewContext result = manager.loadReviewMetadata("review-1").get(1, TimeUnit.SECONDS);
        assertNull(result);
        assertNull(manager.getReviewContext());
    }

    // -------------------- saveReviewMetadata --------------------

    @Test
    void saveReviewMetadata_nullContext_returnsFutureWithoutCallingNotesManager() throws Exception {
        manager.saveReviewMetadata(null).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(notesManager);
    }

    @Test
    void saveReviewMetadata_nullReviewId_returnsFutureWithoutCallingNotesManager() throws Exception {
        ReviewContext ctx = new ReviewContext(null, "T", "S", "A", ReviewStatus.OPEN,
                List.of(), List.of(new Repository("r1", "", "url")), List.of(), "branch", "main", false);
        manager.saveReviewMetadata(ctx).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(notesManager);
    }

    @Test
    void saveReviewMetadata_emptyReviewId_returnsFutureWithoutCallingNotesManager() throws Exception {
        ReviewContext ctx = new ReviewContext("", "T", "S", "A", ReviewStatus.OPEN,
                List.of(), List.of(new Repository("r1", "", "url")), List.of(), "branch", "main", false);
        manager.saveReviewMetadata(ctx).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(notesManager);
    }

    @Test
    void saveReviewMetadata_emptyRepositories_returnsFutureWithoutCallingNotesManager() throws Exception {
        ReviewContext ctx = new ReviewContext("review-1", "T", "S", "A", ReviewStatus.OPEN,
                List.of(), List.of(), List.of(), "branch", "main", false);
        manager.saveReviewMetadata(ctx).get(1, TimeUnit.SECONDS);
        verifyNoInteractions(notesManager);
    }

    @Test
    void saveReviewMetadata_validContext_callsSaveAllMetadataBatchAndUpdatesCurrentContext() throws Exception {
        when(notesManager.saveAllMetadataBatch(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Repository repo = new Repository("repo1", "", "url");
        ReviewerInfo reviewer = new ReviewerInfo("Alice");
        ReviewContext ctx = new ReviewContext("review-1", "Title", "Summary", "Author", ReviewStatus.OPEN,
                List.of(reviewer), List.of(repo), List.of(), "branch", "main", false);
        manager.saveReviewMetadata(ctx).get(2, TimeUnit.SECONDS);
        verify(notesManager).saveAllMetadataBatch(eq("review-1"), eq("Author"), eq("Title"), eq("Summary"),
                eq("Author"), eq("OPEN"), anyList());
        assertEquals(ctx, manager.getReviewContext());
    }

    @Test
    void saveReviewMetadata_nullAuthor_usesSystemAsEditor() throws Exception {
        when(notesManager.saveAllMetadataBatch(anyString(), anyString(), anyString(), anyString(),
                isNull(), anyString(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));
        Repository repo = new Repository("repo1", "", "url");
        ReviewContext ctx = new ReviewContext("review-1", "Title", "Summary", null, ReviewStatus.OPEN,
                List.of(), List.of(repo), List.of(), "branch", "main", false);
        manager.saveReviewMetadata(ctx).get(2, TimeUnit.SECONDS);
        verify(notesManager).saveAllMetadataBatch(eq("review-1"), eq("system"), anyString(), anyString(),
                isNull(), anyString(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveReviewMetadata_withCurrentContext_includesLeftEntryForRemovedReviewer() throws Exception {
        when(notesManager.saveAllMetadataBatch(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));

        Repository repo = new Repository("repo1", "", "url");
        ReviewerInfo removedReviewer = new ReviewerInfo("RemovedAlice");
        ReviewerInfo stayingReviewer = new ReviewerInfo("StayingBob");

        ReviewContext currentCtx = new ReviewContext("review-1", "T", "S", "A", ReviewStatus.OPEN,
                List.of(removedReviewer, stayingReviewer), List.of(repo), List.of(), "branch", "main", false);
        manager.setReviewContext(currentCtx);

        ReviewContext updatedCtx = new ReviewContext("review-1", "T", "S", "A", ReviewStatus.OPEN,
                List.of(stayingReviewer), List.of(repo), List.of(), "branch", "main", false);

        ArgumentCaptor<List<Map.Entry<String, com.kalynx.serverlessreviewtool.models.review.ReviewerData>>> entriesCaptor
                =  ArgumentCaptor.forClass(List.class);

        manager.saveReviewMetadata(updatedCtx).get(2, TimeUnit.SECONDS);

        verify(notesManager).saveAllMetadataBatch(eq("review-1"), anyString(), anyString(), anyString(),
                anyString(), anyString(), entriesCaptor.capture());

        List<Map.Entry<String, com.kalynx.serverlessreviewtool.models.review.ReviewerData>> captured
                = entriesCaptor.getValue();
        assertTrue(captured.stream().anyMatch(e -> "RemovedAlice".equals(e.getKey())),
                "Expected a LEFT entry for the removed reviewer RemovedAlice");
    }

    @Test
    void saveReviewMetadata_withCurrentContextButNoRemovedReviewers_doesNotAddLeftEntry() throws Exception {
        when(notesManager.saveAllMetadataBatch(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyList()))
                .thenReturn(CompletableFuture.completedFuture(null));

        Repository repo = new Repository("repo1", "", "url");
        ReviewerInfo alice = new ReviewerInfo("Alice");

        ReviewContext currentCtx = new ReviewContext("review-1", "T", "S", "A", ReviewStatus.OPEN,
                List.of(alice), List.of(repo), List.of(), "branch", "main", false);
        manager.setReviewContext(currentCtx);

        ReviewContext updatedCtx = new ReviewContext("review-1", "T", "S", "A", ReviewStatus.OPEN,
                List.of(new ReviewerInfo("Alice"), new ReviewerInfo("Bob")), List.of(repo), List.of(), "branch", "main", false);

        manager.saveReviewMetadata(updatedCtx).get(2, TimeUnit.SECONDS);
        verify(notesManager).saveAllMetadataBatch(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyList());
    }

    @Test
    void saveReviewMetadata_saveFails_throwsException() {
        when(notesManager.saveAllMetadataBatch(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyList()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("git error")));
        Repository repo = new Repository("repo1", "", "url");
        ReviewContext ctx = new ReviewContext("review-1", "T", "S", "A", ReviewStatus.OPEN,
                List.of(), List.of(repo), List.of(), "branch", "main", false);
        assertThrows(Exception.class, () -> manager.saveReviewMetadata(ctx).get(2, TimeUnit.SECONDS));
    }

    // -------------------- Listener management --------------------

    @Test
    void addListener_immediatelyCalledWithCurrentContext() {
        ReviewContext ctx = buildContext("review-1");
        manager.setReviewContext(ctx);
        List<ReviewContext> received = new ArrayList<>();
        manager.addListener(received::add);
        assertEquals(1, received.size());
        assertEquals(ctx, received.getFirst());
    }

    @Test
    void addListener_calledAgainWhenContextChanges() {
        List<ReviewContext> received = new ArrayList<>();
        manager.addListener(received::add);
        ReviewContext ctx = buildContext("review-1");
        manager.setReviewContext(ctx);
        assertEquals(2, received.size());
        assertNull(received.get(0));
        assertEquals(ctx, received.get(1));
    }

    @Test
    void addListener_withNullInitialContext_receivesNull() {
        List<ReviewContext> received = new ArrayList<>();
        manager.addListener(received::add);
        assertEquals(1, received.size());
        assertNull(received.getFirst());
    }

    @Test
    void removeListener_listenerNoLongerCalledAfterRemoval() {
        AtomicInteger callCount = new AtomicInteger(0);
        Consumer<ReviewContext> listener = _ -> callCount.incrementAndGet();
        manager.addListener(listener);
        assertEquals(1, callCount.get());
        manager.removeListener(listener);
        manager.setReviewContext(buildContext("review-1"));
        assertEquals(1, callCount.get());
    }

    @Test
    void setReviewContext_notifiesAllRegisteredListeners() {
        List<ReviewContext> firstReceived = new ArrayList<>();
        List<ReviewContext> secondReceived = new ArrayList<>();
        manager.addListener(firstReceived::add);
        manager.addListener(secondReceived::add);
        ReviewContext ctx = buildContext("review-1");
        manager.setReviewContext(ctx);
        assertTrue(firstReceived.contains(ctx));
        assertTrue(secondReceived.contains(ctx));
    }

    // -------------------- Reviewer delegation --------------------

    @Test
    void addReviewer_delegatesToReviewerManager() throws Exception {
        when(reviewerManager.addReviewer("r1", "Alice", List.of("repo1")))
                .thenReturn(CompletableFuture.completedFuture(null));
        manager.addReviewer("r1", "Alice", List.of("repo1")).get(1, TimeUnit.SECONDS);
        verify(reviewerManager).addReviewer("r1", "Alice", List.of("repo1"));
    }

    @Test
    void updateReviewerStatus_delegatesToReviewerManager() throws Exception {
        when(reviewerManager.updateReviewerStatus("r1", "Alice", ReviewerStatus.APPROVED, List.of("repo1")))
                .thenReturn(CompletableFuture.completedFuture(null));
        manager.updateReviewerStatus("r1", "Alice", ReviewerStatus.APPROVED, List.of("repo1")).get(1, TimeUnit.SECONDS);
        verify(reviewerManager).updateReviewerStatus("r1", "Alice", ReviewerStatus.APPROVED, List.of("repo1"));
    }

    @Test
    void removeReviewer_delegatesToReviewerManager() throws Exception {
        when(reviewerManager.removeReviewer("r1", "Alice", List.of("repo1")))
                .thenReturn(CompletableFuture.completedFuture(null));
        manager.removeReviewer("r1", "Alice", List.of("repo1")).get(1, TimeUnit.SECONDS);
        verify(reviewerManager).removeReviewer("r1", "Alice", List.of("repo1"));
    }

    // -------------------- helpers --------------------

    private ReviewContext buildContext(String reviewId) {
        return new ReviewContext(reviewId, "Title", "Summary", "Author", ReviewStatus.OPEN,
                List.of(), List.of(new Repository("repo1", "", "url")), List.of(),
                "branch", "main", false);
    }
}


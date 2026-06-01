package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.OrphanBranchReviewManager;
import com.kalynx.serverlessreviewtool.git.ReviewBranchManagerFactory;
import com.kalynx.serverlessreviewtool.indexer.CommentIndexerClient;
import com.kalynx.serverlessreviewtool.indexer.CommentRoutingEntry;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.plugin.dataobjects.RepositoryDescriptor;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ReviewCommentManager.
 */
class ReviewCommentManagerTests {

    private static final String REVIEW_ID = "test-review-001";
    private static final String REPO_NAME = "my-repo";
    private static final String REPO_URL = "file:///my-repo";

    private OrphanBranchReviewManager notesManager;
    private ReviewCommentManager commentManager;
    private CommentIndexerClient indexerClient;
    private RepositoryManager repositoryManager;

    @BeforeEach
    void setUp() {
        notesManager = mock(OrphanBranchReviewManager.class);
        indexerClient = mock(CommentIndexerClient.class);
        repositoryManager = new RepositoryManager();
        ReviewBranchManagerFactory factory = _ -> notesManager;
        commentManager = new ReviewCommentManager(factory, indexerClient, repositoryManager);

        when(notesManager.writeComment(anyString(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(notesManager.writeCommentStatus(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void saveComment_nullReviewId_returnsCompletedFutureWithoutWriting() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "File.java", 10, "author", "text", "ts");

        CompletableFuture<Void> result = commentManager.saveComment(null, REPO_NAME, comment);

        result.get(1, TimeUnit.SECONDS);
        verify(notesManager, never()).writeComment(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void saveComment_emptyReviewId_returnsCompletedFutureWithoutWriting() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "File.java", 10, "author", "text", "ts");

        CompletableFuture<Void> result = commentManager.saveComment("", REPO_NAME, comment);

        result.get(1, TimeUnit.SECONDS);
        verify(notesManager, never()).writeComment(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void saveComment_nullComment_returnsCompletedFutureWithoutWriting() throws Exception {
        CompletableFuture<Void> result = commentManager.saveComment(REVIEW_ID, REPO_NAME, null);

        result.get(1, TimeUnit.SECONDS);
        verify(notesManager, never()).writeComment(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void saveComment_regularComment_writesMetadataAndTextButNotStatus() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "File.java", 10, "alice", "looks good", "ts");

        commentManager.saveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager).writeComment(eq(REVIEW_ID), eq("c1"), eq("alice"), any(), any(), isNull());
    }

    @Test
    void saveComment_commentNeedingResolution_writesMetadataTextAndStatus() throws Exception {
        ReviewComment comment = new ReviewComment("c2", "Main.java", 5, "bob", "fix this", "ts", null, true);

        commentManager.saveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager).writeComment(eq(REVIEW_ID), eq("c2"), eq("bob"), any(), any(), notNull());
    }

    @Test
    void saveAllComments_emptyList_returnsImmediatelyWithoutWriting() throws Exception {
        CompletableFuture<Void> result = commentManager.saveAllComments(REVIEW_ID, REPO_NAME, List.of());

        result.get(1, TimeUnit.SECONDS);
        verify(notesManager, never()).writeComment(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void saveAllComments_multipleComments_savesEachComment() throws Exception {
        ReviewComment c1 = new ReviewComment("c1", "A.java", 1, "alice", "text1", "ts");
        ReviewComment c2 = new ReviewComment("c2", "B.java", 2, "bob", "text2", "ts");

        commentManager.saveAllComments(REVIEW_ID, REPO_NAME, List.of(c1, c2)).get(1, TimeUnit.SECONDS);

        verify(notesManager, times(2)).writeComment(eq(REVIEW_ID), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void reloadComment_commentMissingMetadata_returnsNull() throws Exception {
        repositoryManager.setRepositoriesFromNotification(
            List.of(new RepositoryDescriptor(REPO_NAME, REPO_URL)));

        String commentId = "c-no-meta";
        OrphanBranchReviewManager.CommentTextData text =
            new OrphanBranchReviewManager.CommentTextData("text", null, "comment");
        StreamEntry<OrphanBranchReviewManager.CommentTextData> textEntry =
            new StreamEntry<>("t1", Instant.now(), "dev", text);

        Map<String, OrphanBranchReviewManager.AllCommentData> data = new LinkedHashMap<>();
        data.put(commentId, new OrphanBranchReviewManager.AllCommentData(List.of(), List.of(textEntry), List.of()));
        when(notesManager.readAllComments(eq(REVIEW_ID), anyList()))
            .thenReturn(CompletableFuture.completedFuture(data));

        ReviewComment result = commentManager.reloadComment(REVIEW_ID, REPO_URL, commentId)
            .get(2, TimeUnit.SECONDS);

        assertNull(result);
    }

    @Test
    void reloadComment_withStatusEntryResolved_marksCommentResolved() throws Exception {
        repositoryManager.setRepositoriesFromNotification(
            List.of(new RepositoryDescriptor(REPO_NAME, REPO_URL)));

        String commentId = "c-resolved";
        OrphanBranchReviewManager.CommentMetadata meta =
            new OrphanBranchReviewManager.CommentMetadata("X.java", 1, 1, null);
        OrphanBranchReviewManager.CommentTextData text =
            new OrphanBranchReviewManager.CommentTextData("text", null, "review");
        OrphanBranchReviewManager.CommentStatusData status =
            new OrphanBranchReviewManager.CommentStatusData(true, true);
        StreamEntry<OrphanBranchReviewManager.CommentMetadata> metaEntry =
            new StreamEntry<>("m1", Instant.now(), "alice", meta);
        StreamEntry<OrphanBranchReviewManager.CommentTextData> textEntry =
            new StreamEntry<>("t1", Instant.now(), "alice", text);
        StreamEntry<OrphanBranchReviewManager.CommentStatusData> statusEntry =
            new StreamEntry<>("s1", Instant.now(), "bob", status);

        Map<String, OrphanBranchReviewManager.AllCommentData> data = new LinkedHashMap<>();
        data.put(commentId, new OrphanBranchReviewManager.AllCommentData(
            List.of(metaEntry), List.of(textEntry), List.of(statusEntry)));
        when(notesManager.readAllComments(eq(REVIEW_ID), anyList()))
            .thenReturn(CompletableFuture.completedFuture(data));

        ReviewComment result = commentManager.reloadComment(REVIEW_ID, REPO_URL, commentId)
            .get(2, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isResolved());
        assertTrue(result.needsResolution());
    }

    @Test
    void reloadComment_withStatusEntryUnresolved_marksCommentUnresolved() throws Exception {
        repositoryManager.setRepositoriesFromNotification(
            List.of(new RepositoryDescriptor(REPO_NAME, REPO_URL)));

        String commentId = "c-unresolved";
        OrphanBranchReviewManager.CommentMetadata meta =
            new OrphanBranchReviewManager.CommentMetadata("X.java", 1, 1, null);
        OrphanBranchReviewManager.CommentTextData text =
            new OrphanBranchReviewManager.CommentTextData("text", null, "review");
        OrphanBranchReviewManager.CommentStatusData status =
            new OrphanBranchReviewManager.CommentStatusData(true, false);
        StreamEntry<OrphanBranchReviewManager.CommentMetadata> metaEntry =
            new StreamEntry<>("m1", Instant.now(), "alice", meta);
        StreamEntry<OrphanBranchReviewManager.CommentTextData> textEntry =
            new StreamEntry<>("t1", Instant.now(), "alice", text);
        StreamEntry<OrphanBranchReviewManager.CommentStatusData> statusEntry =
            new StreamEntry<>("s1", Instant.now(), "bob", status);

        Map<String, OrphanBranchReviewManager.AllCommentData> data = new LinkedHashMap<>();
        data.put(commentId, new OrphanBranchReviewManager.AllCommentData(
            List.of(metaEntry), List.of(textEntry), List.of(statusEntry)));
        when(notesManager.readAllComments(eq(REVIEW_ID), anyList()))
            .thenReturn(CompletableFuture.completedFuture(data));

        ReviewComment result = commentManager.reloadComment(REVIEW_ID, REPO_URL, commentId)
            .get(2, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.needsResolution());
        assertFalse(result.isResolved());
    }

    @Test
    void saveComment_resolvedComment_writesMetadataTextAndStatus() throws Exception {
        ReviewComment comment = new ReviewComment("c3", "Main.java", 10, "charlie", "done", "ts");
        comment.markResolved("charlie");

        commentManager.saveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager).writeComment(eq(REVIEW_ID), eq("c3"), eq("charlie"), any(), any(), notNull());
    }

    @Test
    void saveAllComments_nullReviewId_returnsCompletedFutureWithoutWriting() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "A.java", 1, "alice", "text", "ts");

        commentManager.saveAllComments(null, REPO_NAME, List.of(comment)).get(1, TimeUnit.SECONDS);

        verify(notesManager, never()).writeComment(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void saveAllComments_nullComments_returnsCompletedFutureWithoutWriting() throws Exception {
        commentManager.saveAllComments(REVIEW_ID, REPO_NAME, null).get(1, TimeUnit.SECONDS);

        verify(notesManager, never()).writeComment(anyString(), anyString(), anyString(), any(), any(), any());
    }

    // ── S1: parallel writes ──────────────────────────────────────────────────

    @Test
    void saveComment_regularComment_metadataAndTextWrittenOnceEach() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "File.java", 10, "alice", "text", "ts");

        commentManager.saveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager, times(1)).writeComment(eq(REVIEW_ID), eq("c1"), eq("alice"), any(), any(), isNull());
    }

    @Test
    void saveComment_commentNeedingResolution_allThreeStreamsWritten() throws Exception {
        ReviewComment comment = new ReviewComment("c2", "File.java", 5, "bob", "fix", "ts", null, true);

        commentManager.saveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager, times(1)).writeComment(any(), any(), any(), any(), any(), notNull());
    }

    @Test
    void saveComment_resolvedComment_allThreeStreamsWritten() throws Exception {
        ReviewComment comment = new ReviewComment("c3", "File.java", 3, "carol", "done", "ts");
        comment.markResolved("carol");

        commentManager.saveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager, times(1)).writeComment(any(), any(), any(), any(), any(), notNull());
    }

    @Test
    void saveComment_regularComment_noStatusStreamWritten() throws Exception {
        ReviewComment comment = new ReviewComment("c4", "File.java", 7, "dave", "ok", "ts");

        commentManager.saveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager).writeComment(eq(REVIEW_ID), eq("c4"), eq("dave"), any(), any(), isNull());
    }

    // ── V1: resolve-only path ────────────────────────────────────────────────

    @Test
    void resolveComment_writesOnlyStatusStream() throws Exception {
        ReviewComment comment = new ReviewComment("c5", "File.java", 1, "eve", "text", "ts", null, true);
        comment.markResolved("eve");

        commentManager.resolveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager, times(1)).writeCommentStatus(eq(REVIEW_ID), eq("c5"), eq("eve"), any(), any());
        verify(notesManager, never()).writeComment(anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void resolveComment_nullReviewId_returnsWithoutWriting() throws Exception {
        ReviewComment comment = new ReviewComment("c5", "File.java", 1, "eve", "text", "ts");

        commentManager.resolveComment(null, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager, never()).writeCommentStatus(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void resolveComment_nullComment_returnsWithoutWriting() throws Exception {
        commentManager.resolveComment(REVIEW_ID, REPO_NAME, null).get(1, TimeUnit.SECONDS);

        verify(notesManager, never()).writeCommentStatus(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void resolveComment_notResolvedComment_writesStatusWithCorrectValues() throws Exception {
        ReviewComment comment = new ReviewComment("c6", "File.java", 2, "frank", "text", "ts", null, true);

        commentManager.resolveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager).writeCommentStatus(eq(REVIEW_ID), eq("c6"), eq("frank"), eq(true), eq(false));
    }

    // ── loadAllComments ──────────────────────────────────────────────────────

    @Test
    void loadAllComments_nullReviewId_returnsEmptyList() throws Exception {
        List<ReviewComment> result = commentManager.loadAllComments(null).get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadAllComments_emptyReviewId_returnsEmptyList() throws Exception {
        List<ReviewComment> result = commentManager.loadAllComments("").get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadAllComments_indexerReturnsEmptyRouting_returnsEmptyList() throws Exception {
        when(indexerClient.getCommentRouting(REVIEW_ID)).thenReturn(List.of());

        List<ReviewComment> result = commentManager.loadAllComments(REVIEW_ID).get(2, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadAllComments_routingEntryWithUnknownUrl_skipsAndReturnsEmpty() throws Exception {
        when(indexerClient.getCommentRouting(REVIEW_ID))
            .thenReturn(List.of(new CommentRoutingEntry("file:///unknown-repo", "c1", "2026-01-01")));

        List<ReviewComment> result = commentManager.loadAllComments(REVIEW_ID).get(2, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadAllComments_routingEntryWithKnownUrl_loadsComment() throws Exception {
        repositoryManager.setRepositoriesFromNotification(
            List.of(new RepositoryDescriptor(REPO_NAME, REPO_URL)));

        String commentId = "c-load-all";
        when(indexerClient.getCommentRouting(REVIEW_ID))
            .thenReturn(List.of(new CommentRoutingEntry(REPO_URL, commentId, "2026-01-01")));

        OrphanBranchReviewManager.CommentMetadata meta =
            new OrphanBranchReviewManager.CommentMetadata("A.java", 3, 3, null);
        OrphanBranchReviewManager.CommentTextData text =
            new OrphanBranchReviewManager.CommentTextData("hello", null, "comment");
        StreamEntry<OrphanBranchReviewManager.CommentMetadata> metaEntry =
            new StreamEntry<>("m1", java.time.Instant.now(), "alice", meta);
        StreamEntry<OrphanBranchReviewManager.CommentTextData> textEntry =
            new StreamEntry<>("t1", java.time.Instant.now(), "alice", text);

        Map<String, OrphanBranchReviewManager.AllCommentData> data = new LinkedHashMap<>();
        data.put(commentId, new OrphanBranchReviewManager.AllCommentData(
            List.of(metaEntry), List.of(textEntry), List.of()));
        when(notesManager.readAllComments(eq(REVIEW_ID), anyList()))
            .thenReturn(CompletableFuture.completedFuture(data));

        List<ReviewComment> result = commentManager.loadAllComments(REVIEW_ID).get(2, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertEquals(commentId, result.getFirst().getId());
        assertEquals("A.java", result.getFirst().getFilePath());
    }

    @Test
    void loadAllComments_twoEntriesSameRepo_loadsBoth() throws Exception {
        repositoryManager.setRepositoriesFromNotification(
            List.of(new RepositoryDescriptor(REPO_NAME, REPO_URL)));

        when(indexerClient.getCommentRouting(REVIEW_ID))
            .thenReturn(List.of(
                new CommentRoutingEntry(REPO_URL, "c-one", "2026-01-01"),
                new CommentRoutingEntry(REPO_URL, "c-two", "2026-01-02")));

        OrphanBranchReviewManager.CommentMetadata meta =
            new OrphanBranchReviewManager.CommentMetadata("B.java", 1, 1, null);
        OrphanBranchReviewManager.CommentTextData text =
            new OrphanBranchReviewManager.CommentTextData("ok", null, "comment");
        StreamEntry<OrphanBranchReviewManager.CommentMetadata> metaEntry =
            new StreamEntry<>("m1", java.time.Instant.now(), "dev", meta);
        StreamEntry<OrphanBranchReviewManager.CommentTextData> textEntry =
            new StreamEntry<>("t1", java.time.Instant.now(), "dev", text);

        Map<String, OrphanBranchReviewManager.AllCommentData> data = new LinkedHashMap<>();
        data.put("c-one", new OrphanBranchReviewManager.AllCommentData(List.of(metaEntry), List.of(textEntry), List.of()));
        data.put("c-two", new OrphanBranchReviewManager.AllCommentData(List.of(metaEntry), List.of(textEntry), List.of()));
        when(notesManager.readAllComments(eq(REVIEW_ID), anyList()))
            .thenReturn(CompletableFuture.completedFuture(data));

        List<ReviewComment> result = commentManager.loadAllComments(REVIEW_ID).get(2, TimeUnit.SECONDS);

        assertEquals(2, result.size());
    }

    // ── reloadComment ────────────────────────────────────────────────────────

    @Test
    void reloadComment_nullReviewId_returnsNull() throws Exception {
        ReviewComment result = commentManager.reloadComment(null, REPO_URL, "c1").get(1, TimeUnit.SECONDS);

        assertNull(result);
    }

    @Test
    void reloadComment_unknownRepositoryUrl_returnsNull() throws Exception {
        ReviewComment result = commentManager.reloadComment(REVIEW_ID, "file:///no-such-repo", "c1")
            .get(1, TimeUnit.SECONDS);

        assertNull(result);
    }

    @Test
    void reloadComment_knownUrl_loadsComment() throws Exception {
        repositoryManager.setRepositoriesFromNotification(
            List.of(new RepositoryDescriptor(REPO_NAME, REPO_URL)));

        String commentId = "c-reload";
        OrphanBranchReviewManager.CommentMetadata meta =
            new OrphanBranchReviewManager.CommentMetadata("C.java", 5, 5, null);
        OrphanBranchReviewManager.CommentTextData text =
            new OrphanBranchReviewManager.CommentTextData("reloaded", null, "comment");
        StreamEntry<OrphanBranchReviewManager.CommentMetadata> metaEntry =
            new StreamEntry<>("m1", java.time.Instant.now(), "bob", meta);
        StreamEntry<OrphanBranchReviewManager.CommentTextData> textEntry =
            new StreamEntry<>("t1", java.time.Instant.now(), "bob", text);

        Map<String, OrphanBranchReviewManager.AllCommentData> data = new LinkedHashMap<>();
        data.put(commentId, new OrphanBranchReviewManager.AllCommentData(
            List.of(metaEntry), List.of(textEntry), List.of()));
        when(notesManager.readAllComments(eq(REVIEW_ID), anyList()))
            .thenReturn(CompletableFuture.completedFuture(data));

        ReviewComment result = commentManager.reloadComment(REVIEW_ID, REPO_URL, commentId)
            .get(2, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(commentId, result.getId());
        assertEquals("C.java", result.getFilePath());
    }
}

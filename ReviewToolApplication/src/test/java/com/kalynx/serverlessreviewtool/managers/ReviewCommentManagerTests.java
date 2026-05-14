package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.GitReviewNotesManager;
import com.kalynx.serverlessreviewtool.git.ReviewNotesManagerFactory;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private GitReviewNotesManager notesManager;
    private ReviewCommentManager commentManager;

    @BeforeEach
    void setUp() {
        notesManager = mock(GitReviewNotesManager.class);
        ReviewNotesManagerFactory factory = _ -> notesManager;
        commentManager = new ReviewCommentManager(factory);

        when(notesManager.writeCommentMetadata(anyString(), anyString(), anyString(), anyString(), any(int.class), any(int.class), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(notesManager.writeCommentText(anyString(), anyString(), anyString(), anyString(), any(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        when(notesManager.writeCommentStatus(anyString(), anyString(), anyString(), any(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void saveComment_nullReviewId_returnsCompletedFutureWithoutWriting() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "File.java", 10, "author", "text", "ts");

        CompletableFuture<Void> result = commentManager.saveComment(null, REPO_NAME, comment);

        result.get(1, TimeUnit.SECONDS);
        verify(notesManager, never()).writeCommentMetadata(anyString(), anyString(), anyString(), anyString(), any(int.class), any(int.class), any());
    }

    @Test
    void saveComment_emptyReviewId_returnsCompletedFutureWithoutWriting() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "File.java", 10, "author", "text", "ts");

        CompletableFuture<Void> result = commentManager.saveComment("", REPO_NAME, comment);

        result.get(1, TimeUnit.SECONDS);
        verify(notesManager, never()).writeCommentMetadata(anyString(), anyString(), anyString(), anyString(), any(int.class), any(int.class), any());
    }

    @Test
    void saveComment_nullComment_returnsCompletedFutureWithoutWriting() throws Exception {
        CompletableFuture<Void> result = commentManager.saveComment(REVIEW_ID, REPO_NAME, null);

        result.get(1, TimeUnit.SECONDS);
        verify(notesManager, never()).writeCommentMetadata(anyString(), anyString(), anyString(), anyString(), any(int.class), any(int.class), any());
    }

    @Test
    void saveComment_regularComment_writesMetadataAndTextButNotStatus() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "File.java", 10, "alice", "looks good", "ts");

        commentManager.saveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager).writeCommentMetadata(eq(REVIEW_ID), eq("c1"), eq("alice"), eq("File.java"), eq(10), eq(10), any());
        verify(notesManager).writeCommentText(eq(REVIEW_ID), eq("c1"), eq("alice"), eq("looks good"), any(), eq("comment"));
        verify(notesManager, never()).writeCommentStatus(anyString(), anyString(), anyString(), any(), any());
    }

    @Test
    void saveComment_commentNeedingResolution_writesMetadataTextAndStatus() throws Exception {
        ReviewComment comment = new ReviewComment("c2", "Main.java", 5, "bob", "fix this", "ts", null, true);

        commentManager.saveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager).writeCommentMetadata(eq(REVIEW_ID), eq("c2"), eq("bob"), eq("Main.java"), eq(5), eq(5), any());
        verify(notesManager).writeCommentText(eq(REVIEW_ID), eq("c2"), eq("bob"), eq("fix this"), any(), eq("review"));
        verify(notesManager).writeCommentStatus(eq(REVIEW_ID), eq("c2"), eq("bob"), eq(true), any());
    }

    @Test
    void saveAllComments_emptyList_returnsImmediatelyWithoutWriting() throws Exception {
        CompletableFuture<Void> result = commentManager.saveAllComments(REVIEW_ID, REPO_NAME, List.of());

        result.get(1, TimeUnit.SECONDS);
        verify(notesManager, never()).writeCommentMetadata(anyString(), anyString(), anyString(), anyString(), any(int.class), any(int.class), any());
    }

    @Test
    void saveAllComments_multipleComments_savesEachComment() throws Exception {
        ReviewComment c1 = new ReviewComment("c1", "A.java", 1, "alice", "text1", "ts");
        ReviewComment c2 = new ReviewComment("c2", "B.java", 2, "bob", "text2", "ts");

        commentManager.saveAllComments(REVIEW_ID, REPO_NAME, List.of(c1, c2)).get(1, TimeUnit.SECONDS);

        verify(notesManager, times(2)).writeCommentMetadata(eq(REVIEW_ID), anyString(), anyString(), anyString(), any(int.class), any(int.class), any());
        verify(notesManager, times(2)).writeCommentText(eq(REVIEW_ID), anyString(), anyString(), anyString(), any(), eq("comment"));
    }

    @Test
    void loadCommentsFromKnownRepository_noCommentIds_returnsEmptyList() throws Exception {
        when(notesManager.listCommentIds(REVIEW_ID)).thenReturn(CompletableFuture.completedFuture(List.of()));

        List<ReviewComment> result = commentManager.loadCommentsFromKnownRepository(REVIEW_ID, REPO_NAME)
            .get(2, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadCommentsFromKnownRepository_withOneComment_loadsAndAssembles() throws Exception {
        String commentId = UUID.randomUUID().toString();
        GitReviewNotesManager.CommentMetadata meta = new GitReviewNotesManager.CommentMetadata("Foo.java", 7, 7, null);
        GitReviewNotesManager.CommentTextData text = new GitReviewNotesManager.CommentTextData("hello world", null, "comment");
        StreamEntry<GitReviewNotesManager.CommentMetadata> metaEntry = new StreamEntry<>("id1", Instant.now(), "alice", meta);
        StreamEntry<GitReviewNotesManager.CommentTextData> textEntry = new StreamEntry<>("id2", Instant.now(), "alice", text);

        when(notesManager.listCommentIds(REVIEW_ID)).thenReturn(CompletableFuture.completedFuture(List.of(commentId)));
        when(notesManager.readCommentMetadata(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of(metaEntry)));
        when(notesManager.readCommentText(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of(textEntry)));
        when(notesManager.readCommentStatus(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of()));

        List<ReviewComment> result = commentManager.loadCommentsFromKnownRepository(REVIEW_ID, REPO_NAME)
            .get(2, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertEquals(commentId, result.getFirst().getId());
        assertEquals("Foo.java", result.getFirst().getFilePath());
        assertEquals(7, result.getFirst().getLineNumber());
        assertEquals("alice", result.getFirst().getAuthor());
        assertEquals("hello world", result.getFirst().getText());
    }

    @Test
    void loadCommentsFromKnownRepository_withMultipleComments_loadsAll() throws Exception {
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        GitReviewNotesManager.CommentMetadata meta = new GitReviewNotesManager.CommentMetadata("X.java", 1, 1, null);
        GitReviewNotesManager.CommentTextData text = new GitReviewNotesManager.CommentTextData("ok", null, "comment");
        StreamEntry<GitReviewNotesManager.CommentMetadata> metaEntry = new StreamEntry<>("m1", Instant.now(), "dev", meta);
        StreamEntry<GitReviewNotesManager.CommentTextData> textEntry = new StreamEntry<>("t1", Instant.now(), "dev", text);

        when(notesManager.listCommentIds(REVIEW_ID)).thenReturn(CompletableFuture.completedFuture(List.of(id1, id2)));
        when(notesManager.readCommentMetadata(eq(REVIEW_ID), anyString())).thenReturn(CompletableFuture.completedFuture(List.of(metaEntry)));
        when(notesManager.readCommentText(eq(REVIEW_ID), anyString())).thenReturn(CompletableFuture.completedFuture(List.of(textEntry)));
        when(notesManager.readCommentStatus(eq(REVIEW_ID), anyString())).thenReturn(CompletableFuture.completedFuture(List.of()));

        List<ReviewComment> result = commentManager.loadCommentsFromKnownRepository(REVIEW_ID, REPO_NAME)
            .get(2, TimeUnit.SECONDS);

        assertEquals(2, result.size());
    }

    @Test
    void loadCommentsFromKnownRepository_commentMissingMetadata_skipsNullEntry() throws Exception {
        String commentId = UUID.randomUUID().toString();
        GitReviewNotesManager.CommentTextData text = new GitReviewNotesManager.CommentTextData("text", null, "comment");
        StreamEntry<GitReviewNotesManager.CommentTextData> textEntry = new StreamEntry<>("t1", Instant.now(), "dev", text);

        when(notesManager.listCommentIds(REVIEW_ID)).thenReturn(CompletableFuture.completedFuture(List.of(commentId)));
        when(notesManager.readCommentMetadata(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of()));
        when(notesManager.readCommentText(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of(textEntry)));
        when(notesManager.readCommentStatus(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of()));

        List<ReviewComment> result = commentManager.loadCommentsFromKnownRepository(REVIEW_ID, REPO_NAME)
            .get(2, TimeUnit.SECONDS);

        assertTrue(result.isEmpty());
    }

    @Test
    void saveComment_resolvedComment_writesMetadataTextAndStatus() throws Exception {
        ReviewComment comment = new ReviewComment("c3", "Main.java", 10, "charlie", "done", "ts");
        comment.markResolved("charlie");

        commentManager.saveComment(REVIEW_ID, REPO_NAME, comment).get(1, TimeUnit.SECONDS);

        verify(notesManager).writeCommentMetadata(eq(REVIEW_ID), eq("c3"), eq("charlie"), eq("Main.java"), eq(10), eq(10), any());
        verify(notesManager).writeCommentStatus(eq(REVIEW_ID), eq("c3"), eq("charlie"), any(), any());
    }

    @Test
    void saveAllComments_nullReviewId_returnsCompletedFutureWithoutWriting() throws Exception {
        ReviewComment comment = new ReviewComment("c1", "A.java", 1, "alice", "text", "ts");

        commentManager.saveAllComments(null, REPO_NAME, List.of(comment)).get(1, TimeUnit.SECONDS);

        verify(notesManager, never()).writeCommentMetadata(anyString(), anyString(), anyString(), anyString(), any(int.class), any(int.class), any());
    }

    @Test
    void saveAllComments_nullComments_returnsCompletedFutureWithoutWriting() throws Exception {
        commentManager.saveAllComments(REVIEW_ID, REPO_NAME, null).get(1, TimeUnit.SECONDS);

        verify(notesManager, never()).writeCommentMetadata(anyString(), anyString(), anyString(), anyString(), any(int.class), any(int.class), any());
    }

    @Test
    void loadCommentsFromKnownRepository_withStatusEntryResolved_marksCommentResolved() throws Exception {
        String commentId = UUID.randomUUID().toString();
        GitReviewNotesManager.CommentMetadata meta = new GitReviewNotesManager.CommentMetadata("X.java", 1, 1, null);
        GitReviewNotesManager.CommentTextData text = new GitReviewNotesManager.CommentTextData("text", null, "review");
        GitReviewNotesManager.CommentStatusData status = new GitReviewNotesManager.CommentStatusData(true, true);

        StreamEntry<GitReviewNotesManager.CommentMetadata> metaEntry = new StreamEntry<>("m1", Instant.now(), "alice", meta);
        StreamEntry<GitReviewNotesManager.CommentTextData> textEntry = new StreamEntry<>("t1", Instant.now(), "alice", text);
        StreamEntry<GitReviewNotesManager.CommentStatusData> statusEntry = new StreamEntry<>("s1", Instant.now(), "bob", status);

        when(notesManager.listCommentIds(REVIEW_ID)).thenReturn(CompletableFuture.completedFuture(List.of(commentId)));
        when(notesManager.readCommentMetadata(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of(metaEntry)));
        when(notesManager.readCommentText(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of(textEntry)));
        when(notesManager.readCommentStatus(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of(statusEntry)));

        List<ReviewComment> result = commentManager.loadCommentsFromKnownRepository(REVIEW_ID, REPO_NAME)
            .get(2, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().isResolved());
        assertTrue(result.getFirst().needsResolution());
    }

    @Test
    void loadCommentsFromKnownRepository_withStatusEntryUnresolved_marksCommentUnresolved() throws Exception {
        String commentId = UUID.randomUUID().toString();
        GitReviewNotesManager.CommentMetadata meta = new GitReviewNotesManager.CommentMetadata("X.java", 1, 1, null);
        GitReviewNotesManager.CommentTextData text = new GitReviewNotesManager.CommentTextData("text", null, "review");
        GitReviewNotesManager.CommentStatusData status = new GitReviewNotesManager.CommentStatusData(true, false);

        StreamEntry<GitReviewNotesManager.CommentMetadata> metaEntry = new StreamEntry<>("m1", Instant.now(), "alice", meta);
        StreamEntry<GitReviewNotesManager.CommentTextData> textEntry = new StreamEntry<>("t1", Instant.now(), "alice", text);
        StreamEntry<GitReviewNotesManager.CommentStatusData> statusEntry = new StreamEntry<>("s1", Instant.now(), "bob", status);

        when(notesManager.listCommentIds(REVIEW_ID)).thenReturn(CompletableFuture.completedFuture(List.of(commentId)));
        when(notesManager.readCommentMetadata(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of(metaEntry)));
        when(notesManager.readCommentText(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of(textEntry)));
        when(notesManager.readCommentStatus(REVIEW_ID, commentId)).thenReturn(CompletableFuture.completedFuture(List.of(statusEntry)));

        List<ReviewComment> result = commentManager.loadCommentsFromKnownRepository(REVIEW_ID, REPO_NAME)
            .get(2, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertTrue(result.getFirst().needsResolution());
        assertFalse(result.getFirst().isResolved());
    }

    @Test
    void loadCommentsFromKnownRepository_listCommentIdsFails_returnsEmptyList() throws Exception {
        when(notesManager.listCommentIds(REVIEW_ID))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("git notes error")));

        List<ReviewComment> result = commentManager.loadCommentsFromKnownRepository(REVIEW_ID, REPO_NAME)
            .get(2, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}


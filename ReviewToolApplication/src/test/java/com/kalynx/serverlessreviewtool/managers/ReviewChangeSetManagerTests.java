package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.git.GitReviewNotesManager;
import com.kalynx.serverlessreviewtool.git.ReviewNotesManagerFactory;
import com.kalynx.serverlessreviewtool.models.FileChangeType;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ReviewChangeSetManager.
 */
class ReviewChangeSetManagerTests {

    private static final String REPO = "my-repo";
    private static final String REVIEW_BRANCH = "feature/my-feature";
    private static final String BASE_BRANCH = "main";

    private Git git;
    private GitReviewNotesManager notesManager;
    private ReviewChangeSetManager changeSetManager;

    @BeforeEach
    void setUp() {
        git = mock(Git.class);
        notesManager = mock(GitReviewNotesManager.class);
        ReviewNotesManagerFactory factory = _ -> notesManager;
        changeSetManager = new ReviewChangeSetManager(git, factory);

        when(git.getDefaultBranch(anyString())).thenReturn(CompletableFuture.completedFuture("main"));

        when(git.executeAsync(anyString(), eq("rev-parse"), eq("--verify"), any()))
            .thenReturn(CompletableFuture.completedFuture("abc123"));
    }

    @Test
    void loadFilesForReview_nullRepositoryName_returnsEmptyList() throws Exception {
        List<ReviewFile> result = changeSetManager.loadFilesForReview(null, REVIEW_BRANCH, BASE_BRANCH)
            .get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadFilesForReview_nullBranch_returnsEmptyList() throws Exception {
        List<ReviewFile> result = changeSetManager.loadFilesForReview(REPO, null, BASE_BRANCH)
            .get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadFilesForReview_nullBaseBranch_returnsEmptyList() throws Exception {
        List<ReviewFile> result = changeSetManager.loadFilesForReview(REPO, REVIEW_BRANCH, null)
            .get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadFilesForReview_refResolvesAndDiffReturnsFiles_parsesStatusAndPath() throws Exception {
        when(git.executeAsync(eq(REPO), eq("rev-parse"), eq("--verify"), eq("origin/" + REVIEW_BRANCH)))
            .thenReturn(CompletableFuture.completedFuture("def456"));
        when(git.listChangedFiles(eq(REPO), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of("A src/Main.java", "M src/Foo.java", "D src/Old.java")));

        List<ReviewFile> result = changeSetManager.loadFilesForReview(REPO, REVIEW_BRANCH, BASE_BRANCH)
            .get(2, TimeUnit.SECONDS);

        assertEquals(3, result.size());

        ReviewFile added = result.stream().filter(f -> f.getPath().contains("Main.java")).findFirst().orElseThrow();
        assertEquals(FileChangeType.ADDED, added.getChangeType());

        ReviewFile modified = result.stream().filter(f -> f.getPath().contains("Foo.java")).findFirst().orElseThrow();
        assertEquals(FileChangeType.MODIFIED, modified.getChangeType());

        ReviewFile deleted = result.stream().filter(f -> f.getPath().contains("Old.java")).findFirst().orElseThrow();
        assertEquals(FileChangeType.DELETED, deleted.getChangeType());
    }

    @Test
    void loadFilesForReview_renamedFile_parsedAsRenamed() throws Exception {
        when(git.executeAsync(eq(REPO), eq("rev-parse"), eq("--verify"), eq("origin/" + REVIEW_BRANCH)))
            .thenReturn(CompletableFuture.completedFuture("def456"));
        when(git.listChangedFiles(eq(REPO), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of("R src/Renamed.java")));

        List<ReviewFile> result = changeSetManager.loadFilesForReview(REPO, REVIEW_BRANCH, BASE_BRANCH)
            .get(2, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertEquals(FileChangeType.RENAMED, result.getFirst().getChangeType());
    }

    @Test
    void loadFilesFromReviewCommits_nullRepositories_returnsEmptyList() throws Exception {
        List<ReviewFile> result = changeSetManager.loadFilesFromReviewCommits(null, REVIEW_BRANCH, BASE_BRANCH)
            .get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadFilesFromReviewCommits_emptyRepositories_returnsEmptyList() throws Exception {
        List<ReviewFile> result = changeSetManager.loadFilesFromReviewCommits(List.of(), REVIEW_BRANCH, BASE_BRANCH)
            .get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadLatestReviewCommits_nullReviewId_returnsEmptyList() throws Exception {
        List<String> result = changeSetManager.loadLatestReviewCommits(null, REPO).get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadLatestReviewCommits_nullRepoName_returnsEmptyList() throws Exception {
        List<String> result = changeSetManager.loadLatestReviewCommits("review-1", null).get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadFilesFromStoredReviewCommits_nullReviewId_returnsEmptyList() throws Exception {
        List<ReviewFile> result = changeSetManager.loadFilesFromStoredReviewCommits(
            null, List.of(), REVIEW_BRANCH, BASE_BRANCH, null).get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void captureReviewCommitSnapshots_nullRepoList_returnsEmptyMap() throws Exception {
        var result = changeSetManager.captureReviewCommitSnapshots("r1", null, REVIEW_BRANCH, BASE_BRANCH, "ed")
            .get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void captureReviewCommitSnapshots_emptyBranch_returnsEmptyMap() throws Exception {
        var result = changeSetManager.captureReviewCommitSnapshots("r1", List.of(), "", BASE_BRANCH, "ed")
            .get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadFilesFromReviewCommits_nullBranch_returnsEmptyList() throws Exception {
        Repository repo = new Repository(REPO, "", "file:///repo");
        List<ReviewFile> result = changeSetManager.loadFilesFromReviewCommits(List.of(repo), null, BASE_BRANCH)
            .get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadFilesFromReviewCommits_validRepos_combinesFiles() throws Exception {
        Repository repo = new Repository(REPO, "", "file:///repo");

        when(git.executeAsync(eq(REPO), eq("rev-parse"), eq("--verify"), any()))
            .thenReturn(CompletableFuture.completedFuture("sha123"));
        when(git.listChangedFiles(eq(REPO), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of("M src/Foo.java")));

        List<ReviewFile> result = changeSetManager.loadFilesFromReviewCommits(
            List.of(repo), REVIEW_BRANCH, BASE_BRANCH).get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(FileChangeType.MODIFIED, result.getFirst().getChangeType());
    }

    @Test
    void loadFilesFromStoredReviewCommits_withStoredCommits_loadsByCommit() throws Exception {
        Repository repo = new Repository(REPO, "", "file:///repo");
        String commitHash = "abc123def";

        when(git.executeAsync(eq(REPO), eq("show"), eq("--name-status"), eq("--pretty=format:"),
                eq("--root"), eq(commitHash)))
            .thenReturn(CompletableFuture.completedFuture("A\tsrc/NewFile.java\n"));

        List<ReviewFile> result = changeSetManager.loadFilesFromStoredReviewCommits(
            "review-1", List.of(repo), REVIEW_BRANCH, BASE_BRANCH,
            Map.of(REPO, List.of(commitHash))).get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(1, result.size());
    }

    @Test
    void loadFilesFromStoredReviewCommits_noStoredCommitsForRepo_fallsBackToBranchDiff() throws Exception {
        Repository repo = new Repository(REPO, "", "file:///repo");

        when(git.listChangedFiles(eq(REPO), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of("M src/Service.java")));

        List<ReviewFile> result = changeSetManager.loadFilesFromStoredReviewCommits(
            "review-1", List.of(repo), REVIEW_BRANCH, BASE_BRANCH, Map.of()
        ).get(3, TimeUnit.SECONDS);

        assertNotNull(result);
    }

    @Test
    void loadLatestReviewCommits_validReviewAndRepo_returnsCommits() throws Exception {
        StreamEntry<List<String>> entry = new StreamEntry<>("id1", Instant.now(), "editor",
            List.of("commitA", "commitB"));
        when(notesManager.readCommits(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(entry)));

        List<String> result = changeSetManager.loadLatestReviewCommits("review-1", REPO)
            .get(2, TimeUnit.SECONDS);

        assertEquals(2, result.size());
        assertEquals("commitA", result.getFirst());
    }

    @Test
    void loadLatestReviewCommits_notesManagerFails_returnsEmptyList() throws Exception {
        when(notesManager.readCommits(anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("notes error")));

        List<String> result = changeSetManager.loadLatestReviewCommits("review-1", REPO)
            .get(2, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadLatestReviewCommits_emptyEntries_returnsEmptyList() throws Exception {
        when(notesManager.readCommits(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        List<String> result = changeSetManager.loadLatestReviewCommits("review-1", REPO)
            .get(2, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void loadFilesForReview_unknownStatusChar_defaultsToModified() throws Exception {
        when(git.executeAsync(eq(REPO), eq("rev-parse"), eq("--verify"), eq("origin/" + REVIEW_BRANCH)))
            .thenReturn(CompletableFuture.completedFuture("def456"));
        when(git.listChangedFiles(eq(REPO), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of("X src/Unknown.java")));

        List<ReviewFile> result = changeSetManager.loadFilesForReview(REPO, REVIEW_BRANCH, BASE_BRANCH)
            .get(2, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertEquals(FileChangeType.MODIFIED, result.getFirst().getChangeType());
    }

    @Test
    void loadFilesForReview_malformedLineNoSpace_defaultsToModified() throws Exception {
        when(git.executeAsync(eq(REPO), eq("rev-parse"), eq("--verify"), eq("origin/" + REVIEW_BRANCH)))
            .thenReturn(CompletableFuture.completedFuture("def456"));
        when(git.listChangedFiles(eq(REPO), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of("malformed")));

        List<ReviewFile> result = changeSetManager.loadFilesForReview(REPO, REVIEW_BRANCH, BASE_BRANCH)
            .get(2, TimeUnit.SECONDS);

        assertEquals(1, result.size());
        assertEquals(FileChangeType.MODIFIED, result.getFirst().getChangeType());
        assertEquals("malformed", result.getFirst().getPath());
    }

    @Test
    void loadFilesForReview_reviewBranchNotInRepo_returnsEmptyList() throws Exception {
        when(git.executeAsync(eq(REPO), eq("rev-parse"), eq("--verify"), eq(REVIEW_BRANCH)))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));
        when(git.executeAsync(eq(REPO), eq("rev-parse"), eq("--verify"), eq("origin/" + REVIEW_BRANCH)))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        List<ReviewFile> result = changeSetManager.loadFilesForReview(REPO, REVIEW_BRANCH, BASE_BRANCH)
            .get(2, TimeUnit.SECONDS);

        assertNotNull(result);
    }

    @Test
    void captureReviewCommitSnapshots_nullReviewId_returnsEmptyMap() throws Exception {
        Repository repo = new Repository(REPO, "", "file:///repo");
        var result = changeSetManager.captureReviewCommitSnapshots(null, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, "editor")
            .get(1, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}


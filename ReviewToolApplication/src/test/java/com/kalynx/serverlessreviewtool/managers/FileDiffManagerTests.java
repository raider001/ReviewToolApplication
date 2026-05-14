package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.models.Commit;
import com.kalynx.serverlessreviewtool.models.FileChangeType;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.CodeViewerModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for FileDiffManager covering commit loading, snapshot loading, and diff display.
 */
class FileDiffManagerTests {

    private static final String REPO = "my-repo";

    private Git git;
    private CodeViewerModel model;
    private FileDiffManager diffManager;

    @BeforeEach
    void setUp() {
        git = mock(Git.class);
        model = new CodeViewerModel();
        diffManager = new FileDiffManager(git, model);
    }

    @Test
    void loadCommitsForReview_gitReturnsEmptyList_setsEmptyAvailableCommits() throws Exception {
        when(git.listCommits(eq(REPO), anyString(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(List.of()));

        diffManager.loadCommitsForReview(REPO, "main", 50).get(2, TimeUnit.SECONDS);

        assertTrue(model.availableCommits.getValue().isEmpty());
    }

    @Test
    void loadCommitsForReview_withCommits_setsAvailableCommits() throws Exception {
        String commitFormat = "abc1234|Author One|2024-01-01|Fix thing";
        String parentRef = "abc1234^";

        when(git.listCommits(eq(REPO), anyString(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(List.of(commitFormat)));
        when(git.executeAsync(eq(REPO), eq("rev-parse"), eq(parentRef)))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("no parent")));

        diffManager.loadCommitsForReview(REPO, "main", 50).get(2, TimeUnit.SECONDS);

        assertFalse(model.availableCommits.getValue().isEmpty());
    }

    @Test
    void loadCommitsForReview_malformedCommitLine_skipped() throws Exception {
        when(git.listCommits(eq(REPO), anyString(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(List.of("malformed-no-pipes")));

        diffManager.loadCommitsForReview(REPO, "main", 50).get(2, TimeUnit.SECONDS);

        assertTrue(model.availableCommits.getValue().isEmpty());
    }

    @Test
    void loadCommitsForReview_withParent_putsParentInAvailableCommits() throws Exception {
        String commitLine = "abc1234|Dev|2024-01-01|Message";
        String parentHash = "parent567890";
        String parentLine = parentHash + "|Dev|2024-01-01|Parent commit";

        when(git.listCommits(eq(REPO), anyString(), anyInt()))
            .thenReturn(CompletableFuture.completedFuture(List.of(commitLine)));
        when(git.executeAsync(eq(REPO), eq("rev-parse"), anyString()))
            .thenReturn(CompletableFuture.completedFuture(parentHash));
        when(git.executeAsync(eq(REPO), eq("show"), eq("-s"),
                eq("--format=%H|%an|%ad|%s"), eq("--date=short"), eq(parentHash)))
            .thenReturn(CompletableFuture.completedFuture(parentLine));

        diffManager.loadCommitsForReview(REPO, "main", 50).get(2, TimeUnit.SECONDS);

        List<Commit> commits = model.availableCommits.getValue();
        assertTrue(commits.stream().anyMatch(c -> c.getHash().startsWith("abc1234")));
    }

    @Test
    void loadCommitsForReview_gitFails_setsEmptyAvailableCommits() throws Exception {
        when(git.listCommits(eq(REPO), anyString(), anyInt()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("git error")));

        diffManager.loadCommitsForReview(REPO, "main", 50).get(2, TimeUnit.SECONDS);

        assertNotNull(model.availableCommits.getValue());
        assertTrue(model.availableCommits.getValue().isEmpty());
    }

    @Test
    void loadCommitsForSnapshot_nullHashes_setsEmptyAvailableCommits() throws Exception {
        diffManager.loadCommitsForSnapshot(REPO, null).get(2, TimeUnit.SECONDS);

        assertTrue(model.availableCommits.getValue().isEmpty());
    }

    @Test
    void loadCommitsForSnapshot_emptyHashes_setsEmptyAvailableCommits() throws Exception {
        diffManager.loadCommitsForSnapshot(REPO, List.of()).get(2, TimeUnit.SECONDS);

        assertTrue(model.availableCommits.getValue().isEmpty());
    }

    @Test
    void loadCommitsForSnapshot_withHashes_loadsCommitsFromGit() throws Exception {
        String hash = "abc1234567";
        String commitLine = hash + "|Dev|2024-01-01|Snapshot commit";

        when(git.executeAsync(eq(REPO), eq("show"), eq("-s"),
                eq("--format=%H|%an|%ad|%s"), eq("--date=short"), eq(hash)))
            .thenReturn(CompletableFuture.completedFuture(commitLine));
        when(git.executeAsync(eq(REPO), eq("rev-parse"), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("no parent")));

        diffManager.loadCommitsForSnapshot(REPO, List.of(hash)).get(2, TimeUnit.SECONDS);

        assertFalse(model.availableCommits.getValue().isEmpty());
    }

    @Test
    void loadCommitsForSnapshot_commitLoadFails_skipsNullCommit() throws Exception {
        when(git.executeAsync(eq(REPO), eq("show"), eq("-s"),
                eq("--format=%H|%an|%ad|%s"), eq("--date=short"), anyString()))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("not found")));

        diffManager.loadCommitsForSnapshot(REPO, List.of("badhash")).get(2, TimeUnit.SECONDS);

        assertTrue(model.availableCommits.getValue().isEmpty());
    }

    @Test
    void loadDiffForFile_nullFile_returnsImmediately() throws Exception {
        Commit start = new Commit("aaa", "msg", "author", "2024-01-01");
        Commit end = new Commit("bbb", "msg", "author", "2024-01-01");

        diffManager.loadDiffForFile(REPO, null, start, end).get(1, TimeUnit.SECONDS);

        verify(git, never()).executeAsync(anyString(), anyString(), anyString());
    }

    @Test
    void loadDiffForFile_nullStartCommit_returnsImmediately() throws Exception {
        ReviewFile file = new ReviewFile("src/Main.java", REPO, FileChangeType.MODIFIED, "main", "feature");

        diffManager.loadDiffForFile(REPO, file, null, new Commit("bbb", "msg", "a", "d")).get(1, TimeUnit.SECONDS);

        verify(git, never()).executeAsync(anyString(), anyString(), anyString());
    }

    @Test
    void loadDiffForFile_nullEndCommit_returnsImmediately() throws Exception {
        ReviewFile file = new ReviewFile("src/Main.java", REPO, FileChangeType.MODIFIED, "main", "feature");

        diffManager.loadDiffForFile(REPO, file, new Commit("aaa", "msg", "a", "d"), null).get(1, TimeUnit.SECONDS);

        verify(git, never()).executeAsync(anyString(), anyString(), anyString());
    }

    @Test
    void loadDiffForFile_validInputs_loadsContentAndSetsModel() throws Exception {
        ReviewFile file = new ReviewFile("src/Main.java", REPO, FileChangeType.MODIFIED, "main", "feature");
        Commit start = new Commit("aaa1111", "msg", "author", "2024-01-01");
        Commit end = new Commit("bbb2222", "msg", "author", "2024-01-02");

        when(git.executeAsync(eq(REPO), eq("show"), eq("aaa1111:src/Main.java")))
            .thenReturn(CompletableFuture.completedFuture("int x = 1;"));
        when(git.executeAsync(eq(REPO), eq("show"), eq("bbb2222:src/Main.java")))
            .thenReturn(CompletableFuture.completedFuture("int x = 2;"));
        when(git.executeAsync(eq(REPO), eq("diff"), eq("aaa1111"), eq("bbb2222"), eq("--"), eq("src/Main.java")))
            .thenReturn(CompletableFuture.completedFuture("@@ -1 +1 @@\n-int x = 1;\n+int x = 2;"));

        diffManager.loadDiffForFile(REPO, file, start, end).get(2, TimeUnit.SECONDS);

        assertEquals("int x = 1;", model.leftContent.getValue());
        assertEquals("int x = 2;", model.rightContent.getValue());
    }

    @Test
    void loadDiffForFile_fileNotFoundInCommit_usesPlaceholder() throws Exception {
        ReviewFile file = new ReviewFile("src/NewFile.java", REPO, FileChangeType.ADDED, "main", "feature");
        Commit start = new Commit("aaa1111", "msg", "author", "2024-01-01");
        Commit end = new Commit("bbb2222", "msg", "author", "2024-01-02");

        when(git.executeAsync(eq(REPO), eq("show"), eq("aaa1111:src/NewFile.java")))
            .thenReturn(CompletableFuture.failedFuture(new RuntimeException("path not in commit")));
        when(git.executeAsync(eq(REPO), eq("show"), eq("bbb2222:src/NewFile.java")))
            .thenReturn(CompletableFuture.completedFuture("class NewFile {}"));
        when(git.executeAsync(eq(REPO), eq("diff"), anyString(), anyString(), eq("--"), anyString()))
            .thenReturn(CompletableFuture.completedFuture(""));

        diffManager.loadDiffForFile(REPO, file, start, end).get(2, TimeUnit.SECONDS);

        assertTrue(model.leftContent.getValue().contains("does not exist"));
        assertEquals("class NewFile {}", model.rightContent.getValue());
    }

    private static void assertFalse(boolean value) {
        org.junit.jupiter.api.Assertions.assertFalse(value);
    }
}


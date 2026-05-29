package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.GitImpl;
import com.kalynx.serverlessreviewtool.git.OrphanBranchReviewManager;
import com.kalynx.serverlessreviewtool.git.OrphanBranchStore;
import com.kalynx.serverlessreviewtool.git.ReviewBranchManagerFactory;
import com.kalynx.serverlessreviewtool.git.ReviewCloneManager;
import com.kalynx.serverlessreviewtool.mockdata.GitRepositoryInitializer;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests that validate commit snapshot preservation for closed code reviews.
 *
 * <p>Targets the pipeline exercised by the code review "Attempt 3 at fixing preservation of
 * code review commits": when a review is closed the commit snapshot must be captured and stored
 * so that reopening the closed review shows only the files that were part of the review, not
 * every file that happens to exist on the branch at the time of viewing.
 *
 * <p>The {@code java-backend-service} mock repository is used for the main snapshot tests. Its
 * {@code feature/oauth-integration} branch touches a single file
 * ({@code src/OAuth2Provider.java}) — well within the "less than 10 files" boundary
 * reported for the real review.
 *
 * <p>A dedicated in-test bare repository ({@code merge-test-service}) is created to exercise
 * the post-merge fallback: once a feature branch has been merged into master the commit range
 * {@code origin/master..origin/feature} becomes empty, and the snapshot captured at close time
 * must be preserved rather than discarded.
 */
class CommitSnapshotPreservationTests {

    private static final String REPO = "java-backend-service";
    private static final String REVIEW_BRANCH = "feature/oauth-integration";
    private static final String BASE_BRANCH = "master";
    private static final String REVIEW_ID = "snapshot-preservation-test-review";
    private static final String AUTHOR = "test.author";
    private static final String OAUTH_FILE = "src/OAuth2Provider.java";

    /**
     * The feature/oauth-integration branch adds exactly one file: src/OAuth2Provider.java.
     * All 7 commits on the branch touch only this single file.
     */
    private static final int EXPECTED_REVIEW_FILE_COUNT = 1;

    /**
     * The master branch of java-backend-service contains these tracked files:
     * README.md, UserService.java, AuthController.java, DatabaseConfig.java, UserRepository.java.
     * The repository must have more files than the review to validate proper snapshot scoping.
     */
    private static final int MINIMUM_MASTER_FILE_COUNT = 5;

    @TempDir
    Path tempDir;

    private Path testRepoPath;
    private GitImpl git;
    private ReviewChangeSetManager changeSetManager;
    private OrphanBranchReviewManager orphanManager;

    @BeforeAll
    static void initMockRepositories() {
        try {
            GitRepositoryInitializer.main();
        } catch (Exception e) {
            throw new RuntimeException("Cannot run tests without mock repositories", e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        testRepoPath = tempDir.resolve("test-repositories");
        git = new GitImpl(testRepoPath);

        Path orphanRemote = tempDir.resolve("orphan-remote.git");
        runGitCommand(tempDir, "git", "init", "--bare", orphanRemote.toString());
        String orphanUrl = "file:///" + orphanRemote.toString().replace("\\", "/");
        Path storeBase = tempDir.resolve("orphan-store");
        Files.createDirectories(storeBase);
        OrphanBranchStore orphanStore = new OrphanBranchStore(orphanUrl, storeBase);
        orphanManager = new OrphanBranchReviewManager(orphanStore, REPO);

        ReviewBranchManagerFactory factory = _ -> orphanManager;
        changeSetManager = new ReviewChangeSetManager(gitAsCloneManager(git), factory);

        Path remoteUrl = GitRepositoryInitializer.getBasePath().resolve(REPO);
        git.cloneRepository("file:///" + remoteUrl.toString().replace("\\", "/"))
            .get(30, TimeUnit.SECONDS);
    }

    @Test
    void captureSnapshot_forOAuthFeatureBranch_storesNonEmptyCommitList() throws Exception {
        Repository repo = new Repository(REPO, "", "");

        Map<String, List<String>> snapshots = changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, AUTHOR
        ).get(30, TimeUnit.SECONDS);

        assertNotNull(snapshots);
        assertFalse(snapshots.isEmpty());

        List<String> commits = snapshots.get(REPO);
        assertNotNull(commits, "Expected stored commits for " + REPO);
        assertFalse(commits.isEmpty(), "Expected non-empty commit list for " + REVIEW_BRANCH);
    }

    @Test
    void captureSnapshot_forOAuthFeatureBranch_storesCommitHashesInNotes() throws Exception {
        Repository repo = new Repository(REPO, "", "");

        changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, AUTHOR
        ).get(30, TimeUnit.SECONDS);

        List<String> storedCommits = changeSetManager
            .loadLatestReviewCommits(REVIEW_ID, REPO)
            .get(10, TimeUnit.SECONDS);

        assertNotNull(storedCommits);
        assertFalse(storedCommits.isEmpty(), "Stored commits must be written to orphan branch after capture");
    }

    @Test
    void loadFilesFromStoredSnapshot_afterCapture_showsOnlyFeatureBranchFile() throws Exception {
        Repository repo = new Repository(REPO, "", "");

        Map<String, List<String>> snapshots = changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, AUTHOR
        ).get(30, TimeUnit.SECONDS);

        List<ReviewFile> files = changeSetManager.loadFilesFromStoredReviewCommits(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, snapshots
        ).get(10, TimeUnit.SECONDS);

        assertNotNull(files);
        assertFalse(files.isEmpty(), "Expected at least one file from stored snapshot");

        boolean containsOAuthFile = files.stream().anyMatch(f -> f.getPath().contains(OAUTH_FILE));
        assertTrue(containsOAuthFile, "Expected " + OAUTH_FILE + " in the file list, got: " + files.stream().map(ReviewFile::getPath).toList());
    }

    @Test
    void loadFilesFromStoredSnapshot_afterCapture_showsExactlyOneFile() throws Exception {
        Repository repo = new Repository(REPO, "", "");

        Map<String, List<String>> snapshots = changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, AUTHOR
        ).get(30, TimeUnit.SECONDS);

        List<ReviewFile> files = changeSetManager.loadFilesFromStoredReviewCommits(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, snapshots
        ).get(10, TimeUnit.SECONDS);

        assertEquals(EXPECTED_REVIEW_FILE_COUNT, files.size(),
            "feature/oauth-integration touches exactly " + EXPECTED_REVIEW_FILE_COUNT
                + " file(s) — stored snapshot must reflect that, got: "
                + files.stream().map(ReviewFile::getPath).toList());
    }

    @Test
    void closedReview_reviewFilesAreProperSubsetOfTotalRepositoryFiles() throws Exception {
        Repository repo = new Repository(REPO, "", "");

        String lsOutput = git.executeAsync(REPO, "ls-files").get(10, TimeUnit.SECONDS);
        List<String> allTrackedFiles = Arrays.stream(lsOutput.split("\n"))
            .map(String::trim)
            .filter(f -> !f.isBlank())
            .toList();

        assertTrue(allTrackedFiles.size() >= MINIMUM_MASTER_FILE_COUNT,
            "Repository must have at least " + MINIMUM_MASTER_FILE_COUNT + " tracked files to demonstrate "
                + "meaningful review scoping, but only found: " + allTrackedFiles);

        Map<String, List<String>> snapshots = changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, AUTHOR
        ).get(30, TimeUnit.SECONDS);

        List<ReviewFile> reviewFiles = changeSetManager.loadFilesFromStoredReviewCommits(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, snapshots
        ).get(10, TimeUnit.SECONDS);

        assertEquals(EXPECTED_REVIEW_FILE_COUNT, reviewFiles.size(),
            "Review must show exactly " + EXPECTED_REVIEW_FILE_COUNT + " file(s)");

        assertTrue(reviewFiles.size() < allTrackedFiles.size(),
            "Closed review shows " + reviewFiles.size() + " file(s) but repository has "
                + allTrackedFiles.size() + " tracked file(s) — review must be a proper subset of the repository");
    }

    @Test
    void fullPipeline_createReviewThenCaptureAndLoadClosedReview_showsCorrectFiles() throws Exception {
        Repository repo = new Repository(REPO, "", "");

        createTestReview(List.of(REPO));

        Map<String, List<String>> captured = changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, AUTHOR
        ).get(30, TimeUnit.SECONDS);

        Map<String, List<String>> stored = changeSetManager
            .loadStoredReviewCommitsForAllRepositories(REVIEW_ID, List.of(repo))
            .get(10, TimeUnit.SECONDS);

        List<ReviewFile> files = changeSetManager.loadFilesFromStoredReviewCommits(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, stored
        ).get(10, TimeUnit.SECONDS);

        assertNotNull(files);
        assertFalse(files.isEmpty(), "Closed review must show files from the stored snapshot");

        List<String> paths = files.stream().map(ReviewFile::getPath).toList();
        assertTrue(paths.stream().anyMatch(p -> p.contains(OAUTH_FILE)),
            "Expected " + OAUTH_FILE + " in closed review file list, got: " + paths);

        assertEquals(captured.get(REPO), stored.get(REPO),
            "Stored commits after reload must match what was captured at close time");
    }

    @Test
    void loadFilesFromStoredCommits_withNoStoredSnapshot_returnsEmptyList() throws Exception {
        Repository repo = new Repository(REPO, "", "");

        List<ReviewFile> files = changeSetManager.loadFilesFromStoredReviewCommits(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, Map.of()
        ).get(10, TimeUnit.SECONDS);

        assertNotNull(files);
        assertTrue(files.isEmpty(),
            "A closed review with no stored snapshot must show an empty file list (not unrelated files)");
    }

    @Test
    void captureSnapshot_calledTwiceWithSameCommits_doesNotDuplicateStoredEntries() throws Exception {
        Repository repo = new Repository(REPO, "", "");

        changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, AUTHOR
        ).get(30, TimeUnit.SECONDS);

        List<String> afterFirstCapture = changeSetManager
            .loadLatestReviewCommits(REVIEW_ID, REPO)
            .get(10, TimeUnit.SECONDS);

        changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, AUTHOR
        ).get(30, TimeUnit.SECONDS);

        List<String> afterSecondCapture = changeSetManager
            .loadLatestReviewCommits(REVIEW_ID, REPO)
            .get(10, TimeUnit.SECONDS);

        assertEquals(afterFirstCapture, afterSecondCapture,
            "Capturing the same commits twice must produce the same stored snapshot");
    }

    @Test
    void fullPipeline_withInitialEmptyCommitsFromCreateReview_captureAtCloseWritesCommits() throws Exception {
        Repository repo = new Repository(REPO, "", "");

        createTestReviewWithEmptyCommits(List.of(REPO));

        List<String> beforeCapture = changeSetManager
            .loadLatestReviewCommits(REVIEW_ID, REPO)
            .get(10, TimeUnit.SECONDS);

        assertTrue(beforeCapture.isEmpty(),
            "Initial review should have empty commits (simulating CreateReviewDialog storing nothing)");

        changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), REVIEW_BRANCH, BASE_BRANCH, AUTHOR
        ).get(30, TimeUnit.SECONDS);

        List<String> afterCapture = changeSetManager
            .loadLatestReviewCommits(REVIEW_ID, REPO)
            .get(10, TimeUnit.SECONDS);

        assertFalse(afterCapture.isEmpty(),
            "After capture at close time, commits must be written even if initial commits were empty");
    }

    private void createTestReview(List<String> repositories) throws Exception {
        List<String> commits = git.listCommits(REPO, BASE_BRANCH + ".." + REVIEW_BRANCH, 100)
            .get(10, TimeUnit.SECONDS)
            .stream()
            .map(row -> row.split("\\|")[0])
            .filter(h -> !h.isBlank())
            .toList();
        orphanManager.createReview(
            REVIEW_ID, AUTHOR,
            "Attempt 3 at fixing preservation of code review commits",
            AUTHOR,
            "Test review for snapshot preservation validation",
            "OPEN",
            commits,
            List.of("reviewer.one"),
            REVIEW_BRANCH,
            BASE_BRANCH
        ).get(30, TimeUnit.SECONDS);
    }

    private void createTestReviewWithEmptyCommits(List<String> repositories) throws Exception {
        orphanManager.createReview(
            REVIEW_ID, AUTHOR,
            "Attempt 3 at fixing preservation of code review commits",
            AUTHOR,
            "Test review with empty initial commits",
            "OPEN",
            List.of(),
            List.of("reviewer.one"),
            REVIEW_BRANCH,
            BASE_BRANCH
        ).get(30, TimeUnit.SECONDS);
    }

    @Test
    void captureSnapshot_afterFeatureBranchMergedIntoMaster_preservesStoredSnapshot() throws Exception {
        String mergeRepo = "merge-test-service";
        String featureBranch = "fix/commit-preservation";
        int expectedMergeReviewFileCount = 2;

        Path bareRemote = setupMergeTestRepository(mergeRepo, featureBranch);
        git.cloneRepository("file:///" + bareRemote.toString().replace("\\", "/"))
            .get(30, TimeUnit.SECONDS);

        Repository repo = new Repository(mergeRepo, "", "");

        Map<String, List<String>> premergeSnapshots = changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), featureBranch, "master", AUTHOR
        ).get(30, TimeUnit.SECONDS);

        List<String> premergeCommits = premergeSnapshots.get(mergeRepo);
        assertFalse(premergeCommits.isEmpty(),
            "Snapshot captured before merge must contain commits from the feature branch");
        assertEquals(expectedMergeReviewFileCount, premergeCommits.size(),
            "Feature branch has " + expectedMergeReviewFileCount + " commits, one per review file");

        mergeFeatureBranchIntoMaster(mergeRepo, featureBranch);
        git.fetch(mergeRepo).get(15, TimeUnit.SECONDS);

        Map<String, List<String>> postmergeSnapshots = changeSetManager.captureReviewCommitSnapshots(
            REVIEW_ID, List.of(repo), featureBranch, "master", AUTHOR
        ).get(30, TimeUnit.SECONDS);

        assertEquals(premergeCommits, postmergeSnapshots.get(mergeRepo),
            "After merge, captureReviewCommitSnapshots must fall back to the existing stored snapshot "
                + "because origin/master..origin/feature is now empty");

        List<ReviewFile> files = changeSetManager.loadFilesFromStoredReviewCommits(
            REVIEW_ID, List.of(repo), featureBranch, "master", postmergeSnapshots
        ).get(10, TimeUnit.SECONDS);

        assertEquals(expectedMergeReviewFileCount, files.size(),
            "Closed review after branch merge must still show exactly " + expectedMergeReviewFileCount
                + " review file(s), got: " + files.stream().map(ReviewFile::getPath).toList());

        List<String> filePaths = files.stream().map(ReviewFile::getPath).toList();
        assertTrue(filePaths.stream().anyMatch(p -> p.contains("SnapshotCapture.java")),
            "Expected SnapshotCapture.java in post-merge file list");
        assertTrue(filePaths.stream().anyMatch(p -> p.contains("CommitStorage.java")),
            "Expected CommitStorage.java in post-merge file list");
    }

    private Path setupMergeTestRepository(String repoName, String featureBranch) throws Exception {
        Path bareRemote = tempDir.resolve("remotes").resolve(repoName);
        Files.createDirectories(bareRemote);
        runGitCommand(bareRemote, "git", "init", "--bare");

        Path workdir = tempDir.resolve("workdir").resolve(repoName);
        Files.createDirectories(workdir);
        runGitCommand(workdir, "git", "clone",
            "file:///" + bareRemote.toString().replace("\\", "/"), ".");
        runGitCommand(workdir, "git", "config", "user.name", "Test User");
        runGitCommand(workdir, "git", "config", "user.email", "test@example.com");
        runGitCommand(workdir, "git", "config", "commit.gpgsign", "false");

        Files.writeString(workdir.resolve("README.md"), "# Merge Test Service\n");
        runGitCommand(workdir, "git", "add", "README.md");
        runGitCommand(workdir, "git", "commit", "-m", "Initial commit");

        Path srcDir = workdir.resolve("src");
        Files.createDirectories(srcDir);

        Files.writeString(srcDir.resolve("ReviewContextManager.java"), "public class ReviewContextManager {}\n");
        runGitCommand(workdir, "git", "add", "src/ReviewContextManager.java");
        runGitCommand(workdir, "git", "commit", "-m", "feat: Add ReviewContextManager");

        Files.writeString(srcDir.resolve("GitNotesManager.java"), "public class GitNotesManager {}\n");
        runGitCommand(workdir, "git", "add", "src/GitNotesManager.java");
        runGitCommand(workdir, "git", "commit", "-m", "feat: Add GitNotesManager");

        runGitCommand(workdir, "git", "push", "origin", "master");

        runGitCommand(workdir, "git", "checkout", "-b", featureBranch);

        Files.writeString(srcDir.resolve("SnapshotCapture.java"), "public class SnapshotCapture {}\n");
        runGitCommand(workdir, "git", "add", "src/SnapshotCapture.java");
        runGitCommand(workdir, "git", "commit", "-m", "fix: Add SnapshotCapture for commit preservation");

        Files.writeString(srcDir.resolve("CommitStorage.java"), "public class CommitStorage {}\n");
        runGitCommand(workdir, "git", "add", "src/CommitStorage.java");
        runGitCommand(workdir, "git", "commit", "-m", "fix: Add CommitStorage for review commits");

        runGitCommand(workdir, "git", "push", "origin", featureBranch);

        return bareRemote;
    }

    private void mergeFeatureBranchIntoMaster(String repoName, String featureBranch) throws Exception {
        Path workdir = tempDir.resolve("workdir").resolve(repoName);
        runGitCommand(workdir, "git", "checkout", "master");
        runGitCommand(workdir, "git", "merge", "--no-ff", featureBranch,
            "-m", "Merge branch '" + featureBranch + "' into master");
        runGitCommand(workdir, "git", "push", "origin", "master");
    }

    private static ReviewCloneManager gitAsCloneManager(GitImpl git) {
        return new ReviewCloneManager() {
            public java.util.concurrent.CompletableFuture<Void> ensureClone(String r) {
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            public java.util.concurrent.CompletableFuture<String> execute(String r, String... args) {
                return git.executeAsync(r, args);
            }
            public java.util.concurrent.CompletableFuture<String> getDefaultBranch(String r) {
                return git.getDefaultBranch(r);
            }
            public java.util.concurrent.CompletableFuture<List<String>> listCommits(String r, String ref, int n) {
                return git.listCommits(r, ref, n);
            }
            public java.util.concurrent.CompletableFuture<List<String>> listChangedFiles(String r, String a, String b) {
                return git.listChangedFiles(r, a, b);
            }
            public java.util.concurrent.CompletableFuture<Void> refresh(String r) {
                return git.fetch(r);
            }
        };
    }

    private void runGitCommand(Path workingDir, String... command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Git command failed (exit " + exitCode + "): "
                + String.join(" ", command));
        }
    }
}

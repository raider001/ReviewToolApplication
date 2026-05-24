package com.kalynx.serverlessreviewtool.git;

import com.kalynx.serverlessreviewtool.mockdata.GitRepositoryInitializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GitImpl covering clone, remove, fetch, and pull.
 * Notes-specific tests (pushNotes, appendToNotes, union merge strategy) have been removed
 * because review data is now stored on refs/heads/kalynx-reviews via OrphanBranchStore,
 * not in git notes.
 */
public class GitImplTests {

    private static final Logger logger = LoggerFactory.getLogger(GitImplTests.class);

    @TempDir
    Path tempDir;

    private GitImpl git;
    private Path testRepoPath;

    @BeforeAll
    static void setUpMockRepositories() {
        try {
            logger.info("Setting up mock Git repositories for tests...");
            GitRepositoryInitializer.main();
        } catch (Exception e) {
            logger.error("Failed to initialize mock repositories", e);
            throw new RuntimeException("Cannot run tests without mock repositories", e);
        }
    }

    @BeforeEach
    void setUp() {
        testRepoPath = tempDir.resolve("test-repos");
        git = new GitImpl(testRepoPath);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (Files.exists(testRepoPath)) {
            deleteDirectory(testRepoPath);
        }
    }

    // -------------------------------------------------------------------------
    // cloneRepository
    // -------------------------------------------------------------------------

    @Test
    void cloneRepository_validRemoteUrl_createsLocalRepository() {
        String repoName = "java-backend-service";
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repoName);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");

        CompletableFuture<Void> result = git.cloneRepository(remoteUrl);

        assertDoesNotThrow(() -> result.get(30, java.util.concurrent.TimeUnit.SECONDS));

        Path clonedRepo = testRepoPath.resolve(repoName);
        assertTrue(Files.exists(clonedRepo), "Cloned repository directory should exist");
        assertTrue(Files.exists(clonedRepo.resolve(".git")), ".git directory should exist");
    }

    @Test
    void cloneRepository_invalidRemoteUrl_throwsExecutionException() {
        String invalidUrl = "invalid://nonexistent.repository";

        CompletableFuture<Void> result = git.cloneRepository(invalidUrl);

        ExecutionException exception = assertThrows(ExecutionException.class,
            () -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertNotNull(exception.getCause(), "Exception should have a cause");
    }

    @Test
    void cloneRepository_alreadyClonedRepository_handlesGracefully() throws Exception {
        String repoName = "java-backend-service";
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repoName);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");

        git.cloneRepository(remoteUrl).get(30, java.util.concurrent.TimeUnit.SECONDS);

        CompletableFuture<Void> secondClone = git.cloneRepository(remoteUrl);

        assertDoesNotThrow(() -> secondClone.get(10, java.util.concurrent.TimeUnit.SECONDS),
            "Should handle already-cloned repository gracefully");

        Path clonedRepo = testRepoPath.resolve(repoName);
        assertTrue(Files.exists(clonedRepo), "Repository should still exist");
        assertTrue(Files.isDirectory(clonedRepo.resolve(".git")), "Repository should still be a valid Git repository");
    }

    @Test
    void cloneRepository_timeoutOnLongOperation_throwsTimeoutException() {
        String remoteUrl = "https://github.com/torvalds/linux.git";

        CompletableFuture<Void> result = git.cloneRepository(remoteUrl);

        assertThrows(TimeoutException.class,
            () -> result.get(1, java.util.concurrent.TimeUnit.MILLISECONDS),
            "Should timeout on very large repository clone");
    }

    @Test
    void cloneRepository_multipleRepositories_createsMultipleDirectories() {
        String repo1 = "java-backend-service";
        String repo2 = "python-api-service";
        Path mockRepo1 = GitRepositoryInitializer.getBasePath().resolve(repo1);
        Path mockRepo2 = GitRepositoryInitializer.getBasePath().resolve(repo2);
        String url1 = "file:///" + mockRepo1.toString().replace("\\", "/");
        String url2 = "file:///" + mockRepo2.toString().replace("\\", "/");

        CompletableFuture<Void> clone1 = git.cloneRepository(url1);
        CompletableFuture<Void> clone2 = git.cloneRepository(url2);

        assertDoesNotThrow(() -> CompletableFuture.allOf(clone1, clone2).get(60, java.util.concurrent.TimeUnit.SECONDS));

        assertTrue(Files.exists(testRepoPath.resolve(repo1)), "First repository should exist");
        assertTrue(Files.exists(testRepoPath.resolve(repo2)), "Second repository should exist");
    }

    // -------------------------------------------------------------------------
    // removeRepository
    // -------------------------------------------------------------------------

    @Test
    void removeRepository_existingRepository_deletesDirectory() throws Exception {
        String repoName = "java-backend-service";
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repoName);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");

        git.cloneRepository(remoteUrl).get(30, java.util.concurrent.TimeUnit.SECONDS);
        Path clonedRepo = testRepoPath.resolve(repoName);
        assertTrue(Files.exists(clonedRepo), "Repository should exist before removal");

        CompletableFuture<Void> result = git.removeRepository(repoName);

        assertDoesNotThrow(() -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(Files.exists(clonedRepo), "Repository should be deleted");
    }

    @Test
    void removeRepository_nonExistentRepository_completesSuccessfully() {
        String nonExistentRepoName = "non-existent-repo";

        CompletableFuture<Void> result = git.removeRepository(nonExistentRepoName);

        assertDoesNotThrow(() -> result.get(5, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void removeRepository_repositoryWithMultipleFiles_deletesAllContents() throws Exception {
        String repoName = "java-backend-service";
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repoName);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");

        git.cloneRepository(remoteUrl).get(30, java.util.concurrent.TimeUnit.SECONDS);
        Path clonedRepo = testRepoPath.resolve(repoName);

        Path testFile = clonedRepo.resolve("test-file.txt");
        Files.writeString(testFile, "test content");
        Path testDir = clonedRepo.resolve("test-dir");
        Files.createDirectory(testDir);
        Files.writeString(testDir.resolve("nested-file.txt"), "nested content");

        CompletableFuture<Void> result = git.removeRepository(repoName);

        assertDoesNotThrow(() -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(Files.exists(clonedRepo), "Repository and all contents should be deleted");
        assertFalse(Files.exists(testFile), "Test file should be deleted");
        assertFalse(Files.exists(testDir), "Test directory should be deleted");
    }

    @Test
    void removeRepository_immediatelyAfterClone_deletesSuccessfully() throws Exception {
        String repoName = "java-backend-service";
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repoName);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");

        git.cloneRepository(remoteUrl).get(30, java.util.concurrent.TimeUnit.SECONDS);
        Path clonedRepo = testRepoPath.resolve(repoName);

        CompletableFuture<Void> removeResult = git.removeRepository(repoName);

        assertDoesNotThrow(() -> removeResult.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(Files.exists(clonedRepo));
    }

    // -------------------------------------------------------------------------
    // fetch
    // -------------------------------------------------------------------------

    @Test
    void fetch_existingRepository_completesSuccessfully() throws Exception {
        String repoName = "java-backend-service";
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repoName);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");

        git.cloneRepository(remoteUrl).get(30, java.util.concurrent.TimeUnit.SECONDS);

        CompletableFuture<Void> result = git.fetch(repoName);

        assertDoesNotThrow(() -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        Path clonedRepo = testRepoPath.resolve(repoName);
        assertTrue(Files.exists(clonedRepo.resolve(".git")), "Git directory should exist after fetch");
    }

    @Test
    void fetch_repositoryWithoutNotes_completesSuccessfully() throws Exception {
        String repoName = "python-api-service";
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repoName);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");

        git.cloneRepository(remoteUrl).get(30, java.util.concurrent.TimeUnit.SECONDS);

        CompletableFuture<Void> result = git.fetch(repoName);

        assertDoesNotThrow(() -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }

    @Test
    void fetch_nonExistentRepository_completesWithoutThrowingBecauseFetchIsBestEffort() {
        // fetchAllBranches intentionally swallows errors (best-effort fetch) so that
        // a temporarily-unreachable remote doesn't crash the application.
        String nonExistentRepoName = "non-existent-repo";

        CompletableFuture<Void> result = git.fetch(nonExistentRepoName);

        assertDoesNotThrow(() -> result.get(5, java.util.concurrent.TimeUnit.SECONDS),
            "fetch is best-effort and should not propagate failures for non-existent repos");
    }

    @Test
    void fetch_multipleRepositories_fetchesIndependently() throws Exception {
        String repo1 = "java-backend-service";
        String repo2 = "react-frontend-app";
        Path mockRepo1 = GitRepositoryInitializer.getBasePath().resolve(repo1);
        Path mockRepo2 = GitRepositoryInitializer.getBasePath().resolve(repo2);
        String url1 = "file:///" + mockRepo1.toString().replace("\\", "/");
        String url2 = "file:///" + mockRepo2.toString().replace("\\", "/");

        git.cloneRepository(url1).get(30, java.util.concurrent.TimeUnit.SECONDS);
        git.cloneRepository(url2).get(30, java.util.concurrent.TimeUnit.SECONDS);

        CompletableFuture<Void> fetch1 = git.fetch(repo1);
        CompletableFuture<Void> fetch2 = git.fetch(repo2);

        assertDoesNotThrow(() -> CompletableFuture.allOf(fetch1, fetch2).get(20, java.util.concurrent.TimeUnit.SECONDS));
    }

    // -------------------------------------------------------------------------
    // pull
    // -------------------------------------------------------------------------

    @Test
    void pull_withNewCommits_updatesLocalRepository() throws Exception {
        String repoName = "java-backend-service";
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repoName);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");

        git.cloneRepository(remoteUrl).get(30, java.util.concurrent.TimeUnit.SECONDS);

        addTestCommitToRemote(mockRepo, "test-file.txt", "Test content for pull");

        CompletableFuture<Void> result = git.pull(repoName);

        assertDoesNotThrow(() -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        Path clonedRepo = testRepoPath.resolve(repoName);
        assertTrue(Files.exists(clonedRepo.resolve("test-file.txt")),
            "Pulled file should exist in local repository");

        removeTestCommitFromRemote(mockRepo);
    }

    @Test
    void pull_nonExistentRepository_throwsExecutionException() {
        String nonExistentRepoName = "non-existent-repo";

        CompletableFuture<Void> result = git.pull(nonExistentRepoName);

        ExecutionException exception = assertThrows(ExecutionException.class,
            () -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertNotNull(exception.getCause(), "Exception should have a cause");
    }

    @Test
    void pull_afterFetch_pullsAdditionalChanges() throws Exception {
        String repoName = "java-backend-service";
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repoName);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");

        git.cloneRepository(remoteUrl).get(30, java.util.concurrent.TimeUnit.SECONDS);
        git.fetch(repoName).get(10, java.util.concurrent.TimeUnit.SECONDS);

        addTestCommitToRemote(mockRepo, "additional-file.txt", "Additional content");

        CompletableFuture<Void> pullResult = git.pull(repoName);

        assertDoesNotThrow(() -> pullResult.get(10, java.util.concurrent.TimeUnit.SECONDS));
        Path clonedRepo = testRepoPath.resolve(repoName);
        assertTrue(Files.exists(clonedRepo.resolve("additional-file.txt")),
            "File from pull should exist");

        removeTestCommitFromRemote(mockRepo);
    }

    @Test
    void pull_multipleRepositories_pullsIndependently() throws Exception {
        String repo1 = "java-backend-service";
        String repo2 = "python-api-service";
        Path mockRepo1 = GitRepositoryInitializer.getBasePath().resolve(repo1);
        Path mockRepo2 = GitRepositoryInitializer.getBasePath().resolve(repo2);
        String url1 = "file:///" + mockRepo1.toString().replace("\\", "/");
        String url2 = "file:///" + mockRepo2.toString().replace("\\", "/");

        git.cloneRepository(url1).get(30, java.util.concurrent.TimeUnit.SECONDS);
        git.cloneRepository(url2).get(30, java.util.concurrent.TimeUnit.SECONDS);

        addTestCommitToRemote(mockRepo1, "repo1-test.txt", "Repo 1 content");
        addTestCommitToRemote(mockRepo2, "repo2-test.txt", "Repo 2 content");

        CompletableFuture<Void> pull1 = git.pull(repo1);
        CompletableFuture<Void> pull2 = git.pull(repo2);

        assertDoesNotThrow(() -> CompletableFuture.allOf(pull1, pull2).get(20, java.util.concurrent.TimeUnit.SECONDS));

        assertTrue(Files.exists(testRepoPath.resolve(repo1).resolve("repo1-test.txt")));
        assertTrue(Files.exists(testRepoPath.resolve(repo2).resolve("repo2-test.txt")));

        removeTestCommitFromRemote(mockRepo1);
        removeTestCommitFromRemote(mockRepo2);
    }

    @Test
    void pull_withConflicts_completesSuccessfully() throws Exception {
        String repoName = "java-backend-service";
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repoName);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");

        git.cloneRepository(remoteUrl).get(30, java.util.concurrent.TimeUnit.SECONDS);

        addTestCommitToRemote(mockRepo, "conflict-test.txt", "Remote content");

        CompletableFuture<Void> result = git.pull(repoName);

        assertDoesNotThrow(() -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));

        removeTestCommitFromRemote(mockRepo);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void addTestCommitToRemote(Path remotePath, String fileName, String content) throws Exception {
        Path testFile = remotePath.resolve(fileName);
        Files.writeString(testFile, content);

        ProcessBuilder pb = new ProcessBuilder("git", "add", fileName);
        pb.directory(remotePath.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.waitFor();

        pb = new ProcessBuilder("git", "commit", "-m", "Test commit: " + fileName);
        pb.directory(remotePath.toFile());
        pb.redirectErrorStream(true);
        process = pb.start();
        process.waitFor();
    }

    private void removeTestCommitFromRemote(Path remotePath) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "reset", "--hard", "HEAD~1");
        pb.directory(remotePath.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.waitFor();
    }

    private void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        if (Files.exists(path.resolve(".git"))) {
            try {
                ProcessBuilder pb = new ProcessBuilder("git", "gc", "--prune=now");
                pb.directory(path.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                process.waitFor();
                Thread.sleep(100);
            } catch (Exception ignored) {
            }
        }

        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(this::deleteWithRetry);
        }
    }

    private void deleteWithRetry(Path path) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                if (Files.exists(path) && !Files.isDirectory(path)) {
                    if (!path.toFile().setWritable(true)) {
                        logger.warn("Could not set file writable: {}", path);
                    }
                }
                Files.delete(path);
                return;
            } catch (IOException e) {
                if (i == maxRetries - 1) {
                    path.toFile().deleteOnExit();
                } else {
                    try {
                        Thread.sleep(50L * (i + 1));
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }
    }
}

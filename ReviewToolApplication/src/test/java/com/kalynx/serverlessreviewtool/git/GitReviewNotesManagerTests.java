package com.kalynx.serverlessreviewtool.git;

import com.kalynx.serverlessreviewtool.mockdata.GitRepositoryInitializer;
import com.kalynx.serverlessreviewtool.models.review.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class GitReviewNotesManagerTests {

    private static final Logger logger = LoggerFactory.getLogger(GitReviewNotesManagerTests.class);

    @TempDir
    Path tempDir;

    private GitReviewNotesManager notesManager;
    private static final String REPO_NAME = "java-backend-service";
    private static final String TEST_REVIEW_ID = "01890a5d-ac96-774b-bcce-b302099a8057";
    private static final String TEST_EDITOR = "test.user@example.com";
    private Path testRepoPath;

    @BeforeAll
    static void setUpMockRepositories() {
        try {
            logger.info("Setting up mock Git repositories for GitReviewNotesManager tests...");
            GitRepositoryInitializer.main();
        } catch (Exception e) {
            logger.error("Failed to initialize mock repositories", e);
            throw new RuntimeException("Cannot run tests without mock repositories", e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        testRepoPath = tempDir.resolve("test-repos");
        GitImpl git = new GitImpl(testRepoPath);
        notesManager = new GitReviewNotesManager(git, REPO_NAME);

        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(REPO_NAME);
        String remoteUrl = "file:///" + mockRepo.toString().replace("\\", "/");
        git.cloneRepository(remoteUrl).get(30, TimeUnit.SECONDS);

        cleanupAllReviewNotes();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (testRepoPath != null && Files.exists(testRepoPath)) {
            cleanupAllReviewNotes();
            deleteDirectory(testRepoPath);
        }
    }

    private void cleanupAllReviewNotes() throws Exception {
        Path clonedRepo = testRepoPath.resolve(REPO_NAME);
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(REPO_NAME);

        if (Files.exists(clonedRepo)) {
            deleteAllNotesRefs(clonedRepo);
        }

        if (Files.exists(mockRepo)) {
            deleteAllNotesRefs(mockRepo);
        }

        Path tempReviewDir = Path.of(System.getProperty("java.io.tmpdir"))
            .resolve("git-reviews")
            .resolve(REPO_NAME);
        if (Files.exists(tempReviewDir)) {
            deleteDirectory(tempReviewDir);
        }
    }

    private void deleteAllNotesRefs(Path repoPath) throws Exception {
        Path notesDir = repoPath.resolve(".git/refs/notes/reviews");
        if (Files.exists(notesDir)) {
            try (var stream = Files.walk(notesDir)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException ignored) {
                        }
                    });
            }
        }
    }

    @Test
    void writeReviewTitle_newReview_writesAndPushesSuccessfully() throws Exception {
        String title = "Add user authentication feature";

        notesManager.writeReviewTitle(TEST_REVIEW_ID, TEST_EDITOR, title)
            .get(10, TimeUnit.SECONDS);

        List<StreamEntry<String>> titles = notesManager.readTitles(TEST_REVIEW_ID)
            .get(10, TimeUnit.SECONDS);

        assertEquals(1, titles.size());
        assertEquals(title, titles.getFirst().data());
        assertEquals(TEST_EDITOR, titles.getFirst().editor());
    }


    @Test
    void writeReviewCommits_newReview_writesCorrectly() throws Exception {
        List<String> commits = Arrays.asList(
            "abc123def456",
            "789ghi012jkl",
            "345mno678pqr"
        );

        notesManager.writeReviewCommits(TEST_REVIEW_ID, TEST_EDITOR, commits)
            .get(10, TimeUnit.SECONDS);

        List<StreamEntry<List<String>>> commitEntries = notesManager.readCommits(TEST_REVIEW_ID)
            .get(10, TimeUnit.SECONDS);

        assertEquals(1, commitEntries.size());
        List<String> readCommits = commitEntries.getFirst().data();
        assertEquals(3, readCommits.size());
        assertTrue(readCommits.contains("abc123def456"));
        assertTrue(readCommits.contains("789ghi012jkl"));
        assertTrue(readCommits.contains("345mno678pqr"));
    }


    @Test
    void writeComment_newReview_writesCorrectly() throws Exception {
        String commentId = "comment-001";
        String file = "src/Main.java";
        int line = 42;
        String text = "Consider using dependency injection here";

        notesManager.writeCommentMetadata(TEST_REVIEW_ID, commentId, TEST_EDITOR, file, line, line, "abc123")
            .get(10, TimeUnit.SECONDS);
        notesManager.writeCommentText(TEST_REVIEW_ID, commentId, TEST_EDITOR, text, null, "review")
            .get(10, TimeUnit.SECONDS);

        List<StreamEntry<GitReviewNotesManager.CommentMetadata>> metadata =
            notesManager.readCommentMetadata(TEST_REVIEW_ID, commentId).get(10, TimeUnit.SECONDS);
        List<StreamEntry<GitReviewNotesManager.CommentTextData>> textData =
            notesManager.readCommentText(TEST_REVIEW_ID, commentId).get(10, TimeUnit.SECONDS);

        assertEquals(1, metadata.size());
        assertEquals(1, textData.size());
        assertEquals(file, metadata.getFirst().data().file());
        assertEquals(line, metadata.getFirst().data().line());
        assertEquals(text, textData.getFirst().data().text());
    }

    @Test
    void writeMultipleTitles_deduplicatesById() throws Exception {
        notesManager.writeReviewTitle(TEST_REVIEW_ID, TEST_EDITOR, "Title 1")
            .get(10, TimeUnit.SECONDS);
        notesManager.writeReviewTitle(TEST_REVIEW_ID, TEST_EDITOR, "Title 2")
            .get(10, TimeUnit.SECONDS);
        notesManager.writeReviewTitle(TEST_REVIEW_ID, TEST_EDITOR, "Title 3")
            .get(10, TimeUnit.SECONDS);

        List<StreamEntry<String>> titles = notesManager.readTitles(TEST_REVIEW_ID)
            .get(10, TimeUnit.SECONDS);

        assertEquals(3, titles.size(),
            "All entries with unique IDs should be preserved");
    }


    @Test
    void writeMultipleComments_maintainsOrder() throws Exception {
        for (int i = 1; i <= 5; i++) {
            String commentId = "comment-" + String.format("%03d", i);
            String text = "Comment " + i;

            notesManager.writeCommentMetadata(TEST_REVIEW_ID, commentId, TEST_EDITOR,
                "src/Test.java", i * 10, i * 10, null)
                .get(10, TimeUnit.SECONDS);
            notesManager.writeCommentText(TEST_REVIEW_ID, commentId, TEST_EDITOR, text, null, "review")
                .get(10, TimeUnit.SECONDS);
        }

        List<String> commentIds = notesManager.listCommentIds(TEST_REVIEW_ID)
            .get(10, TimeUnit.SECONDS);

        assertEquals(5, commentIds.size());

        for (int i = 0; i < 5; i++) {
            String commentId = "comment-" + String.format("%03d", i + 1);
            List<StreamEntry<GitReviewNotesManager.CommentTextData>> textData =
                notesManager.readCommentText(TEST_REVIEW_ID, commentId).get(10, TimeUnit.SECONDS);
            assertEquals("Comment " + (i + 1), textData.getFirst().data().text());
        }
    }

    @Test
    void readTitles_nonExistentReview_returnsEmptyList() throws Exception {
        String nonExistentReviewId = "01890a5d-0000-0000-0000-000000000000";

        List<StreamEntry<String>> titles = notesManager.readTitles(nonExistentReviewId)
            .get(10, TimeUnit.SECONDS);

        assertTrue(titles.isEmpty());
    }

    @Test
    void writeMultipleDataTypes_maintainsSeparately() throws Exception {
        notesManager.writeReviewTitle(TEST_REVIEW_ID, TEST_EDITOR, "My Review")
            .get(10, TimeUnit.SECONDS);
        notesManager.writeReviewStatus(TEST_REVIEW_ID, TEST_EDITOR, "pending")
            .get(10, TimeUnit.SECONDS);

        String commentId = "comment-001";
        notesManager.writeCommentMetadata(TEST_REVIEW_ID, commentId, TEST_EDITOR,
            "src/Test.java", 10, 10, null)
            .get(10, TimeUnit.SECONDS);
        notesManager.writeCommentText(TEST_REVIEW_ID, commentId, TEST_EDITOR, "A comment", null, "review")
            .get(10, TimeUnit.SECONDS);

        List<StreamEntry<String>> titles = notesManager.readTitles(TEST_REVIEW_ID)
            .get(10, TimeUnit.SECONDS);
        List<String> commentIds = notesManager.listCommentIds(TEST_REVIEW_ID)
            .get(10, TimeUnit.SECONDS);
        List<StreamEntry<GitReviewNotesManager.CommentTextData>> textData =
            notesManager.readCommentText(TEST_REVIEW_ID, commentId).get(10, TimeUnit.SECONDS);

        assertEquals(1, titles.size());
        assertEquals(1, commentIds.size());
        assertEquals("My Review", titles.getFirst().data());
        assertEquals("A comment", textData.getFirst().data().text());
    }

    @Test
    void concurrentWrites_handleGracefully() throws Exception {
        int threadCount = 3;
        int commentsPerThread = 3;
        Thread[] threads = new Thread[threadCount];
        List<String> reviewIds = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            reviewIds.add(java.util.UUID.randomUUID().toString());
        }

        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i;
            final String reviewId = reviewIds.get(i);
            threads[i] = new Thread(() -> {
                try {
                    for (int j = 0; j < commentsPerThread; j++) {
                        String commentId = "comment-thread" + threadNum + "-" + j;
                        String text = "Comment from thread " + threadNum + " iteration " + j;

                        notesManager.writeCommentMetadata(reviewId, commentId, "editor" + threadNum,
                            "src/Test.java", j * 10, j * 10, null)
                            .get(15, TimeUnit.SECONDS);
                        notesManager.writeCommentText(reviewId, commentId, "editor" + threadNum,
                            text, null, "review")
                            .get(15, TimeUnit.SECONDS);
                    }
                } catch (Exception e) {
                    fail("Concurrent write failed: " + e.getMessage());
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join(30000);
        }

        for (int i = 0; i < threadCount; i++) {
            List<String> commentIds = notesManager.listCommentIds(reviewIds.get(i))
                .get(10, TimeUnit.SECONDS);
            assertEquals(commentsPerThread, commentIds.size(),
                "Each review should have " + commentsPerThread + " comments");
        }
    }

    @Test
    void writeAfterRead_updatesCorrectly() throws Exception {
        notesManager.writeReviewTitle(TEST_REVIEW_ID, TEST_EDITOR, "Initial Title")
            .get(10, TimeUnit.SECONDS);

        List<StreamEntry<String>> titles1 = notesManager.readTitles(TEST_REVIEW_ID)
            .get(10, TimeUnit.SECONDS);
        assertEquals(1, titles1.size());

        notesManager.writeReviewTitle(TEST_REVIEW_ID, TEST_EDITOR, "Updated Title")
            .get(10, TimeUnit.SECONDS);

        List<StreamEntry<String>> titles2 = notesManager.readTitles(TEST_REVIEW_ID)
            .get(10, TimeUnit.SECONDS);
        assertEquals(2, titles2.size());
        assertEquals("Initial Title", titles2.get(0).data());
        assertEquals("Updated Title", titles2.get(1).data());
    }

    @Test
    void multipleReviewIds_storeSeparately() throws Exception {
        String reviewId1 = "01890a5d-0001-0000-0000-000000000001";
        String reviewId2 = "01890a5d-0002-0000-0000-000000000002";

        notesManager.writeReviewTitle(reviewId1, TEST_EDITOR, "Review 1 Title")
            .get(10, TimeUnit.SECONDS);
        notesManager.writeReviewTitle(reviewId2, TEST_EDITOR, "Review 2 Title")
            .get(10, TimeUnit.SECONDS);

        List<StreamEntry<String>> titles1 = notesManager.readTitles(reviewId1)
            .get(10, TimeUnit.SECONDS);
        List<StreamEntry<String>> titles2 = notesManager.readTitles(reviewId2)
            .get(10, TimeUnit.SECONDS);

        assertEquals(1, titles1.size());
        assertEquals(1, titles2.size());
        assertEquals("Review 1 Title", titles1.getFirst().data());
        assertEquals("Review 2 Title", titles2.getFirst().data());
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


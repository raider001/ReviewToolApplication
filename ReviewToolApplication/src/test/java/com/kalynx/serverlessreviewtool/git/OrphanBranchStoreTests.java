package com.kalynx.serverlessreviewtool.git;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link OrphanBranchStore}.
 *
 * <p>Each test uses two local bare repos:
 * <ul>
 *   <li>{@code remoteRepo} — acts as the "remote" (the repository the store pushes to)</li>
 *   <li>The store's own bare repo under {@code storeBase} — acts as the local mirror</li>
 * </ul>
 * Both are plain on-disk bare repos; no network is involved.
 */
class OrphanBranchStoreTests {

    @TempDir
    Path tempDir;

    private Path remoteRepo;
    private Path storeBase;
    private String remoteUrl;
    private OrphanBranchStore store;

    @BeforeEach
    void setUp() throws Exception {
        remoteRepo = tempDir.resolve("remote.git");
        storeBase = tempDir.resolve("store-base");
        Files.createDirectories(storeBase);

        // Create a bare "remote" repo that the store will push to
        runGit(tempDir, "git", "init", "--bare", remoteRepo.toString());

        remoteUrl = "file:///" + remoteRepo.toString().replace("\\", "/");
        store = new OrphanBranchStore(remoteUrl, storeBase);
    }

    @AfterEach
    void tearDown() {
        // TempDir cleans up automatically
    }

    // -------------------------------------------------------------------------
    // listReviewIds — empty repo
    // -------------------------------------------------------------------------

    @Test
    void listReviewIds_emptyRepo_returnsEmptyList() throws Exception {
        List<String> ids = store.listReviewIds().get(30, TimeUnit.SECONDS);
        assertNotNull(ids);
        assertTrue(ids.isEmpty(), "Empty repo should have no review IDs");
    }

    // -------------------------------------------------------------------------
    // readFile — file not present
    // -------------------------------------------------------------------------

    @Test
    void readFile_fileDoesNotExist_returnsEmpty() throws Exception {
        Optional<byte[]> result = store.readFile("review-001", "metadata/title")
                .get(30, TimeUnit.SECONDS);
        assertTrue(result.isEmpty(), "Non-existent file should return Optional.empty()");
    }

    // -------------------------------------------------------------------------
    // writeFile / readFile — round trip
    // -------------------------------------------------------------------------

    @Test
    void writeFile_thenReadFile_returnsWrittenContent() throws Exception {
        byte[] content = "{\"id\":\"e1\",\"data\":\"Test Title\"}".getBytes(StandardCharsets.UTF_8);

        store.writeFile("review-001", "metadata/title", content).get(30, TimeUnit.SECONDS);

        Optional<byte[]> result = store.readFile("review-001", "metadata/title")
                .get(30, TimeUnit.SECONDS);

        assertTrue(result.isPresent(), "File should be readable after write");
        assertArrayEquals(content, result.get());
    }

    @Test
    void writeFile_unicodeContent_survivesRoundTrip() throws Exception {
        byte[] content = "{\"id\":\"e1\",\"data\":\"Ünïcödé Títlé\"}".getBytes(StandardCharsets.UTF_8);

        store.writeFile("review-unicode", "metadata/title", content).get(30, TimeUnit.SECONDS);

        Optional<byte[]> result = store.readFile("review-unicode", "metadata/title")
                .get(30, TimeUnit.SECONDS);

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get());
    }

    // -------------------------------------------------------------------------
    // writeFiles — multiple paths in one commit
    // -------------------------------------------------------------------------

    @Test
    void writeFiles_multiplePaths_allReadableAfterSingleCommit() throws Exception {
        byte[] titleBytes = "title-content".getBytes(StandardCharsets.UTF_8);
        byte[] statusBytes = "OPEN".getBytes(StandardCharsets.UTF_8);
        byte[] authorBytes = "alice".getBytes(StandardCharsets.UTF_8);

        store.writeFiles("review-multi", Map.of(
                "metadata/title", titleBytes,
                "metadata/status", statusBytes,
                "metadata/author", authorBytes
        )).get(30, TimeUnit.SECONDS);

        assertArrayEquals(titleBytes,
                store.readFile("review-multi", "metadata/title").get(30, TimeUnit.SECONDS).orElseThrow());
        assertArrayEquals(statusBytes,
                store.readFile("review-multi", "metadata/status").get(30, TimeUnit.SECONDS).orElseThrow());
        assertArrayEquals(authorBytes,
                store.readFile("review-multi", "metadata/author").get(30, TimeUnit.SECONDS).orElseThrow());
    }

    @Test
    void writeFiles_deepNestedPath_readable() throws Exception {
        byte[] content = "comment-text".getBytes(StandardCharsets.UTF_8);

        store.writeFile("review-deep", "comments/c1/text", content).get(30, TimeUnit.SECONDS);

        Optional<byte[]> result = store.readFile("review-deep", "comments/c1/text")
                .get(30, TimeUnit.SECONDS);

        assertTrue(result.isPresent());
        assertArrayEquals(content, result.get());
    }

    // -------------------------------------------------------------------------
    // overwrite — second write replaces first
    // -------------------------------------------------------------------------

    @Test
    void writeFile_twice_secondValueWins() throws Exception {
        byte[] first = "first".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second".getBytes(StandardCharsets.UTF_8);

        store.writeFile("review-overwrite", "metadata/title", first).get(30, TimeUnit.SECONDS);
        store.writeFile("review-overwrite", "metadata/title", second).get(30, TimeUnit.SECONDS);

        byte[] result = store.readFile("review-overwrite", "metadata/title")
                .get(30, TimeUnit.SECONDS).orElseThrow();
        assertArrayEquals(second, result, "Second write should overwrite the first");
    }

    // -------------------------------------------------------------------------
    // sibling files — updating one path does not destroy siblings
    // -------------------------------------------------------------------------

    @Test
    void writeFile_toSiblingPath_doesNotErasePreviousFile() throws Exception {
        byte[] title = "my title".getBytes(StandardCharsets.UTF_8);
        byte[] status = "OPEN".getBytes(StandardCharsets.UTF_8);

        store.writeFile("review-siblings", "metadata/title", title).get(30, TimeUnit.SECONDS);
        store.writeFile("review-siblings", "metadata/status", status).get(30, TimeUnit.SECONDS);

        // Both files should still be present after two separate commits
        assertArrayEquals(title,
                store.readFile("review-siblings", "metadata/title").get(30, TimeUnit.SECONDS).orElseThrow(),
                "Original title should survive after writing sibling status");
        assertArrayEquals(status,
                store.readFile("review-siblings", "metadata/status").get(30, TimeUnit.SECONDS).orElseThrow());
    }

    @Test
    void writeFile_toSiblingReview_doesNotErasePreviousReview() throws Exception {
        byte[] r1 = "review-1-title".getBytes(StandardCharsets.UTF_8);
        byte[] r2 = "review-2-title".getBytes(StandardCharsets.UTF_8);

        store.writeFile("review-001", "metadata/title", r1).get(30, TimeUnit.SECONDS);
        store.writeFile("review-002", "metadata/title", r2).get(30, TimeUnit.SECONDS);

        assertArrayEquals(r1,
                store.readFile("review-001", "metadata/title").get(30, TimeUnit.SECONDS).orElseThrow(),
                "Review-001 should survive after writing review-002");
        assertArrayEquals(r2,
                store.readFile("review-002", "metadata/title").get(30, TimeUnit.SECONDS).orElseThrow());
    }

    // -------------------------------------------------------------------------
    // listReviewIds — populated repo
    // -------------------------------------------------------------------------

    @Test
    void listReviewIds_afterWritingTwoReviews_returnsTheirIds() throws Exception {
        store.writeFile("review-aaa", "metadata/title", "A".getBytes(StandardCharsets.UTF_8))
                .get(30, TimeUnit.SECONDS);
        store.writeFile("review-bbb", "metadata/title", "B".getBytes(StandardCharsets.UTF_8))
                .get(30, TimeUnit.SECONDS);

        List<String> ids = store.listReviewIds().get(30, TimeUnit.SECONDS);
        assertTrue(ids.contains("review-aaa"), "Should contain first review ID");
        assertTrue(ids.contains("review-bbb"), "Should contain second review ID");
        assertEquals(2, ids.size());
    }

    // -------------------------------------------------------------------------
    // remote persistence — data is actually on the remote
    // -------------------------------------------------------------------------

    @Test
    void writtenData_persistsOnRemote_readableFromFreshStore() throws Exception {
        byte[] content = "persisted-content".getBytes(StandardCharsets.UTF_8);
        store.writeFile("review-persist", "metadata/title", content).get(30, TimeUnit.SECONDS);

        // A second store instance pointing at the same remote should see the data
        Path storeBase2 = tempDir.resolve("store-base-2");
        Files.createDirectories(storeBase2);
        OrphanBranchStore store2 = new OrphanBranchStore(remoteUrl, storeBase2);

        Optional<byte[]> result = store2.readFile("review-persist", "metadata/title")
                .get(30, TimeUnit.SECONDS);

        assertTrue(result.isPresent(), "Second store instance should read data persisted by first");
        assertArrayEquals(content, result.get());
    }

    @Test
    void listReviewIds_fromFreshStore_returnsPersistedIds() throws Exception {
        store.writeFile("review-x", "metadata/title", "X".getBytes(StandardCharsets.UTF_8))
                .get(30, TimeUnit.SECONDS);

        Path storeBase2 = tempDir.resolve("store-base-2");
        Files.createDirectories(storeBase2);
        OrphanBranchStore store2 = new OrphanBranchStore(remoteUrl, storeBase2);

        List<String> ids = store2.listReviewIds().get(30, TimeUnit.SECONDS);
        assertTrue(ids.contains("review-x"));
    }

    // -------------------------------------------------------------------------
    // concurrent writes — serialized within one instance
    // -------------------------------------------------------------------------

    @Test
    void concurrentWritesToSameReview_allSucceed() throws Exception {
        // Fire several writes concurrently; the single-thread executor serialises them.
        // All should complete without exception and the last-writer-wins value should persist.
        var f1 = store.writeFile("review-concurrent", "metadata/title",
                "v1".getBytes(StandardCharsets.UTF_8));
        var f2 = store.writeFile("review-concurrent", "metadata/title",
                "v2".getBytes(StandardCharsets.UTF_8));
        var f3 = store.writeFile("review-concurrent", "metadata/title",
                "v3".getBytes(StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> f1.get(30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> f2.get(30, TimeUnit.SECONDS));
        assertDoesNotThrow(() -> f3.get(30, TimeUnit.SECONDS));

        Optional<byte[]> result = store.readFile("review-concurrent", "metadata/title")
                .get(30, TimeUnit.SECONDS);
        assertTrue(result.isPresent());
        // The final value should be one of the written values (last writer wins)
        String value = new String(result.get(), StandardCharsets.UTF_8);
        assertTrue(value.equals("v1") || value.equals("v2") || value.equals("v3"),
                "Final value should be one of the written values, was: " + value);
    }

    // -------------------------------------------------------------------------
    // listCommentIds
    // -------------------------------------------------------------------------

    @Test
    void listCommentIds_noComments_returnsEmptyList() throws Exception {
        store.writeFile("review-c1", "metadata/title", "t".getBytes(StandardCharsets.UTF_8))
                .get(30, TimeUnit.SECONDS);

        List<String> ids = store.listCommentIds("review-c1").get(30, TimeUnit.SECONDS);
        assertNotNull(ids);
        assertTrue(ids.isEmpty(), "Review with no comments should return empty list");
    }

    @Test
    void listCommentIds_afterWritingTwoComments_returnsBothIds() throws Exception {
        store.writeFile("review-c2", "comments/c1/text", "hello".getBytes(StandardCharsets.UTF_8))
                .get(30, TimeUnit.SECONDS);
        store.writeFile("review-c2", "comments/c2/text", "world".getBytes(StandardCharsets.UTF_8))
                .get(30, TimeUnit.SECONDS);

        List<String> ids = store.listCommentIds("review-c2").get(30, TimeUnit.SECONDS);
        assertEquals(2, ids.size());
        assertTrue(ids.contains("c1"));
        assertTrue(ids.contains("c2"));
    }

    @Test
    void listCommentIds_unknownReview_returnsEmptyList() throws Exception {
        List<String> ids = store.listCommentIds("nonexistent-review").get(30, TimeUnit.SECONDS);
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    // -------------------------------------------------------------------------
    // empty content
    // -------------------------------------------------------------------------

    @Test
    void writeFile_emptyContent_writesAndReadsBack() throws Exception {
        byte[] empty = new byte[0];
        store.writeFile("review-empty", "metadata/title", empty).get(30, TimeUnit.SECONDS);

        Optional<byte[]> result = store.readFile("review-empty", "metadata/title")
                .get(30, TimeUnit.SECONDS);
        assertTrue(result.isPresent());
        assertEquals(0, result.get().length);
    }

    // -------------------------------------------------------------------------
    // helper
    // -------------------------------------------------------------------------

    private static void runGit(Path workDir, String... args) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(args)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("Command " + List.of(args) + " failed with exit " + exit);
        }
    }
}

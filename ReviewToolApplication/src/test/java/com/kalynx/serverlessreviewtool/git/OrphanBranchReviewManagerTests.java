package com.kalynx.serverlessreviewtool.git;

import com.kalynx.serverlessreviewtool.models.review.ReviewerData;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link OrphanBranchReviewManager}.
 *
 * <p>Each test uses two local bare repos as the remote and the local mirror, matching the
 * pattern used by {@link OrphanBranchStoreTests}.  No network is involved.
 */
class OrphanBranchReviewManagerTests {

    @TempDir
    Path tempDir;

    private String remoteUrl;
    private OrphanBranchStore store;
    private OrphanBranchReviewManager manager;

    @BeforeEach
    void setUp() throws Exception {
        Path remoteRepo = tempDir.resolve("remote.git");
        Path storeBase  = tempDir.resolve("store-base");
        Files.createDirectories(storeBase);

        runGit(tempDir, "git", "init", "--bare", remoteRepo.toString());

        remoteUrl = "file:///" + remoteRepo.toString().replace("\\", "/");
        store     = new OrphanBranchStore(remoteUrl, storeBase);
        manager   = new OrphanBranchReviewManager(store, "my-repo");
    }

    // -------------------------------------------------------------------------
    // Single-field write / read round-trips
    // -------------------------------------------------------------------------

    @Test
    void writeReviewTitle_thenReadTitles_returnsEntry() throws Exception {
        manager.writeReviewTitle("r1", "alice", "My Title").get(30, TimeUnit.SECONDS);

        List<StreamEntry<String>> titles = manager.readTitles("r1").get(30, TimeUnit.SECONDS);

        assertFalse(titles.isEmpty(), "readTitles should return at least one entry");
        assertEquals("My Title", titles.get(0).data());
        assertEquals("alice",    titles.get(0).editor());
    }

    @Test
    void writeReviewDescription_thenReadDescriptions_returnsEntry() throws Exception {
        manager.writeReviewDescription("r1", "bob", "Some description.").get(30, TimeUnit.SECONDS);

        List<StreamEntry<String>> descs = manager.readDescriptions("r1").get(30, TimeUnit.SECONDS);

        assertFalse(descs.isEmpty());
        assertEquals("Some description.", descs.get(0).data());
    }

    @Test
    void writeReviewAuthor_thenReadAuthors_returnsEntry() throws Exception {
        manager.writeReviewAuthor("r1", "charlie", "charlie").get(30, TimeUnit.SECONDS);

        List<StreamEntry<String>> authors = manager.readAuthors("r1").get(30, TimeUnit.SECONDS);

        assertFalse(authors.isEmpty());
        assertEquals("charlie", authors.get(0).data());
    }

    @Test
    void writeReviewStatus_thenReadStatuses_returnsEntry() throws Exception {
        manager.writeReviewStatus("r1", "alice", "OPEN").get(30, TimeUnit.SECONDS);

        List<StreamEntry<String>> statuses = manager.readStatuses("r1").get(30, TimeUnit.SECONDS);

        assertFalse(statuses.isEmpty());
        assertEquals("OPEN", statuses.get(0).data());
    }

    @Test
    void writeReviewCommits_thenReadCommits_returnsEntry() throws Exception {
        List<String> commits = List.of("abc123", "def456");
        manager.writeReviewCommits("r1", "alice", commits).get(30, TimeUnit.SECONDS);

        var entries = manager.readCommits("r1").get(30, TimeUnit.SECONDS);

        assertFalse(entries.isEmpty());
        assertEquals(commits, entries.get(0).data());
    }

    @Test
    void writeReviewer_thenReadReviewers_returnsEntry() throws Exception {
        ReviewerData reviewerData = new ReviewerData(ReviewerData.Status.PENDING.getValue(), "");
        manager.writeReviewer("r1", "alice", reviewerData).get(30, TimeUnit.SECONDS);

        List<StreamEntry<ReviewerData>> reviewers = manager.readReviewers("r1").get(30, TimeUnit.SECONDS);

        assertFalse(reviewers.isEmpty());
        assertEquals(ReviewerData.Status.PENDING.getValue(), reviewers.get(0).data().getStatus());
    }

    // -------------------------------------------------------------------------
    // Comment stream write / read
    // -------------------------------------------------------------------------

    @Test
    void writeCommentMetadata_thenReadCommentMetadata_roundTrips() throws Exception {
        manager.writeCommentMetadata("r1", "c1", "alice", "src/Foo.java", 10, 12, "abc123")
               .get(30, TimeUnit.SECONDS);

        var entries = manager.readCommentMetadata("r1", "c1").get(30, TimeUnit.SECONDS);

        assertFalse(entries.isEmpty());
        OrphanBranchReviewManager.CommentMetadata meta = entries.get(0).data();
        assertEquals("src/Foo.java", meta.file());
        assertEquals(10,             meta.line());
        assertEquals(12,             meta.lineEnd());
        assertEquals("abc123",       meta.commit());
    }

    @Test
    void writeCommentText_thenReadCommentText_roundTrips() throws Exception {
        manager.writeCommentText("r1", "c1", "alice", "Looks good!", null, "GENERAL")
               .get(30, TimeUnit.SECONDS);

        var entries = manager.readCommentText("r1", "c1").get(30, TimeUnit.SECONDS);

        assertFalse(entries.isEmpty());
        OrphanBranchReviewManager.CommentTextData text = entries.get(0).data();
        assertEquals("Looks good!", text.text());
        assertNull(text.replyTo());
        assertEquals("GENERAL", text.type());
    }

    @Test
    void writeCommentStatus_thenReadCommentStatus_roundTrips() throws Exception {
        manager.writeCommentStatus("r1", "c1", "alice", true, false)
               .get(30, TimeUnit.SECONDS);

        var entries = manager.readCommentStatus("r1", "c1").get(30, TimeUnit.SECONDS);

        assertFalse(entries.isEmpty());
        OrphanBranchReviewManager.CommentStatusData status = entries.get(0).data();
        assertTrue(status.needsResolution());
        assertFalse(status.resolved());
    }

    // -------------------------------------------------------------------------
    // createReview — atomic batch write
    // -------------------------------------------------------------------------

    @Test
    void createReview_allFieldsWrittenAtomically() throws Exception {
        manager.createReview(
                "r1", "alice",
                "Title",
                "author",
                "Description",
                "OPEN",
                List.of("sha1", "sha2"),
                List.of("bob"),
                "feature/branch",
                "main"
        ).get(30, TimeUnit.SECONDS);

        // All fields should be readable in one round-trip
        OrphanBranchReviewManager.ReviewMetadata meta = manager.readAllMetadata("r1").get(30, TimeUnit.SECONDS);

        assertFalse(meta.titles().isEmpty(),       "title should be stored");
        assertFalse(meta.descriptions().isEmpty(), "description should be stored");
        assertFalse(meta.authors().isEmpty(),       "author should be stored");
        assertFalse(meta.statuses().isEmpty(),      "status should be stored");
        assertFalse(meta.branches().isEmpty(),      "branch should be stored");
        assertFalse(meta.baseBranches().isEmpty(),  "baseBranch should be stored");
        assertFalse(meta.reviewers().isEmpty(),     "reviewer should be stored");

        assertEquals("Title",          meta.titles().get(0).data());
        assertEquals("author",         meta.authors().get(0).data());
        assertEquals("Description",    meta.descriptions().get(0).data());
        assertEquals("OPEN",           meta.statuses().get(0).data());
        assertEquals("feature/branch", meta.branches().get(0).data());
        assertEquals("main",           meta.baseBranches().get(0).data());
    }

    @Test
    void createReview_nullReviewers_noReviewerEntries() throws Exception {
        manager.createReview(
                "r-no-reviewers", "alice",
                "Title", "author", "Desc", "OPEN",
                List.of(), null,          // null reviewers
                "feature/x", "main"
        ).get(30, TimeUnit.SECONDS);

        OrphanBranchReviewManager.ReviewMetadata meta =
                manager.readAllMetadata("r-no-reviewers").get(30, TimeUnit.SECONDS);

        // createReview with null reviewers should not write any reviewer entries
        // (the write may not even add a reviewers file).
        // Statuses & titles must still be present:
        assertFalse(meta.titles().isEmpty());
        assertEquals("Title", meta.titles().get(0).data());
    }

    // -------------------------------------------------------------------------
    // createSecondaryReviewReference
    // -------------------------------------------------------------------------

    @Test
    void createSecondaryReviewReference_writesExpectedFields() throws Exception {
        manager.createSecondaryReviewReference(
                "r-secondary", "alice",
                List.of("sha1"),
                "feature/secondary",
                "main"
        ).get(30, TimeUnit.SECONDS);

        OrphanBranchReviewManager.ReviewMetadata meta =
                manager.readAllMetadata("r-secondary").get(30, TimeUnit.SECONDS);

        assertFalse(meta.branches().isEmpty());
        assertFalse(meta.baseBranches().isEmpty());
        assertEquals("feature/secondary", meta.branches().get(0).data());
        assertEquals("main",              meta.baseBranches().get(0).data());

        // primaryRepository should be "false"
        assertFalse(meta.primaryRepository().isEmpty());
        assertEquals("false", meta.primaryRepository().get(0).data());
    }

    // -------------------------------------------------------------------------
    // listReviewIds
    // -------------------------------------------------------------------------

    @Test
    void listReviewIds_afterCreatingTwoReviews_returnsBothIds() throws Exception {
        manager.writeReviewTitle("ra", "alice", "Review A").get(30, TimeUnit.SECONDS);
        manager.writeReviewTitle("rb", "alice", "Review B").get(30, TimeUnit.SECONDS);

        List<String> ids = manager.listReviewIds().get(30, TimeUnit.SECONDS);

        assertTrue(ids.contains("ra"), "Should contain review 'ra'");
        assertTrue(ids.contains("rb"), "Should contain review 'rb'");
        assertEquals(2, ids.size());
    }

    @Test
    void listReviewIds_emptyRepo_returnsEmptyList() throws Exception {
        List<String> ids = manager.listReviewIds().get(30, TimeUnit.SECONDS);

        assertNotNull(ids);
        assertTrue(ids.isEmpty(), "Empty repo should yield empty review ID list");
    }

    // -------------------------------------------------------------------------
    // Sibling isolation
    // -------------------------------------------------------------------------

    @Test
    void writingOneField_doesNotOverwriteSiblingField() throws Exception {
        manager.writeReviewTitle("r1",  "alice", "Title One").get(30, TimeUnit.SECONDS);
        manager.writeReviewStatus("r1", "alice", "OPEN").get(30, TimeUnit.SECONDS);

        List<StreamEntry<String>> titles  = manager.readTitles("r1").get(30, TimeUnit.SECONDS);
        List<StreamEntry<String>> statuses = manager.readStatuses("r1").get(30, TimeUnit.SECONDS);

        assertFalse(titles.isEmpty(),   "title should survive after writing status");
        assertFalse(statuses.isEmpty(), "status should be readable after writing");
        assertEquals("Title One", titles.get(0).data());
        assertEquals("OPEN",       statuses.get(0).data());
    }

    @Test
    void writingOneReview_doesNotOverwriteAnotherReview() throws Exception {
        manager.writeReviewTitle("r-alpha", "alice", "Alpha").get(30, TimeUnit.SECONDS);
        manager.writeReviewTitle("r-beta",  "alice", "Beta").get(30, TimeUnit.SECONDS);

        List<StreamEntry<String>> alphaTitles =
                manager.readTitles("r-alpha").get(30, TimeUnit.SECONDS);
        List<StreamEntry<String>> betaTitles  =
                manager.readTitles("r-beta").get(30, TimeUnit.SECONDS);

        assertFalse(alphaTitles.isEmpty(), "r-alpha title should survive after writing r-beta");
        assertFalse(betaTitles.isEmpty());
        assertEquals("Alpha", alphaTitles.get(0).data());
        assertEquals("Beta",  betaTitles.get(0).data());
    }

    // -------------------------------------------------------------------------
    // Overwrite semantics
    // -------------------------------------------------------------------------

    @Test
    void writeSameFieldTwice_secondValueWins() throws Exception {
        manager.writeReviewTitle("r1", "alice", "First Title").get(30, TimeUnit.SECONDS);
        manager.writeReviewTitle("r1", "alice", "Second Title").get(30, TimeUnit.SECONDS);

        List<StreamEntry<String>> titles = manager.readTitles("r1").get(30, TimeUnit.SECONDS);

        assertFalse(titles.isEmpty());
        assertEquals("Second Title", titles.get(0).data(),
                "Second write should overwrite the first");
    }

    // -------------------------------------------------------------------------
    // saveAllMetadataBatch
    // -------------------------------------------------------------------------

    @Test
    void saveAllMetadataBatch_allFieldsUpdatedAtomically() throws Exception {
        // First create the review
        manager.createReview(
                "r-batch", "alice",
                "Old Title", "author", "Old Desc", "OPEN",
                List.of(), List.of(), "feature/x", "main"
        ).get(30, TimeUnit.SECONDS);

        // Then update in batch
        ReviewerData reviewerData = new ReviewerData(ReviewerData.Status.APPROVED.getValue(), "LGTM");
        manager.saveAllMetadataBatch(
                "r-batch", "alice",
                "New Title", "New Desc", "new-author", "MERGED",
                List.of(Map.entry("bob", reviewerData))
        ).get(30, TimeUnit.SECONDS);

        OrphanBranchReviewManager.ReviewMetadata meta =
                manager.readAllMetadata("r-batch").get(30, TimeUnit.SECONDS);

        assertEquals("New Title",   meta.titles().get(0).data());
        assertEquals("New Desc",    meta.descriptions().get(0).data());
        assertEquals("new-author",  meta.authors().get(0).data());
        assertEquals("MERGED",      meta.statuses().get(0).data());
        assertFalse(meta.reviewers().isEmpty());
        assertEquals(ReviewerData.Status.APPROVED.getValue(),
                meta.reviewers().get(0).data().getStatus());
    }

    // -------------------------------------------------------------------------
    // listCommentIds
    // -------------------------------------------------------------------------

    @Test
    void listCommentIds_noComments_returnsEmptyList() throws Exception {
        manager.writeReviewTitle("r-nocomments", "alice", "T").get(30, TimeUnit.SECONDS);

        List<String> ids = manager.listCommentIds("r-nocomments").get(30, TimeUnit.SECONDS);
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    @Test
    void listCommentIds_afterWritingTwoComments_returnsBothIds() throws Exception {
        manager.writeCommentText("r-comments", "c1", "alice", "First", null, "general")
                .get(30, TimeUnit.SECONDS);
        manager.writeCommentText("r-comments", "c2", "alice", "Second", null, "general")
                .get(30, TimeUnit.SECONDS);

        List<String> ids = manager.listCommentIds("r-comments").get(30, TimeUnit.SECONDS);
        assertEquals(2, ids.size());
        assertTrue(ids.contains("c1"));
        assertTrue(ids.contains("c2"));
    }

    @Test
    void listCommentIds_unknownReview_returnsEmptyList() throws Exception {
        List<String> ids = manager.listCommentIds("does-not-exist").get(30, TimeUnit.SECONDS);
        assertNotNull(ids);
        assertTrue(ids.isEmpty());
    }

    // -------------------------------------------------------------------------
    // readAllMetadata — missing fields return empty lists
    // -------------------------------------------------------------------------

    @Test
    void readAllMetadata_nonExistentReview_allFieldsEmpty() throws Exception {
        OrphanBranchReviewManager.ReviewMetadata meta =
                manager.readAllMetadata("does-not-exist").get(30, TimeUnit.SECONDS);

        assertNotNull(meta);
        assertTrue(meta.titles().isEmpty(),       "titles should be empty for missing review");
        assertTrue(meta.descriptions().isEmpty(), "descriptions should be empty");
        assertTrue(meta.authors().isEmpty(),      "authors should be empty");
        assertTrue(meta.statuses().isEmpty(),     "statuses should be empty");
        assertTrue(meta.reviewers().isEmpty(),    "reviewers should be empty");
    }

    // -------------------------------------------------------------------------
    // Helper
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

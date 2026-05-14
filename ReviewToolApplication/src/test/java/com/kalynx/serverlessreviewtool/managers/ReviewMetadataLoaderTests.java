package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.GitReviewNotesManager;
import com.kalynx.serverlessreviewtool.git.ReviewNotesManagerFactory;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import com.kalynx.serverlessreviewtool.models.review.ReviewerData;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ReviewMetadataLoader covering null-guard paths and metadata assembly.
 */
class ReviewMetadataLoaderTests {

    private static final String REVIEW_ID = "review-abc";
    private static final String REPO_NAME = "backend";

    private GitReviewNotesManager notesManager;
    private RepositoryManager repositoryManager;
    private ReviewMetadataLoader loader;

    @BeforeEach
    void setUp() {
        notesManager = mock(GitReviewNotesManager.class);
        repositoryManager = mock(RepositoryManager.class);
        ReviewCommentManager commentManager = mock(ReviewCommentManager.class);

        ReviewNotesManagerFactory factory = _ -> notesManager;
        loader = new ReviewMetadataLoader(factory, repositoryManager, commentManager, () -> null);

        when(commentManager.loadCommentsFromKnownRepository(anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of()));
    }

    @Test
    void loadReviewMetadata_nullId_returnsNull() throws Exception {
        ReviewContext result = loader.loadReviewMetadata(null).get(1, TimeUnit.SECONDS);
        assertNull(result);
    }

    @Test
    void loadReviewMetadata_emptyId_returnsNull() throws Exception {
        ReviewContext result = loader.loadReviewMetadata("").get(1, TimeUnit.SECONDS);
        assertNull(result);
    }

    @Test
    void loadReviewMetadata_noRepositories_returnsNull() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of());

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID).get(1, TimeUnit.SECONDS);
        assertNull(result);
    }

    @Test
    void loadReviewMetadataOnly_nullId_returnsNull() throws Exception {
        ReviewContext result = loader.loadReviewMetadataOnly(null).get(1, TimeUnit.SECONDS);
        assertNull(result);
    }

    @Test
    void loadReviewMetadata_withRepositoryAndMetadata_assemblesContext() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        stubNotesManagerForReviewFound("My Review Title", "author@example.com",
            "OPEN", "feature/my-branch", "main");

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(REVIEW_ID, result.getReviewId());
        assertEquals("My Review Title", result.getTitle());
        assertEquals("author@example.com", result.getAuthor());
        assertEquals(ReviewStatus.OPEN, result.status);
        assertEquals("feature/my-branch", result.getBranch());
        assertEquals("main", result.getBaseBranch());
        assertEquals(REPO_NAME, result.getRepositories().getFirst().getName());
    }

    @Test
    void loadReviewMetadata_closedStatus_setsCompletedStatus() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        stubNotesManagerForReviewFound("Done", "author", "COMPLETED", "feature", "main");

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(ReviewStatus.COMPLETED, result.status);
    }

    @Test
    void loadReviewMetadata_unknownStatus_defaultsToOpen() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        stubNotesManagerForReviewFound("Title", "author", "BOGUS_STATUS", "branch", "base");

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(ReviewStatus.OPEN, result.status);
    }

    @Test
    void loadReviewMetadata_withReviewerApproved_parsesReviewerStatus() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        GitReviewNotesManager.ReviewMetadata metadata = buildMetadata("Title", "author", "OPEN", "branch", "base",
            List.of(streamEntry("alice", new ReviewerData("approved", null))));

        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry("alice", "Title"))));
        when(notesManager.readAllMetadata(anyString()))
            .thenReturn(CompletableFuture.completedFuture(metadata));

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(1, result.getReviewers().size());
        assertEquals("alice", result.getReviewers().getFirst().getName());
        assertEquals(ReviewerStatus.APPROVED, result.getReviewers().getFirst().getStatus());
    }

    @Test
    void loadReviewMetadata_reviewerWithLeftStatus_excludedFromReviewers() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        GitReviewNotesManager.ReviewMetadata metadata = buildMetadata("Title", "author", "OPEN", "branch", "base",
            List.of(streamEntry("bob", new ReviewerData("left", null))));

        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry("alice", "Title"))));
        when(notesManager.readAllMetadata(anyString()))
            .thenReturn(CompletableFuture.completedFuture(metadata));

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.getReviewers().isEmpty());
    }

    @Test
    void loadReviewMetadata_missingTitle_defaultsToUntitledReview() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        GitReviewNotesManager.ReviewMetadata metadata = new GitReviewNotesManager.ReviewMetadata(
            List.of(), List.of(), List.of(stringEntry("author", "author")),
            List.of(), List.of(), List.of(), List.of(stringEntry("system", "OPEN")), List.of());

        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry("x", "something"))));
        when(notesManager.readAllMetadata(anyString()))
            .thenReturn(CompletableFuture.completedFuture(metadata));

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals("Untitled Review", result.getTitle());
    }

    @Test
    void loadReviewMetadataOnly_withRepositoriesAndKnownPrimary_loadsMetadataWithoutComments() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        stubNotesManagerForReviewFound("Title", "author", "OPEN", "feature", "main");

        ReviewContext result = loader.loadReviewMetadataOnly(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(REVIEW_ID, result.getReviewId());
        assertEquals("Title", result.getTitle());
    }

    @Test
    void loadReviewMetadataOnly_nullRepoNames_fallsBackToGlobalSearch() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of());

        ReviewContext result = loader.loadReviewMetadataOnly(REVIEW_ID, null)
            .get(2, TimeUnit.SECONDS);

        assertNull(result);
    }

    @Test
    void loadReviewMetadataOnly_emptyRepoNames_fallsBackToGlobalSearch() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of());

        ReviewContext result = loader.loadReviewMetadataOnly(REVIEW_ID, List.of())
            .get(2, TimeUnit.SECONDS);

        assertNull(result);
    }

    @Test
    void loadReviewMetadata_nullRepoNames_fallsBackToGlobalSearch() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of());

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, null)
            .get(2, TimeUnit.SECONDS);

        assertNull(result);
    }

    @Test
    void loadReviewMetadata_emptyRepoNames_fallsBackToGlobalSearch() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of());

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of())
            .get(2, TimeUnit.SECONDS);

        assertNull(result);
    }

    @Test
    void loadReviewMetadata_repoNamesNotFoundInManager_fallsBackToGlobalSearch() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of());
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(null);

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME))
            .get(2, TimeUnit.SECONDS);

        assertNull(result);
    }

    @Test
    void loadReviewMetadata_withReviewerChangesRequested_parsesStatus() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        GitReviewNotesManager.ReviewMetadata metadata = buildMetadata("Title", "author", "OPEN", "branch", "base",
            List.of(streamEntry("charlie", new ReviewerData("changes_requested", null))));

        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry("author", "Title"))));
        when(notesManager.readAllMetadata(anyString()))
            .thenReturn(CompletableFuture.completedFuture(metadata));

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(1, result.getReviewers().size());
        assertEquals(ReviewerStatus.CHANGES_REQUESTED, result.getReviewers().getFirst().getStatus());
    }

    @Test
    void loadReviewMetadata_withReviewerRejected_mapsToChangesRequested() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        GitReviewNotesManager.ReviewMetadata metadata = buildMetadata("Title", "author", "OPEN", "branch", "base",
            List.of(streamEntry("dave", new ReviewerData("rejected", null))));

        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry("author", "Title"))));
        when(notesManager.readAllMetadata(anyString()))
            .thenReturn(CompletableFuture.completedFuture(metadata));

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(ReviewerStatus.CHANGES_REQUESTED, result.getReviewers().getFirst().getStatus());
    }

    @Test
    void loadReviewMetadata_withReviewerReviewing_parsesStatus() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        GitReviewNotesManager.ReviewMetadata metadata = buildMetadata("Title", "author", "OPEN", "branch", "base",
            List.of(streamEntry("eve", new ReviewerData("reviewing", null))));

        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry("author", "Title"))));
        when(notesManager.readAllMetadata(anyString()))
            .thenReturn(CompletableFuture.completedFuture(metadata));

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(ReviewerStatus.REVIEWING, result.getReviewers().getFirst().getStatus());
    }

    @Test
    void loadReviewMetadata_withReviewerPending_mapsToReviewing() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        GitReviewNotesManager.ReviewMetadata metadata = buildMetadata("Title", "author", "OPEN", "branch", "base",
            List.of(streamEntry("fred", new ReviewerData("pending", null))));

        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry("author", "Title"))));
        when(notesManager.readAllMetadata(anyString()))
            .thenReturn(CompletableFuture.completedFuture(metadata));

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(ReviewerStatus.REVIEWING, result.getReviewers().getFirst().getStatus());
    }

    @Test
    void loadReviewMetadata_withReviewerUnknownStatus_defaultsToReviewing() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        GitReviewNotesManager.ReviewMetadata metadata = buildMetadata("Title", "author", "OPEN", "branch", "base",
            List.of(streamEntry("grace", new ReviewerData("unknown_gibberish", null))));

        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry("author", "Title"))));
        when(notesManager.readAllMetadata(anyString()))
            .thenReturn(CompletableFuture.completedFuture(metadata));

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals(ReviewerStatus.REVIEWING, result.getReviewers().getFirst().getStatus());
    }

    @Test
    void loadReviewMetadata_cancelledStatusInHistory_hasClosedHistory() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        GitReviewNotesManager.ReviewMetadata metadata = new GitReviewNotesManager.ReviewMetadata(
            List.of(stringEntry("author", "Title")),
            List.of(),
            List.of(stringEntry("author", "author")),
            List.of(),
            List.of(stringEntry("author", "feature")),
            List.of(stringEntry("author", "main")),
            List.of(stringEntry("author", "CANCELLED"), stringEntry("author", "OPEN")),
            List.of()
        );

        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry("author", "Title"))));
        when(notesManager.readAllMetadata(anyString()))
            .thenReturn(CompletableFuture.completedFuture(metadata));

        ReviewContext result = loader.loadReviewMetadata(REVIEW_ID, List.of(REPO_NAME), REPO_NAME)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertTrue(result.hasClosedHistory());
    }

    @Test
    void loadReviewMetadataOnly_withNullKnownPrimary_fallsBackToResolutionWithExplicitPrimary() throws Exception {
        when(repositoryManager.getRepositories()).thenReturn(List.of(new Repository(REPO_NAME, "", "")));
        when(repositoryManager.getRepositoryByName(REPO_NAME)).thenReturn(new Repository(REPO_NAME, "", ""));

        GitReviewNotesManager.ReviewMetadata metadataWithPrimary = new GitReviewNotesManager.ReviewMetadata(
            List.of(stringEntry("author", "Title")),
            List.of(),
            List.of(stringEntry("author", "author")),
            List.of(stringEntry("author", "true")),
            List.of(stringEntry("author", "feature")),
            List.of(stringEntry("author", "main")),
            List.of(stringEntry("author", "OPEN")),
            List.of()
        );

        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry("author", "Title"))));
        when(notesManager.readAllMetadata(anyString()))
            .thenReturn(CompletableFuture.completedFuture(metadataWithPrimary));

        ReviewContext result = loader.loadReviewMetadataOnly(REVIEW_ID, List.of(REPO_NAME), null)
            .get(3, TimeUnit.SECONDS);

        assertNotNull(result);
        assertEquals("Title", result.getTitle());
    }

    private void stubNotesManagerForReviewFound(String title, String author,
                                                String status, String branch, String baseBranch) {
        GitReviewNotesManager.ReviewMetadata metadata = buildMetadata(title, author, status, branch, baseBranch, List.of());
        when(notesManager.readTitles(anyString()))
            .thenReturn(CompletableFuture.completedFuture(List.of(stringEntry(author, title))));
        when(notesManager.readAllMetadata(ReviewMetadataLoaderTests.REVIEW_ID))
            .thenReturn(CompletableFuture.completedFuture(metadata));
    }

    private GitReviewNotesManager.ReviewMetadata buildMetadata(String title, String author, String status,
                                                               String branch, String baseBranch,
                                                               List<StreamEntry<ReviewerData>> reviewers) {
        return new GitReviewNotesManager.ReviewMetadata(
            List.of(stringEntry(author, title)),
            List.of(),
            List.of(stringEntry(author, author)),
            List.of(),
            List.of(stringEntry(author, branch)),
            List.of(stringEntry(author, baseBranch)),
            List.of(stringEntry(author, status)),
            reviewers
        );
    }

    private StreamEntry<String> stringEntry(String editor, String value) {
        return new StreamEntry<>("id-" + editor, Instant.now(), editor, value);
    }

    private <T> StreamEntry<T> streamEntry(String editor, T value) {
        return new StreamEntry<>("id-" + editor, Instant.now(), editor, value);
    }
}




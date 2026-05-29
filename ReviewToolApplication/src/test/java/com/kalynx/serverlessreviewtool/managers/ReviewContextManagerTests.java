package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.git.GitImpl;
import com.kalynx.serverlessreviewtool.git.OrphanBranchReviewManager;
import com.kalynx.serverlessreviewtool.git.OrphanBranchStore;
import com.kalynx.serverlessreviewtool.git.ReviewBranchManagerFactory;
import com.kalynx.serverlessreviewtool.git.ReviewCloneManager;
import com.kalynx.serverlessreviewtool.indexer.CommentIndexerClient;
import static org.mockito.Mockito.mock;
import com.kalynx.serverlessreviewtool.mockdata.GitRepositoryInitializer;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.plugin.RepositoryDescriptor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ReviewContextManagerTests {

    private static final String PRIMARY_REPOSITORY = "java-backend-service";
    private static final String SECONDARY_REPOSITORY = "python-api-service";
    private static final String REVIEW_BRANCH = "trial/multi-repository-review-live";
    private static final String BASE_BRANCH = "master";
    private static final String TITLE = "Multi-repo regression review";
    private static final String AUTHOR = "Sir CommitsALot";
    private static final String SUMMARY = "Verifies primary repository metadata is used.";

    @TempDir
    Path tempDir;

    private Git git;
    private ReviewContextManager reviewContextManager;
    private OrphanBranchReviewManager primaryOrphanManager;
    private OrphanBranchReviewManager secondaryOrphanManager;

    @BeforeAll
    static void setUpMockRepositories() {
        try {
            GitRepositoryInitializer.main();
        } catch (Exception e) {
            throw new RuntimeException("Cannot run tests without mock repositories", e);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Path gitRoot = tempDir.resolve("test-repositories");
        git = new GitImpl(gitRoot);

        String primaryUrl = toFileRemoteUrl(PRIMARY_REPOSITORY);
        String secondaryUrl = toFileRemoteUrl(SECONDARY_REPOSITORY);

        OrphanBranchStore primaryStore = new OrphanBranchStore(primaryUrl, gitRoot);
        OrphanBranchStore secondaryStore = new OrphanBranchStore(secondaryUrl, gitRoot);
        primaryOrphanManager = new OrphanBranchReviewManager(primaryStore, PRIMARY_REPOSITORY);
        secondaryOrphanManager = new OrphanBranchReviewManager(secondaryStore, SECONDARY_REPOSITORY);

        ReviewBranchManagerFactory factory = repoName ->
            repoName.equals(PRIMARY_REPOSITORY) ? primaryOrphanManager : secondaryOrphanManager;

        RepositoryManager repositoryManager = new RepositoryManager();
        reviewContextManager = new ReviewContextManager(repositoryManager,
            new ReviewCommentManager(factory, mock(CommentIndexerClient.class), repositoryManager),
            new ReviewChangeSetManager(mock(ReviewCloneManager.class), factory),
            factory);

        git.cloneRepository(primaryUrl).get(30, TimeUnit.SECONDS);
        git.cloneRepository(secondaryUrl).get(30, TimeUnit.SECONDS);

        repositoryManager.setRepositoriesFromNotification(List.of(
            new RepositoryDescriptor(SECONDARY_REPOSITORY, secondaryUrl),
            new RepositoryDescriptor(PRIMARY_REPOSITORY, primaryUrl)
        ));
    }

    @Test
    void loadReviewMetadata_secondaryRepositoryListedFirst_usesPrimaryRepositoryMetadataAndOrdering() throws Exception {
        String reviewId = "test-review-" + UUID.randomUUID();
        createMultiRepositoryReview(reviewId);

        ReviewContext context = reviewContextManager
            .loadReviewMetadata(reviewId, List.of(SECONDARY_REPOSITORY, PRIMARY_REPOSITORY))
            .get(30, TimeUnit.SECONDS);

        assertNotNull(context);
        assertEquals(TITLE, context.getTitle());
        assertEquals(AUTHOR, context.getAuthor());
        assertEquals(SUMMARY, context.getSummary());
        assertEquals(REVIEW_BRANCH, context.getBranch());
        assertEquals(BASE_BRANCH, context.getBaseBranch());
        assertEquals(PRIMARY_REPOSITORY, context.getRepositories().getFirst().getName());
        assertEquals(List.of(PRIMARY_REPOSITORY, SECONDARY_REPOSITORY),
            context.getRepositories().stream().map(Repository::getName).collect(Collectors.toList()));
    }

    @Test
    void loadReviewMetadataOnly_secondaryRepositoryListedFirst_usesPrimaryRepositoryMetadataAndOrdering() throws Exception {
        String reviewId = "test-review-" + UUID.randomUUID();
        createMultiRepositoryReview(reviewId);

        ReviewContext context = reviewContextManager
            .loadReviewMetadataOnly(reviewId, List.of(SECONDARY_REPOSITORY, PRIMARY_REPOSITORY))
            .get(30, TimeUnit.SECONDS);

        assertNotNull(context);
        assertEquals(TITLE, context.getTitle());
        assertEquals(AUTHOR, context.getAuthor());
        assertEquals(SUMMARY, context.getSummary());
        assertEquals(PRIMARY_REPOSITORY, context.getRepositories().getFirst().getName());
        assertEquals(List.of(PRIMARY_REPOSITORY, SECONDARY_REPOSITORY),
            context.getRepositories().stream().map(Repository::getName).collect(Collectors.toList()));
    }

    private void createMultiRepositoryReview(String reviewId) throws Exception {
        primaryOrphanManager.createReview(
            reviewId,
            AUTHOR,
            TITLE,
            AUTHOR,
            SUMMARY,
            "open",
            List.of(),
            List.of("Reviewer One"),
            REVIEW_BRANCH,
            BASE_BRANCH
        ).get(30, TimeUnit.SECONDS);

        secondaryOrphanManager.createSecondaryReviewReference(
            reviewId,
            AUTHOR,
            List.of(),
            REVIEW_BRANCH,
            BASE_BRANCH
        ).get(30, TimeUnit.SECONDS);
    }

    private String toFileRemoteUrl(String repositoryName) {
        Path mockRepo = GitRepositoryInitializer.getBasePath().resolve(repositoryName);
        return "file:///" + mockRepo.toString().replace("\\", "/");
    }
}

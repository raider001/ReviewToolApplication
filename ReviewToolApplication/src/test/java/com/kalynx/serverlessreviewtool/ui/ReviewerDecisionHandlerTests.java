package com.kalynx.serverlessreviewtool.ui;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.managers.ReviewChangeSetManager;
import com.kalynx.serverlessreviewtool.managers.ReviewCommentManager;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ReviewerDecisionHandler;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewPanelModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ReviewerDecisionHandler.
 * Covers approve, request-changes, and re-review (W1 workflow fix) paths.
 */
class ReviewerDecisionHandlerTests {

    private static final String REVIEW_ID = "review-001";
    private static final String CURRENT_USER = "alice";
    private static final String OTHER_REVIEWER = "bob";
    private static final List<Repository> REPOS = List.of(new Repository("repo1", "", "url"));

    private StubContextManager stub;
    private AtomicReference<ReviewContext> contextHolder;

    private ReviewerDecisionHandler handler;

    @BeforeEach
    void setUp() {
        stub = new StubContextManager();
        SettingsManager settingsManager = mock(SettingsManager.class);
        contextHolder = new AtomicReference<>();

        when(settingsManager.getCurrentUserName()).thenReturn(CURRENT_USER);

        handler = new ReviewerDecisionHandler(
            stub,
            new ReviewPanelModel(),
            settingsManager,
            contextHolder::get,
            _ -> {}
        );
    }

    @Test
    void handleApprove_noReviewContext_doesNothing() {
        contextHolder.set(null);

        handler.handleApprove();

        assertNull(stub.lastUpdatedStatus, "updateReviewerStatus should not be called when context is null");
    }

    @Test
    void handleRequestChanges_noReviewContext_doesNothing() {
        contextHolder.set(null);

        handler.handleRequestChanges();

        assertNull(stub.lastUpdatedStatus);
    }

    @Test
    void handleRequestChanges_setsOverallStatusToChangesRequested() throws Exception {
        ReviewerInfo alice = new ReviewerInfo(CURRENT_USER);
        contextHolder.set(buildContext(ReviewStatus.IN_PROGRESS, List.of(alice)));

        ReviewerInfo aliceChanges = new ReviewerInfo(CURRENT_USER);
        aliceChanges.setStatus(ReviewerStatus.CHANGES_REQUESTED);
        stub.metadataOnlyResult = buildContext(ReviewStatus.IN_PROGRESS, List.of(aliceChanges));
        stub.metadataOnlySecondResult = buildContext(ReviewStatus.CHANGES_REQUESTED, List.of(aliceChanges));

        handler.handleRequestChanges();
        Thread.sleep(200);

        assertEquals(ReviewStatus.CHANGES_REQUESTED, stub.lastSavedContext.status);
    }

    @Test
    void handleRequestChanges_doesNotSyncWhenStatusIsTerminal() throws Exception {
        ReviewerInfo alice = new ReviewerInfo(CURRENT_USER);
        contextHolder.set(buildContext(ReviewStatus.COMPLETED, List.of(alice)));

        ReviewerInfo aliceChanges = new ReviewerInfo(CURRENT_USER);
        aliceChanges.setStatus(ReviewerStatus.CHANGES_REQUESTED);
        stub.metadataOnlyResult = buildContext(ReviewStatus.COMPLETED, List.of(aliceChanges));

        handler.handleRequestChanges();
        Thread.sleep(200);

        assertNull(stub.lastSavedContext, "saveReviewMetadata should not be called for terminal status");
    }

    @Test
    void handleReRequestReview_noReviewContext_doesNothing() {
        contextHolder.set(null);

        handler.handleReRequestReview(OTHER_REVIEWER);

        assertNull(stub.lastUpdatedStatus);
    }

    @Test
    void handleReRequestReview_resetsReviewerStatusToReviewing() throws Exception {
        ReviewerInfo bob = new ReviewerInfo(OTHER_REVIEWER);
        bob.setStatus(ReviewerStatus.APPROVED);
        contextHolder.set(buildContext(ReviewStatus.IN_PROGRESS, List.of(bob)));

        ReviewerInfo bobReviewing = new ReviewerInfo(OTHER_REVIEWER);
        bobReviewing.setStatus(ReviewerStatus.REVIEWING);
        stub.metadataOnlyResult = buildContext(ReviewStatus.IN_PROGRESS, List.of(bobReviewing));

        handler.handleReRequestReview(OTHER_REVIEWER);
        Thread.sleep(200);

        assertEquals(ReviewerStatus.REVIEWING, stub.lastUpdatedStatus);
        assertEquals(OTHER_REVIEWER, stub.lastUpdatedReviewer);
    }

    @Test
    void handleReRequestReview_lastBlockingReviewerReset_updatesOverallStatusToInProgress() throws Exception {
        ReviewerInfo bob = new ReviewerInfo(OTHER_REVIEWER);
        bob.setStatus(ReviewerStatus.CHANGES_REQUESTED);
        contextHolder.set(buildContext(ReviewStatus.CHANGES_REQUESTED, List.of(bob)));

        ReviewerInfo bobReviewing = new ReviewerInfo(OTHER_REVIEWER);
        bobReviewing.setStatus(ReviewerStatus.REVIEWING);
        stub.metadataOnlyResult = buildContext(ReviewStatus.CHANGES_REQUESTED, List.of(bobReviewing));
        stub.metadataOnlySecondResult = buildContext(ReviewStatus.IN_PROGRESS, List.of(bobReviewing));

        handler.handleReRequestReview(OTHER_REVIEWER);
        Thread.sleep(200);

        assertEquals(ReviewStatus.IN_PROGRESS, stub.lastSavedContext.status,
            "overall status should revert to IN_PROGRESS when no reviewer still requests changes");
    }

    @Test
    void handleReRequestReview_anotherReviewerStillBlocking_doesNotChangeOverallStatus() throws Exception {
        ReviewerInfo alice = new ReviewerInfo(CURRENT_USER);
        alice.setStatus(ReviewerStatus.CHANGES_REQUESTED);
        ReviewerInfo bob = new ReviewerInfo(OTHER_REVIEWER);
        bob.setStatus(ReviewerStatus.CHANGES_REQUESTED);
        contextHolder.set(buildContext(ReviewStatus.CHANGES_REQUESTED, List.of(alice, bob)));

        ReviewerInfo aliceStillBlocking = new ReviewerInfo(CURRENT_USER);
        aliceStillBlocking.setStatus(ReviewerStatus.CHANGES_REQUESTED);
        ReviewerInfo bobReviewing = new ReviewerInfo(OTHER_REVIEWER);
        bobReviewing.setStatus(ReviewerStatus.REVIEWING);
        stub.metadataOnlyResult = buildContext(ReviewStatus.CHANGES_REQUESTED, List.of(aliceStillBlocking, bobReviewing));

        handler.handleReRequestReview(OTHER_REVIEWER);
        Thread.sleep(200);

        assertNull(stub.lastSavedContext, "saveReviewMetadata should not be called when another reviewer still blocks");
    }

    @Test
    void handleReRequestReview_terminalReview_doesNotSyncStatus() throws Exception {
        ReviewerInfo bob = new ReviewerInfo(OTHER_REVIEWER);
        bob.setStatus(ReviewerStatus.CHANGES_REQUESTED);
        contextHolder.set(buildContext(ReviewStatus.COMPLETED, List.of(bob)));

        ReviewerInfo bobReviewing = new ReviewerInfo(OTHER_REVIEWER);
        bobReviewing.setStatus(ReviewerStatus.REVIEWING);
        stub.metadataOnlyResult = buildContext(ReviewStatus.COMPLETED, List.of(bobReviewing));

        handler.handleReRequestReview(OTHER_REVIEWER);
        Thread.sleep(200);

        assertNull(stub.lastSavedContext, "saveReviewMetadata should not be called for terminal reviews");
    }

    @Test
    void handleReRequestReview_nullContextAfterReload_doesNotCrash() throws Exception {
        ReviewerInfo bob = new ReviewerInfo(OTHER_REVIEWER);
        contextHolder.set(buildContext(ReviewStatus.IN_PROGRESS, List.of(bob)));

        stub.metadataOnlyResult = null;

        handler.handleReRequestReview(OTHER_REVIEWER);
        Thread.sleep(200);

        assertNull(stub.lastSavedContext);
    }

    private ReviewContext buildContext(ReviewStatus status, List<ReviewerInfo> reviewers) {
        return new ReviewContext(REVIEW_ID, "Title", "Summary", CURRENT_USER, status,
            new ArrayList<>(reviewers), new ArrayList<>(REPOS), List.of(), "feature", "main", false);
    }

    /**
     * Hand-written stub for ReviewContextManager that records calls and returns configurable results.
     * Used instead of a Mockito mock to avoid Java 25 + ByteBuddy class-modification restrictions.
     */
    private static class StubContextManager extends ReviewContextManager {

        ReviewerStatus lastUpdatedStatus;
        String lastUpdatedReviewer;
        ReviewContext lastSavedContext;
        ReviewContext metadataOnlyResult;
        ReviewContext metadataOnlySecondResult;
        private int metadataOnlyCallCount;

        StubContextManager() {
            super(mock(Git.class), mock(RepositoryManager.class),
                mock(ReviewCommentManager.class), mock(ReviewChangeSetManager.class));
        }

        @Override
        public CompletableFuture<Void> updateReviewerStatus(
                String reviewId, String reviewerName, ReviewerStatus reviewerStatus, List<String> repositoryNames) {
            lastUpdatedStatus = reviewerStatus;
            lastUpdatedReviewer = reviewerName;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<ReviewContext> loadReviewMetadataOnly(
                String reviewId, List<String> repositoryNames, String knownPrimaryRepoName) {
            metadataOnlyCallCount++;
            ReviewContext result = (metadataOnlyCallCount > 1 && metadataOnlySecondResult != null)
                ? metadataOnlySecondResult
                : metadataOnlyResult;
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<Void> saveReviewMetadata(ReviewContext reviewContext) {
            lastSavedContext = reviewContext;
            return CompletableFuture.completedFuture(null);
        }
    }
}



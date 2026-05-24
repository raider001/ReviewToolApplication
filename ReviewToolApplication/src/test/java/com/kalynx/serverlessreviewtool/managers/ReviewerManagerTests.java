package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.git.OrphanBranchReviewManager;
import com.kalynx.serverlessreviewtool.git.ReviewBranchManagerFactory;
import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import com.kalynx.serverlessreviewtool.models.review.ReviewerData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ReviewerManager.
 */
class ReviewerManagerTests {

    private static final String REVIEW_ID = "review-001";
    private static final String REVIEWER_NAME = "alice";
    private static final String REPO_NAME = "backend";

    private OrphanBranchReviewManager notesManager;
    private ReviewerManager reviewerManager;

    @BeforeEach
    void setUp() {
        notesManager = mock(OrphanBranchReviewManager.class);
        ReviewBranchManagerFactory factory = _ -> notesManager;
        reviewerManager = new ReviewerManager(factory);

        when(notesManager.writeReviewer(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));
    }

    @Test
    void addReviewer_nullRepositories_failsWithIllegalArgument() {
        CompletableFuture<Void> result = reviewerManager.addReviewer(REVIEW_ID, REVIEWER_NAME, null);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void addReviewer_emptyRepositories_failsWithIllegalArgument() {
        CompletableFuture<Void> result = reviewerManager.addReviewer(REVIEW_ID, REVIEWER_NAME, List.of());

        ExecutionException ex = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void addReviewer_validInputs_writesReviewingStatus() throws Exception {
        reviewerManager.addReviewer(REVIEW_ID, REVIEWER_NAME, List.of(REPO_NAME)).get(1, TimeUnit.SECONDS);

        ArgumentCaptor<ReviewerData> dataCaptor = ArgumentCaptor.forClass(ReviewerData.class);
        verify(notesManager).writeReviewer(eq(REVIEW_ID), eq(REVIEWER_NAME), dataCaptor.capture());
        assertTrue("REVIEWING".equalsIgnoreCase(dataCaptor.getValue().getStatus()));
    }

    @Test
    void updateReviewerStatus_nullStatus_failsWithIllegalArgument() {
        CompletableFuture<Void> result = reviewerManager.updateReviewerStatus(REVIEW_ID, REVIEWER_NAME, null, List.of(REPO_NAME));

        ExecutionException ex = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void updateReviewerStatus_approved_writesApprovedLowercase() throws Exception {
        reviewerManager.updateReviewerStatus(REVIEW_ID, REVIEWER_NAME, ReviewerStatus.APPROVED, List.of(REPO_NAME))
            .get(1, TimeUnit.SECONDS);

        ArgumentCaptor<ReviewerData> dataCaptor = ArgumentCaptor.forClass(ReviewerData.class);
        verify(notesManager).writeReviewer(eq(REVIEW_ID), eq(REVIEWER_NAME), dataCaptor.capture());
        assertTrue("approved".equalsIgnoreCase(dataCaptor.getValue().getStatus()));
    }

    @Test
    void updateReviewerStatus_changesRequested_writesChangesRequested() throws Exception {
        reviewerManager.updateReviewerStatus(REVIEW_ID, REVIEWER_NAME, ReviewerStatus.CHANGES_REQUESTED, List.of(REPO_NAME))
            .get(1, TimeUnit.SECONDS);

        ArgumentCaptor<ReviewerData> dataCaptor = ArgumentCaptor.forClass(ReviewerData.class);
        verify(notesManager).writeReviewer(eq(REVIEW_ID), eq(REVIEWER_NAME), dataCaptor.capture());
        assertTrue("changes_requested".equalsIgnoreCase(dataCaptor.getValue().getStatus()));
    }

    @Test
    void updateReviewerStatus_reviewing_writesReviewing() throws Exception {
        reviewerManager.updateReviewerStatus(REVIEW_ID, REVIEWER_NAME, ReviewerStatus.REVIEWING, List.of(REPO_NAME))
            .get(1, TimeUnit.SECONDS);

        ArgumentCaptor<ReviewerData> dataCaptor = ArgumentCaptor.forClass(ReviewerData.class);
        verify(notesManager).writeReviewer(eq(REVIEW_ID), eq(REVIEWER_NAME), dataCaptor.capture());
        assertTrue("reviewing".equalsIgnoreCase(dataCaptor.getValue().getStatus()));
    }

    @Test
    void removeReviewer_nullRepositories_failsWithIllegalArgument() {
        CompletableFuture<Void> result = reviewerManager.removeReviewer(REVIEW_ID, REVIEWER_NAME, null);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    void removeReviewer_validInputs_writesLeftStatus() throws Exception {
        reviewerManager.removeReviewer(REVIEW_ID, REVIEWER_NAME, List.of(REPO_NAME)).get(1, TimeUnit.SECONDS);

        ArgumentCaptor<ReviewerData> dataCaptor = ArgumentCaptor.forClass(ReviewerData.class);
        verify(notesManager).writeReviewer(eq(REVIEW_ID), eq(REVIEWER_NAME), dataCaptor.capture());
        assertTrue("LEFT".equalsIgnoreCase(dataCaptor.getValue().getStatus()));
    }

    @Test
    void addReviewer_usesFirstRepositoryAsPrimary() throws Exception {
        OrphanBranchReviewManager secondNotesManager = mock(OrphanBranchReviewManager.class);
        when(secondNotesManager.writeReviewer(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(null));

        ReviewBranchManagerFactory orderedFactory = repo -> repo.equals("primary") ? notesManager : secondNotesManager;
        ReviewerManager orderedManager = new ReviewerManager(orderedFactory);

        orderedManager.addReviewer(REVIEW_ID, REVIEWER_NAME, List.of("primary", "secondary")).get(1, TimeUnit.SECONDS);

        verify(notesManager).writeReviewer(anyString(), anyString(), any());
    }
}


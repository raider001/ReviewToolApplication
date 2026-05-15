package com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import com.kalynx.swingtheme.ComponentModel;

public class ReviewActionModel {

    public final ComponentModel<ReviewerStatus> currentReviewerStatus = new ComponentModel<>();
    public final ComponentModel<String> summaryComment = new ComponentModel<>();
    public final ComponentModel<Boolean> canSubmitReview = new ComponentModel<>();
    public final ComponentModel<Boolean> isSubmittingReview = new ComponentModel<>();

    public final ComponentModel<Integer> unresolvedCommentCount = new ComponentModel<>();
    public final ComponentModel<Boolean> hasUnresolvedComments = new ComponentModel<>();

    public ReviewActionModel() {
        initializeDefaults();
        setupValidationListeners();
    }

    private void initializeDefaults() {
        currentReviewerStatus.setValue(ReviewerStatus.REVIEWING);
        summaryComment.setValue("");
        canSubmitReview.setValue(false);
        isSubmittingReview.setValue(false);
        unresolvedCommentCount.setValue(0);
        hasUnresolvedComments.setValue(false);
    }

    private void setupValidationListeners() {
        currentReviewerStatus.addChangeListener(status -> updateCanSubmit());
        summaryComment.addChangeListener(comment -> updateCanSubmit());
        isSubmittingReview.addChangeListener(submitting -> updateCanSubmit());
    }

    public void clear() {
        initializeDefaults();
    }

    public void setReviewerStatus(ReviewerStatus status) {
        currentReviewerStatus.setValue(status != null ? status : ReviewerStatus.REVIEWING);
    }

    public void setSummaryComment(String comment) {
        summaryComment.setValue(comment != null ? comment : "");
    }

    public void setUnresolvedCommentCount(int count) {
        unresolvedCommentCount.setValue(count);
        hasUnresolvedComments.setValue(count > 0);
        updateCanSubmit();
    }

    public void startSubmitting() {
        isSubmittingReview.setValue(true);
    }

    public void finishSubmitting() {
        isSubmittingReview.setValue(false);
    }

    private void updateCanSubmit() {
        if (isSubmittingReview.getValue()) {
            canSubmitReview.setValue(false);
            return;
        }

        ReviewerStatus status = currentReviewerStatus.getValue();
        String comment = summaryComment.getValue();

        boolean hasStatus = status != null && status != ReviewerStatus.REVIEWING;
        boolean hasComment = comment != null && !comment.trim().isEmpty();
        boolean allowSubmit = hasStatus;

        if (status == ReviewerStatus.CHANGES_REQUESTED) {
            allowSubmit = hasComment;
        }

        canSubmitReview.setValue(allowSubmit);
    }

    public boolean canApprove() {
        return !hasUnresolvedComments.getValue();
    }

    public void resetToReviewing() {
        setReviewerStatus(ReviewerStatus.REVIEWING);
        setSummaryComment("");
    }
}


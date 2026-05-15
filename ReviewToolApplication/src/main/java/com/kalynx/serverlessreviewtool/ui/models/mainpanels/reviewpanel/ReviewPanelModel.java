package com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.swingtheme.ComponentModel;

import java.util.List;

public class ReviewPanelModel {

    public final ComponentModel<String> currentReviewId = new ComponentModel<>();
    public final ComponentModel<Boolean> hasActiveReview = new ComponentModel<>();
    public final ComponentModel<String> errorMessage = new ComponentModel<>();
    public final ComponentModel<List<Repository>> repositories = new ComponentModel<>();

    public final ReviewDetailModel reviewDetailModel = new ReviewDetailModel();
    public final CodeViewerModel codeViewerModel = new CodeViewerModel();
    public final CommentsPanelModel commentsPanelModel = new CommentsPanelModel();
    public final ReviewActionModel reviewActionModel = new ReviewActionModel();

    public ReviewPanelModel() {
        initializeDefaults();
    }

    private void initializeDefaults() {
        currentReviewId.setValue(null);
        hasActiveReview.setValue(false);
        errorMessage.setValue("");
        repositories.setValue(List.of());
    }

    public void setCurrentReview(String reviewId) {
        currentReviewId.setValue(reviewId);
        hasActiveReview.setValue(reviewId != null && !reviewId.isEmpty());
        if (reviewId == null || reviewId.isEmpty()) {
            clear();
        }
    }

    public void setRepositories(List<Repository> repos) {
        repositories.setValue(repos != null ? repos : List.of());
    }

    public void clear() {
        currentReviewId.setValue(null);
        hasActiveReview.setValue(false);
        errorMessage.setValue("");
        repositories.setValue(List.of());
        reviewDetailModel.clear();
        codeViewerModel.clear();
        commentsPanelModel.clear();
        reviewActionModel.clear();
    }

    public void setError(String error) {
        errorMessage.setValue(error != null ? error : "");
    }
}

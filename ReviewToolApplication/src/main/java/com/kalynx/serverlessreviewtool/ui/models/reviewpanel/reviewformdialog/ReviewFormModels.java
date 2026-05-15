package com.kalynx.serverlessreviewtool.ui.models.reviewpanel.reviewformdialog;

import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.swingtheme.ComponentModel;
import com.kalynx.serverlessreviewtool.utils.UuidV7Generator;

import java.util.ArrayList;
import java.util.List;

public class ReviewFormModels {

    public final ComponentModel<String> reviewId = new ComponentModel<>();
    public final ComponentModel<String> title = new ComponentModel<>();
    public final ComponentModel<String> author = new ComponentModel<>();
    public final ComponentModel<String> summary = new ComponentModel<>();

    public final ComponentModel<String> selectedBranchModel = new ComponentModel<>();
    public final ComponentModel<String> selectedBaseBranchModel = new ComponentModel<>();

    public final ComponentModel<List<String>> selectedRepositories = new ComponentModel<>();
    public final ComponentModel<List<ReviewerInfo>> selectedReviewers = new ComponentModel<>();

    public final ComponentModel<List<String>> availableRepositories = new ComponentModel<>();
    public final ComponentModel<List<String>> availableReviewers = new ComponentModel<>();
    public final ComponentModel<List<String>> availableBranches = new ComponentModel<>();

    public ReviewFormModels() {
    }

    private void initializeDefaults() {
        reviewId.setValue(UuidV7Generator.generate());
        title.setValue("Loading...");
        summary.setValue("");
        selectedBranchModel.setValue("");
        selectedBaseBranchModel.setValue("main");
        selectedRepositories.setValue(new ArrayList<>());
        selectedReviewers.setValue(new ArrayList<>());
    }

    public void clear() {
        initializeDefaults();
    }
}

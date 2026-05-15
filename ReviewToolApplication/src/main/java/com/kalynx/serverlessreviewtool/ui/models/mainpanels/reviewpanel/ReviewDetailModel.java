package com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.swingtheme.ComponentModel;

import java.util.ArrayList;
import java.util.List;

/**
 * ReviewDetailModel holds the view state for ReviewDetailPanel.
 * Displays review metadata: title, author, summary, status, and reviewers.
 */
public class ReviewDetailModel {

    public final ComponentModel<String> title = new ComponentModel<>();
    public final ComponentModel<String> author = new ComponentModel<>();
    public final ComponentModel<String> summary = new ComponentModel<>();
    public final ComponentModel<ReviewStatus> status = new ComponentModel<>();
    public final ComponentModel<List<ReviewerInfo>> reviewers = new ComponentModel<>();

    public ReviewDetailModel() {
        initializeDefaults();
    }

    private void initializeDefaults() {
        title.setValue("");
        author.setValue("");
        summary.setValue("");
        status.setValue(ReviewStatus.OPEN);
        reviewers.setValue(new ArrayList<>());

    }

    public void clear() {
        initializeDefaults();
    }

    public void setReviewData(String reviewId, String title, String author, String summary,
                             ReviewStatus status, List<ReviewerInfo> reviewers) {
        this.title.setValue(title != null ? title : "");
        this.author.setValue(author != null ? author : "");
        this.summary.setValue(summary != null ? summary : "");
        this.status.setValue(status != null ? status : ReviewStatus.OPEN);
        this.reviewers.setValue(reviewers != null ? new ArrayList<>(reviewers) : new ArrayList<>());
    }
}


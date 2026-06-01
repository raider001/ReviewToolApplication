package com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewselectionpanel;

import com.kalynx.serverlessreviewtool.configuration.AppSettings;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.swingtheme.ComponentModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Pattern;

public class ReviewSelectionPanelModel {

    /** Fires when a full repository refresh replaces the review list. */
    public final ComponentModel<List<ReviewItem>> allReviews = new ComponentModel<>();
    /** Fires when a targeted update adds or modifies a single review. */
    public final ComponentModel<ReviewItem> upsertedReview = new ComponentModel<>();
    /** Fires when a targeted delete removes a review entirely; value is the review ID. */
    public final ComponentModel<String> removedReviewId = new ComponentModel<>();

    public final ComponentModel<ReviewItem> selectedReview = new ComponentModel<>();
    public final ComponentModel<Boolean> isLoading = new ComponentModel<>();
    public final ComponentModel<String> errorMessage = new ComponentModel<>();

    private final LinkedHashMap<String, ReviewItem> reviewMap = new LinkedHashMap<>();

    private String currentUserEmail = "";
    private String currentUserName = "";

    public ReviewSelectionPanelModel() {
        initializeDefaults();
    }

    public void setCurrentUser(String email, String name) {
        this.currentUserEmail = email != null ? email : "";
        this.currentUserName = name != null ? name : "";
    }

    private void initializeDefaults() {
        allReviews.setValue(new ArrayList<>());
        selectedReview.setValue(null);
        isLoading.setValue(false);
        errorMessage.setValue("");
    }

    public void clear() {
        reviewMap.clear();
        initializeDefaults();
    }

    /** Replaces the full review list and notifies {@link #allReviews} listeners. */
    public void setAllReviews(List<ReviewItem> reviews) {
        reviewMap.clear();
        reviews.forEach(r -> reviewMap.put(r.getReviewId(), r));
        allReviews.setValue(new ArrayList<>(reviews));
    }

    /** Inserts or updates a single review and notifies {@link #upsertedReview} listeners. */
    public void upsertReview(ReviewItem item) {
        reviewMap.put(item.getReviewId(), item);
        upsertedReview.setValue(item);
    }

    /** Removes a review by ID and notifies {@link #removedReviewId} listeners. */
    public void removeReview(String reviewId) {
        reviewMap.remove(reviewId);
        removedReviewId.setValue(reviewId);
    }

    /**
     * Returns all reviews that match the given tab filter configuration.
     */
    public List<ReviewItem> filterForTab(AppSettings.ReviewTabConfig tab) {
        if (reviewMap.isEmpty()) return new ArrayList<>();
        return reviewMap.values().stream()
            .filter(r -> shouldShowInTab(r, tab))
            .toList();
    }

    /**
     * Returns true if the given review item passes all filters defined by the tab config.
     * Used for O(1) per-item filter checks during targeted upsert updates.
     */
    public boolean shouldShowInTab(ReviewItem r, AppSettings.ReviewTabConfig tab) {
        String titleContains          = tab.getTitleContains();
        String authorContains         = tab.getAuthorContains();
        List<Pattern> reviewerRegexes = compilePatterns(tab.getReviewerPatterns());
        List<Pattern> repoRegexes     = compilePatterns(tab.getRepositories());
        List<String> statusFilters    = tab.getStatusFilters();

        return (titleContains.isEmpty()  || r.getTitle().toLowerCase().contains(titleContains.toLowerCase()))
            && (authorContains.isEmpty() || r.getAuthor().toLowerCase().contains(authorContains.toLowerCase()))
            && matchesCompiledPatterns(r.getReviewers(), reviewerRegexes)
            && matchesCompiledPatterns(r.getRepositories(), repoRegexes)
            && matchesStatusFilters(r, statusFilters)
            && matchesInvolvementFilter(r, tab.getInvolvementFilter());
    }

    private List<Pattern> compilePatterns(List<String> wildcards) {
        if (wildcards == null || wildcards.isEmpty()) return List.of();
        return wildcards.stream().map(this::compileWildcard).toList();
    }

    private Pattern compileWildcard(String wildcard) {
        return Pattern.compile("(?i)" + Pattern.quote(wildcard).replace("\\*", ".*").replace("\\?", "."));
    }

    private boolean matchesCompiledPatterns(List<String> values, List<Pattern> patterns) {
        if (patterns.isEmpty()) return true;
        return values.stream().anyMatch(v -> patterns.stream().anyMatch(p -> p.matcher(v).matches()));
    }

    private boolean matchesStatusFilters(ReviewItem r, List<String> filters) {
        if (filters == null || filters.isEmpty()) return true;
        return filters.stream().anyMatch(f -> matchesSingleStatus(r, f));
    }

    private boolean matchesSingleStatus(ReviewItem r, String filter) {
        return switch (filter) {
            case "OPEN"               -> r.getStatus() == ReviewStatus.OPEN;
            case "IN_PROGRESS"        -> r.getStatus() == ReviewStatus.IN_PROGRESS;
            case "CHANGES_REQUESTED"  -> r.getStatus() == ReviewStatus.CHANGES_REQUESTED;
            case "COMPLETED"          -> r.getStatus() == ReviewStatus.COMPLETED;
            case "CANCELLED"          -> r.getStatus() == ReviewStatus.CANCELLED;
            case "ACTIVE"             -> r.getStatus() != ReviewStatus.COMPLETED && r.getStatus() != ReviewStatus.CANCELLED;
            default                   -> true;
        };
    }

    private boolean matchesInvolvementFilter(ReviewItem r, String filter) {
        return switch (filter) {
            case "MINE"   ->  isMyReview(r);
            case "OTHERS" -> !isMyReview(r);
            default       -> true;
        };
    }

    private boolean isMyReview(ReviewItem review) {
        if (currentUserEmail.isEmpty() && currentUserName.isEmpty()) {
            return false;
        }

        boolean isAuthor = (!currentUserName.isEmpty() && currentUserName.equals(review.getAuthor())) ||
                          (!currentUserEmail.isEmpty() && currentUserEmail.equals(review.getAuthor()));

        boolean isReviewer = review.getReviewers().stream()
            .anyMatch(reviewer ->
                (!currentUserName.isEmpty() && reviewer.equals(currentUserName)) ||
                (!currentUserEmail.isEmpty() && reviewer.equals(currentUserEmail))
            );

        return isAuthor || isReviewer;
    }

    public void setError(String error) {
        errorMessage.setValue(error);
        isLoading.setValue(false);
    }
}
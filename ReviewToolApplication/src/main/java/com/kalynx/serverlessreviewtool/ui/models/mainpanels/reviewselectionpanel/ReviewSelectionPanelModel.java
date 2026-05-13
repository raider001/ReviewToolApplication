package com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewselectionpanel;

import com.kalynx.serverlessreviewtool.configuration.AppSettings;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.swingextensions.ComponentModel;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class ReviewSelectionPanelModel {

    public final ComponentModel<List<ReviewItem>> allReviews = new ComponentModel<>();
    public final ComponentModel<List<ReviewItem>> allReviewsFiltered = new ComponentModel<>();
    public final ComponentModel<List<ReviewItem>> myReviews = new ComponentModel<>();
    public final ComponentModel<List<ReviewItem>> openReviews = new ComponentModel<>();
    public final ComponentModel<List<ReviewItem>> completedReviews = new ComponentModel<>();

    public final ComponentModel<String> titleFilter = new ComponentModel<>();
    public final ComponentModel<String> authorFilter = new ComponentModel<>();
    public final ComponentModel<List<String>> repositoryFilter = new ComponentModel<>();

    public final ComponentModel<ReviewItem> selectedReview = new ComponentModel<>();

    public final ComponentModel<Integer> selectedTabIndex = new ComponentModel<>();

    public final ComponentModel<Boolean> isLoading = new ComponentModel<>();
    public final ComponentModel<String> errorMessage = new ComponentModel<>();

    private String currentUserEmail = "";
    private String currentUserName = "";

    public ReviewSelectionPanelModel() {
        initializeDefaults();
    }

    public void setCurrentUser(String email, String name) {
        this.currentUserEmail = email != null ? email : "";
        this.currentUserName = name != null ? name : "";
        applyFiltersToAllLists(allReviews.getValue());
    }

    private void initializeDefaults() {
        allReviews.setValue(new ArrayList<>());
        allReviewsFiltered.setValue(new ArrayList<>());
        myReviews.setValue(new ArrayList<>());
        openReviews.setValue(new ArrayList<>());
        completedReviews.setValue(new ArrayList<>());
        titleFilter.setValue("");
        authorFilter.setValue("");
        repositoryFilter.setValue(new ArrayList<>());
        selectedReview.setValue(null);
        selectedTabIndex.setValue(0);
        isLoading.setValue(false);
        errorMessage.setValue("");
    }

    public void clear() {
        initializeDefaults();
    }

    public void setAllReviews(List<ReviewItem> reviews) {
        allReviews.setValue(new ArrayList<>(reviews));
        applyFiltersToAllLists(reviews);
    }

    public void setFilters(String title, String author, List<String> repositories) {
        titleFilter.setValue(title != null ? title : "");
        authorFilter.setValue(author != null ? author : "");
        repositoryFilter.setValue(repositories != null ? new ArrayList<>(repositories) : new ArrayList<>());
        applyFiltersToAllLists(allReviews.getValue());
    }

    private void applyFiltersToAllLists(List<ReviewItem> reviews) {
        String title = titleFilter.getValue();
        String author = authorFilter.getValue();
        List<String> repos = repositoryFilter.getValue();

        List<ReviewItem> filtered = reviews.stream()
            .filter(r -> matchesFilters(r, title, author, repos))
            .toList();

        allReviewsFiltered.setValue(filtered);
        myReviews.setValue(filterMyReviews(filtered));
        openReviews.setValue(filterOpenReviews(filtered));
        completedReviews.setValue(filterCompletedReviews(filtered));
    }

    private boolean matchesFilters(ReviewItem review, String title, String author, List<String> repos) {
        boolean titleMatch = title.isEmpty() ||
            review.getTitle().toLowerCase().contains(title.toLowerCase());

        boolean authorMatch = author.isEmpty() ||
            review.getAuthor().toLowerCase().contains(author.toLowerCase());

        boolean repoMatch = repos == null || repos.isEmpty() ||
            review.getRepositories().stream().anyMatch(repos::contains);

        return titleMatch && authorMatch && repoMatch;
    }

    private List<ReviewItem> filterMyReviews(List<ReviewItem> reviews) {
        return reviews.stream()
            .filter(r -> isMyReview(r) && !isCompleted(r))
            .toList();
    }

    private List<ReviewItem> filterOpenReviews(List<ReviewItem> reviews) {
        return reviews.stream()
            .filter(r -> !isMyReview(r) && !isCompleted(r))
            .toList();
    }

    private List<ReviewItem> filterCompletedReviews(List<ReviewItem> reviews) {
        return reviews.stream()
            .filter(this::isCompleted)
            .toList();
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

    private boolean isCompleted(ReviewItem review) {
        return review.getStatus() == ReviewStatus.COMPLETED;
    }

    /**
     * Returns all reviews from {@code allReviews} that match the given tab filter configuration.
     *
     * @param tab the tab's filter configuration
     * @return filtered list of review items
     */
    public List<ReviewItem> filterForTab(AppSettings.ReviewTabConfig tab) {
        List<ReviewItem> all = allReviews.getValue();
        if (all == null || all.isEmpty()) return new ArrayList<>();

        String titleContains          = tab.getTitleContains();
        String authorContains         = tab.getAuthorContains();
        List<Pattern> reviewerRegexes = compilePatterns(tab.getReviewerPatterns());
        List<Pattern> repoRegexes     = compilePatterns(tab.getRepositories());
        List<String> statusFilters    = tab.getStatusFilters();

        return all.stream()
            .filter(r -> titleContains.isEmpty()  || r.getTitle().toLowerCase().contains(titleContains.toLowerCase()))
            .filter(r -> authorContains.isEmpty() || r.getAuthor().toLowerCase().contains(authorContains.toLowerCase()))
            .filter(r -> matchesCompiledPatterns(r.getReviewers(), reviewerRegexes))
            .filter(r -> matchesCompiledPatterns(r.getRepositories(), repoRegexes))
            .filter(r -> matchesStatusFilters(r, statusFilters))
            .filter(r -> matchesInvolvementFilter(r, tab.getInvolvementFilter()))
            .toList();
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

    public void setError(String error) {
        errorMessage.setValue(error);
        isLoading.setValue(false);
    }

}

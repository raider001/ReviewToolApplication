package com.kalynx.serverlessreviewtool.configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AppSettings - POJO representing all application settings
 * Serialized to/from JSON for persistence
 */
public class AppSettings {

    private final WindowSettings window;
    private String notificationServiceUrl;
    private String indexerUrl;
    private String indexerBearerToken;
    private List<RepositoryConfig> repositories;
    private String theme;
    private String loggedInUserName;
    private String loggedInUserEmail;
    private List<ReviewTabConfig> reviewTabs;

    public AppSettings() {
        this.window = new WindowSettings();
        this.notificationServiceUrl = "";
        this.indexerUrl = "";
        this.indexerBearerToken = "";
        this.repositories = new ArrayList<>();
        this.theme = "Dark";
        this.loggedInUserName = "";
        this.loggedInUserEmail = "";
        this.reviewTabs = createDefaultTabs();
    }

    /**
     * Returns the default tab configurations used when no tabs are stored.
     *
     * @return ordered list of default review tabs
     */
    public static List<ReviewTabConfig> createDefaultTabs() {
        List<ReviewTabConfig> tabs = new ArrayList<>();
        List<String> activeStatuses = List.of("OPEN", "IN_PROGRESS", "CHANGES_REQUESTED");
        tabs.add(new ReviewTabConfig(UUID.randomUUID().toString(), "My Reviews",   "", "", new ArrayList<>(), new ArrayList<>(), new ArrayList<>(activeStatuses), "MINE"));
        tabs.add(new ReviewTabConfig(UUID.randomUUID().toString(), "Open Reviews", "", "", new ArrayList<>(), new ArrayList<>(), new ArrayList<>(activeStatuses), "OTHERS"));
        tabs.add(new ReviewTabConfig(UUID.randomUUID().toString(), "Completed",    "", "", new ArrayList<>(), new ArrayList<>(), List.of("COMPLETED"),             "ANY"));
        tabs.add(new ReviewTabConfig(UUID.randomUUID().toString(), "All Reviews",  "", "", new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),                "ANY"));
        return tabs;
    }

    public WindowSettings getWindow() { return window; }

    public String getNotificationServiceUrl() { return notificationServiceUrl; }
    public void setNotificationServiceUrl(String url) { this.notificationServiceUrl = url; }

    public String getIndexerUrl() { return indexerUrl != null ? indexerUrl : ""; }
    public void setIndexerUrl(String url) { this.indexerUrl = url; }

    public String getIndexerBearerToken() { return indexerBearerToken != null ? indexerBearerToken : ""; }
    public void setIndexerBearerToken(String token) { this.indexerBearerToken = token; }

    public List<RepositoryConfig> getRepositories() { return repositories; }
    public void setRepositories(List<RepositoryConfig> repositories) { this.repositories = repositories; }

    public String getTheme() { return theme; }
    public void setTheme(String theme) { this.theme = theme; }


    public String getLoggedInUserName() { return loggedInUserName; }
    public void setLoggedInUserName(String loggedInUserName) { this.loggedInUserName = loggedInUserName; }

    public String getLoggedInUserEmail() { return loggedInUserEmail; }
    public void setLoggedInUserEmail(String loggedInUserEmail) { this.loggedInUserEmail = loggedInUserEmail; }

    public List<ReviewTabConfig> getReviewTabs() {
        if (reviewTabs == null || reviewTabs.isEmpty()) {
            reviewTabs = createDefaultTabs();
        }
        return reviewTabs;
    }
    public void setReviewTabs(List<ReviewTabConfig> reviewTabs) { this.reviewTabs = reviewTabs; }

    /**
     * Window settings - size and position
     */
    public static class WindowSettings {
        private int defaultWidth;
        private int defaultHeight;

        public WindowSettings() {
            this.defaultWidth = 1000;
            this.defaultHeight = 700;
        }

        public int getDefaultWidth() { return defaultWidth; }
        public void setDefaultWidth(int width) { this.defaultWidth = width; }

        public int getDefaultHeight() { return defaultHeight; }
        public void setDefaultHeight(int height) { this.defaultHeight = height; }
    }

    /**
     * Repository configuration
     */
    public static class RepositoryConfig {
        private String name;
        private String url;

        public RepositoryConfig(String name, String url) {
            this.name = name;
            this.url = url;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }


        @Override
        public String toString() { return name + " (" + url + ")"; }
    }

    /**
     * Review tab configuration — defines what reviews a tab shows.
     * <p>
     * {@code statusFilters} values (per element): {@code "ACTIVE"}, {@code "COMPLETED"}, {@code "CANCELLED"}.
     * An empty list means "any status".<br>
     * {@code repositories} supports wildcard patterns, e.g. {@code "*bob*"}.<br>
     * {@code involvementFilter} values: {@code "ANY"}, {@code "MINE"}, {@code "OTHERS"}
     * <p>
     * Fields are non-final to ensure Gson can deserialize them correctly without relying on
     * constructor injection.
     */
    public static class ReviewTabConfig {
        private String id;
        private String name;
        private final String titleContains;
        private final String authorContains;
        private final List<String> reviewerPatterns;
        private List<String> repositories;
        private final List<String> statusFilters;
        private final String involvementFilter;

        public ReviewTabConfig(String id, String name, String titleContains, String authorContains,
                               List<String> reviewerPatterns, List<String> repositories,
                               List<String> statusFilters, String involvementFilter) {
            this.id = id;
            this.name = name;
            this.titleContains = titleContains != null ? titleContains : "";
            this.authorContains = authorContains != null ? authorContains : "";
            this.reviewerPatterns = reviewerPatterns != null ? new ArrayList<>(reviewerPatterns) : new ArrayList<>();
            this.repositories = repositories != null ? new ArrayList<>(repositories) : new ArrayList<>();
            this.statusFilters = statusFilters != null ? new ArrayList<>(statusFilters) : new ArrayList<>();
            this.involvementFilter = involvementFilter != null ? involvementFilter : "ANY";
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public String getTitleContains() { return titleContains; }

        public String getAuthorContains() { return authorContains; }

        public List<String> getReviewerPatterns() { return reviewerPatterns; }

        public List<String> getRepositories() { return repositories != null ? repositories : new ArrayList<>(); }
        public void setRepositories(List<String> repositories) { this.repositories = repositories; }

        public List<String> getStatusFilters() { return statusFilters; }

        public String getInvolvementFilter() { return involvementFilter; }
    }
}

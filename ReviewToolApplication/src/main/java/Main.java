import com.kalynx.lwdi.DependencyInjectionException;
import com.kalynx.lwdi.DependencyInjector;
import com.kalynx.serverlessreviewtool.configuration.AppSettings;
import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.git.*;
import com.kalynx.serverlessreviewtool.indexer.CommentIndexerClient;
import com.kalynx.serverlessreviewtool.indexer.IndexerEventSource;
import com.kalynx.serverlessreviewtool.indexer.CommentSseEvent;
import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.managers.ReviewChangeSetManager;
import com.kalynx.serverlessreviewtool.managers.ReviewCommentManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.managers.ReviewItemManager;
import com.kalynx.serverlessreviewtool.managers.UserManager;
import com.kalynx.serverlessreviewtool.mockdata.GitRepositoryInitializer;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.User;
import com.kalynx.serverlessreviewtool.plugin.UserPlugin;
import com.kalynx.serverlessreviewtool.plugin.NotificationPlugin;
import com.kalynx.serverlessreviewtool.plugin.RepositoryDescriptor;
import com.kalynx.serverlessreviewtool.plugin.RepositoryListUpdate;
import com.kalynx.serverlessreviewtool.ui.MainFrame;
import com.kalynx.serverlessreviewtool.ui.review.InlineCommentDialog;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewPanelModel;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewselectionpanel.ReviewSelectionPanelModel;
import com.kalynx.serverlessreviewtool.ui.models.reviewpanel.reviewformdialog.ReviewFormModels;
import com.kalynx.serverlessreviewtool.utils.ConsoleLogBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    private static final DependencyInjector DI = new DependencyInjector();
    private static final String userHome = System.getProperty("user.home");
    private static final Path gitLocalPath = Paths.get(userHome, ".serverless-review-tool", "repositories");

    private static final RepositoryManager REPOSITORY_MANAGER;
    private static final SettingsManager SETTINGS_MANAGER;
    private static final IndexerEventSource INDEXER_EVENT_SOURCE;
    private static final ReviewItemManager REVIEW_ITEM_MANAGER;
    private static final ReviewCommentManager REVIEW_COMMENT_MANAGER;
    private static final ReviewContextManager REVIEW_CONTEXT_MANAGER;
    private static final UserManager USER_MANAGER;
    private static final PluginManager PLUGIN_MANAGER;
    private static final ReviewFormModels REVIEW_FORM_MODELS;
    private static final ReviewSelectionPanelModel REVIEW_SELECTION_PANEL_MODEL;
    private static final ReviewPanelModel REVIEW_PANEL_MODEL;

    static {
        try {
            GitImpl gitImpl = new GitImpl(gitLocalPath);
            DI.add(Git.class, gitImpl);
            Map<String, OrphanBranchStore> storeRegistry = new ConcurrentHashMap<>();
            OrphanBranchReviewManagerFactory managerFactory = (repoName, remoteUrl) -> {
                OrphanBranchStore store = storeRegistry.computeIfAbsent(remoteUrl,
                        url -> new OrphanBranchStore(url, gitLocalPath));
                return new OrphanBranchReviewManager(store, repoName);
            };
            DI.add(OrphanBranchReviewManagerFactory.class, managerFactory);
            DI.inject(RepositoryLoader.class);
            REPOSITORY_MANAGER = DI.inject(RepositoryManager.class);
            ReviewCloneManager reviewCloneManager = new ReviewCloneManagerImpl(REPOSITORY_MANAGER);
            DI.add(ReviewCloneManager.class, reviewCloneManager);
            // Single-arg factory registered after RepositoryManager is available so URL lookup works.
            final RepositoryManager repoMgr = REPOSITORY_MANAGER;
            ReviewBranchManagerFactory branchManagerFactory = repoName -> {
                com.kalynx.serverlessreviewtool.models.Repository repo = repoMgr.getRepositoryByName(repoName);
                String url = (repo != null && repo.getUrl() != null) ? repo.getUrl() : "";
                return managerFactory.create(repoName, url);
            };
            DI.add(ReviewBranchManagerFactory.class, branchManagerFactory);
            SETTINGS_MANAGER = DI.inject(SettingsManager.class);
            INDEXER_EVENT_SOURCE = new IndexerEventSource(SETTINGS_MANAGER);
            DI.inject(CommentIndexerClient.class);
            DI.inject(ReviewItemLoader.class);
            REVIEW_ITEM_MANAGER = DI.inject(ReviewItemManager.class);
            REVIEW_COMMENT_MANAGER = DI.inject(ReviewCommentManager.class);
            DI.inject(ReviewChangeSetManager.class);
            REVIEW_CONTEXT_MANAGER = DI.inject(ReviewContextManager.class);
            USER_MANAGER = DI.inject(UserManager.class);
            PLUGIN_MANAGER = DI.inject(PluginManager.class);
            REVIEW_FORM_MODELS = DI.inject(ReviewFormModels.class);
            REVIEW_SELECTION_PANEL_MODEL = DI.inject(ReviewSelectionPanelModel.class);
            REVIEW_PANEL_MODEL = DI.inject(ReviewPanelModel.class);
        } catch (DependencyInjectionException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] ignored) {
        ConsoleLogBridge.install();
        logger.info("ServerlessReviewTool - Java Application");
        logger.info("Launching application...");



        USER_MANAGER.addListener(users -> REVIEW_FORM_MODELS.availableReviewers.setValue(users.stream().map(User::getName).toList()));
        ensureConfiguredMockRepositoriesExist();

        PLUGIN_MANAGER.addListenerToUserPlugins(UserPlugin.NotificationType.USER_ADDED, usernames ->
                USER_MANAGER.addUsers(Arrays.stream(usernames).map(u -> new User(u, "", u)).toList())
        );

        PLUGIN_MANAGER.addListenerToUserPlugins(UserPlugin.NotificationType.USER_REMOVED,
                usernames -> {
                    USER_MANAGER.removeUsers(usernames);
                    String loggedInUserName = SETTINGS_MANAGER.getLoggedInUserName();
                    if (Arrays.asList(usernames).contains(loggedInUserName)) {
                        SETTINGS_MANAGER.logoutUser();
                    }
                }
        );

        PLUGIN_MANAGER.addListenerToNotificationPlugins(
            NotificationPlugin.NotificationType.REVIEW_UPDATED,
                REVIEW_ITEM_MANAGER::applyNotificationUpdates
        );

        PLUGIN_MANAGER.addListenerToNotificationRepositoryUpdates(Main::onNotificationRepositoriesUpdated);

        INDEXER_EVENT_SOURCE.start(REVIEW_ITEM_MANAGER::applyNotificationUpdates);
        INDEXER_EVENT_SOURCE.addCommentEventListener(Main::onCommentSseEvent);

        PLUGIN_MANAGER.initialize();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            INDEXER_EVENT_SOURCE.stop();
            PLUGIN_MANAGER.shutdown();
        }));

        if (SETTINGS_MANAGER.isLoggedIn() && !isKnownUser(SETTINGS_MANAGER.getLoggedInUserName())) {
            SETTINGS_MANAGER.logoutUser();
        }


        setupReviewFormModelUpdaters();
        setupReviewSelectionPanelModelUpdaters();
        setupReviewPanelModelUpdaters();


        SwingUtilities.invokeLater(() -> {
            try {
                MainFrame frame = DI.inject(MainFrame.class);
                frame.setVisible(true);

            } catch (Exception e) {
                logger.error("Failed to create MainFrame: {}", e.getMessage(), e);
                System.exit(1);
            }
        });

    }

    private static void setupReviewPanelModelUpdaters() {
        REVIEW_CONTEXT_MANAGER.addListener(reviewContext -> {
            if (reviewContext == null) return;
            REVIEW_PANEL_MODEL.setCurrentReview(reviewContext.getReviewId());
            REVIEW_PANEL_MODEL.reviewDetailModel.title.setValue(reviewContext.title);
            REVIEW_PANEL_MODEL.reviewDetailModel.summary.setValue(reviewContext.summary);
            REVIEW_PANEL_MODEL.reviewDetailModel.author.setValue(reviewContext.author);
            REVIEW_PANEL_MODEL.reviewDetailModel.status.setValue(reviewContext.status);
            REVIEW_PANEL_MODEL.reviewDetailModel.reviewers.setValue(reviewContext.reviewers);
            REVIEW_PANEL_MODEL.commentsPanelModel.setComments(reviewContext.comments);
        });
    }

    private static void onCommentSseEvent(CommentSseEvent event) {
        String activeReviewId = REVIEW_PANEL_MODEL.currentReviewId.getValue();
        if (event.reviewId() == null || !event.reviewId().equals(activeReviewId)) return;

        REVIEW_COMMENT_MANAGER.reloadComment(event.reviewId(), event.repositoryUrl(), event.commentId())
            .thenAccept(comment -> {
                if (comment == null) return;
                SwingUtilities.invokeLater(() -> {
                    ReviewContext ctx = REVIEW_CONTEXT_MANAGER.getReviewContext();
                    List<ReviewComment> existing = REVIEW_PANEL_MODEL.commentsPanelModel.allComments.getValue();
                    boolean inModel = existing.stream().anyMatch(c -> c.getId().equals(comment.getId()));
                    boolean inCtx = ctx != null && ctx.comments.stream().anyMatch(c -> c.getId().equals(comment.getId()));
                    if (inModel) {
                        REVIEW_PANEL_MODEL.commentsPanelModel.updateComment(comment);
                    } else {
                        REVIEW_PANEL_MODEL.commentsPanelModel.addComment(comment);
                    }
                    if (ctx != null) {
                        if (inCtx) ctx.updateComment(comment);
                        else ctx.addComment(comment);
                    }
                    InlineCommentDialog.notifyAllCommentChanged();
                });
            });
    }

    private static void setupReviewFormModelUpdaters() {
        REPOSITORY_MANAGER.addListener(repositories -> REVIEW_FORM_MODELS.availableRepositories.setValue(repositories.stream().map(Repository::getName).toList()));

        @SuppressWarnings("unchecked")
        List<String>[] indexerBranchHolder = new List[]{List.of()};
        REPOSITORY_MANAGER.addListener(repositories -> {
            List<String> repoBranches = repositories.stream().flatMap(r -> r.getBranches().stream()).toList();
            REVIEW_FORM_MODELS.availableBranches.setValue(mergeBranches(repoBranches, indexerBranchHolder[0]));
        });
        INDEXER_EVENT_SOURCE.addBranchListener(branches -> {
            indexerBranchHolder[0] = branches;
            List<String> repoBranches = REPOSITORY_MANAGER.getRepositories().stream()
                .flatMap(r -> r.getBranches().stream()).toList();
            REVIEW_FORM_MODELS.availableBranches.setValue(mergeBranches(repoBranches, branches));
        });

        SETTINGS_MANAGER.addUserNameListener(REVIEW_FORM_MODELS.author::setValue);
        REVIEW_FORM_MODELS.author.setValue(SETTINGS_MANAGER.getCurrentUserName());
    }

    private static List<String> mergeBranches(List<String> repoBranches, List<String> indexerBranches) {
        return java.util.stream.Stream.concat(repoBranches.stream(), indexerBranches.stream())
            .distinct()
            .toList();
    }

    private static void onNotificationRepositoriesUpdated(RepositoryListUpdate[] updates) {
        List<RepositoryDescriptor> repositories = updates.length == 0
            ? List.of()
            : updates[updates.length - 1].repositories();
        REPOSITORY_MANAGER.setRepositoriesFromNotification(repositories);
        REVIEW_ITEM_MANAGER.setNotificationPluginRepositories(repositories);
    }

    private static boolean isKnownUser(String username) {
        return USER_MANAGER.getUsers().stream().anyMatch(u -> u.getUsername().equals(username));
    }

    private static void ensureConfiguredMockRepositoriesExist() {
        List<String> configuredMockUrls = SETTINGS_MANAGER.getSettings().getRepositories().stream()
            .map(AppSettings.RepositoryConfig::getUrl)
            .filter(Main::isConfiguredMockRepositoryUrl)
            .toList();

        if (configuredMockUrls.isEmpty()) {
            return;
        }

        try {
            GitRepositoryInitializer.ensureMockRepositoriesExist();
        } catch (Exception e) {
            logger.error("Failed to initialize configured mock repositories", e);
        }
    }

    private static boolean isConfiguredMockRepositoryUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }

        try {
            Path configuredPath = Path.of(url).toAbsolutePath().normalize();
            Path mockBasePath = GitRepositoryInitializer.getBasePath().toAbsolutePath().normalize();
            return configuredPath.startsWith(mockBasePath);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void setupReviewSelectionPanelModelUpdaters() {
        REVIEW_ITEM_MANAGER.addListener(REVIEW_SELECTION_PANEL_MODEL::setAllReviews);
        REVIEW_SELECTION_PANEL_MODEL.setCurrentUser(SETTINGS_MANAGER.getCurrentUserEmail(), SETTINGS_MANAGER.getCurrentUserName());
        SETTINGS_MANAGER.addUserNameListener(userName ->
                REVIEW_SELECTION_PANEL_MODEL.setCurrentUser(SETTINGS_MANAGER.getCurrentUserEmail(), userName)
        );
    }
}


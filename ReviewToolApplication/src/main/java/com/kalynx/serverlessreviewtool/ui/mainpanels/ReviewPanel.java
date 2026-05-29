package com.kalynx.serverlessreviewtool.ui.mainpanels;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.git.ReviewBranchManagerFactory;
import com.kalynx.serverlessreviewtool.git.ReviewCloneManager;
import com.kalynx.serverlessreviewtool.managers.FileDiffManager;
import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.serverlessreviewtool.plugin.NotificationPlugin;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedSplitPane;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.CodePanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.RejectApprovePanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ReviewAutoRefreshController;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ReviewAuthorActionHandler;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ReviewDetailPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ReviewLoadController;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ReviewMembershipHandler;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ReviewerDecisionHandler;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.UpdateToastWindow;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ViewportRestoreState;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewPanelModel;
import com.kalynx.serverlessreviewtool.ui.models.reviewpanel.reviewformdialog.ReviewFormModels;
import com.kalynx.serverlessreviewtool.ui.review.EditReviewDialog;
import com.kalynx.serverlessreviewtool.ui.review.InlineCommentDialog;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.JSplitPane;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;


/**
 * ReviewPanel - Main panel for reviewing code changes across multiple repositories.
 * Composes focused child components and delegates each responsibility to a dedicated handler.
 */
public class ReviewPanel extends ThemedPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewPanel.class);

    private final SettingsManager settingsManager;
    private final ReviewContextManager reviewContextManager;
    private final RepositoryManager repositoryManager;
    private final ReviewFormModels reviewFormModels;
    private final ReviewPanelModel model;
    private final Git git;
    private final ReviewBranchManagerFactory branchManagerFactory;
    private final ReviewCloneManager cloneManager;

    private final ReviewDetailPanel reviewDetailPanel;
    private final CodePanel codePanel;
    private final RejectApprovePanel rejectApprovePanel = new RejectApprovePanel();

    private final ReviewLoadController loadController;
    private final ReviewAutoRefreshController autoRefreshController;
    private final ReviewerDecisionHandler reviewerDecisionHandler;
    private final ReviewMembershipHandler membershipHandler;
    private final ReviewAuthorActionHandler authorActionHandler;
    private final UpdateToastWindow toastWindow;

    private volatile ReviewContext currentReviewContext;
    private final List<Consumer<Boolean>> additionalReviewerStatusListeners = new ArrayList<>();
    private boolean isCurrentUserReviewer = false;
    private boolean isReviewTerminal = false;

    /**
     * @param settingsManager      application settings
     * @param reviewContextManager review context and metadata operations
     * @param repositoryManager    repository configuration
     * @param reviewFormModels     models used by the edit-review dialog
     * @param reviewPanelModel     shared panel model
     * @param git                  git operations (orphan branch / edit review dialog)
     * @param pluginManager        plugin registry
     * @param branchManagerFactory factory for orphan branch managers
     * @param cloneManager         blobless clone manager for diff operations
     */
    public ReviewPanel(SettingsManager settingsManager,
                       ReviewContextManager reviewContextManager,
                       RepositoryManager repositoryManager,
                       ReviewFormModels reviewFormModels,
                       ReviewPanelModel reviewPanelModel,
                       Git git,
                       PluginManager pluginManager,
                       ReviewBranchManagerFactory branchManagerFactory,
                       ReviewCloneManager cloneManager) {
        this.settingsManager = settingsManager;
        this.reviewContextManager = reviewContextManager;
        this.repositoryManager = repositoryManager;
        this.reviewFormModels = reviewFormModels;
        this.model = reviewPanelModel;
        this.git = git;
        this.branchManagerFactory = branchManagerFactory;
        this.cloneManager = cloneManager;

        FileDiffManager fileDiffManager = new FileDiffManager(cloneManager, reviewPanelModel.codeViewerModel);
        this.reviewDetailPanel = new ReviewDetailPanel(settingsManager, reviewPanelModel.reviewDetailModel);
        this.codePanel = new CodePanel(settingsManager, reviewContextManager, reviewPanelModel.codeViewerModel,
            fileDiffManager, cloneManager, pluginManager);
        this.toastWindow = new UpdateToastWindow(this);

        this.loadController = new ReviewLoadController(reviewContextManager, reviewPanelModel,
            fileDiffManager, cloneManager, codePanel);
        this.reviewerDecisionHandler = new ReviewerDecisionHandler(reviewContextManager, reviewPanelModel,
            settingsManager, () -> currentReviewContext, ctx -> currentReviewContext = ctx);
        this.membershipHandler = new ReviewMembershipHandler(reviewContextManager, reviewPanelModel,
            settingsManager, () -> currentReviewContext, ctx -> currentReviewContext = ctx);
        this.authorActionHandler = new ReviewAuthorActionHandler(reviewContextManager, reviewPanelModel,
            settingsManager, () -> currentReviewContext, ctx -> {
                currentReviewContext = ctx;
                if (ctx != null && ctx.hasClosedHistory()) {
                    triggerSnapshotReload(ctx);
                }
            });
        this.autoRefreshController = new ReviewAutoRefreshController(
            () -> currentReviewContext, reviewPanelModel, codePanel, this::reloadForAutoRefresh);

        setupActions();
        wireDetailPanelCallbacks();
        setupListeners(pluginManager);
        configureLayout();
    }

    private void setupActions() {
        rejectApprovePanel.setOnApproveAction(reviewerDecisionHandler::handleApprove);
        rejectApprovePanel.setOnRequestChangesAction(reviewerDecisionHandler::handleRequestChanges);
    }

    private void wireDetailPanelCallbacks() {
        reviewDetailPanel.setOnEditAction(this::handleEditReview);
        reviewDetailPanel.setOnJoinReviewAction(membershipHandler::handleJoinReview);
        reviewDetailPanel.setOnLeaveReviewAction(membershipHandler::handleLeaveReview);
        reviewDetailPanel.setOnCloseReviewAction(authorActionHandler::handleCloseReview);
        reviewDetailPanel.setOnMarkInProgressAction(authorActionHandler::handleMarkInProgress);
        reviewDetailPanel.setOnCancelReviewAction(authorActionHandler::handleCancelReview);
        reviewDetailPanel.setOnReviewerStatusChanged(this::onReviewerStatusChanged);
        reviewDetailPanel.setOnReRequestReview(reviewerDecisionHandler::handleReRequestReview);
    }

    private void setupListeners(PluginManager pluginManager) {
        reviewContextManager.addListener(this::onReviewContextChanged);
        settingsManager.addUserNameListener(model.commentsPanelModel::setCurrentUser);
        model.reviewDetailModel.status.addChangeListener(this::onReviewStatusChanged);
        pluginManager.addListenerToNotificationPlugins(
            NotificationPlugin.NotificationType.REVIEW_UPDATED,
            autoRefreshController::onReviewUpdatesReceived);
    }

    private void configureLayout() {
        reviewDetailPanel.setMinimumSize(new Dimension(0, 0));
        codePanel.setMinimumSize(new Dimension(0, 0));

        ThemedSplitPane splitPane = new ThemedSplitPane(JSplitPane.VERTICAL_SPLIT, reviewDetailPanel, codePanel);
        splitPane.setResizeWeight(0.2);
        splitPane.setDividerSize(6);
        splitPane.setDividerLocation(200);

        setLayout(new MigLayout("fill, insets 10", "[grow]", "[grow]0[]"));
        add(splitPane, "grow, wrap");
        add(rejectApprovePanel, "growx");
    }

    /**
     * Approves the currently loaded review on behalf of the current user.
     */
    public void handleApprove() {
        reviewerDecisionHandler.handleApprove();
    }

    /**
     * Requests changes on the currently loaded review on behalf of the current user.
     */
    public void handleRequestChanges() {
        reviewerDecisionHandler.handleRequestChanges();
    }

    /**
     * Loads the specified review, replacing the current content.
     *
     * @param reviewItem the review to load
     */
    public void loadReview(ReviewItem reviewItem) {
        loadController.load(reviewItem, null, false, true, null,
            ctx -> currentReviewContext = ctx, toastWindow::show);
    }

    /**
     * Adds a listener that is notified whenever the current user's reviewer status changes.
     *
     * @param listener receives {@code true} if the user is now a reviewer, {@code false} otherwise
     */
    public void addReviewerStatusListener(Consumer<Boolean> listener) {
        additionalReviewerStatusListeners.add(listener);
    }

    private CompletableFuture<Void> reloadForAutoRefresh(ReviewItem reviewItem,
                                                         ViewportRestoreState restoreState) {
        return loadController.load(reviewItem, restoreState, true, false,
            "Review updated with new changes", ctx -> currentReviewContext = ctx, toastWindow::show)
            .thenRun(InlineCommentDialog::notifyAllCommentChanged);
    }

    private void handleEditReview() {
        if (currentReviewContext == null) {
            LOGGER.warn("Cannot edit review - no review context loaded");
            return;
        }
        LOGGER.debug("Opening edit dialog for review: {}", currentReviewContext.reviewId);
        EditReviewDialog dialog = new EditReviewDialog(
            this, currentReviewContext, reviewFormModels, repositoryManager, reviewContextManager, git, branchManagerFactory);
        dialog.setOnReviewUpdated(() -> refreshReviewDetail(currentReviewContext));
        dialog.setVisible(true);
    }

    private void refreshReviewDetail(ReviewContext context) {
        List<String> repoNames = context.repositories.stream()
            .map(Repository::getName)
            .toList();
        reviewContextManager.loadReviewMetadata(
                context.reviewId, repoNames, context.repositories.getFirst().getName())
            .thenAccept(updatedContext -> {
                if (updatedContext != null) {
                    currentReviewContext = updatedContext;
                    SwingUtilities.invokeLater(() -> model.reviewDetailModel.setReviewData(
                        updatedContext.reviewId, updatedContext.title, updatedContext.author,
                        updatedContext.summary, updatedContext.status, updatedContext.reviewers));
                }
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to refresh review context", error);
                return null;
            });
    }

    private void onReviewContextChanged(ReviewContext context) {
        if (context != null) {
            model.commentsPanelModel.setComments(context.getComments());
            model.commentsPanelModel.setCurrentUser(settingsManager.getCurrentUserName());
            LOGGER.debug("Synced {} comments to CommentsPanelModel for user: {}",
                context.getComments().size(), settingsManager.getCurrentUserName());
        } else {
            model.commentsPanelModel.clear();
        }
    }

    private void onReviewerStatusChanged(Boolean isReviewer) {
        isCurrentUserReviewer = Boolean.TRUE.equals(isReviewer);
        updateActionButtonStates();
        additionalReviewerStatusListeners.forEach(listener -> listener.accept(isReviewer));
    }

    private void onReviewStatusChanged(ReviewStatus status) {
        isReviewTerminal = status == ReviewStatus.COMPLETED || status == ReviewStatus.CANCELLED;
        SwingUtilities.invokeLater(this::updateActionButtonStates);
    }

    private void triggerSnapshotReload(ReviewContext ctx) {
        if (ctx == null || ctx.reviewId == null) return;
        List<String> repoNames = ctx.repositories.stream().map(Repository::getName).toList();
        String primaryRepo = repoNames.isEmpty() ? null : repoNames.getFirst();
        List<String> reviewerNames = ctx.reviewers.stream().map(ReviewerInfo::getName).toList();
        ReviewItem reviewItem = new ReviewItem(
            ctx.reviewId, ctx.title, ctx.author, primaryRepo,
            repoNames, ctx.status, System.currentTimeMillis(),
            reviewerNames, ctx.getBranch(), ctx.getBaseBranch()
        );
        loadController.load(reviewItem, null, true, false, null,
            c -> currentReviewContext = c, toastWindow::show);
    }

    private void updateActionButtonStates() {
        boolean actionsEnabled = isCurrentUserReviewer && !isReviewTerminal;
        rejectApprovePanel.setButtonsEnabled(actionsEnabled);
        codePanel.setCommentsEnabled(actionsEnabled);
    }
}

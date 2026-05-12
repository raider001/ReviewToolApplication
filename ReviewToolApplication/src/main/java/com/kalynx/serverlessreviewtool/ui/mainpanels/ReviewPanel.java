package com.kalynx.serverlessreviewtool.ui.mainpanels;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.managers.FileDiffManager;
import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.plugin.NotificationPlugin;
import com.kalynx.serverlessreviewtool.plugin.ReviewListUpdate;
import com.kalynx.serverlessreviewtool.plugin.ReviewUpdateType;

import com.kalynx.serverlessreviewtool.models.*;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedPanel;
import com.kalynx.serverlessreviewtool.theme.LoadingStateManager;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.CodePanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.RejectApprovePanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ReviewDetailPanel;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewPanelModel;
import com.kalynx.serverlessreviewtool.ui.models.reviewpanel.reviewformdialog.ReviewFormModels;
import com.kalynx.serverlessreviewtool.ui.review.EditReviewDialog;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.BorderFactory;
import javax.swing.JWindow;
import javax.swing.JLabel;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.Window;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;


/**
 * ReviewPanel - Main panel for reviewing code changes across multiple repositories
 * Supports file navigation, diff viewing (side-by-side and unified), commit comparison, and inline comments
 */
public class ReviewPanel extends ThemedPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewPanel.class);

    private final SettingsManager settingsManager;
    private final ReviewContextManager reviewContextManager;
    private final RepositoryManager repositoryManager;
    private final ReviewFormModels reviewFormModels;
    private final ReviewPanelModel model;
    private final FileDiffManager fileDiffManager;
    private final Git git;

    private final ReviewDetailPanel reviewDetailPanel;
    private final CodePanel codePanel;
    private final RejectApprovePanel rejectApprovePanel = new RejectApprovePanel();

    private ReviewContext currentReviewContext;
    private final List<Consumer<Boolean>> additionalReviewerStatusListeners = new ArrayList<>();
    private boolean isCurrentUserReviewer = false;
    private boolean isReviewTerminal = false;
    private volatile boolean autoRefreshInProgress;
    private volatile boolean autoRefreshPending;
    private JWindow updateToastWindow;

    public ReviewPanel(SettingsManager settingsManager,
                       ReviewContextManager reviewContextManager,
                      RepositoryManager repositoryManager,
                      ReviewFormModels reviewFormModels,
                      ReviewPanelModel reviewPanelModel,
                      Git git,
                      PluginManager pluginManager) {
        this.settingsManager = settingsManager;
        this.reviewContextManager = reviewContextManager;
        this.repositoryManager = repositoryManager;
        this.reviewFormModels = reviewFormModels;
        this.model = reviewPanelModel;
        this.git = git;
        this.fileDiffManager = new FileDiffManager(git, reviewPanelModel.codeViewerModel);
        this.reviewDetailPanel = new ReviewDetailPanel(settingsManager, reviewPanelModel.reviewDetailModel);
        this.codePanel = new CodePanel(settingsManager, reviewContextManager, reviewPanelModel.codeViewerModel, fileDiffManager, git, pluginManager);

        setupActions();

        reviewDetailPanel.setOnEditAction(this::handleEditReview);
        reviewDetailPanel.setOnJoinReviewAction(this::handleJoinReview);
        reviewDetailPanel.setOnLeaveReviewAction(this::handleLeaveReview);
        reviewDetailPanel.setOnCloseReviewAction(this::handleCloseReview);
        reviewDetailPanel.setOnMarkInProgressAction(this::handleMarkInProgress);
        reviewDetailPanel.setOnCancelReviewAction(this::handleCancelReview);
        reviewDetailPanel.setOnReviewerStatusChanged(this::onReviewerStatusChanged);

        reviewContextManager.addListener(this::onReviewContextChanged);
        settingsManager.addUserNameListener(model.commentsPanelModel::setCurrentUser);
        model.reviewDetailModel.status.addChangeListener(this::onReviewStatusChanged);

        pluginManager.addListenerToNotificationPlugins(
            NotificationPlugin.NotificationType.REVIEW_UPDATED,
            this::onReviewUpdatesReceived
        );

        configureLayout();
    }

    private void setupActions() {
        rejectApprovePanel.setOnApproveAction(this::handleApprove);
        rejectApprovePanel.setOnRequestChangesAction(this::handleRequestChanges);
    }

    public void handleApprove() {
        if (currentReviewContext == null) {
            LOGGER.warn("Cannot approve - no review context loaded");
            return;
        }

        applyReviewerDecision(ReviewerStatus.APPROVED, "Approving review...");
    }

    public void handleRequestChanges() {
        if (currentReviewContext == null) {
            LOGGER.warn("Cannot request changes - no review context loaded");
            return;
        }

        applyReviewerDecision(ReviewerStatus.CHANGES_REQUESTED, "Requesting changes...");
    }

    private void applyReviewerDecision(ReviewerStatus status, String loadingMessage) {
        if (currentReviewContext == null) {
            LOGGER.warn("Cannot apply reviewer decision - no review context loaded");
            return;
        }

        String currentUser = settingsManager.getCurrentUserName();
        if (currentUser == null || currentUser.isBlank()) {
            LOGGER.warn("Cannot apply reviewer decision - current user is not set");
            return;
        }

        List<String> repositoryNames = currentReviewContext.repositories.stream()
            .map(Repository::getName)
            .toList();

        LOGGER.debug("Applying reviewer decision {} for user {} on review {}",
            status, currentUser, currentReviewContext.reviewId);

        LoadingStateManager.getInstance().startLoading(loadingMessage);
        reviewContextManager.updateReviewerStatus(currentReviewContext.reviewId, currentUser, status, repositoryNames)
            .thenCompose(ignored -> reviewContextManager.loadReviewMetadataOnly(currentReviewContext.reviewId, repositoryNames, currentReviewContext.repositories.getFirst().getName()))
            .thenCompose(updatedContext -> {
                if (updatedContext == null) {
                    return CompletableFuture.completedFuture(null);
                }
                currentReviewContext = updatedContext;
                ReviewStatus desired = computeOverallStatus(updatedContext);
                if (desired != updatedContext.status && !isTerminalStatus(updatedContext.status)) {
                    LOGGER.debug("Syncing overall status to {} based on reviewer decisions", desired);
                    ReviewContext synced = new ReviewContext(
                        updatedContext.reviewId,
                        updatedContext.title,
                        updatedContext.summary,
                        updatedContext.author,
                        desired,
                        new ArrayList<>(updatedContext.reviewers),
                        new ArrayList<>(updatedContext.repositories),
                        new ArrayList<>(updatedContext.comments),
                        updatedContext.getBranch(),
                        updatedContext.getBaseBranch(),
                        updatedContext.hasClosedHistory()
                    );
                    return reviewContextManager.saveReviewMetadata(synced)
                        .thenCompose(ignored2 -> reviewContextManager.loadReviewMetadataOnly(updatedContext.reviewId, repositoryNames, updatedContext.repositories.getFirst().getName()));
                }
                return CompletableFuture.completedFuture(updatedContext);
            })
            .thenAccept(finalContext -> {
                LoadingStateManager.getInstance().stopLoading(loadingMessage);
                if (finalContext != null) {
                    currentReviewContext = finalContext;
                    SwingUtilities.invokeLater(() -> model.reviewDetailModel.setReviewData(
                        finalContext.reviewId,
                        finalContext.title,
                        finalContext.author,
                        finalContext.summary,
                        finalContext.status,
                        finalContext.reviewers
                    ));
                }
            })
            .exceptionally(error -> {
                LoadingStateManager.getInstance().stopLoading(loadingMessage);
                LOGGER.error("Failed to apply reviewer decision {}", status, error);
                return null;
            });
    }

    private ReviewStatus computeOverallStatus(ReviewContext context) {
        boolean anyChangesRequested = context.reviewers.stream()
            .anyMatch(r -> r.getStatus() == ReviewerStatus.CHANGES_REQUESTED);
        return anyChangesRequested ? ReviewStatus.CHANGES_REQUESTED : ReviewStatus.IN_PROGRESS;
    }

    private boolean isTerminalStatus(ReviewStatus status) {
        return status == ReviewStatus.COMPLETED || status == ReviewStatus.CANCELLED;
    }

    private void onReviewerStatusChanged(Boolean isReviewer) {
        isCurrentUserReviewer = Boolean.TRUE.equals(isReviewer);
        updateActionButtonStates();
        additionalReviewerStatusListeners.forEach(listener -> listener.accept(isReviewer));
    }

    private void onReviewStatusChanged(ReviewStatus status) {
        isReviewTerminal = isTerminalStatus(status);
        SwingUtilities.invokeLater(this::updateActionButtonStates);
    }

    private void updateActionButtonStates() {
        boolean actionsEnabled = isCurrentUserReviewer && !isReviewTerminal;
        rejectApprovePanel.setButtonsEnabled(actionsEnabled);
        codePanel.setCommentsEnabled(actionsEnabled);
    }

    public void addReviewerStatusListener(Consumer<Boolean> listener) {
        additionalReviewerStatusListeners.add(listener);
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


    private void configureLayout() {
        setLayout(new MigLayout("fill, insets 10", "[grow]", "[]0[grow]0[]"));

        add(reviewDetailPanel, "cell 0 0, growx, wrap");
        add(codePanel, "grow, wrap");
        add(rejectApprovePanel, "cell 0 2, growx");
    }

    public void loadReview(ReviewItem reviewItem) {
        loadReviewInternal(reviewItem, null, false, true, null);
    }

    private CompletableFuture<Void> loadReviewInternal(ReviewItem reviewItem,
                                                       ViewportRestoreState viewportRestoreState,
                                                       boolean preserveModelState,
                                                       boolean showLoadingIndicator,
                                                       String postLoadNotificationMessage) {
        if (!preserveModelState) {
            this.model.clear();
        }
        if (reviewItem == null || reviewItem.getReviewId() == null || reviewItem.getReviewId().isEmpty()) {
            model.clear();
            return CompletableFuture.completedFuture(null);
        }

        String reviewId = reviewItem.getReviewId();
        List<String> repositoryNames = reviewItem.getRepositories();

        if (!preserveModelState || !reviewId.equals(model.currentReviewId.getValue())) {
            model.setCurrentReview(reviewId);
        }
        if (showLoadingIndicator) {
            LoadingStateManager.getInstance().startLoading("Loading review context...");
        }

        long overallStart = System.nanoTime();
        LOGGER.info("TIMING [{}] === REVIEW LOAD START ===", reviewId);
        LOGGER.debug("Repository Names from ReviewItem: {}", repositoryNames);

        String upfrontBranch = reviewItem.getBranch();
        String upfrontBaseBranch = reviewItem.getBaseBranch();
        CompletableFuture<Void> upfrontFetchFutureLocal = CompletableFuture.completedFuture(null);
        if (upfrontBranch != null && upfrontBaseBranch != null && !repositoryNames.isEmpty()) {
            long upfrontFetchStart = System.nanoTime();
            List<String> upfrontBranchesToFetch = new ArrayList<>();
            upfrontBranchesToFetch.add(upfrontBranch);
            upfrontBranchesToFetch.add(upfrontBaseBranch);
            List<CompletableFuture<Void>> upfrontFetchFutures = repositoryNames.stream()
                .map(repoName -> git.fetchBranches(repoName, upfrontBranchesToFetch))
                .toList();
            upfrontFetchFutureLocal = CompletableFuture.allOf(upfrontFetchFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> LOGGER.info("TIMING [{}] git.fetchBranchesUpfront ({} repos, targeted): {}ms",
                    reviewId, repositoryNames.size(), elapsedMs(upfrontFetchStart)));
        }
        final CompletableFuture<Void> upfrontFetchFuture = upfrontFetchFutureLocal;

        long metadataStart = System.nanoTime();
        return reviewContextManager.loadReviewMetadata(reviewId, repositoryNames, reviewItem.getPrimaryRepository())
            .thenCompose(reviewContext -> {
                LOGGER.info("TIMING [{}] loadReviewMetadata: {}ms", reviewId, elapsedMs(metadataStart));

                if (reviewContext == null) {
                    LOGGER.warn("ReviewContext is null for review: {}", reviewId);
                    model.setError("Review not found");
                    return CompletableFuture.completedFuture(null);
                }

                currentReviewContext = reviewContext;

                List<com.kalynx.serverlessreviewtool.models.Repository> repositories = reviewContext.getRepositories();

                LOGGER.debug("=== REPOSITORIES FROM REVIEW CONTEXT ===");
                LOGGER.debug("Number of repositories: {}", repositories.size());
                for (com.kalynx.serverlessreviewtool.models.Repository repo : repositories) {
                    LOGGER.debug("  - Repository: {} (url: {})", repo.getName(), repo.getUrl());
                }

                if (repositories.isEmpty()) {
                    LOGGER.warn("No repositories found in ReviewContext for review: {}", reviewId);
                    model.setError("No repositories found for review");
                    return CompletableFuture.completedFuture(null);
                }

                model.setRepositories(repositories);

                CompletableFuture<Void> fetchFuture = upfrontFetchFuture;
                boolean canReuseUpfrontFetch = upfrontBranch != null
                    && upfrontBaseBranch != null
                    && upfrontBranch.equals(reviewContext.getBranch())
                    && upfrontBaseBranch.equals(reviewContext.getBaseBranch())
                    && new HashSet<>(repositoryNames).containsAll(repositories.stream().map(Repository::getName).toList());

                if (!canReuseUpfrontFetch) {
                    List<String> branchesToFetch = new ArrayList<>();
                    branchesToFetch.add(reviewContext.getBranch());
                    branchesToFetch.add(reviewContext.getBaseBranch());
                    long fetchStart = System.nanoTime();
                    List<CompletableFuture<Void>> fetchFutures = repositories.stream()
                        .map(repo -> git.fetchBranches(repo.getName(), branchesToFetch))
                        .toList();
                    fetchFuture = CompletableFuture.allOf(fetchFutures.toArray(new CompletableFuture[0]))
                        .thenRun(() -> LOGGER.info("TIMING [{}] git.fetchBranches ({} repos, targeted): {}ms",
                            reviewId, repositories.size(), elapsedMs(fetchStart)));
                } else {
                    LOGGER.debug("Reusing upfront targeted branch fetch for review {}", reviewId);
                }

                return fetchFuture
                    .thenCompose(ignored -> {

                        com.kalynx.serverlessreviewtool.models.Repository primaryRepo = repositories.getFirst();

                        String reviewBranchName = reviewContext.getBranch();
                        String remoteBranch = "origin/" + reviewBranchName;

                        CompletableFuture<Void> commitsFuture;
                        CompletableFuture<List<ReviewFile>> filesFuture;
                        long commitsAndFilesStart = System.nanoTime();

                        if (reviewContext.hasClosedHistory()) {
                            LOGGER.debug("Review {} has closed-history; using stored commit snapshot loading", reviewId);
                            String snapshotEditor = settingsManager.getCurrentUserName();
                            if (snapshotEditor == null || snapshotEditor.isBlank()) {
                                snapshotEditor = "system";
                            }

                            CompletableFuture<Map<String, List<String>>> reconcileSnapshotsFuture = reviewContextManager
                                .captureReviewCommitSnapshots(
                                    reviewId,
                                    repositories,
                                    reviewContext.getBranch(),
                                    reviewContext.getBaseBranch(),
                                    snapshotEditor);

                            commitsFuture = reconcileSnapshotsFuture
                                .thenApply(commitsByRepository -> commitsByRepository.getOrDefault(primaryRepo.getName(), List.of()))
                                .thenCompose(commitHashes -> fileDiffManager.loadCommitsForSnapshot(
                                    primaryRepo.getName(), commitHashes));
                            filesFuture = reconcileSnapshotsFuture
                                .thenCompose(commitsByRepository -> reviewContextManager
                                    .loadFilesFromStoredReviewCommits(
                                        reviewId,
                                        repositories,
                                        reviewContext.getBranch(),
                                        reviewContext.getBaseBranch(),
                                        commitsByRepository));
                        } else {
                            long commitsStart = System.nanoTime();
                            commitsFuture = fileDiffManager
                                .loadCommitsForReview(primaryRepo.getName(), remoteBranch, 1000)
                                .thenRun(() -> LOGGER.info("TIMING [{}] loadCommitsForReview ({}): {}ms",
                                    reviewId, primaryRepo.getName(), elapsedMs(commitsStart)));

                            long filesStart = System.nanoTime();
                            filesFuture = reviewContextManager
                                .loadFilesFromReviewCommits(
                                    repositories,
                                    reviewContext.getBranch(),
                                    reviewContext.getBaseBranch())
                                .thenApply(files -> {
                                    LOGGER.info("TIMING [{}] loadFilesFromReviewCommits ({} repos): {}ms",
                                        reviewId, repositories.size(), elapsedMs(filesStart));
                                    return files;
                                });
                        }

                        LOGGER.debug("Repositories being passed to loadFilesFromReviewCommits: {}",
                            repositories.stream().map(Repository::getName).toList());

                        return CompletableFuture.allOf(commitsFuture, filesFuture)
                            .thenAccept(_ -> {
                                LOGGER.info("TIMING [{}] commits+files (parallel): {}ms",
                                    reviewId, elapsedMs(commitsAndFilesStart));

                                List<ReviewFile> allFiles = filesFuture.join();

                                LOGGER.debug("Total files: {}", allFiles.size());
                                for (ReviewFile file : allFiles) {
                                    LOGGER.debug("  - {} (repository: {})", file.getPath(), file.getRepository());
                                }

                                if (!allFiles.isEmpty()) {
                                    ReviewFile firstFile = allFiles.getFirst();
                                    String reviewBranch = firstFile.getReviewBranch();
                                    String baseBranch = firstFile.getBaseBranch();

                                    if (reviewBranch != null && baseBranch != null) {
                                        LOGGER.debug("Setting review branches in model: base={}, review={}",
                                            baseBranch, reviewBranch);
                                        model.codeViewerModel.setReviewBranches(reviewBranch, baseBranch);
                                    }
                                }

                                model.codeViewerModel.setAvailableFiles(allFiles);

                                if (viewportRestoreState != null) {
                                    restoreViewportState(allFiles, viewportRestoreState);
                                }

                                if (postLoadNotificationMessage != null && !postLoadNotificationMessage.isBlank()) {
                                    showUpdateToast(postLoadNotificationMessage);
                                }

                                LOGGER.info("TIMING [{}] === REVIEW LOAD COMPLETE: {}ms total ===",
                                    reviewId, elapsedMs(overallStart));
                            });
                    });
            })
            .whenComplete((ignored, _) -> {
                if (showLoadingIndicator) {
                    LoadingStateManager.getInstance().stopLoading("Loading review context...");
                }
            })
            .exceptionally(error -> {
                LOGGER.info("TIMING [{}] === REVIEW LOAD FAILED: {}ms ===", reviewId, elapsedMs(overallStart));
                model.setError("Failed to load review: " + error.getMessage());
                return null;
            });
    }

    private void onReviewUpdatesReceived(ReviewListUpdate[] updates) {
        if (updates == null || updates.length == 0) {
            return;
        }
        if (currentReviewContext == null || currentReviewContext.getReviewId() == null || currentReviewContext.getReviewId().isBlank()) {
            return;
        }

        String activeReviewId = currentReviewContext.getReviewId();
        Set<String> activeRepositories = currentReviewContext.getRepositories().stream()
            .map(Repository::getName)
            .collect(java.util.stream.Collectors.toSet());

        boolean hasRelevantUpdate = java.util.Arrays.stream(updates)
            .filter(java.util.Objects::nonNull)
            .filter(update -> update.updateType() == ReviewUpdateType.UPDATED)
            .anyMatch(update -> isRelevantToCurrentReview(update, activeReviewId, activeRepositories));

        if (!hasRelevantUpdate) {
            return;
        }

        triggerAutoRefreshForOpenReview();
    }

    private boolean isRelevantToCurrentReview(ReviewListUpdate update,
                                              String activeReviewId,
                                              Set<String> activeRepositories) {
        if (activeReviewId.equals(update.reviewId())) {
            return true;
        }
        if (update.primaryRepository() != null && activeRepositories.contains(update.primaryRepository())) {
            return true;
        }
        if (update.repositories() == null || update.repositories().isEmpty()) {
            return false;
        }
        return update.repositories().stream().anyMatch(activeRepositories::contains);
    }

    private void triggerAutoRefreshForOpenReview() {
        synchronized (this) {
            if (autoRefreshInProgress) {
                autoRefreshPending = true;
                return;
            }
            autoRefreshInProgress = true;
        }

        SwingUtilities.invokeLater(() -> {
            ReviewContext context = currentReviewContext;
            if (context == null || context.getReviewId() == null || context.getReviewId().isBlank()) {
                completeAutoRefreshCycle();
                return;
            }

            ReviewFile selectedFile = model.codeViewerModel.selectedFile.getValue();
            ViewportRestoreState restoreState = new ViewportRestoreState(
                selectedFile != null ? selectedFile.getRepository() : null,
                selectedFile != null ? selectedFile.getPath() : null,
                codePanel.getTopVisibleLine()
            );

            ReviewItem reviewItem = new ReviewItem(
                context.getReviewId(),
                context.getTitle(),
                context.getAuthor(),
                context.getRepositories().isEmpty() ? null : context.getRepositories().getFirst().getName(),
                context.getRepositories().stream().map(Repository::getName).toList(),
                context.status,
                System.currentTimeMillis(),
                context.getReviewers().stream().map(ReviewerInfo::getName).toList(),
                context.getBranch(),
                context.getBaseBranch()
            );

            loadReviewInternal(reviewItem, restoreState, true, false, "Review updated with new changes")
                .whenComplete((_, ignore) -> completeAutoRefreshCycle());
        });
    }

    private void completeAutoRefreshCycle() {
        boolean shouldRunAgain;
        synchronized (this) {
            shouldRunAgain = autoRefreshPending;
            autoRefreshPending = false;
            autoRefreshInProgress = false;
        }
        if (shouldRunAgain) {
            triggerAutoRefreshForOpenReview();
        }
    }

    private void restoreViewportState(List<ReviewFile> files, ViewportRestoreState state) {
        if (state == null || files == null || files.isEmpty()) {
            return;
        }

        if (state.repositoryName() != null && state.filePath() != null) {
            ReviewFile currentlySelected = model.codeViewerModel.selectedFile.getValue();
            boolean alreadySelected = currentlySelected != null
                && state.repositoryName().equals(currentlySelected.getRepository())
                && state.filePath().equals(currentlySelected.getPath());

            if (!alreadySelected) {
                files.stream()
                    .filter(file -> state.repositoryName().equals(file.getRepository()) && state.filePath().equals(file.getPath()))
                    .findFirst()
                    .ifPresent(model.codeViewerModel::selectFile);
            }
        }

        if (state.topVisibleLine() > 0) {
            codePanel.restoreTopVisibleLine(state.topVisibleLine());
        }
    }

    private void showUpdateToast(String message) {
        SwingUtilities.invokeLater(() -> {
            Window owner = SwingUtilities.getWindowAncestor(this);
            if (owner == null || !owner.isDisplayable()) {
                return;
            }

            if (updateToastWindow != null) {
                updateToastWindow.dispose();
            }

            JWindow toast = new JWindow(owner);
            JLabel label = new JLabel(message);
            label.setForeground(Color.WHITE);

            javax.swing.JPanel panel = new javax.swing.JPanel(new java.awt.BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            panel.setBackground(new Color(32, 32, 32, 230));
            panel.add(label, java.awt.BorderLayout.CENTER);

            toast.setBackground(new Color(0, 0, 0, 0));
            toast.setContentPane(panel);
            toast.pack();

            Point panelPoint;
            try {
                panelPoint = getLocationOnScreen();
            } catch (IllegalComponentStateException e) {
                panelPoint = owner.getLocationOnScreen();
            }
            toast.setLocation(panelPoint.x + 16, panelPoint.y + 16);
            toast.setAlwaysOnTop(false);
            toast.setVisible(true);
            setWindowOpacity(toast, 0.95f);

            int totalDurationMs = 5000;
            int fadeDurationMs = 1000;
            int fadeStartMs = totalDurationMs - fadeDurationMs;
            long startedAt = System.currentTimeMillis();

            Timer timer = new Timer(50, event -> {
                long elapsed = System.currentTimeMillis() - startedAt;
                if (elapsed >= totalDurationMs) {
                    ((Timer) event.getSource()).stop();
                    toast.dispose();
                    if (updateToastWindow == toast) {
                        updateToastWindow = null;
                    }
                    return;
                }

                if (elapsed >= fadeStartMs) {
                    float fadeProgress = (elapsed - fadeStartMs) / (float) fadeDurationMs;
                    float opacity = Math.max(0.0f, 0.95f * (1.0f - fadeProgress));
                    setWindowOpacity(toast, opacity);
                }
            });
            timer.setRepeats(true);
            timer.start();

            updateToastWindow = toast;
        });
    }

    private void setWindowOpacity(Window window, float opacity) {
        try {
            window.setOpacity(Math.max(0.0f, Math.min(1.0f, opacity)));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    private record ViewportRestoreState(String repositoryName, String filePath, int topVisibleLine) {}

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private void handleEditReview() {
        if (currentReviewContext == null) {
            LOGGER.warn("Cannot edit review - no review context loaded");
            return;
        }

        LOGGER.debug("Opening edit dialog for review: {}", currentReviewContext.reviewId);

        EditReviewDialog dialog = new EditReviewDialog(
            this,
            currentReviewContext,
            reviewFormModels,
            repositoryManager,
            reviewContextManager,
            git
        );

        dialog.setOnReviewUpdated(() -> {
            LOGGER.debug("Review field updated, refreshing context...");
            reviewContextManager.loadReviewMetadata(currentReviewContext.reviewId,
                currentReviewContext.repositories.stream()
                    .map(Repository::getName)
                    .toList(),
                currentReviewContext.repositories.getFirst().getName())
                .thenAccept(updatedContext -> {
                    if (updatedContext != null) {
                        currentReviewContext = updatedContext;
                        SwingUtilities.invokeLater(() -> model.reviewDetailModel.setReviewData(
                            updatedContext.reviewId,
                            updatedContext.title,
                            updatedContext.author,
                            updatedContext.summary,
                            updatedContext.status,
                            updatedContext.reviewers
                        ));
                    }
                })
                .exceptionally(error -> {
                    LOGGER.error("Failed to refresh review context", error);
                    return null;
                });
        });

        dialog.setVisible(true);
    }

    private void handleJoinReview() {
        if (currentReviewContext == null) {
            LOGGER.warn("Cannot join review - no review context loaded");
            return;
        }

        final String currentUser = settingsManager.getCurrentUserName();

        LOGGER.debug("Adding {} as reviewer to review: {}", currentUser, currentReviewContext.reviewId);

        LoadingStateManager.getInstance().startLoading("Joining review...");

        reviewContextManager.addReviewer(currentReviewContext.reviewId, currentUser,
            currentReviewContext.repositories.stream()
                .map(Repository::getName)
                .toList())
            .thenAccept(_ -> {
                LOGGER.debug("Successfully added {} as reviewer", currentUser);
                reviewContextManager.loadReviewMetadata(currentReviewContext.reviewId,
                    currentReviewContext.repositories.stream()
                        .map(Repository::getName)
                        .toList(),
                    currentReviewContext.repositories.getFirst().getName())
                    .thenAccept(updatedContext -> {
                        LoadingStateManager.getInstance().stopLoading("Joining review...");
                        if (updatedContext != null) {
                            currentReviewContext = updatedContext;
                            SwingUtilities.invokeLater(() -> model.reviewDetailModel.setReviewData(
                                updatedContext.reviewId,
                                updatedContext.title,
                                updatedContext.author,
                                updatedContext.summary,
                                updatedContext.status,
                                updatedContext.reviewers
                            ));
                        }
                    })
                    .exceptionally(error -> {
                        LoadingStateManager.getInstance().stopLoading("Joining review...");
                        LOGGER.error("Failed to refresh review after joining", error);
                        return null;
                    });
            })
            .exceptionally(error -> {
                LoadingStateManager.getInstance().stopLoading("Joining review...");
                LOGGER.error("Failed to join review", error);
                return null;
            });
    }

    private void handleLeaveReview() {
        if (currentReviewContext == null) {
            LOGGER.warn("Cannot leave review - no review context loaded");
            return;
        }

        final String currentUser = settingsManager.getCurrentUserName();

        LOGGER.debug("Removing {} from review: {}", currentUser, currentReviewContext.reviewId);

        LoadingStateManager.getInstance().startLoading("Leaving review...");

        reviewContextManager.removeReviewer(currentReviewContext.reviewId, currentUser,
            currentReviewContext.repositories.stream()
                .map(Repository::getName)
                .toList())
            .thenAccept(_ -> {
                LOGGER.debug("Successfully removed {} from reviewers", currentUser);
                reviewContextManager.loadReviewMetadata(currentReviewContext.reviewId,
                    currentReviewContext.repositories.stream()
                        .map(Repository::getName)
                        .toList(),
                    currentReviewContext.repositories.getFirst().getName())
                    .thenAccept(updatedContext -> {
                        LoadingStateManager.getInstance().stopLoading("Leaving review...");
                        if (updatedContext != null) {
                            currentReviewContext = updatedContext;
                            SwingUtilities.invokeLater(() -> model.reviewDetailModel.setReviewData(
                                updatedContext.reviewId,
                                updatedContext.title,
                                updatedContext.author,
                                updatedContext.summary,
                                updatedContext.status,
                                updatedContext.reviewers
                            ));
                        }
                    })
                    .exceptionally(error -> {
                        LoadingStateManager.getInstance().stopLoading("Leaving review...");
                        LOGGER.error("Failed to refresh review after leaving", error);
                        return null;
                    });
            })
            .exceptionally(error -> {
                LoadingStateManager.getInstance().stopLoading("Leaving review...");
                LOGGER.error("Failed to leave review", error);
                return null;
            });
    }

    private void handleCloseReview() {
        applyAuthorStatusChange(ReviewStatus.COMPLETED, "Closing review...", "close review");
    }

    private void handleMarkInProgress() {
        applyAuthorStatusChange(ReviewStatus.IN_PROGRESS, "Updating review...", "mark review in progress");
    }

    private void handleCancelReview() {
        applyAuthorStatusChange(ReviewStatus.CANCELLED, "Cancelling review...", "cancel review");
    }

    private void applyAuthorStatusChange(ReviewStatus targetStatus, String loadingMessage, String actionDescription) {
        if (currentReviewContext == null) {
            LOGGER.warn("Cannot {} - no review context loaded", actionDescription);
            return;
        }

        String currentUser = settingsManager.getCurrentUserName();
        if (currentUser == null || currentUser.isBlank()) {
            LOGGER.warn("Cannot {} - current user is not set", actionDescription);
            return;
        }

        if (!currentUser.trim().equals(currentReviewContext.author != null ? currentReviewContext.author.trim() : "")) {
            LOGGER.warn("Cannot {} - current user is not the review author", actionDescription);
            return;
        }

        if (currentReviewContext.status == targetStatus) {
            LOGGER.debug("Review {} is already {}", currentReviewContext.reviewId, targetStatus);
            return;
        }

        LOGGER.debug("Applying status {} to review {} by author {}", targetStatus, currentReviewContext.reviewId, currentUser);
        LoadingStateManager.getInstance().startLoading(loadingMessage);

        ReviewContext updatedContext = new ReviewContext(
            currentReviewContext.reviewId,
            currentReviewContext.title,
            currentReviewContext.summary,
            currentReviewContext.author,
            targetStatus,
            new ArrayList<>(currentReviewContext.reviewers),
            new ArrayList<>(currentReviewContext.repositories),
            new ArrayList<>(currentReviewContext.comments),
            currentReviewContext.getBranch(),
            currentReviewContext.getBaseBranch(),
            currentReviewContext.hasClosedHistory() || isTerminalStatus(targetStatus)
        );

        CompletableFuture<Map<String, List<String>>> snapshotFuture = isTerminalStatus(targetStatus)
            ? reviewContextManager.captureReviewCommitSnapshots(
                updatedContext.reviewId,
                updatedContext.getRepositories(),
                updatedContext.getBranch(),
                updatedContext.getBaseBranch(),
                currentUser)
            : CompletableFuture.completedFuture(Map.of());

        snapshotFuture
            .thenCompose(ignored -> reviewContextManager.saveReviewMetadata(updatedContext))
            .thenCompose(ignored -> reviewContextManager.loadReviewMetadataOnly(
                currentReviewContext.reviewId,
                currentReviewContext.repositories.stream().map(Repository::getName).toList(),
                currentReviewContext.repositories.getFirst().getName()
            ))
            .thenAccept(reloaded -> {
                LoadingStateManager.getInstance().stopLoading(loadingMessage);
                if (reloaded != null) {
                    currentReviewContext = reloaded;
                    SwingUtilities.invokeLater(() -> model.reviewDetailModel.setReviewData(
                        reloaded.reviewId,
                        reloaded.title,
                        reloaded.author,
                        reloaded.summary,
                        reloaded.status,
                        reloaded.reviewers
                    ));
                }
            })
            .exceptionally(error -> {
                LoadingStateManager.getInstance().stopLoading(loadingMessage);
                LOGGER.error("Failed to {}", actionDescription, error);
                return null;
            });
    }
}

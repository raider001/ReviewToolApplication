package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.managers.FileDiffManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.swingtheme.theme.LoadingStateManager;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewPanelModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

/**
 * Orchestrates the async pipeline for loading a review: metadata loading, branch fetching,
 * parallel commit and file resolution, and model updates on completion.
 */
public class ReviewLoadController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewLoadController.class);

    private final ReviewContextManager reviewContextManager;
    private final ReviewPanelModel model;
    private final FileDiffManager fileDiffManager;
    private final Git git;
    private final CodePanel codePanel;

    /**
     * @param reviewContextManager manager for review context operations
     * @param model                shared panel model
     * @param fileDiffManager      file diff loading manager
     * @param git                  git operations
     * @param settingsManager      settings access
     * @param codePanel            code viewer panel, used for viewport restore
     */
    public ReviewLoadController(ReviewContextManager reviewContextManager,
                                ReviewPanelModel model,
                                FileDiffManager fileDiffManager,
                                Git git,
                                SettingsManager settingsManager,
                                CodePanel codePanel) {
        this.reviewContextManager = reviewContextManager;
        this.model = model;
        this.fileDiffManager = fileDiffManager;
        this.git = git;
        this.codePanel = codePanel;
    }

    /**
     * Loads a review, updating the shared model on completion.
     *
     * @param reviewItem                 the review to load
     * @param viewportRestoreState       optional state to restore after load
     * @param preserveModelState         if {@code false}, clears the model before loading
     * @param showLoadingIndicator       whether to show the loading overlay
     * @param postLoadNotificationMessage optional toast message shown on success
     * @param onContextLoaded            receives the loaded {@link ReviewContext}, or {@code null} on failure
     * @param showToast                  callback to display a toast notification
     * @return future that completes when loading finishes
     */
    public CompletableFuture<Void> load(ReviewItem reviewItem,
                                        ViewportRestoreState viewportRestoreState,
                                        boolean preserveModelState,
                                        boolean showLoadingIndicator,
                                        String postLoadNotificationMessage,
                                        Consumer<ReviewContext> onContextLoaded,
                                        Consumer<String> showToast) {
        if (!preserveModelState) {
            model.clear();
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

        CompletableFuture<Void> upfrontFetchFuture = buildUpfrontFetchFuture(reviewItem, repositoryNames, reviewId);
        long metadataStart = System.nanoTime();

        return reviewContextManager.loadReviewMetadata(reviewId, repositoryNames, reviewItem.getPrimaryRepository())
            .thenCompose(reviewContext -> {
                LOGGER.info("TIMING [{}] loadReviewMetadata: {}ms", reviewId, elapsedMs(metadataStart));

                if (reviewContext == null) {
                    LOGGER.warn("ReviewContext is null for review: {}", reviewId);
                    model.setError("Review not found");
                    onContextLoaded.accept(null);
                    return CompletableFuture.completedFuture(null);
                }

                onContextLoaded.accept(reviewContext);
                List<Repository> repositories = reviewContext.getRepositories();
                logRepositories(repositories);

                if (repositories.isEmpty()) {
                    LOGGER.warn("No repositories found in ReviewContext for review: {}", reviewId);
                    model.setError("No repositories found for review");
                    return CompletableFuture.completedFuture(null);
                }

                model.setRepositories(repositories);

                CompletableFuture<Void> fetchFuture = chooseFetchFuture(
                    upfrontFetchFuture, reviewItem, reviewContext, repositoryNames, repositories, reviewId);

                return fetchFuture.thenCompose(ignored ->
                    loadCommitsAndFiles(reviewContext, repositories, viewportRestoreState,
                        postLoadNotificationMessage, overallStart, reviewId, showToast));
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

    private CompletableFuture<Void> buildUpfrontFetchFuture(ReviewItem reviewItem,
                                                            List<String> repositoryNames,
                                                            String reviewId) {
        String branch = reviewItem.getBranch();
        String baseBranch = reviewItem.getBaseBranch();
        if (branch == null || baseBranch == null || repositoryNames.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        long fetchStart = System.nanoTime();
        List<String> branchesToFetch = List.of(branch, baseBranch);
        List<CompletableFuture<Void>> futures = repositoryNames.stream()
            .map(repoName -> git.fetchBranches(repoName, branchesToFetch))
            .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> LOGGER.info("TIMING [{}] git.fetchBranchesUpfront ({} repos, targeted): {}ms",
                reviewId, repositoryNames.size(), elapsedMs(fetchStart)));
    }

    private CompletableFuture<Void> chooseFetchFuture(CompletableFuture<Void> upfrontFetchFuture,
                                                      ReviewItem reviewItem,
                                                      ReviewContext reviewContext,
                                                      List<String> repositoryNames,
                                                      List<Repository> repositories,
                                                      String reviewId) {
        String upfrontBranch = reviewItem.getBranch();
        String upfrontBaseBranch = reviewItem.getBaseBranch();
        boolean canReuse = upfrontBranch != null
            && upfrontBaseBranch != null
            && upfrontBranch.equals(reviewContext.getBranch())
            && upfrontBaseBranch.equals(reviewContext.getBaseBranch())
            && new HashSet<>(repositoryNames).containsAll(
                repositories.stream().map(Repository::getName).toList());

        if (canReuse) {
            LOGGER.debug("Reusing upfront targeted branch fetch for review {}", reviewId);
            return upfrontFetchFuture;
        }

        List<String> branchesToFetch = List.of(reviewContext.getBranch(), reviewContext.getBaseBranch());
        long fetchStart = System.nanoTime();
        List<CompletableFuture<Void>> futures = repositories.stream()
            .map(repo -> git.fetchBranches(repo.getName(), branchesToFetch))
            .toList();
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> LOGGER.info("TIMING [{}] git.fetchBranches ({} repos, targeted): {}ms",
                reviewId, repositories.size(), elapsedMs(fetchStart)));
    }

    private CompletableFuture<Void> loadCommitsAndFiles(ReviewContext reviewContext,
                                                        List<Repository> repositories,
                                                        ViewportRestoreState viewportRestoreState,
                                                        String postLoadNotificationMessage,
                                                        long overallStart,
                                                        String reviewId,
                                                        Consumer<String> showToast) {
        Repository primaryRepo = repositories.getFirst();
        String branch = reviewContext.getBranch();
        String remoteBranch = (branch != null && !branch.isBlank()) ? "origin/" + branch : null;
        long commitsAndFilesStart = System.nanoTime();

        CompletableFuture<Void> commitsFuture;
        CompletableFuture<List<ReviewFile>> filesFuture;

        if (reviewContext.hasClosedHistory()) {
            LOGGER.debug("Review {} has closed-history; using stored commit snapshot loading", reviewId);
            CommitAndFileFutures snapshotFutures = buildSnapshotFutures(reviewContext, repositories, primaryRepo, reviewId);
            commitsFuture = snapshotFutures.commits();
            filesFuture = snapshotFutures.files();
        } else {
            CommitAndFileFutures liveFutures = buildLiveFutures(reviewContext, repositories, primaryRepo, remoteBranch, reviewId);
            commitsFuture = liveFutures.commits();
            filesFuture = liveFutures.files();
        }

        LOGGER.debug("Repositories being passed to loadFilesFromReviewCommits: {}",
            repositories.stream().map(Repository::getName).toList());

        CompletableFuture<List<ReviewFile>> finalFilesFuture = filesFuture;
        return CompletableFuture.allOf(commitsFuture, filesFuture)
            .thenAccept(_ -> {
                LOGGER.info("TIMING [{}] commits+files (parallel): {}ms", reviewId, elapsedMs(commitsAndFilesStart));
                List<ReviewFile> allFiles = finalFilesFuture.join();
                logFiles(allFiles);
                long totalElapsedMs = elapsedMs(overallStart);

                SwingUtilities.invokeLater(() -> {
                    updateBranchesInModel(allFiles);
                    model.codeViewerModel.setAvailableFiles(allFiles);

                    if (viewportRestoreState != null) {
                        restoreViewportState(allFiles, viewportRestoreState);
                    }
                    if (postLoadNotificationMessage != null && !postLoadNotificationMessage.isBlank()) {
                        showToast.accept(postLoadNotificationMessage);
                    }
                    LOGGER.info("TIMING [{}] === REVIEW LOAD COMPLETE: {}ms total ===", reviewId, totalElapsedMs);
                });
            });
    }

    private CommitAndFileFutures buildSnapshotFutures(ReviewContext reviewContext,
                                                      List<Repository> repositories,
                                                      Repository primaryRepo,
                                                      String reviewId) {
        CompletableFuture<Map<String, List<String>>> snapshotsFuture = reviewContextManager
            .loadStoredReviewCommitsForAllRepositories(reviewId, repositories);

        CompletableFuture<Void> commits = snapshotsFuture
            .thenApply(map -> map.getOrDefault(primaryRepo.getName(), List.of()))
            .thenCompose(hashes -> fileDiffManager.loadCommitsForSnapshot(primaryRepo.getName(), hashes));

        CompletableFuture<List<ReviewFile>> files = snapshotsFuture
            .thenCompose(map -> reviewContextManager.loadFilesFromStoredReviewCommits(
                reviewId, repositories, reviewContext.getBranch(), reviewContext.getBaseBranch(), map));

        return new CommitAndFileFutures(commits, files);
    }

    private CommitAndFileFutures buildLiveFutures(ReviewContext reviewContext,
                                                  List<Repository> repositories,
                                                  Repository primaryRepo,
                                                  String remoteBranch,
                                                  String reviewId) {
        long commitsStart = System.nanoTime();
        CompletableFuture<Void> commits = (remoteBranch != null)
            ? fileDiffManager
                .loadCommitsForReview(primaryRepo.getName(), remoteBranch, 1000)
                .thenRun(() -> LOGGER.info("TIMING [{}] loadCommitsForReview ({}): {}ms",
                    reviewId, primaryRepo.getName(), elapsedMs(commitsStart)))
            : CompletableFuture.completedFuture(null);

        long filesStart = System.nanoTime();
        CompletableFuture<List<ReviewFile>> files = reviewContextManager
            .loadFilesFromReviewCommits(repositories, reviewContext.getBranch(), reviewContext.getBaseBranch())
            .thenApply(fileList -> {
                LOGGER.info("TIMING [{}] loadFilesFromReviewCommits ({} repos): {}ms",
                    reviewId, repositories.size(), elapsedMs(filesStart));
                return fileList;
            });

        return new CommitAndFileFutures(commits, files);
    }

    private void updateBranchesInModel(List<ReviewFile> files) {
        if (files.isEmpty()) {
            return;
        }
        ReviewFile first = files.getFirst();
        String reviewBranch = first.getReviewBranch();
        String baseBranch = first.getBaseBranch();
        if (reviewBranch != null && baseBranch != null) {
            LOGGER.debug("Setting review branches in model: base={}, review={}", baseBranch, reviewBranch);
            model.codeViewerModel.setReviewBranches(reviewBranch, baseBranch);
        }
    }

    private void restoreViewportState(List<ReviewFile> files, ViewportRestoreState state) {
        if (state.repositoryName() != null && state.filePath() != null) {
            ReviewFile current = model.codeViewerModel.selectedFile.getValue();
            boolean alreadySelected = current != null
                && state.repositoryName().equals(current.getRepository())
                && state.filePath().equals(current.getPath());
            if (!alreadySelected) {
                files.stream()
                    .filter(f -> state.repositoryName().equals(f.getRepository())
                        && state.filePath().equals(f.getPath()))
                    .findFirst()
                    .ifPresent(model.codeViewerModel::selectFile);
            }
        }
        if (state.topVisibleLine() > 0) {
            codePanel.restoreTopVisibleLine(state.topVisibleLine());
        }
    }

    private void logRepositories(List<Repository> repositories) {
        LOGGER.debug("=== REPOSITORIES FROM REVIEW CONTEXT ===");
        LOGGER.debug("Number of repositories: {}", repositories.size());
        for (Repository repo : repositories) {
            LOGGER.debug("  - Repository: {} (url: {})", repo.getName(), repo.getUrl());
        }
    }

    private void logFiles(List<ReviewFile> files) {
        LOGGER.debug("Total files: {}", files.size());
        for (ReviewFile file : files) {
            LOGGER.debug("  - {} (repository: {})", file.getPath(), file.getRepository());
        }
    }

    private static long elapsedMs(long startNano) {
        return (System.nanoTime() - startNano) / 1_000_000;
    }

    private record CommitAndFileFutures(CompletableFuture<Void> commits,
                                        CompletableFuture<List<ReviewFile>> files) {}
}


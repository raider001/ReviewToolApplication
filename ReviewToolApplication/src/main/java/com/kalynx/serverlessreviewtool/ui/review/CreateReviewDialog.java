package com.kalynx.serverlessreviewtool.ui.review;

import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.git.GitReviewNotesManager;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.swingtheme.themedcomponents.ThemedOptionPane;
import com.kalynx.swingtheme.theme.LoadingStateManager;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ReviewFormDialog;
import com.kalynx.serverlessreviewtool.ui.models.reviewpanel.reviewformdialog.ReviewFormModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Dialog for creating a new code review.
 */
public class CreateReviewDialog extends ReviewFormDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateReviewDialog.class);

    /**
     * @param parent            parent component for dialog placement
     * @param models            shared form models
     * @param repositoryManager repository lookup for URL resolution
     * @param git               git client used for clone/fetch operations
     */
    public CreateReviewDialog(Component parent,
                              ReviewFormModels models,
                              RepositoryManager repositoryManager,
                              Git git) {
        super(parent, "Create Code Review", models, repositoryManager, git);
        models.clear();
    }

    @Override
    protected String getSubmitButtonLabel() { return "Create Review"; }

    @Override
    protected void onFormSubmit() {
        String reviewId = models.reviewId.getValue();
        String title = models.title.getValue();
        String author = models.author.getValue();
        String summary = models.summary.getValue();
        String branch = models.selectedBranchModel.getValue();
        String baseBranch = models.selectedBaseBranchModel.getValue();
        List<String> repositories = models.selectedRepositories.getValue();
        List<ReviewerInfo> reviewerInfos = models.selectedReviewers.getValue();

        if (repositories == null || repositories.isEmpty()) {
            ThemedOptionPane.showWarning(this, "Please select at least one repository");
            return;
        }

        String primaryRepo = repositories.getFirst();
        String editor = author.isEmpty() ? "unknown" : author;

        List<String> reviewerNames = reviewerInfos.stream()
            .map(ReviewerInfo::getName)
            .collect(Collectors.toList());

        createReview(primaryRepo, repositories, reviewId, editor, title, author, summary, branch, baseBranch, reviewerNames);
    }

    private void createReview(String primaryRepository,
                               List<String> allRepositories,
                               String reviewId,
                               String editor,
                               String title,
                               String author,
                               String summary,
                               String branch,
                               String baseBranch,
                               List<String> reviewers) {

        LoadingStateManager.getInstance().startLoading("create-review");
        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, primaryRepository);

        LOGGER.info("Creating review - Primary Repository: {}, All Repositories: {}, Branch: {}, Base: {}",
            primaryRepository, allRepositories, branch, baseBranch);

        List<CompletableFuture<Void>> cloneFutures = allRepositories.stream()
            .map(repoName -> {
                String repoUrl = resolveRepositoryUrl(repoName);
                return git.ensureCloned(repoName, repoUrl)
                    .thenCompose(_ -> git.fetch(repoName));
            })
            .toList();

        CompletableFuture.allOf(cloneFutures.toArray(new CompletableFuture[0]))
            .thenCompose(_ -> notesManager.createReviewAcrossRepositories(
                reviewId, editor, title, author, summary, "open",
                Map.of(), reviewers, allRepositories, branch, baseBranch
            ))
            .thenAccept(_ -> SwingUtilities.invokeLater(() -> {
                LoadingStateManager.getInstance().stopLoading("create-review");
                confirmed = true;
                dispose();
                LOGGER.info("Review created successfully across {} repositories: {}", allRepositories.size(), reviewId);
            }))
            .exceptionally(ex -> {
                LoadingStateManager.getInstance().stopLoading("create-review");
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                String errorMessage = resolveErrorMessage(cause, branch, baseBranch);
                SwingUtilities.invokeLater(() -> ThemedOptionPane.showError(this, errorMessage));
                LOGGER.error("Failed to create review {}: {}", reviewId, cause.getMessage(), cause);
                return null;
            });
    }

    private String resolveRepositoryUrl(String repoName) {
        com.kalynx.serverlessreviewtool.models.Repository repo = repositoryManager.getRepositoryByName(repoName);
        if (repo == null || repo.getUrl() == null || repo.getUrl().isEmpty()) {
            throw new RuntimeException("No URL configured for repository: " + repoName);
        }
        return repo.getUrl();
    }

    private String resolveErrorMessage(Throwable cause, String branch, String baseBranch) {
        if (cause instanceof IllegalArgumentException) {
            return cause.getMessage();
        }
        String msg = cause.getMessage();
        if (msg == null) {
            return "An unexpected error occurred while creating the review.";
        }
        if (msg.contains("has no reachable commits") || msg.contains("has no commits")) {
            return msg;
        }
        if (msg.contains("ambiguous argument 'HEAD'") || msg.contains("unknown revision or path not in the working tree")) {
            return "Cannot create review: one or more selected repositories has no commits on its default branch.\n" +
                   "Branches requested: '" + baseBranch + "' → '" + branch + "'.\n" +
                   "Ensure both branches exist on the remote and have at least one commit.";
        }
        if (msg.startsWith("Git command failed:")) {
            return "Failed to create review due to a Git error.\n" +
                   "Verify that all selected repositories are accessible and the branches exist on the remote.";
        }
        return "Failed to create review: " + msg;
    }
}

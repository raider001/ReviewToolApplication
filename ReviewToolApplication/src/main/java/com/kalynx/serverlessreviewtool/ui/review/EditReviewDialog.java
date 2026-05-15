package com.kalynx.serverlessreviewtool.ui.review;

import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.git.GitReviewNotesManager;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.models.ReviewContext;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.swingtheme.themedcomponents.ThemedOptionPane;
import com.kalynx.swingtheme.theme.LoadingStateManager;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.ReviewFormDialog;
import com.kalynx.serverlessreviewtool.ui.models.reviewpanel.reviewformdialog.ReviewFormModels;
import com.kalynx.serverlessreviewtool.models.review.StreamEntry;
import com.kalynx.swingtheme.utils.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class EditReviewDialog extends ReviewFormDialog {

    private static final Logger LOGGER = LoggerFactory.getLogger(EditReviewDialog.class);

    private final ReviewContext originalContext;
    private final ReviewContextManager reviewContextManager;
    private final LoadingStateManager loadingStateManager;
    private Runnable onReviewUpdated;

    private String lastSavedTitle;
    private String lastSavedAuthor;
    private String lastSavedSummary;
    private List<ReviewerInfo> lastSavedReviewers;
    private List<String> lastSavedRepositories;
    private boolean suppressRepositoryChangeHandling;

    public EditReviewDialog(Component parent,
                            ReviewContext context,
                            ReviewFormModels models,
                            RepositoryManager repositoryManager,
                            ReviewContextManager reviewContextManager,
                            Git git) {
        super(parent, "Edit Code Review", models, repositoryManager, git);
        this.originalContext = context;
        this.reviewContextManager = reviewContextManager;
        this.loadingStateManager = LoadingStateManager.getInstance();

        this.lastSavedTitle = context.title;
        this.lastSavedAuthor = context.author;
        this.lastSavedSummary = context.summary;
        this.lastSavedReviewers = new ArrayList<>(context.reviewers);
        this.lastSavedRepositories = context.repositories.stream()
            .map(Repository::getName)
            .collect(Collectors.toList());

        populateModelsFromContext(context);
        loadBranchInformationAndDisable(context);
        setupAutoSaveListeners();
    }

    private void loadBranchInformationAndDisable(ReviewContext context) {
        Repository primaryRepo = context.repositories.isEmpty() ? null : context.repositories.getFirst();
        if (primaryRepo == null) {
            sourcePanel.setEnabled(false);
            return;
        }

        GitReviewNotesManager notesManager = new GitReviewNotesManager(git, primaryRepo.getName());
        notesManager.readAllMetadata(context.reviewId)
            .thenAccept(metadata -> {
                String branch = getLatestValue(metadata.branches());
                String baseBranch = getLatestValue(metadata.baseBranches());

                SwingUtilities.invokeLater(() -> {
                    if (branch != null) {
                        sourcePanel.setBranchName(branch);
                    }
                    if (baseBranch != null) {
                        sourcePanel.setReviewAgainstBranch(baseBranch);
                    }
                    sourcePanel.setEnabled(false);
                });
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to load branch information", error);
                SwingUtilities.invokeLater(() -> sourcePanel.setEnabled(false));
                return null;
            });
    }
    private String getLatestValue(List<StreamEntry<String>> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.getLast().data();
    }

    @Override
    protected boolean shouldShowFooter() {
        return false;
    }

    @Override
    protected String getSubmitButtonLabel() {
        return "Save Changes";
    }

    @Override
    protected void onFormSubmit() {
        confirmed = true;
        dispose();
    }

    public void setOnReviewUpdated(Runnable callback) {
        this.onReviewUpdated = callback;
    }

    private void setupAutoSaveListeners() {
        detailsPanel.setupValidation(
            this::validateTitle,
            title -> saveField("title", () -> lastSavedTitle = title)
        );

        detailsPanel.setupAuthorValidation(
            this::validateAuthor,
            author -> saveField("author", () -> lastSavedAuthor = author)
        );

        detailsPanel.setupSummaryValidation(
            this::validateSummary,
            summary -> saveField("summary", () -> lastSavedSummary = summary)
        );

        models.selectedReviewers.addChangeListener(this::onReviewersChanged);
        models.selectedRepositories.addChangeListener(this::onRepositoriesChanged);
    }

    private void onReviewersChanged(List<ReviewerInfo> newReviewers) {
        if (newReviewers == null) {
            return;
        }

        Set<String> newReviewerNames = newReviewers.stream()
            .map(ReviewerInfo::getName)
            .collect(Collectors.toSet());

        Set<String> lastSavedReviewerNames = lastSavedReviewers.stream()
            .map(ReviewerInfo::getName)
            .collect(Collectors.toSet());

        if (!newReviewerNames.equals(lastSavedReviewerNames)) {
            saveReviewers(newReviewers);
        }
    }

    private void onRepositoriesChanged(List<String> newRepositories) {
        if (suppressRepositoryChangeHandling) {
            return;
        }
        if (newRepositories == null) {
            return;
        }

        List<String> normalizedRepositories = enforceAddOnlyRepositories(newRepositories);
        Set<String> newRepoSet = new HashSet<>(normalizedRepositories);
        Set<String> lastSavedSet = new HashSet<>(lastSavedRepositories);

        if (!newRepoSet.equals(lastSavedSet)) {
            saveRepositories(normalizedRepositories);
        }
    }

    private List<String> enforceAddOnlyRepositories(List<String> requestedRepositories) {
        Set<String> requestedSet = new HashSet<>(requestedRepositories);
        List<String> restored = new ArrayList<>(lastSavedRepositories);
        boolean removedAny = false;

        for (String savedRepository : lastSavedRepositories) {
            if (!requestedSet.contains(savedRepository)) {
                removedAny = true;
                break;
            }
        }

        for (String repositoryName : requestedRepositories) {
            if (!restored.contains(repositoryName)) {
                restored.add(repositoryName);
            }
        }

        if (removedAny) {
            ThemedOptionPane.showWarning(this, "Removing repositories from an existing review is not allowed. New repositories can still be added.");
            suppressRepositoryChangeHandling = true;
            try {
                models.selectedRepositories.setValue(new ArrayList<>(restored));
            } finally {
                suppressRepositoryChangeHandling = false;
            }
        }

        return restored;
    }

    private void saveReviewers(List<ReviewerInfo> reviewers) {
        String operationId = "edit-review-reviewers-" + UUID.randomUUID();
        loadingStateManager.startLoading(operationId);

        ReviewContext updatedContext = buildUpdatedContext();

        reviewContextManager.saveReviewMetadata(updatedContext)
            .thenRun(() -> SwingUtilities.invokeLater(() -> {
                loadingStateManager.stopLoading(operationId);
                lastSavedReviewers = new ArrayList<>(reviewers);
                if (onReviewUpdated != null) {
                    onReviewUpdated.run();
                }
            }))
            .exceptionally(error -> {
                SwingUtilities.invokeLater(() -> {
                    loadingStateManager.stopLoading(operationId);
                    handleReviewersSaveError(error);
                });
                return null;
            });
    }

    private void saveRepositories(List<String> repositories) {
        String operationId = "edit-review-repositories-" + UUID.randomUUID();
        loadingStateManager.startLoading(operationId);

        ReviewContext updatedContext = buildUpdatedContext();

        reviewContextManager.saveReviewMetadata(updatedContext)
            .thenRun(() -> SwingUtilities.invokeLater(() -> {
                loadingStateManager.stopLoading(operationId);
                lastSavedRepositories = new ArrayList<>(repositories);
                if (onReviewUpdated != null) {
                    onReviewUpdated.run();
                }
            }))
            .exceptionally(error -> {
                SwingUtilities.invokeLater(() -> {
                    loadingStateManager.stopLoading(operationId);
                    handleRepositoriesSaveError(error);
                });
                return null;
            });
    }

    private void handleReviewersSaveError(Throwable error) {
        String message = "Failed to save reviewers: " + error.getMessage();
        ThemedOptionPane.showError(this, message);
        models.selectedReviewers.setValue(new ArrayList<>(lastSavedReviewers));
    }

    private void handleRepositoriesSaveError(Throwable error) {
        String message = "Failed to save repositories: " + error.getMessage();
        ThemedOptionPane.showError(this, message);
        models.selectedRepositories.setValue(new ArrayList<>(lastSavedRepositories));
    }

    private void saveField(String fieldName, Runnable onSuccess) {
        String operationId = "edit-review-" + fieldName + "-" + UUID.randomUUID();
        loadingStateManager.startLoading(operationId);

        ReviewContext updatedContext = buildUpdatedContext();

        reviewContextManager.saveReviewMetadata(updatedContext)
            .thenRun(() -> SwingUtilities.invokeLater(() -> {
                loadingStateManager.stopLoading(operationId);
                onSuccess.run();
                if (onReviewUpdated != null) {
                    onReviewUpdated.run();
                }
            }))
            .exceptionally(error -> {
                SwingUtilities.invokeLater(() -> {
                    loadingStateManager.stopLoading(operationId);
                    handleSaveError(fieldName, error);
                });
                return null;
            });
    }

    private void handleSaveError(String fieldName, Throwable error) {
        String message = "Failed to save " + fieldName + ": " + error.getMessage();
        ThemedOptionPane.showError(this, message);

        switch (fieldName) {
            case "title":
                models.title.setValue(lastSavedTitle);
                break;
            case "author":
                models.author.setValue(lastSavedAuthor);
                break;
            case "summary":
                models.summary.setValue(lastSavedSummary);
                break;
        }
    }

    private Validator.ValidationResult validateTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return Validator.ValidationResult.invalid("Title cannot be empty");
        }
        return Validator.ValidationResult.valid();
    }

    private Validator.ValidationResult validateAuthor(String author) {
        if (isValidAuthor(author)) {
            return Validator.ValidationResult.invalid(getInvalidAuthorMessage());
        }
        return Validator.ValidationResult.valid();
    }

    private Validator.ValidationResult validateSummary(String summary) {
        return Validator.ValidationResult.valid();
    }

    private ReviewContext buildUpdatedContext() {
        List<ReviewerInfo> updatedReviewers = new ArrayList<>(originalContext.reviewers);
        Set<String> selectedReviewerNames = models.selectedReviewers.getValue().stream()
            .map(ReviewerInfo::getName)
            .collect(Collectors.toSet());

        updatedReviewers.removeIf(r -> !selectedReviewerNames.contains(r.getName()));
        
        Set<String> existingNames = updatedReviewers.stream()
            .map(ReviewerInfo::getName)
            .collect(Collectors.toSet());
        
        for (String name : selectedReviewerNames) {
            if (!existingNames.contains(name)) {
                updatedReviewers.add(new ReviewerInfo(name));
            }
        }

        List<Repository> updatedRepositories = new ArrayList<>();
        Set<String> selectedRepoNames = new HashSet<>(models.selectedRepositories.getValue());

        for (Repository repo : originalContext.repositories) {
            if (selectedRepoNames.contains(repo.getName())) {
                updatedRepositories.add(repo);
                selectedRepoNames.remove(repo.getName());
            }
        }
        
        for (String newRepoName : selectedRepoNames) {
            updatedRepositories.add(new Repository(newRepoName, "", ""));
        }

        return new ReviewContext(
            originalContext.reviewId,
            models.title.getValue(),
            models.summary.getValue(),
            models.author.getValue(),
            originalContext.status,
            updatedReviewers,
            updatedRepositories,
            originalContext.comments,
            originalContext.getBranch(),
            originalContext.getBaseBranch(),
            originalContext.hasClosedHistory()
        );
    }

    private void populateModelsFromContext(ReviewContext ctx) {
        models.title.setValue(ctx.title);
        String existingAuthor = ctx.author;
        if (existingAuthor != null && !existingAuthor.isBlank() && !"Unknown".equalsIgnoreCase(existingAuthor.trim())) {
            models.author.setValue(existingAuthor);
            SwingUtilities.invokeLater(() -> models.author.setValue(existingAuthor));
        }
        models.summary.setValue(ctx.summary);

        List<String> repoNames = ctx.repositories.stream()
            .map(Repository::getName)
            .collect(Collectors.toList());
        models.selectedRepositories.setValue(repoNames);

        models.selectedReviewers.setValue(new ArrayList<>(ctx.reviewers));
    }
}




package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.swingtheme.themedcomponents.*;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.reviewformdialog.*;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.reviewformdialog.ReviewDetailsPanel;
import com.kalynx.serverlessreviewtool.ui.models.reviewpanel.reviewformdialog.ReviewFormModels;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public abstract class ReviewFormDialog extends ThemedPopupDialog {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewFormDialog.class);

    private static final int DIALOG_W = 880;
    private static final int DIALOG_H = 720;
    private static final int GAP = 12;
    private static final int INSET = 16;

    protected final ThemeManager themeManager;
    protected final ReviewFormModels models;
    protected final RepositoryManager repositoryManager;
    protected final Git git;
    protected boolean confirmed = false;

    protected final ReviewDetailsPanel detailsPanel;
    protected final SourcePanel sourcePanel;
    protected final RepositoriesPanel repositoriesPanel;
    protected final ReviewersPanel reviewersPanel;

    protected ReviewFormDialog(Component parent,
                                String dialogTitle,
                                ReviewFormModels models,
                                RepositoryManager repositoryManager,
                                Git git) {
        super(parent, dialogTitle);
        this.themeManager = ThemeManager.getInstance();
        this.models = models;
        this.repositoryManager = repositoryManager;
        this.git = git;

        this.detailsPanel = new ReviewDetailsPanel(
            models.title,
            models.author,
            models.summary,
            models.availableReviewers
        );
        this.sourcePanel = new SourcePanel(
            models.availableBranches,
            models.selectedBranchModel,
            models.selectedBaseBranchModel
        );
        this.repositoriesPanel = new RepositoriesPanel(
            models.availableRepositories,
            models.selectedRepositories
        );
        this.reviewersPanel = new ReviewersPanel(
            models.availableReviewers,
            models.selectedReviewers
        );

        setDialogSize(themeManager.scale(DIALOG_W), themeManager.scale(DIALOG_H));
        setUserResizable(true);

        configureLayout();
        setupListeners();

        if (parent != null) {
            Point p = parent.getLocationOnScreen();
            setLocation(
                p.x + parent.getWidth() / 2 - getWidth() / 2,
                p.y + parent.getHeight() / 2 - getHeight() / 2
            );
        }
    }

    private void configureLayout() {
        ThemedPanel content = (ThemedPanel) getContentPanel();
        content.setLayout(new MigLayout(
            "fill, insets " + INSET + ", gap " + GAP + " " + GAP,
            "[grow]",
            "[grow]" + GAP + "[]" + GAP + "[]" + GAP + "[]"
        ));

        content.add(detailsPanel, "grow, wmin 0, wrap");
        content.add(createSelectionSection(), "grow, wmin 0, wrap");
        content.add(sourcePanel, "grow, wmin 0, wrap");

        if (shouldShowFooter()) {
            content.add(createFooter(), "growx");
        }
    }

    protected boolean shouldShowFooter() {
        return true;
    }

    private void setupListeners() {
        models.selectedRepositories.addChangeListener(repos -> SwingUtilities.invokeLater(() -> updateAvailableBranches(repos)));
        updateAvailableBranches(models.selectedRepositories.getValue());
    }

    private void updateAvailableBranches(List<String> selectedRepoNames) {
        if (selectedRepoNames == null || selectedRepoNames.isEmpty()) {
            models.availableBranches.setValue(new ArrayList<>());
            return;
        }

        List<Repository> allRepos = repositoryManager.getRepositories();
        List<Repository> selectedRepos = selectedRepoNames.stream()
            .map(name -> findRepositoryByName(allRepos, name))
            .filter(Objects::nonNull)
            .toList();

        if (selectedRepos.isEmpty()) {
            models.availableBranches.setValue(new ArrayList<>());
            return;
        }

        List<CompletableFuture<Map.Entry<String, List<String>>>> fetchFutures =
            selectedRepos.stream()
                .map(repo -> fetchBranchesWithFallback(repo.getName(), repo)
                    .thenApply(branches -> Map.entry(repo.getName(), branches))
                    .exceptionally(error -> {
                        LOGGER.error("Failed to fetch branches for {}", repo.getName(), error);
                        return Map.entry(repo.getName(), repo.getBranches());
                    }))
                .toList();

        CompletableFuture.allOf(fetchFutures.toArray(new CompletableFuture[0]))
            .thenAccept(ignored -> {
                Map<String, List<String>> branchesByRepo = fetchFutures.stream()
                    .map(CompletableFuture::join)
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                SwingUtilities.invokeLater(() -> {
                    repositoryManager.updateBranchesForRepositories(branchesByRepo);

                    List<Repository> updatedRepos = repositoryManager.getRepositories();
                    List<Repository> updatedSelectedRepos = selectedRepoNames.stream()
                        .map(name -> findRepositoryByName(updatedRepos, name))
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                    models.availableBranches.setValue(findCommonBranches(updatedSelectedRepos));
                });
            });
    }

    protected CompletableFuture<List<String>> fetchBranchesWithFallback(
            String repoName, Repository repo) {
        if (repo != null && repo.getUrl() != null && !repo.getUrl().isEmpty()) {
            return git.listBranchesRemote(repo.getUrl())
                .exceptionally(error -> {
                    LOGGER.debug("Failed to fetch branches remotely for {}, attempting local fetch", repoName, error);
                    return git.listBranches(repoName).join();
                });
        }
        return git.fetch(repoName)
            .thenCompose(ignored -> git.listBranches(repoName))
            .exceptionally(error -> {
                if (error.getMessage() != null && error.getMessage().contains("not found")) {
                    throw new RuntimeException("Repository " + repoName + " not cloned locally and no URL provided", error);
                }
                throw new RuntimeException(error);
            });
    }

    private Repository findRepositoryByName(List<Repository> repositories, String name) {
        return repositories.stream()
            .filter(repo -> repo.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    private List<String> findCommonBranches(List<Repository> repositories) {
        if (repositories.isEmpty()) {
            return new ArrayList<>();
        }

        if (repositories.size() == 1) {
            return new ArrayList<>(repositories.getFirst().getBranches());
        }

        List<String> commonBranches = new ArrayList<>(repositories.getFirst().getBranches());

        for (int i = 1; i < repositories.size(); i++) {
            List<String> repoBranches = repositories.get(i).getBranches();
            commonBranches.retainAll(repoBranches);
        }

        return commonBranches;
    }

    private ThemedPanel createSelectionSection() {
        ThemedPanel row = new ThemedPanel();
        row.setLayout(new MigLayout(
            "fill, insets 0, gap " + GAP + " 0",
            "[grow,fill,sg cols]" + GAP + "[grow,fill,sg cols]",
            "[grow,fill]"
        ));

        row.add(repositoriesPanel, "grow, wmin 0");
        row.add(reviewersPanel, "grow, wmin 0");

        return row;
    }

    private ThemedPanel createFooter() {
        ThemedPanel footer = new ThemedPanel();
        footer.setLayout(new MigLayout("insets 0, gap 8", "[grow,fill][][]", "[]"));
        footer.setOpaque(false);

        JSeparator sep = new JSeparator();
        sep.setForeground(themeManager.getCurrentTheme().getBorderColor());

        ThemedButton cancelBtn = new ThemedButton("Cancel");
        cancelBtn.addActionListener(this::dispose);

        ThemedButton submitBtn = new ThemedButton(getSubmitButtonLabel());
        submitBtn.setAccentStyle(true);
        submitBtn.addActionListener(this::handleSubmit);

        footer.add(sep, "growx, wrap, gapbottom 10, span");
        footer.add(Box.createGlue(), "growx");
        footer.add(cancelBtn);
        footer.add(submitBtn);
        return footer;
    }


    private void handleSubmit() {
        printReviewFormModels();

        if (detailsPanel.getTitle().trim().isEmpty()) {
            warn("Please enter a title for the review");
            return;
        }

        if (isValidAuthor(detailsPanel.getAuthor())) {
            warn(getInvalidAuthorMessage());
            return;
        }

        if (sourcePanel.getBranchName().trim().isEmpty()) {
            warn("Please enter a branch name to review");
            return;
        }

        if (sourcePanel.getReviewAgainstBranch() == null) {
            warn("Please select a branch to review against");
            return;
        }

        List<ReviewerInfo> reviewers = models.selectedReviewers.getValue();
        if (reviewers == null || reviewers.isEmpty()) {
            warn("Please select at least one reviewer");
            return;
        }

        onFormSubmit();
    }

    private void warn(String msg) {
        ThemedOptionPane.showWarning(this, msg);
    }

    private void printReviewFormModels() {
        LOGGER.debug("=== ReviewFormModels Values ===");
        LOGGER.debug("Review ID: {}", models.reviewId.getValue());
        LOGGER.debug("Title: {}", models.title.getValue());
        LOGGER.debug("Author: {}", models.author.getValue());
        LOGGER.debug("Summary: {}", models.summary.getValue());
        LOGGER.debug("Selected Branch: {}", models.selectedBranchModel.getValue());
        LOGGER.debug("Selected Base Branch: {}", models.selectedBaseBranchModel.getValue());
        LOGGER.debug("Available Branches: {}", models.availableBranches.getValue());
        LOGGER.debug("Selected Repositories: {}", models.selectedRepositories.getValue());
        LOGGER.debug("Available Repositories: {}", models.availableRepositories.getValue());
        LOGGER.debug("Selected Reviewers: {}", models.selectedReviewers.getValue());
        LOGGER.debug("Available Reviewers: {}", models.availableReviewers.getValue());
        LOGGER.debug("===============================");
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public String getAuthor() {
        return detailsPanel.getAuthor();
    }

    public String getSummary() {
        return detailsPanel.getSummary();
    }

    protected boolean isValidAuthor(String author) {
        if (author == null || author.trim().isEmpty()) {
            return true;
        }

        List<String> validAuthors = models.availableReviewers.getValue();
        if (validAuthors == null || validAuthors.isEmpty()) {
            return false;
        }

        String normalizedAuthor = author.trim();
        return validAuthors.stream().noneMatch(normalizedAuthor::equals);
    }

    protected String getInvalidAuthorMessage() {
        List<String> validAuthors = models.availableReviewers.getValue();
        if (validAuthors == null || validAuthors.isEmpty()) {
            return "Please enter an author name";
        }
        return "Please enter a valid author from the known users list";
    }

    protected abstract String getSubmitButtonLabel();

    protected abstract void onFormSubmit();
}

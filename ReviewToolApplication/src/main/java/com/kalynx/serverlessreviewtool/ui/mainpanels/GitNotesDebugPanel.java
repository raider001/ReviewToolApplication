package com.kalynx.serverlessreviewtool.ui.mainpanels;

import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.ui.mainpanels.gitnotesdebugpanel.RawContentPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.gitnotesdebugpanel.RefsListPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedCheckBox;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedList;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedScrollPane;
import com.kalynx.swingtheme.themedcomponents.ThemedSplitPane;
import com.kalynx.swingtheme.themedcomponents.ThemedTitledBorder;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * GitNotesDebugPanel provides a debug view for inspecting raw git notes data across
 * multiple repositories. Users select one or more repositories, optionally fetch the
 * latest notes from remotes, load all discovered notes refs, and view the raw NDJSON
 * content of each selected ref. A filter field inside the refs tree allows narrowing
 * results by path text.
 */
public class GitNotesDebugPanel extends ThemedPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitNotesDebugPanel.class);
    private static final String NOTES_REF_PREFIX = "refs/notes/reviews/";

    private final Git git;

    private final DefaultListModel<String> repositoryListModel = new DefaultListModel<>();
    private final ThemedList<String> repositoryList = new ThemedList<>();
    private final ThemedCheckBox fetchFirstCheckBox = new ThemedCheckBox("Fetch from remote first", false);
    private final ThemedButton loadButton = new ThemedButton("Load Notes");
    private final RefsListPanel refsListPanel = new RefsListPanel();
    private final RawContentPanel rawContentPanel = new RawContentPanel();

    private final Map<String, String> anchorCommitByRepo = new HashMap<>();

    /**
     * Constructs a GitNotesDebugPanel wired to the provided Git and RepositoryManager.
     *
     * @param git               the git executor
     * @param repositoryManager the repository provider
     */
    public GitNotesDebugPanel(Git git, RepositoryManager repositoryManager) {
        this.git = git;
        repositoryList.setModel(repositoryListModel);
        repositoryList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        repositoryList.setVisibleRowCount(4);
        configureLayout();
        setupListeners();
        repositoryManager.addListener(this::onRepositoriesChanged);
    }

    private void configureLayout() {
        setLayout(new MigLayout("fill, insets 0", "[grow]", "[][grow]"));

        ThemedPanel headerPanel = new ThemedPanel();
        headerPanel.setLayout(new MigLayout("insets 5", "[][grow 0][][]", "[]"));
        headerPanel.setBorder(ThemedTitledBorder.create("Git Notes Inspector"));
        headerPanel.add(new ThemedLabel("Repositories:"), "top");
        headerPanel.add(new ThemedScrollPane(repositoryList), "h 80!, w 230!, gapright 10, top");
        headerPanel.add(fetchFirstCheckBox, "top, gapright 10");
        headerPanel.add(loadButton, "top");

        ThemedSplitPane splitPane = new ThemedSplitPane(JSplitPane.HORIZONTAL_SPLIT, refsListPanel, rawContentPanel);
        splitPane.setDividerLocation(340);
        splitPane.setResizeWeight(0.28);

        add(headerPanel, "growx, wrap");
        add(splitPane, "grow");
    }

    private void setupListeners() {
        loadButton.addActionListener(_ -> onLoadClicked());
        refsListPanel.setOnRefSelected(this::onRefSelected);
    }

    private void onRepositoriesChanged(List<Repository> repositories) {
        SwingUtilities.invokeLater(() -> {
            repositoryListModel.clear();
            repositories.stream().map(Repository::getName).forEach(repositoryListModel::addElement);
            if (!repositoryListModel.isEmpty()) {
                repositoryList.setSelectionInterval(0, repositoryListModel.size() - 1);
            }
        });
    }

    private void onLoadClicked() {
        List<String> selectedRepos = repositoryList.getSelectedValuesList();
        if (selectedRepos.isEmpty()) {
            refsListPanel.setStatusMessage("No repositories selected.");
            return;
        }

        loadButton.setEnabled(false);
        refsListPanel.setStatusMessage("Loading…");
        rawContentPanel.clear();

        boolean fetchFirst = fetchFirstCheckBox.isSelected();

        List<CompletableFuture<RepoResult>> futures = selectedRepos.stream()
            .map(repoName -> loadRefsForRepo(repoName, fetchFirst))
            .toList();

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenAccept(_ -> {
                Map<String, List<String>> refsByRepo = new LinkedHashMap<>();
                Map<String, String> anchors = new LinkedHashMap<>();
                for (CompletableFuture<RepoResult> future : futures) {
                    RepoResult result = future.join();
                    refsByRepo.put(result.repoName(), result.refs());
                    if (result.anchorCommit() != null && !result.anchorCommit().isBlank()) {
                        anchors.put(result.repoName(), result.anchorCommit());
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    anchorCommitByRepo.clear();
                    anchorCommitByRepo.putAll(anchors);
                    refsListPanel.setRefs(refsByRepo);
                    loadButton.setEnabled(true);
                });
            })
            .exceptionally(ex -> {
                LOGGER.error("Failed to load notes refs", ex);
                refsListPanel.setStatusMessage("Error: " + unwrapMessage(ex));
                SwingUtilities.invokeLater(() -> loadButton.setEnabled(true));
                return null;
            });
    }

    private CompletableFuture<RepoResult> loadRefsForRepo(String repoName, boolean fetchFirst) {
        CompletableFuture<Void> fetchStep = fetchFirst
            ? fetchNotesFromRemote(repoName)
            : CompletableFuture.completedFuture(null);

        return fetchStep
            .thenCompose(_ -> resolveAnchorCommit(repoName))
            .thenCompose(anchorCommit ->
                git.executeAsync(repoName, "for-each-ref", "--format=%(refname)", NOTES_REF_PREFIX)
                    .thenApply(output -> {
                        List<String> refs = Arrays.stream(output.split("\n"))
                            .map(String::trim)
                            .filter(line -> !line.isBlank())
                            .sorted()
                            .toList();
                        return new RepoResult(repoName, anchorCommit, refs);
                    })
            )
            .exceptionally(ex -> {
                LOGGER.error("Failed to load refs for repo '{}'", repoName, ex);
                return new RepoResult(repoName, null, List.of());
            });
    }

    private void onRefSelected(String repoName, String ref) {
        String anchorCommit = anchorCommitByRepo.get(repoName);
        if (anchorCommit == null || anchorCommit.isBlank()) {
            return;
        }

        rawContentPanel.setContent("Loading…");
        git.executeAsync(repoName, "notes", "--ref=" + ref, "show", anchorCommit)
            .thenAccept(rawContentPanel::setContent)
            .exceptionally(ex -> {
                LOGGER.error("Failed to read notes ref '{}' in repository '{}'", ref, repoName, ex);
                rawContentPanel.setContent("Error reading ref:\n" + unwrapMessage(ex));
                return null;
            });
    }

    private CompletableFuture<Void> fetchNotesFromRemote(String repoName) {
        return git.executeAsync(repoName, "fetch", "origin",
                "+" + NOTES_REF_PREFIX + "*:" + NOTES_REF_PREFIX + "*")
            .thenApply(_ -> (Void) null)
            .exceptionally(ex -> {
                LOGGER.warn("Notes fetch from remote failed for '{}': {}", repoName, unwrapMessage(ex));
                return null;
            });
    }

    private CompletableFuture<String> resolveAnchorCommit(String repoName) {
        return git.executeAsync(repoName, "rev-list", "--max-parents=0", "HEAD")
            .thenApply(output -> output.trim().split("\n")[0].trim())
            .exceptionally(_ -> {
                LOGGER.warn("Could not resolve anchor commit from HEAD for '{}', falling back to remotes", repoName);
                return null;
            })
            .thenCompose(commit -> {
                if (commit != null && !commit.isBlank()) {
                    return CompletableFuture.completedFuture(commit);
                }
                return git.executeAsync(repoName, "rev-list", "--max-parents=0", "--remotes")
                    .thenApply(output -> output.trim().split("\n")[0].trim());
            })
            .exceptionally(_ -> {
                LOGGER.warn("Could not resolve any anchor commit for '{}'", repoName);
                return "";
            });
    }

    private String unwrapMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : ex.toString();
    }

    private record RepoResult(String repoName, String anchorCommit, List<String> refs) {}
}

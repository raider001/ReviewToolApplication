package com.kalynx.serverlessreviewtool.ui.review;

import java.io.Serial;

import com.kalynx.serverlessreviewtool.git.ReviewCloneManager;
import com.kalynx.serverlessreviewtool.models.*;
import com.kalynx.swingtheme.themedcomponents.ThemedComboBox;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedWindow;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.CodeViewerModel;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * CommitSelectorPanel - Custom dual-slider for commit range selection for specific files
 * Displays commits that touched the currently selected file and allows selection of commit range
 */
public class CommitSelectorPanel extends ThemedPanel {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(CommitSelectorPanel.class);

    private transient final CodeViewerModel codeViewerModel;
    private transient final ReviewCloneManager cloneManager;

    private ThemedComboBox<DiffViewMode> viewModeComboBox;
    private CommitSliderPanel commitSliderPanel;

    public CommitSelectorPanel(CodeViewerModel codeViewerModel, ReviewCloneManager cloneManager) {
        this.codeViewerModel = codeViewerModel;
        this.cloneManager = cloneManager;
        configureLayout();
        setupModelListeners();
    }

    private void configureLayout() {
        setLayout(new MigLayout("fillx, insets 0", "[grow]10[][]", "[]"));

        commitSliderPanel = new CommitSliderPanel();
        add(commitSliderPanel, "growx, h 50!");

        ThemedLabel viewLabel = new ThemedLabel("View:");
        viewModeComboBox = new ThemedComboBox<>(DiffViewMode.values());
        viewModeComboBox.setSelectedItem(DiffViewMode.UNIFIED);
        viewModeComboBox.addActionListener(this::onViewModeChanged);

        add(viewLabel);
        add(viewModeComboBox, "w 120!");
    }

    private void setupModelListeners() {
        codeViewerModel.selectedFile.addChangeListener(this::onFileSelected);
    }

    private void onFileSelected(ReviewFile file) {
        if (file != null) {
            loadCommitsForFile(file);
        } else {
            commitSliderPanel.setCommits(new ArrayList<>());
        }
    }

    private void loadCommitsForFile(ReviewFile file) {
        String repositoryName = file.getRepository();
        String filePath = file.getPath();

        String branch = codeViewerModel.reviewBranch.getValue();
        String baseBranch = codeViewerModel.reviewBaseBranch.getValue();
        if (branch == null || branch.isBlank()) {
            LOGGER.warn("No review branch set, cannot load file commits");
            return;
        }

        LOGGER.debug("=== LOADING COMMITS FOR FILE ===");
        LOGGER.debug("File: {} in repository: {}", filePath, repositoryName);
        LOGGER.debug("Review Branch: {}", branch);
        LOGGER.debug("Base Branch: {}", baseBranch);

        resolveHistoryRefs(repositoryName, baseBranch, branch)
            .thenCompose(refs -> loadCommitsWithFallback(repositoryName, filePath, refs.baseRef(), refs.branchRef()))
            .thenApply(output -> parseCommits(output.lines().toList()))
            .thenCompose(commits -> {
                LOGGER.debug("Loaded {} commits for file {}", commits.size(), filePath);

                if (!commits.isEmpty()) {
                    LOGGER.debug("First commit: {} - {}", commits.getFirst().getShortHash(), commits.getFirst().getMessage());
                    LOGGER.debug("Last commit: {} - {}", commits.getLast().getShortHash(), commits.getLast().getMessage());
                }

                if (commits.isEmpty()) {
                    commitSliderPanel.setCommits(new ArrayList<>());
                    return CompletableFuture.completedFuture(null);
                }

                Commit oldestCommit = commits.getLast();
                Commit newestCommit = commits.getFirst();
                return resolveBaselineCommit(repositoryName, oldestCommit)
                    .thenAccept(baseline -> {
                        List<Commit> reversedCommits = new ArrayList<>(commits);
                        java.util.Collections.reverse(reversedCommits);

                        if (baseline != null && reversedCommits.stream().noneMatch(c -> c.getHash().equals(baseline.getHash()))) {
                            reversedCommits.addFirst(baseline);
                        }

                        commitSliderPanel.setCommits(reversedCommits);
                        commitSliderPanel.setStartIndex(0);
                        commitSliderPanel.setEndIndex(reversedCommits.size() - 1);

                        Commit start = baseline != null ? baseline : oldestCommit;
                        LOGGER.debug("Setting initial commit range: {} -> {}", start.getShortHash(), newestCommit.getShortHash());
                        fireCommitRangeChanged(start, newestCommit);
                    });
            })
            .exceptionally(error -> {
                LOGGER.error("Failed to load commits for file {}: {}", filePath, error.getMessage());
                commitSliderPanel.setCommits(new ArrayList<>());
                return null;
            });
    }

    private CompletableFuture<ResolvedRefs> resolveHistoryRefs(String repositoryName, String baseBranch, String branch) {
        CompletableFuture<String> resolvedBranch = resolveReviewBranchRef(repositoryName, branch);
        CompletableFuture<String> resolvedBase = resolveBaseBranchRef(repositoryName, baseBranch);
        return resolvedBase.thenCombine(resolvedBranch, ResolvedRefs::new);
    }

    private CompletableFuture<String> resolveReviewBranchRef(String repositoryName, String branch) {
        return resolveCommitHashFromCandidates(repositoryName, buildRefCandidates(branch))
            .handle((resolved, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(resolved);
                }
                LOGGER.warn("Unable to resolve review branch {} for repository {}. Falling back to HEAD", branch, repositoryName);
                return resolveCommitHashFromCandidates(repositoryName, List.of("HEAD"));
            })
            .thenCompose(future -> future);
    }

    private CompletableFuture<String> resolveBaseBranchRef(String repositoryName, String baseBranch) {
        if (baseBranch == null || baseBranch.isBlank()) {
            return cloneManager.getDefaultBranch(repositoryName)
                .thenCompose(defaultBranch -> resolveCommitHashFromCandidates(repositoryName, buildRefCandidates(defaultBranch)))
                .handle((resolved, error) -> {
                    if (error == null) {
                        return CompletableFuture.completedFuture(resolved);
                    }
                    LOGGER.warn("Unable to resolve default base branch for repository {}. Falling back to HEAD", repositoryName);
                    return resolveCommitHashFromCandidates(repositoryName, List.of("HEAD"));
                })
                .thenCompose(future -> future);
        }

        return resolveCommitHashFromCandidates(repositoryName, buildRefCandidates(baseBranch))
            .handle((resolved, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(resolved);
                }
                LOGGER.warn("Unable to resolve base branch {} for repository {}. Falling back to default branch", baseBranch, repositoryName);
                return cloneManager.getDefaultBranch(repositoryName)
                    .thenCompose(defaultBranch -> resolveCommitHashFromCandidates(repositoryName, buildRefCandidates(defaultBranch)))
                    .exceptionallyCompose(ignored -> resolveCommitHashFromCandidates(repositoryName, List.of("HEAD")));
            })
            .thenCompose(future -> future);
    }

    private List<String> buildRefCandidates(String ref) {
        if (ref == null || ref.isBlank()) {
            return List.of();
        }

        Set<String> candidates = new LinkedHashSet<>();
        String trimmed = ref.trim();
        String normalized = normalizeRef(trimmed);

        candidates.add(trimmed);
        candidates.add(normalized);
        candidates.add("origin/" + normalized);
        candidates.add("refs/heads/" + normalized);
        candidates.add("refs/remotes/origin/" + normalized);
        candidates.add("refs/remotes/" + normalized);

        return candidates.stream()
            .filter(candidate -> candidate != null && !candidate.isBlank())
            .toList();
    }

    private String normalizeRef(String ref) {
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        if (ref.startsWith("refs/remotes/origin/")) {
            return ref.substring("refs/remotes/origin/".length());
        }
        if (ref.startsWith("origin/")) {
            return ref.substring("origin/".length());
        }
        return ref;
    }

    private CompletableFuture<String> resolveCommitHashFromCandidates(String repositoryName, List<String> candidates) {
        return tryResolveCommitHash(repositoryName, candidates, 0);
    }

    private CompletableFuture<String> tryResolveCommitHash(String repositoryName, List<String> candidates, int index) {
        if (index >= candidates.size()) {
            return CompletableFuture.failedFuture(new RuntimeException("Unable to resolve commit ref in repository " + repositoryName));
        }

        String candidate = candidates.get(index);
        return cloneManager.execute(repositoryName, "rev-parse", "--verify", candidate + "^{commit}")
            .thenApply(String::trim)
            .handle((resolved, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(resolved);
                }
                return tryResolveCommitHash(repositoryName, candidates, index + 1);
            })
            .thenCompose(future -> future);
    }

    private CompletableFuture<String> loadCommitsWithFallback(String repositoryName, String filePath, String baseCommit, String branchCommit) {
        String commitRange = baseCommit + ".." + branchCommit;
        return cloneManager.execute(repositoryName, "log", "--format=%H|%an|%ad|%s", "--date=short", "--follow", commitRange, "--", filePath)
            .handle((output, error) -> {
                if (error == null) {
                    return CompletableFuture.completedFuture(output);
                }

                if (isBadRevisionError(error)) {
                    LOGGER.warn("Invalid commit range {} for repository {}. Falling back to branch-only file history for {}", commitRange, repositoryName, filePath);
                    return cloneManager.execute(repositoryName, "log", "--format=%H|%an|%ad|%s", "--date=short", "--follow", branchCommit, "--", filePath);
                }

                return CompletableFuture.<String>failedFuture(error);
            })
            .thenCompose(future -> future);
    }

    private record ResolvedRefs(String baseRef, String branchRef) {}

    private boolean isBadRevisionError(Throwable error) {
        String message = error.getMessage();
        return message != null && message.contains("bad revision");
    }

    private List<Commit> parseCommits(List<String> commitStrings) {
        List<Commit> commits = new ArrayList<>();
        for (String commitString : commitStrings) {
            try {
                String[] parts = commitString.split("\\|", 4);
                if (parts.length >= 4) {
                    commits.add(new Commit(parts[0], parts[3], parts[1], parts[2]));
                }
            } catch (Exception e) {
                LOGGER.error("Error parsing commit line: {}", commitString, e);
            }
        }
        return commits;
    }

    private CompletableFuture<Commit> resolveBaselineCommit(String repositoryName, Commit oldestCommit) {
        String parentRef = oldestCommit.getHash() + "^";
        return cloneManager.execute(repositoryName, "rev-parse", parentRef)
            .thenApply(String::trim)
            .thenCompose(parentHash -> cloneManager.execute(repositoryName,
                "show", "-s", "--format=%H|%an|%ad|%s", "--date=short", parentHash)
                .thenApply(this::parseCommit)
                .exceptionally(ignored -> new Commit(
                    parentHash,
                    "Baseline (parent of " + oldestCommit.getShortHash() + ")",
                    oldestCommit.getAuthor(),
                    oldestCommit.getDate()
                )))
            .exceptionally(ignored -> oldestCommit);
    }

    private Commit parseCommit(String output) {
        String[] lines = output.split("\\R");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 4);
            if (parts.length >= 4) {
                return new Commit(parts[0], parts[3], parts[1], parts[2]);
            }
        }
        throw new IllegalArgumentException("Unable to parse commit output: " + output);
    }

    private void onViewModeChanged() {
        DiffViewMode mode = (DiffViewMode) viewModeComboBox.getSelectedItem();
        if (mode != null) {
            if (mode == DiffViewMode.SIDE_BY_SIDE) {
                codeViewerModel.setDiffMode(CodeViewerModel.DiffMode.SIDE_BY_SIDE);
            } else {
                codeViewerModel.setDiffMode(CodeViewerModel.DiffMode.UNIFIED);
            }
        }
    }

    private void fireCommitRangeChanged(Commit startCommit, Commit endCommit) {
        LOGGER.debug("=== FIRING COMMIT RANGE CHANGED ===");
        LOGGER.debug("Start: {} - {}", startCommit.getShortHash(), startCommit.getMessage());
        LOGGER.debug("End: {} - {}", endCommit.getShortHash(), endCommit.getMessage());
        codeViewerModel.setCommitRange(startCommit, endCommit);
    }



    /**
     * Custom slider panel with two thumbs for selecting commit range
     */
    private class CommitSliderPanel extends ThemedPanel {
        @Serial
        private static final long serialVersionUID = 1L;

        private transient List<Commit> commits = new ArrayList<>();
        private int startIndex = 0;
        private int endIndex = 0;
        private int dragThumb = -1;
        private final int THUMB_RADIUS = themeManager.scale(6);
        private final int TRACK_HEIGHT = themeManager.scale(3);
        private final int MARGIN = themeManager.scale(20);

        private ThemedWindow tooltipWindow;
        private ThemedLabel tooltipLabel;

        public CommitSliderPanel() {
            setupMouseListeners();
            createTooltipWindow();
        }

        private void createTooltipWindow() {
            tooltipWindow = new ThemedWindow();

            tooltipLabel = new ThemedLabel();
            tooltipLabel.setFont(new Font("Segoe UI", Font.PLAIN, themeManager.scale(11)));

            tooltipWindow.setContent(tooltipLabel);
        }

        private void setupMouseListeners() {
            MouseAdapter mouseAdapter = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (commits.isEmpty()) return;

                    int startThumbX = getThumbX(startIndex);
                    int endThumbX = getThumbX(endIndex);
                    int thumbY = getHeight() / 2 + themeManager.scale(5);

                    LOGGER.debug("Mouse pressed at x={}, startThumbX={}, endThumbX={}", e.getX(), startThumbX, endThumbX);

                    if (isNearPoint(e.getX(), e.getY(), startThumbX, thumbY, THUMB_RADIUS)) {
                        dragThumb = 0;
                        LOGGER.debug("Selected START thumb (dragThumb=0), current startIndex={}", startIndex);
                        showTooltipForIndex(startIndex);
                    } else if (isNearPoint(e.getX(), e.getY(), endThumbX, thumbY, THUMB_RADIUS)) {
                        dragThumb = 1;
                        LOGGER.debug("Selected END thumb (dragThumb=1), current endIndex={}", endIndex);
                        showTooltipForIndex(endIndex);
                    } else {
                        int index = getIndexFromX(e.getX());
                        LOGGER.debug("Clicked on track (not on thumb), index={}", index);
                        showTooltipForIndex(index);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (dragThumb != -1 && !commits.isEmpty()) {
                        Commit start = commits.get(startIndex);
                        Commit end = commits.get(endIndex);
                        LOGGER.debug("Mouse released - dragThumb was {}, Final: startIndex={} ({}), endIndex={} ({})",
                            dragThumb, startIndex, start.getShortHash(), endIndex, end.getShortHash());
                        fireCommitRangeChanged(start, end);
                    }
                    dragThumb = -1;
                    hideTooltip();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragThumb == -1 || commits.isEmpty()) return;

                    int newIndex = getIndexFromX(e.getX());

                    if (dragThumb == 0) {
                        int oldStartIndex = startIndex;
                        startIndex = Math.max(0, Math.min(newIndex, endIndex - 1));
                        if (oldStartIndex != startIndex) {
                            LOGGER.debug("Dragging START thumb: index {} -> {} (endIndex={})",
                                oldStartIndex, startIndex, endIndex);
                        }
                        showTooltipForIndex(startIndex);
                    } else {
                        int oldEndIndex = endIndex;
                        endIndex = Math.max(startIndex + 1, Math.min(newIndex, commits.size() - 1));
                        if (oldEndIndex != endIndex) {
                            LOGGER.debug("Dragging END thumb: index {} -> {} (startIndex={})",
                                oldEndIndex, endIndex, startIndex);
                        }
                        showTooltipForIndex(endIndex);
                    }

                    repaint();
                }
            };

            addMouseListener(mouseAdapter);
            addMouseMotionListener(mouseAdapter);
        }

        private void showTooltipForIndex(int index) {
            if (commits.isEmpty()) return;

            index = Math.max(0, Math.min(index, commits.size() - 1));
            Commit commit = commits.get(index);

            String tooltipText = String.format(
                "<html><b>%s</b><br/>%s<br/><i>%s - %s</i></html>",
                commit.getHash().substring(0, Math.min(7, commit.getHash().length())),
                commit.getMessage(),
                commit.getAuthor(),
                commit.getDate()
            );
            tooltipLabel.setText(tooltipText);

            tooltipWindow.pack();

            Point locationOnScreen = getLocationOnScreen();
            int thumbX = getThumbX(index);
            int thumbY = getHeight() / 2 + themeManager.scale(5);

            int tooltipX = locationOnScreen.x + thumbX - tooltipWindow.getWidth() / 2;
            int tooltipY = locationOnScreen.y + thumbY - tooltipWindow.getHeight() - themeManager.scale(10);

            tooltipWindow.showAt(tooltipX, tooltipY);
        }

        private void hideTooltip() {
            if (tooltipWindow != null) {
                tooltipWindow.setVisible(false);
            }
        }


        private boolean isNearPoint(int x, int y, int centerX, int centerY, int radius) {
            int dx = x - centerX;
            int dy = y - centerY;
            return Math.sqrt(dx * dx + dy * dy) <= radius + 5;
        }

        private int getThumbX(int index) {
            if (commits.size() <= 1) return MARGIN;
            int trackWidth = getWidth() - 2 * MARGIN;
            return MARGIN + (index * trackWidth) / (commits.size() - 1);
        }

        private int getIndexFromX(int x) {
            if (commits.size() <= 1) return 0;
            int trackWidth = getWidth() - 2 * MARGIN;
            int index = Math.round((float) (x - MARGIN) * (commits.size() - 1) / trackWidth);
            return Math.max(0, Math.min(index, commits.size() - 1));
        }

        public void setCommits(List<Commit> commits) {
            this.commits = new ArrayList<>(commits);
            this.startIndex = 0;
            this.endIndex = Math.max(0, commits.size() - 1);
            repaint();
        }

        public void setStartIndex(int index) {
            this.startIndex = Math.max(0, Math.min(index, commits.size() - 1));
            repaint();
        }

        public void setEndIndex(int index) {
            this.endIndex = Math.max(0, Math.min(index, commits.size() - 1));
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (commits.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            Theme theme = themeManager.getCurrentTheme();
            int width = getWidth();
            int height = getHeight();
            int centerY = height / 2 + themeManager.scale(5);
            int trackWidth = width - 2 * MARGIN;
            int trackArc = themeManager.scale(8);

            g2.setFont(new Font("Consolas", Font.PLAIN, themeManager.scale(9)));
            FontMetrics fm = g2.getFontMetrics();

            int[] visibleIndices = getVisibleCommitIndices();
            for (int i : visibleIndices) {
                if (i >= 0 && i < commits.size()) {
                    int x = getThumbX(i);
                    String hash = commits.get(i).getHash().substring(0, Math.min(6, commits.get(i).getHash().length()));
                    int textWidth = fm.stringWidth(hash);

                    g2.setColor(theme.getSecondaryTextColor());
                    g2.drawString(hash, x - textWidth / 2, centerY - themeManager.scale(12));

                    g2.setColor(theme.getBorderColor());
                    g2.fillOval(x - 2, centerY - TRACK_HEIGHT / 2 - 2, 4, 4);
                }
            }

            g2.setColor(theme.getBorderColor());
            g2.fillRoundRect(MARGIN, centerY - TRACK_HEIGHT / 2, trackWidth, TRACK_HEIGHT, trackArc, trackArc);

            int startX = getThumbX(startIndex);
            int endX = getThumbX(endIndex);
            g2.setColor(theme.getAccentColor());
            g2.fillRoundRect(startX, centerY - TRACK_HEIGHT / 2, endX - startX, TRACK_HEIGHT, trackArc, trackArc);

            drawThumb(g2, startX, centerY, startIndex == dragThumb, theme);
            drawThumb(g2, endX, centerY, endIndex == dragThumb, theme);

            g2.dispose();
        }

        private int[] getVisibleCommitIndices() {
            if (commits.size() <= 3) {
                int[] indices = new int[commits.size()];
                for (int i = 0; i < commits.size(); i++) {
                    indices[i] = i;
                }
                return indices;
            } else if (commits.size() <= 10) {
                return new int[]{0, commits.size() / 2, commits.size() - 1};
            } else {
                return new int[]{0, commits.size() - 1};
            }
        }

        private void drawThumb(Graphics2D g2, int x, int y, boolean isDragging, Theme theme) {
            int radius = isDragging ? THUMB_RADIUS + 2 : THUMB_RADIUS;

            g2.setColor(theme.getBackgroundColor());
            g2.fillOval(x - radius, y - radius, radius * 2, radius * 2);

            g2.setColor(theme.getAccentColor());
            g2.setStroke(new BasicStroke(themeManager.scale(2)));
            g2.drawOval(x - radius, y - radius, radius * 2, radius * 2);

            if (isDragging) {
                g2.setColor(new Color(theme.getAccentColor().getRed(),
                                      theme.getAccentColor().getGreen(),
                                      theme.getAccentColor().getBlue(), 80));
                g2.fillOval(x - radius - 3, y - radius - 3, (radius + 3) * 2, (radius + 3) * 2);
            }
        }
    }
}

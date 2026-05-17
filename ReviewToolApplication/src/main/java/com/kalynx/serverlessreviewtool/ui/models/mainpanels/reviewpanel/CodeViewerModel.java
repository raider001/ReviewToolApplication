package com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.models.Commit;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.swingtheme.ComponentModel;

import java.util.ArrayList;
import java.util.List;

public class CodeViewerModel {

    public enum DiffMode {
        SIDE_BY_SIDE,
        UNIFIED
    }

    /**
     * Holds all three content values that make up a diff view update.
     * Fired as a single atomic event to prevent race conditions between separate listeners.
     *
     * @param left    before-content for the left pane
     * @param right   after-content for the right pane
     * @param unified unified diff string
     */
    public record FileContent(String left, String right, String unified) {
    }

    public final ComponentModel<List<ReviewFile>> availableFiles = new ComponentModel<>();
    public final ComponentModel<ReviewFile> selectedFile = new ComponentModel<>();
    public final ComponentModel<DiffMode> diffMode = new ComponentModel<>();

    public final ComponentModel<Commit> startCommit = new ComponentModel<>();
    public final ComponentModel<Commit> endCommit = new ComponentModel<>();
    public final ComponentModel<List<Commit>> availableCommits = new ComponentModel<>();

    public final ComponentModel<String> reviewBranch = new ComponentModel<>();
    public final ComponentModel<String> reviewBaseBranch = new ComponentModel<>();

    public final ComponentModel<String> leftContent = new ComponentModel<>();
    public final ComponentModel<String> rightContent = new ComponentModel<>();
    public final ComponentModel<String> unifiedDiffContent = new ComponentModel<>();
    public final ComponentModel<FileContent> fileContent = new ComponentModel<>();

    public final ComponentModel<Integer> selectedLine = new ComponentModel<>();
    public final ComponentModel<Boolean> isLoadingFile = new ComponentModel<>();

    public CodeViewerModel() {
        initializeDefaults();
    }

    private void initializeDefaults() {
        availableFiles.setValue(new ArrayList<>());
        selectedFile.setValue(null);
        diffMode.setValue(DiffMode.UNIFIED);

        startCommit.setValue(null);
        endCommit.setValue(null);
        availableCommits.setValue(new ArrayList<>());
        
        reviewBranch.setValue(null);
        reviewBaseBranch.setValue(null);

        leftContent.setValue("");
        rightContent.setValue("");
        unifiedDiffContent.setValue("");
        selectedLine.setValue(-1);
        isLoadingFile.setValue(false);
    }

    public void clear() {
        initializeDefaults();
    }

    public void setAvailableFiles(List<ReviewFile> files) {
        availableFiles.setValue(files != null ? new ArrayList<>(files) : new ArrayList<>());
        if (files != null && !files.isEmpty() && selectedFile.getValue() == null) {
            selectedFile.setValue(files.getFirst());
        }
    }

    public void setAvailableCommits(List<Commit> commits) {
        availableCommits.setValue(commits != null ? new ArrayList<>(commits) : new ArrayList<>());
    }

    public void selectFile(ReviewFile file) {
        selectedFile.setValue(file);
        selectedLine.setValue(-1);
    }

    public void setCommitRange(Commit start, Commit end) {
        startCommit.setValue(start);
        endCommit.setValue(end);
    }

    public void setReviewBranches(String branch, String baseBranch) {
        reviewBranch.setValue(branch);
        reviewBaseBranch.setValue(baseBranch);
    }

    public void setDiffMode(DiffMode mode) {
        diffMode.setValue(mode);
    }

    public void setFileContent(String left, String right, String unified) {
        String safeLeft = left != null ? left : "";
        String safeRight = right != null ? right : "";
        String safeUnified = unified != null ? unified : "";
        leftContent.setValue(safeLeft);
        rightContent.setValue(safeRight);
        unifiedDiffContent.setValue(safeUnified);
        fileContent.setValue(new FileContent(safeLeft, safeRight, safeUnified));
    }
}


package com.kalynx.serverlessreviewtool.ui.review;

import java.io.Serial;

import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.models.ReviewComment;
import com.kalynx.serverlessreviewtool.models.ReviewFile;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.CodeViewerModel;
import com.kalynx.serverlessreviewtool.ui.review.diffviewerpanel.SideBySidePanel;
import com.kalynx.serverlessreviewtool.ui.review.diffviewerpanel.UnifiedDiffPanel;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * DiffViewerPanel is a container that switches between side-by-side and unified diff views.
 * Content is driven by a single combined event on the CodeViewerModel to avoid race conditions.
 */
public class DiffViewerPanel extends ThemedPanel {
    @Serial
    private static final long serialVersionUID = 1L;

    private final CodeViewerModel codeViewerModel;
    private final SideBySidePanel sideBySidePanel;
    private final UnifiedDiffPanel unifiedDiffPanel;
    private final ThemedPanel contentPanel = new ThemedPanel(new CardLayout());

    private DiffViewMode currentMode = DiffViewMode.UNIFIED;
    private ReviewFile currentFile;
    private volatile int pendingTopLineRestore = -1;

    /**
     * @param codeViewerModel model providing file content and diff state
     * @param pluginManager   plugin manager used to resolve syntax highlighters
     */
    public DiffViewerPanel(CodeViewerModel codeViewerModel, PluginManager pluginManager) {
        this.codeViewerModel = codeViewerModel;
        this.sideBySidePanel = new SideBySidePanel(pluginManager);
        this.unifiedDiffPanel = new UnifiedDiffPanel(pluginManager);
        configureLayout();
        setupModelListeners();
    }

    private void configureLayout() {
        setLayout(new BorderLayout());
        contentPanel.add(sideBySidePanel, DiffViewMode.SIDE_BY_SIDE.name());
        contentPanel.add(unifiedDiffPanel, DiffViewMode.UNIFIED.name());
        add(contentPanel, BorderLayout.CENTER);
        showCard(currentMode);
    }

    private void setupModelListeners() {
        codeViewerModel.fileContent.addChangeListener(this::onFileContentChanged);
        codeViewerModel.diffMode.addChangeListener(mode -> {
            DiffViewMode viewMode = mode == CodeViewerModel.DiffMode.UNIFIED
                    ? DiffViewMode.UNIFIED
                    : DiffViewMode.SIDE_BY_SIDE;
            setViewMode(viewMode);
        });
        codeViewerModel.selectedFile.addChangeListener(file -> this.currentFile = file);
    }

    private void onFileContentChanged(CodeViewerModel.FileContent content) {
        if (content == null) return;
        ReviewFile file = currentFile;
        SwingUtilities.invokeLater(() -> {
            sideBySidePanel.render(content.left(), content.right(), content.unified(), file);
            unifiedDiffPanel.render(content.right(), content.unified(), file);
            applyPendingTopLineRestore();
        });
    }

    /**
     * Switches to the specified view mode.
     *
     * @param mode the view mode to activate
     */
    public void setViewMode(DiffViewMode mode) {
        if (mode != null && mode != currentMode) {
            currentMode = mode;
            showCard(currentMode);
        }
    }

    /**
     * Clears both diff panes and resets state.
     */
    public void clear() {
        sideBySidePanel.clear();
        unifiedDiffPanel.clear();
        currentFile = null;
    }

    /**
     * Updates comment annotations in all active panes.
     *
     * @param comments comments for the currently viewed file
     */
    public void setCommentsForCurrentFile(List<ReviewComment> comments) {
        sideBySidePanel.setComments(comments);
        unifiedDiffPanel.setComments(comments);
    }

    /**
     * Registers a double-click listener on all diff panes.
     *
     * @param listener consumer receiving 1-based line numbers
     */
    public void setOnLineDoubleClickListener(Consumer<Integer> listener) {
        sideBySidePanel.setOnLineDoubleClickListener(listener);
        unifiedDiffPanel.setOnLineDoubleClickListener(listener);
    }

    /**
     * Returns the first visible line number in the active diff pane.
     *
     * @return 1-based line number, or -1 when unavailable
     */
    public int getTopVisibleLine() {
        if (currentMode == DiffViewMode.UNIFIED) {
            return unifiedDiffPanel.getTopVisibleLine();
        }
        return sideBySidePanel.getTopVisibleLine();
    }

    /**
     * Schedules a scroll to the given line after the next content render.
     *
     * @param lineNumber 1-based line number to align at the top of the viewport
     */
    public void scrollToTopLine(int lineNumber) {
        if (lineNumber <= 0) return;
        pendingTopLineRestore = lineNumber;
        SwingUtilities.invokeLater(this::applyPendingTopLineRestore);
    }

    private void applyPendingTopLineRestore() {
        int target = pendingTopLineRestore;
        if (target <= 0) return;
        if (currentMode == DiffViewMode.UNIFIED) {
            unifiedDiffPanel.scrollToLine(target);
        } else {
            sideBySidePanel.scrollToLine(target);
        }
        pendingTopLineRestore = -1;
    }

    private void showCard(DiffViewMode mode) {
        ((CardLayout) contentPanel.getLayout()).show(contentPanel, mode.name());
    }
}


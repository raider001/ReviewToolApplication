package com.kalynx.serverlessreviewtool.ui.review;

import java.io.Serial;

import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.models.*;
import com.kalynx.serverlessreviewtool.plugin.SyntaxHighlighterPlugin;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.LineNumberedTextPane;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedPanel;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedScrollPane;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedSplitPane;
import com.kalynx.serverlessreviewtool.theme.ThemeManager;
import com.kalynx.serverlessreviewtool.theme.Theme;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.CodeViewerModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.List;

/**
 * DiffViewerPanel - Shows file diffs in side-by-side or unified mode
 */
public class DiffViewerPanel extends ThemedPanel {
    @Serial
    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(DiffViewerPanel.class);

    private transient final ThemeManager themeManager = ThemeManager.getInstance();
    private transient final CodeViewerModel codeViewerModel;
    private transient final PluginManager pluginManager;

    private DiffViewMode currentMode = DiffViewMode.SIDE_BY_SIDE;
    private ThemedPanel contentPanel;

    private LineNumberedTextPane leftPane;
    private LineNumberedTextPane rightPane;
    private LineNumberedTextPane unifiedPane;

    private transient ReviewFile currentFile;
    private transient Commit startCommit;
    private transient Commit endCommit;
    private transient Theme lastRenderedTheme;
    private volatile int pendingTopLineRestore = -1;
    private volatile boolean syncingScrollBars;

    public DiffViewerPanel(CodeViewerModel codeViewerModel, PluginManager pluginManager) {
        this.codeViewerModel = codeViewerModel;
        this.pluginManager = pluginManager;
        setLayout(new BorderLayout());
        initializeComponents();
        setupModelListeners();
    }

    private void initializeComponents() {

        contentPanel = new ThemedPanel(new CardLayout());

        // Create side-by-side view
        createSideBySideView();

        // Create unified view
        createUnifiedView();

        add(contentPanel, BorderLayout.CENTER);

        // Show initial view
        switchViewMode();
    }

    private void setupModelListeners() {
        codeViewerModel.leftContent.addChangeListener(_ -> updateLeftContent());
        codeViewerModel.rightContent.addChangeListener(_ -> updateRightContent());
        codeViewerModel.unifiedDiffContent.addChangeListener(_ -> updateUnifiedContent());
        codeViewerModel.diffMode.addChangeListener(mode -> {
            if (mode == CodeViewerModel.DiffMode.SIDE_BY_SIDE) {
                setViewMode(DiffViewMode.SIDE_BY_SIDE);
            } else {
                setViewMode(DiffViewMode.UNIFIED);
            }
        });
        codeViewerModel.selectedFile.addChangeListener(file -> {
            this.currentFile = file;
            this.startCommit = codeViewerModel.startCommit.getValue();
            this.endCommit = codeViewerModel.endCommit.getValue();
        });
        codeViewerModel.startCommit.addChangeListener(commit -> this.startCommit = commit);
        codeViewerModel.endCommit.addChangeListener(commit -> this.endCommit = commit);
    }

    private void updateLeftContent() {
        String leftContent = codeViewerModel.leftContent.getValue();
        String rightContent = codeViewerModel.rightContent.getValue();

        if (leftContent != null && rightContent != null && leftPane != null && rightPane != null) {
            if (leftContent.isEmpty()) {
                LOGGER.warn("[DiffViewerPanel] Left content is empty");
            } else {
                LOGGER.debug("[DiffViewerPanel] Updating left content with highlighting: {} chars", leftContent.length());
            }

            SwingUtilities.invokeLater(() -> {
                if (currentMode == DiffViewMode.SIDE_BY_SIDE) {
                    highlightDiffWithInlineChanges(leftPane, rightPane, leftContent, rightContent);
                    applyPendingTopLineRestore();
                }
            });
        }
    }

    private void updateRightContent() {
        String leftContent = codeViewerModel.leftContent.getValue();
        String rightContent = codeViewerModel.rightContent.getValue();

        if (leftContent != null && rightContent != null && leftPane != null && rightPane != null) {
            if (rightContent.isEmpty()) {
                LOGGER.warn("[DiffViewerPanel] Right content is empty");
            } else {
                LOGGER.debug("[DiffViewerPanel] Updating right content with highlighting: {} chars", rightContent.length());
            }

            SwingUtilities.invokeLater(() -> {
                if (currentMode == DiffViewMode.SIDE_BY_SIDE) {
                    highlightDiffWithInlineChanges(leftPane, rightPane, leftContent, rightContent);
                    applyPendingTopLineRestore();
                }
            });
        }
    }

    private void updateUnifiedContent() {
        String content = codeViewerModel.unifiedDiffContent.getValue();
        if (content != null && unifiedPane != null) {
            if (content.isEmpty()) {
                LOGGER.warn("[DiffViewerPanel] Unified content is empty");
            } else {
                LOGGER.debug("[DiffViewerPanel] Updating unified content with highlighting: {} chars", content.length());
            }

            SwingUtilities.invokeLater(() -> {
                if (currentMode == DiffViewMode.UNIFIED) {
                    highlightUnifiedDiff(unifiedPane, content);
                    applyPendingTopLineRestore();
                }
            });
        }
    }

    private void createSideBySideView() {
        // Create line numbered panes for both sides
        leftPane = new LineNumberedTextPane();
        rightPane = new LineNumberedTextPane();

        // Wrap in scroll panes
        ThemedScrollPane leftScrollPane = new ThemedScrollPane(leftPane);
        leftScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        leftScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        ThemedScrollPane rightScrollPane = new ThemedScrollPane(rightPane);
        rightScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        rightScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        // Synchronize scrolling between left and right panes
        synchronizeScrollPanes(leftScrollPane, rightScrollPane);

        // Create split pane with the two scrolled line-numbered panes
        ThemedSplitPane splitPane = new ThemedSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightScrollPane);
        splitPane.setResizeWeight(0.5);

        contentPanel.add(splitPane, DiffViewMode.SIDE_BY_SIDE.name());
    }

    private void createUnifiedView() {
        unifiedPane = new LineNumberedTextPane();

        // Wrap in scroll pane
        ThemedScrollPane unifiedScrollPane = new ThemedScrollPane(unifiedPane);
        unifiedScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        unifiedScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        contentPanel.add(unifiedScrollPane, DiffViewMode.UNIFIED.name());
    }

    private void synchronizeScrollPanes(ThemedScrollPane leftPane, ThemedScrollPane rightPane) {
        // Link vertical scroll bars
        JScrollBar leftScrollBar = leftPane.getVerticalScrollBar();
        JScrollBar rightScrollBar = rightPane.getVerticalScrollBar();

        if (leftScrollBar != null && rightScrollBar != null) {
            leftScrollBar.addAdjustmentListener(e -> {
                if (syncingScrollBars) {
                    return;
                }
                syncingScrollBars = true;
                try {
                    rightScrollBar.setValue(e.getValue());
                } finally {
                    syncingScrollBars = false;
                }
            });

            rightScrollBar.addAdjustmentListener(e -> {
                if (syncingScrollBars) {
                    return;
                }
                syncingScrollBars = true;
                try {
                    leftScrollBar.setValue(e.getValue());
                } finally {
                    syncingScrollBars = false;
                }
            });
        }
    }

    private void switchViewMode() {
        CardLayout layout = (CardLayout) contentPanel.getLayout();
        layout.show(contentPanel, currentMode.name());

        if (currentFile != null) {
            showDiff(currentFile, startCommit, endCommit);
        }
    }

    public void setViewMode(DiffViewMode mode) {
        if (mode != null && mode != currentMode) {
            currentMode = mode;
            switchViewMode();
        }
    }

    public void showDiff(ReviewFile file, Commit newStartCommit, Commit newEndCommit) {
        this.currentFile = file;
        this.startCommit = newStartCommit;
        this.endCommit = newEndCommit;
        this.lastRenderedTheme = themeManager.getCurrentTheme();

        if (currentMode == DiffViewMode.SIDE_BY_SIDE) {
            showSideBySideDiff(file);
        } else {
            showUnifiedDiff(file);
        }
    }

    private void showSideBySideDiff(ReviewFile file) {
        // Get content from model
        String beforeContent = codeViewerModel.leftContent.getValue();
        String afterContent = codeViewerModel.rightContent.getValue();

        // Fall back to emptyif not available
        if (beforeContent == null || beforeContent.isEmpty()) {
            beforeContent = "// Content not available for " + file.getPath();
        }
        if (afterContent == null || afterContent.isEmpty()) {
            afterContent = "// Content not available for " + file.getPath();
        }

        // Set content and apply highlighting
        highlightDiffWithInlineChanges(leftPane, rightPane, beforeContent, afterContent);
    }

    private void showUnifiedDiff(ReviewFile file) {
        // Get unified diff from model
        String unifiedDiff = codeViewerModel.unifiedDiffContent.getValue();

        // Fall back to placeholder if not available
        if (unifiedDiff == null || unifiedDiff.isEmpty()) {
            unifiedDiff = "// Unified diff not available for " + file.getPath();
        }

        highlightUnifiedDiff(unifiedPane, unifiedDiff);
    }

    private void highlightDiffWithInlineChanges(LineNumberedTextPane leftPane, LineNumberedTextPane rightPane,
                                                 String beforeContent, String afterContent) {
        // Get unified diff to understand line operations
        String unifiedDiff = codeViewerModel.unifiedDiffContent.getValue();

        // Align the content using the unified diff
        AlignedContent aligned = alignContentUsingDiff(beforeContent, afterContent, unifiedDiff);

        // Set aligned text in both panes
        leftPane.setText(aligned.leftContent);
        rightPane.setText(aligned.rightContent);

        Theme theme = themeManager.getCurrentTheme();
        JTextPane leftTextPane = leftPane.getTextPane();
        JTextPane rightTextPane = rightPane.getTextPane();

        StyledDocument leftDoc = leftTextPane.getStyledDocument();
        StyledDocument rightDoc = rightTextPane.getStyledDocument();

        // Create styles
        Style removedStyle = leftTextPane.addStyle("removed", null);
        StyleConstants.setForeground(removedStyle, theme.getForegroundColor());
        StyleConstants.setBackground(removedStyle, theme.getRemovedLineColor());

        Style addedStyle = rightTextPane.addStyle("added", null);
        StyleConstants.setForeground(addedStyle, theme.getForegroundColor());
        StyleConstants.setBackground(addedStyle, theme.getAddedLineColor());

        Style modifiedStyle = leftTextPane.addStyle("modified", null);
        StyleConstants.setForeground(modifiedStyle, theme.getForegroundColor());
        StyleConstants.setBackground(modifiedStyle, theme.getModifiedLineColor());

        Style modifiedStyleRight = rightTextPane.addStyle("modifiedRight", null);
        StyleConstants.setForeground(modifiedStyleRight, theme.getForegroundColor());
        StyleConstants.setBackground(modifiedStyleRight, theme.getModifiedLineColor());

        Style defaultStyle = leftTextPane.addStyle("default", null);
        StyleConstants.setForeground(defaultStyle, theme.getForegroundColor());

        Style defaultStyleRight = rightTextPane.addStyle("default", null);
        StyleConstants.setForeground(defaultStyleRight, theme.getForegroundColor());

        Style emptyLineStyle = leftTextPane.addStyle("empty", null);
        StyleConstants.setForeground(emptyLineStyle, theme.getSecondaryTextColor());
        StyleConstants.setBackground(emptyLineStyle, theme.getBackgroundColor());

        Style emptyLineStyleRight = rightTextPane.addStyle("emptyRight", null);
        StyleConstants.setForeground(emptyLineStyleRight, theme.getSecondaryTextColor());
        StyleConstants.setBackground(emptyLineStyleRight, theme.getBackgroundColor());

        // Split into lines and apply styling
        String[] leftLines = aligned.leftContent.split("\n", -1);
        String[] rightLines = aligned.rightContent.split("\n", -1);

        int leftOffset = 0;
        int rightOffset = 0;

        for (int i = 0; i < leftLines.length && i < rightLines.length; i++) {
            String leftLine = leftLines[i];
            String rightLine = rightLines[i];

            int leftLineLength = leftLine.length() + 1;
            int rightLineLength = rightLine.length() + 1;

            // Check if line is empty placeholder or modified
            boolean leftIsEmpty = aligned.leftEmptyLines.contains(i);
            boolean rightIsEmpty = aligned.rightEmptyLines.contains(i);
            boolean isModified = aligned.modifiedLines.contains(i);

            // Apply appropriate styling
            if (isModified) {
                // Modified line - both sides exist, show with modified color
                if (leftOffset + leftLineLength <= leftDoc.getLength()) {
                    leftDoc.setCharacterAttributes(leftOffset, leftLineLength, defaultStyle, true);
                }
                if (rightOffset + rightLineLength <= rightDoc.getLength()) {
                    rightDoc.setCharacterAttributes(rightOffset, rightLineLength, defaultStyleRight, true);
                }

                // Find and highlight inline differences with modified color
                int commonPrefix = 0;
                int minLen = Math.min(leftLine.length(), rightLine.length());
                while (commonPrefix < minLen && leftLine.charAt(commonPrefix) == rightLine.charAt(commonPrefix)) {
                    commonPrefix++;
                }

                int commonSuffix = 0;
                while (commonSuffix < minLen - commonPrefix &&
                       leftLine.charAt(leftLine.length() - 1 - commonSuffix) ==
                       rightLine.charAt(rightLine.length() - 1 - commonSuffix)) {
                    commonSuffix++;
                }

                // Highlight the differences on left
                if (commonPrefix < leftLine.length() - commonSuffix) {
                    int diffLength = leftLine.length() - commonPrefix - commonSuffix;
                    leftDoc.setCharacterAttributes(leftOffset + commonPrefix, diffLength, modifiedStyle, true);
                }

                // Highlight the differences on right
                if (commonPrefix < rightLine.length() - commonSuffix) {
                    int diffLength = rightLine.length() - commonPrefix - commonSuffix;
                    rightDoc.setCharacterAttributes(rightOffset + commonPrefix, diffLength, modifiedStyle, true);
                }
            } else if (leftIsEmpty) {
                // Empty line on left (line was added on right)
                if (leftOffset + leftLineLength <= leftDoc.getLength()) {
                    leftDoc.setCharacterAttributes(leftOffset, leftLineLength, emptyLineStyle, true);
                }
                if (rightOffset + rightLineLength <= rightDoc.getLength()) {
                    rightDoc.setCharacterAttributes(rightOffset, rightLineLength, addedStyle, true);
                }
            } else if (rightIsEmpty) {
                // Empty line on right (line was removed from left)
                if (leftOffset + leftLineLength <= leftDoc.getLength()) {
                    leftDoc.setCharacterAttributes(leftOffset, leftLineLength, removedStyle, true);
                }
                if (rightOffset + rightLineLength <= rightDoc.getLength()) {
                    rightDoc.setCharacterAttributes(rightOffset, rightLineLength, emptyLineStyleRight, true);
                }
            } else if (!leftLine.equals(rightLine)) {
                // Lines differ - highlight the differences
                if (leftOffset + leftLineLength <= leftDoc.getLength()) {
                    leftDoc.setCharacterAttributes(leftOffset, leftLineLength, defaultStyle, true);
                }
                if (rightOffset + rightLineLength <= rightDoc.getLength()) {
                    rightDoc.setCharacterAttributes(rightOffset, rightLineLength, defaultStyleRight, true);
                }

                // Find and highlight inline differences
                int commonPrefix = 0;
                int minLen = Math.min(leftLine.length(), rightLine.length());
                while (commonPrefix < minLen && leftLine.charAt(commonPrefix) == rightLine.charAt(commonPrefix)) {
                    commonPrefix++;
                }

                int commonSuffix = 0;
                while (commonSuffix < minLen - commonPrefix &&
                       leftLine.charAt(leftLine.length() - 1 - commonSuffix) ==
                       rightLine.charAt(rightLine.length() - 1 - commonSuffix)) {
                    commonSuffix++;
                }

                if (commonPrefix < leftLine.length() - commonSuffix) {
                    int diffLength = leftLine.length() - commonPrefix - commonSuffix;
                    leftDoc.setCharacterAttributes(leftOffset + commonPrefix, diffLength, removedStyle, true);
                }

                if (commonPrefix < rightLine.length() - commonSuffix) {
                    int diffLength = rightLine.length() - commonPrefix - commonSuffix;
                    rightDoc.setCharacterAttributes(rightOffset + commonPrefix, diffLength, addedStyle, true);
                }
            } else {
                // Lines are the same - default style
                if (leftOffset + leftLineLength <= leftDoc.getLength()) {
                    leftDoc.setCharacterAttributes(leftOffset, leftLineLength, defaultStyle, true);
                }
                if (rightOffset + rightLineLength <= rightDoc.getLength()) {
                    rightDoc.setCharacterAttributes(rightOffset, rightLineLength, defaultStyleRight, true);
                }
            }

            leftOffset += leftLineLength;
            rightOffset += rightLineLength;
        }

        applySyntaxHighlighting(leftTextPane, aligned.leftContent);
        applySyntaxHighlighting(rightTextPane, aligned.rightContent);
    }

    private AlignedContent alignContentUsingDiff(String beforeContent, String afterContent, String unifiedDiff) {
        // If no valid diff or files are identical, don't align
        if (unifiedDiff == null || unifiedDiff.isEmpty() || unifiedDiff.startsWith("//")) {
            LOGGER.debug("[DiffViewer] No valid unified diff, returning unaligned content");
            return new AlignedContent(beforeContent, afterContent, new java.util.HashSet<>(), new java.util.HashSet<>(), new java.util.HashSet<>());
        }

        // Check if diff shows no changes
        boolean hasChanges = false;
        for (String line : unifiedDiff.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) {
                hasChanges = true;
                break;
            }
            if (line.startsWith("-") && !line.startsWith("---")) {
                hasChanges = true;
                break;
            }
        }

        if (!hasChanges) {
            LOGGER.debug("[DiffViewer] Diff shows no changes, returning unaligned content");
            return new AlignedContent(beforeContent, afterContent, new java.util.HashSet<>(), new java.util.HashSet<>(), new java.util.HashSet<>());
        }

        LOGGER.debug("[DiffViewer] Aligning content using diff with modification detection");

        String[] beforeLines = beforeContent.split("\n", -1);
        String[] afterLines = afterContent.split("\n", -1);
        String[] diffLines = unifiedDiff.split("\n");

        java.util.List<String> alignedLeft = new java.util.ArrayList<>();
        java.util.List<String> alignedRight = new java.util.ArrayList<>();
        java.util.Set<Integer> leftEmpty = new java.util.HashSet<>();
        java.util.Set<Integer> rightEmpty = new java.util.HashSet<>();
        java.util.Set<Integer> modifiedLines = new java.util.HashSet<>();

        int beforeIdx = 0;
        int afterIdx = 0;
        int currentLine = 0;

        // Pre-scan to identify modification patterns (consecutive - and + lines)
        java.util.List<DiffLine> parsedDiff = new java.util.ArrayList<>();
        for (String diffLine : diffLines) {
            if (diffLine.startsWith("@@")) {
                try {
                    String header = diffLine.substring(3, diffLine.indexOf("@@", 3)).trim();
                    String[] parts = header.split(" ");
                    String beforePart = parts[0].substring(1);
                    int hunkBeforeStart = beforePart.contains(",") ?
                        Integer.parseInt(beforePart.split(",")[0]) - 1 :
                        Integer.parseInt(beforePart) - 1;
                    String afterPart = parts[1].substring(1);
                    int hunkAfterStart = afterPart.contains(",") ?
                        Integer.parseInt(afterPart.split(",")[0]) - 1 :
                        Integer.parseInt(afterPart) - 1;

                    while (beforeIdx < hunkBeforeStart && afterIdx < hunkAfterStart) {
                        if (beforeIdx < beforeLines.length && afterIdx < afterLines.length) {
                            alignedLeft.add(beforeLines[beforeIdx]);
                            alignedRight.add(afterLines[afterIdx]);
                            beforeIdx++;
                            afterIdx++;
                            currentLine++;
                        } else {
                            break;
                        }
                    }
                } catch (Exception e) {
                    LOGGER.warn("[DiffViewer] Failed to parse hunk header: {}", diffLine, e);
                }
                parsedDiff.add(new DiffLine(DiffLineType.HUNK, diffLine));
            } else if (diffLine.startsWith("---") || diffLine.startsWith("+++") || diffLine.startsWith("diff ") || diffLine.startsWith("index ")) {
                parsedDiff.add(new DiffLine(DiffLineType.HEADER, diffLine));
            } else if (diffLine.startsWith("-")) {
                parsedDiff.add(new DiffLine(DiffLineType.REMOVED, diffLine.length() > 1 ? diffLine.substring(1) : ""));
            } else if (diffLine.startsWith("+")) {
                parsedDiff.add(new DiffLine(DiffLineType.ADDED, diffLine.length() > 1 ? diffLine.substring(1) : ""));
            } else if (diffLine.startsWith(" ")) {
                parsedDiff.add(new DiffLine(DiffLineType.CONTEXT, diffLine.length() > 1 ? diffLine.substring(1) : ""));
            }
        }

        // Process diff lines, detecting modifications (consecutive - and +)
        for (int i = 0; i < parsedDiff.size(); i++) {
            DiffLine current = parsedDiff.get(i);

            if (current.type == DiffLineType.HEADER || current.type == DiffLineType.HUNK) {
                continue;
            }

            if (current.type == DiffLineType.REMOVED) {
                // Check if next line is ADDED (indicates modification)
                boolean isModification = false;
                if (i + 1 < parsedDiff.size()) {
                    DiffLine next = parsedDiff.get(i + 1);
                    if (next.type == DiffLineType.ADDED) {
                        isModification = true;
                    }
                }

                if (isModification) {
                    // Modified line - show both versions
                    if (beforeIdx < beforeLines.length && afterIdx < afterLines.length) {
                        alignedLeft.add(beforeLines[beforeIdx]);
                        alignedRight.add(afterLines[afterIdx]);
                        modifiedLines.add(currentLine);
                        beforeIdx++;
                        afterIdx++;
                        currentLine++;
                        i++; // Skip the next + line since we processed it
                    }
                } else {
                    // Pure removal
                    if (beforeIdx < beforeLines.length) {
                        alignedLeft.add(beforeLines[beforeIdx]);
                        alignedRight.add("");
                        rightEmpty.add(currentLine);
                        beforeIdx++;
                        currentLine++;
                    }
                }
            } else if (current.type == DiffLineType.ADDED) {
                // Pure addition (not part of a modification)
                if (afterIdx < afterLines.length) {
                    alignedLeft.add("");
                    alignedRight.add(afterLines[afterIdx]);
                    leftEmpty.add(currentLine);
                    afterIdx++;
                    currentLine++;
                }
            } else if (current.type == DiffLineType.CONTEXT) {
                // Unchanged line
                if (beforeIdx < beforeLines.length && afterIdx < afterLines.length) {
                    alignedLeft.add(beforeLines[beforeIdx]);
                    alignedRight.add(afterLines[afterIdx]);
                    beforeIdx++;
                    afterIdx++;
                    currentLine++;
                }
            }
        }

        // Add any remaining unchanged lines
        while (beforeIdx < beforeLines.length && afterIdx < afterLines.length) {
            alignedLeft.add(beforeLines[beforeIdx]);
            alignedRight.add(afterLines[afterIdx]);
            beforeIdx++;
            afterIdx++;
            currentLine++;
        }

        // Add any remaining removed lines
        while (beforeIdx < beforeLines.length) {
            alignedLeft.add(beforeLines[beforeIdx]);
            alignedRight.add("");
            rightEmpty.add(currentLine);
            beforeIdx++;
            currentLine++;
        }

        // Add any remaining added lines
        while (afterIdx < afterLines.length) {
            alignedLeft.add("");
            alignedRight.add(afterLines[afterIdx]);
            leftEmpty.add(currentLine);
            afterIdx++;
            currentLine++;
        }

        String leftAligned = String.join("\n", alignedLeft);
        String rightAligned = String.join("\n", alignedRight);

        LOGGER.debug("[DiffViewer] Alignment complete: {} lines, {} left empty, {} right empty, {} modified",
            alignedLeft.size(), leftEmpty.size(), rightEmpty.size(), modifiedLines.size());

        return new AlignedContent(leftAligned, rightAligned, leftEmpty, rightEmpty, modifiedLines);
    }

    private enum DiffLineType {
        HEADER, HUNK, ADDED, REMOVED, CONTEXT
    }

    private static class DiffLine {
        final DiffLineType type;
        final String content;

        DiffLine(DiffLineType type, String content) {
            this.type = type;
            this.content = content;
        }
    }

    private static class AlignedContent {
        final String leftContent;
        final String rightContent;
        final java.util.Set<Integer> leftEmptyLines;
        final java.util.Set<Integer> rightEmptyLines;
        final java.util.Set<Integer> modifiedLines;

        AlignedContent(String leftContent, String rightContent,
                      java.util.Set<Integer> leftEmptyLines, java.util.Set<Integer> rightEmptyLines,
                      java.util.Set<Integer> modifiedLines) {
            this.leftContent = leftContent;
            this.rightContent = rightContent;
            this.leftEmptyLines = leftEmptyLines;
            this.rightEmptyLines = rightEmptyLines;
            this.modifiedLines = modifiedLines;
        }
    }

    private void highlightUnifiedDiff(LineNumberedTextPane pane, String unifiedDiff) {
        // Clean the diff to remove git syntax
        CleanedDiff cleaned = cleanUnifiedDiff(unifiedDiff);
        pane.setText(cleaned.content);

        Theme theme = themeManager.getCurrentTheme();
        JTextPane textPane = pane.getTextPane();
        StyledDocument doc = textPane.getStyledDocument();

        // Create styles
        Style addedStyle = textPane.addStyle("added", null);
        StyleConstants.setForeground(addedStyle, theme.getForegroundColor());
        StyleConstants.setBackground(addedStyle, theme.getAddedLineColor());

        Style removedStyle = textPane.addStyle("removed", null);
        StyleConstants.setForeground(removedStyle, theme.getForegroundColor());
        StyleConstants.setBackground(removedStyle, theme.getRemovedLineColor());

        Style defaultStyle = textPane.addStyle("default", null);
        StyleConstants.setForeground(defaultStyle, theme.getForegroundColor());

        // Apply styles based on cleaned line types
        String[] lines = cleaned.content.split("\n", -1);
        int offset = 0;
        int lineNumber = 1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineLength = line.length() + 1; // +1 for newline

            if (cleaned.addedLines.contains(i)) {
                if (offset + lineLength <= doc.getLength()) {
                    doc.setCharacterAttributes(offset, lineLength, addedStyle, true);
                }
                pane.markLineAdded(lineNumber);
            } else if (cleaned.removedLines.contains(i)) {
                if (offset + lineLength <= doc.getLength()) {
                    doc.setCharacterAttributes(offset, lineLength, removedStyle, true);
                }
                pane.markLineRemoved(lineNumber);
            } else {
                if (offset + lineLength <= doc.getLength()) {
                    doc.setCharacterAttributes(offset, lineLength, defaultStyle, true);
                }
            }

            offset += lineLength;
            lineNumber++;
        }

        applySyntaxHighlighting(textPane, cleaned.content);
    }

    private CleanedDiff cleanUnifiedDiff(String unifiedDiff) {
        String[] lines = unifiedDiff.split("\n");
        java.util.List<String> cleanedLines = new java.util.ArrayList<>();
        java.util.Set<Integer> addedLineNumbers = new java.util.HashSet<>();
        java.util.Set<Integer> removedLineNumbers = new java.util.HashSet<>();

        int currentLine = 0;

        for (String line : lines) {
            // Skip git metadata lines
            if (line.startsWith("diff --git")) {
                continue;
            }

            // Skip index line (hash info)
            if (line.startsWith("index ")) {
                continue;
            }

            // Skip file headers (--- and +++)
            if (line.startsWith("---") || line.startsWith("+++")) {
                continue;
            }

            // Skip hunk headers (@@ ... @@)
            if (line.startsWith("@@")) {
                continue;
            }

            // Added line - remove the leading +
            if (line.startsWith("+")) {
                String cleanLine = line.length() > 1 ? line.substring(1) : "";
                cleanedLines.add(cleanLine);
                addedLineNumbers.add(currentLine);
                currentLine++;
            }
            // Removed line - remove the leading -
            else if (line.startsWith("-")) {
                String cleanLine = line.length() > 1 ? line.substring(1) : "";
                cleanedLines.add(cleanLine);
                removedLineNumbers.add(currentLine);
                currentLine++;
            }
            // Context line - remove the leading space
            else if (line.startsWith(" ")) {
                String cleanLine = line.length() > 1 ? line.substring(1) : "";
                cleanedLines.add(cleanLine);
                currentLine++;
            }
            // Fallback for any other line
            else {
                cleanedLines.add(line);
                currentLine++;
            }
        }

        String cleanedContent = String.join("\n", cleanedLines);
        return new CleanedDiff(cleanedContent, addedLineNumbers, removedLineNumbers);
    }

    private static class CleanedDiff {
        final String content;
        final java.util.Set<Integer> addedLines;
        final java.util.Set<Integer> removedLines;

        CleanedDiff(String content, java.util.Set<Integer> addedLines, java.util.Set<Integer> removedLines) {
            this.content = content;
            this.addedLines = addedLines;
            this.removedLines = removedLines;
        }
    }

    public void clear() {
        leftPane.setText("");
        rightPane.setText("");
        unifiedPane.setText("");
        currentFile = null;
    }

    private String getFileExtension() {
        if (currentFile == null || currentFile.getPath() == null) return "";
        String path = currentFile.getPath();
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
    }

    private void applySyntaxHighlighting(JTextPane textPane, String source) {
        String ext = getFileExtension();
        if (ext.isEmpty()) return;
        pluginManager.getSyntaxHighlighterFor(ext).ifPresent(plugin -> {
            List<SyntaxHighlighterPlugin.SyntaxToken> tokens = safeTokenize(plugin, source, ext);
            if (tokens.isEmpty()) {
                return;
            }
            StyledDocument doc = textPane.getStyledDocument();
            boolean darkTheme = isDarkTheme();
            for (SyntaxHighlighterPlugin.SyntaxToken token : tokens) {
                if (token.offset < 0 || token.length <= 0) continue;
                if (token.offset + token.length > doc.getLength()) continue;
                Style style = textPane.addStyle(null, null);
                StyleConstants.setForeground(style, resolveTokenColor(plugin, token.type, darkTheme));
                doc.setCharacterAttributes(token.offset, token.length, style, false);
            }
        });
    }

    private Color resolveTokenColor(SyntaxHighlighterPlugin plugin, SyntaxHighlighterPlugin.TokenType type, boolean darkTheme) {
        try {
            Object resolved = plugin.getClass()
                .getMethod("getColorForTokenType", SyntaxHighlighterPlugin.TokenType.class, boolean.class)
                .invoke(plugin, type, darkTheme);
            if (resolved instanceof Color color) {
                return color;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        try {
            Object resolved = plugin.getClass()
                .getMethod("getColorForTokenType", SyntaxHighlighterPlugin.TokenType.class)
                .invoke(plugin, type);
            if (resolved instanceof Color color) {
                return color;
            }
        } catch (ReflectiveOperationException ignored) {
        }

        return themeManager.getCurrentTheme().getForegroundColor();
    }

    private List<SyntaxHighlighterPlugin.SyntaxToken> safeTokenize(SyntaxHighlighterPlugin plugin, String source, String extension) {
        try {
            return plugin.tokenize(source);
        } catch (StackOverflowError error) {
            LOGGER.error("Syntax highlighting failed with stack overflow for extension {} ({} chars)", extension, source == null ? 0 : source.length(), error);
            return List.of();
        } catch (RuntimeException error) {
            LOGGER.error("Syntax highlighting failed for extension {} ({} chars)", extension, source == null ? 0 : source.length(), error);
            return List.of();
        }
    }

    private boolean isDarkTheme() {
        Theme theme = themeManager.getCurrentTheme();
        Color background = theme.getBackgroundColor();
        double luminance = (0.2126 * background.getRed())
            + (0.7152 * background.getGreen())
            + (0.0722 * background.getBlue());
        return luminance < 128.0;
    }

    public void setOnLineDoubleClickListener(java.util.function.Consumer<Integer> listener) {
        leftPane.setOnLineDoubleClickListener(listener);
        rightPane.setOnLineDoubleClickListener(listener);
        unifiedPane.setOnLineDoubleClickListener(listener);
    }

    public void setCommentsForCurrentFile(java.util.List<ReviewComment> comments) {
        leftPane.setComments(comments);
        rightPane.setComments(comments);
        unifiedPane.setComments(comments);
    }

    /**
     * Returns the first visible line number in the active diff pane.
     *
     * @return 1-based line number, or -1 when unavailable
     */
    public int getTopVisibleLine() {
        JTextPane pane = getActiveTextPane();
        if (pane == null || pane.getDocument() == null || pane.getDocument().getLength() == 0) {
            return -1;
        }

        JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, pane);
        if (viewport == null) {
            return -1;
        }

        Point viewPoint = viewport.getViewPosition();
        int offset = pane.viewToModel2D(new Point(0, Math.max(0, viewPoint.y)));
        if (offset < 0) {
            return -1;
        }

        Element root = pane.getDocument().getDefaultRootElement();
        return root.getElementIndex(offset) + 1;
    }

    /**
     * Scrolls the active diff pane so the provided line is at the top of the viewport.
     *
     * @param lineNumber 1-based line number
     */
    public void scrollToTopLine(int lineNumber) {
        if (lineNumber <= 0) {
            return;
        }

        pendingTopLineRestore = lineNumber;
        SwingUtilities.invokeLater(this::applyPendingTopLineRestore);
    }

    private JTextPane getActiveTextPane() {
        if (currentMode == DiffViewMode.UNIFIED) {
            return unifiedPane != null ? unifiedPane.getTextPane() : null;
        }
        return leftPane != null ? leftPane.getTextPane() : null;
    }

    private void applyPendingTopLineRestore() {
        int targetLine = pendingTopLineRestore;
        if (targetLine <= 0) {
            return;
        }

        JTextPane pane = getActiveTextPane();
        if (pane == null || pane.getDocument() == null || pane.getDocument().getLength() == 0) {
            return;
        }

        Element root = pane.getDocument().getDefaultRootElement();
        int maxLine = root.getElementCount();
        int clampedLine = Math.min(targetLine, Math.max(maxLine, 1));
        Element lineElement = root.getElement(clampedLine - 1);
        if (lineElement == null) {
            return;
        }

        try {
            Rectangle lineRect = pane.modelToView2D(lineElement.getStartOffset()).getBounds();
            JViewport viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, pane);
            if (viewport != null) {
                viewport.setViewPosition(new Point(0, Math.max(0, lineRect.y)));
                pendingTopLineRestore = -1;
            }
        } catch (BadLocationException ignored) {
        }
    }

    @Override
    public void paint(Graphics g) {
        if (currentFile != null && lastRenderedTheme != themeManager.getCurrentTheme()) {
            showDiff(currentFile, startCommit, endCommit);
        }
        super.paint(g);
    }
}


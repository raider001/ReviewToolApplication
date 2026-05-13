package com.kalynx.serverlessreviewtool.ui.review;

import java.io.Serial;

import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.models.*;
import com.kalynx.serverlessreviewtool.plugin.SyntaxHighlighterPlugin;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.AnnotatedScrollPane;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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

    private AnnotatedScrollPane leftScrollPane;
    private AnnotatedScrollPane rightScrollPane;
    private AnnotatedScrollPane unifiedScrollPane;

    private transient List<ReviewComment> currentComments = List.of();
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
        String unifiedDiff = codeViewerModel.unifiedDiffContent.getValue();
        if (unifiedDiff != null && unifiedPane != null) {
            if (unifiedDiff.isEmpty()) {
                LOGGER.warn("[DiffViewerPanel] Unified content is empty");
            } else {
                LOGGER.debug("[DiffViewerPanel] Updating unified content with highlighting: {} chars", unifiedDiff.length());
            }

            SwingUtilities.invokeLater(() -> {
                if (currentMode == DiffViewMode.UNIFIED) {
                    String afterContent = codeViewerModel.rightContent.getValue();
                    if (afterContent == null) afterContent = "";
                    highlightUnifiedDiff(unifiedPane, afterContent, unifiedDiff);
                    applyPendingTopLineRestore();
                }
            });
        }
    }

    private void createSideBySideView() {
        leftPane = new LineNumberedTextPane();
        rightPane = new LineNumberedTextPane();

        leftScrollPane = new AnnotatedScrollPane(leftPane);
        leftScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        leftScrollPane.setReferencePaneForAnnotations(leftPane);

        rightScrollPane = new AnnotatedScrollPane(rightPane);
        rightScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        rightScrollPane.setReferencePaneForAnnotations(rightPane);

        synchronizeScrollPanes(leftScrollPane, rightScrollPane);

        ThemedSplitPane splitPane = new ThemedSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftScrollPane, rightScrollPane);
        splitPane.setResizeWeight(0.5);

        contentPanel.add(splitPane, DiffViewMode.SIDE_BY_SIDE.name());
    }

    private void createUnifiedView() {
        unifiedPane = new LineNumberedTextPane();

        unifiedScrollPane = new AnnotatedScrollPane(unifiedPane);
        unifiedScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        unifiedScrollPane.setReferencePaneForAnnotations(unifiedPane);

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
        String afterContent = codeViewerModel.rightContent.getValue();
        String unifiedDiff = codeViewerModel.unifiedDiffContent.getValue();

        if (afterContent == null || afterContent.isEmpty()) {
            afterContent = "// Content not available for " + file.getPath();
        }
        if (unifiedDiff == null) {
            unifiedDiff = "";
        }

        highlightUnifiedDiff(unifiedPane, afterContent, unifiedDiff);
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

            boolean leftIsEmpty = aligned.leftEmptyLines.contains(i);
            boolean rightIsEmpty = aligned.rightEmptyLines.contains(i);

            if (leftIsEmpty) {
                if (leftOffset + leftLineLength <= leftDoc.getLength()) {
                    leftDoc.setCharacterAttributes(leftOffset, leftLineLength, emptyLineStyle, true);
                }
                if (rightOffset + rightLineLength <= rightDoc.getLength()) {
                    rightDoc.setCharacterAttributes(rightOffset, rightLineLength, addedStyle, true);
                }
            } else if (rightIsEmpty) {
                if (leftOffset + leftLineLength <= leftDoc.getLength()) {
                    leftDoc.setCharacterAttributes(leftOffset, leftLineLength, removedStyle, true);
                }
                if (rightOffset + rightLineLength <= rightDoc.getLength()) {
                    rightDoc.setCharacterAttributes(rightOffset, rightLineLength, emptyLineStyleRight, true);
                }
            } else {
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

        pushSideBySideAnnotations(aligned);
    }

    private void pushSideBySideAnnotations(AlignedContent aligned) {
        int totalLines = aligned.leftContent.split("\n", -1).length;
        Set<Integer> leftRemoved = toOneBased(aligned.rightEmptyLines);
        Set<Integer> rightAdded = toOneBased(aligned.leftEmptyLines);

        if (leftScrollPane != null) {
            leftScrollPane.setChangeAnnotations(totalLines, Set.of(), leftRemoved, Set.of());
            leftScrollPane.setCommentAnnotations(currentComments);
        }
        if (rightScrollPane != null) {
            rightScrollPane.setChangeAnnotations(totalLines, rightAdded, Set.of(), Set.of());
            rightScrollPane.setCommentAnnotations(currentComments);
        }
    }

    private Set<Integer> toOneBased(Set<Integer> zeroBased) {
        return zeroBased.stream().map(i -> i + 1).collect(Collectors.toSet());
    }

    private AlignedContent alignContentUsingDiff(String beforeContent, String afterContent, String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isEmpty() || unifiedDiff.startsWith("//")) {
            LOGGER.debug("[DiffViewer] No valid unified diff, returning unaligned content");
            return new AlignedContent(beforeContent, afterContent, new java.util.HashSet<>(), new java.util.HashSet<>(), new java.util.HashSet<>());
        }

        String[] beforeLines = beforeContent.split("\n", -1);
        String[] afterLines = afterContent.split("\n", -1);

        java.util.List<String> alignedLeft = new java.util.ArrayList<>();
        java.util.List<String> alignedRight = new java.util.ArrayList<>();
        java.util.Set<Integer> leftEmpty = new java.util.HashSet<>();
        java.util.Set<Integer> rightEmpty = new java.util.HashSet<>();

        int beforeIdx = 0;
        int afterIdx = 0;
        int rowIdx = 0;

        for (String diffLine : unifiedDiff.split("\n")) {
            if (diffLine.startsWith("diff ") || diffLine.startsWith("index ")
                    || diffLine.startsWith("---") || diffLine.startsWith("+++")
                    || diffLine.startsWith("\\")) {
                continue;
            }

            if (diffLine.startsWith("@@")) {
                int hunkBeforeStart = parseHunkOldStart(diffLine);
                int hunkAfterStart = parseHunkNewStart(diffLine);
                if (hunkBeforeStart >= 0 && hunkAfterStart >= 0) {
                    while (beforeIdx < hunkBeforeStart && afterIdx < hunkAfterStart
                            && beforeIdx < beforeLines.length && afterIdx < afterLines.length) {
                        alignedLeft.add(beforeLines[beforeIdx]);
                        alignedRight.add(afterLines[afterIdx]);
                        beforeIdx++;
                        afterIdx++;
                        rowIdx++;
                    }
                }
            } else if (diffLine.startsWith("-")) {
                if (beforeIdx < beforeLines.length) {
                    alignedLeft.add(beforeLines[beforeIdx]);
                    alignedRight.add("");
                    rightEmpty.add(rowIdx);
                    beforeIdx++;
                    rowIdx++;
                }
            } else if (diffLine.startsWith("+")) {
                if (afterIdx < afterLines.length) {
                    alignedLeft.add("");
                    alignedRight.add(afterLines[afterIdx]);
                    leftEmpty.add(rowIdx);
                    afterIdx++;
                    rowIdx++;
                }
            } else if (diffLine.startsWith(" ")) {
                if (beforeIdx < beforeLines.length && afterIdx < afterLines.length) {
                    alignedLeft.add(beforeLines[beforeIdx]);
                    alignedRight.add(afterLines[afterIdx]);
                    beforeIdx++;
                    afterIdx++;
                    rowIdx++;
                }
            }
        }

        while (beforeIdx < beforeLines.length && afterIdx < afterLines.length) {
            alignedLeft.add(beforeLines[beforeIdx]);
            alignedRight.add(afterLines[afterIdx]);
            beforeIdx++;
            afterIdx++;
            rowIdx++;
        }

        while (beforeIdx < beforeLines.length) {
            alignedLeft.add(beforeLines[beforeIdx]);
            alignedRight.add("");
            rightEmpty.add(rowIdx);
            beforeIdx++;
            rowIdx++;
        }

        while (afterIdx < afterLines.length) {
            alignedLeft.add("");
            alignedRight.add(afterLines[afterIdx]);
            leftEmpty.add(rowIdx);
            afterIdx++;
            rowIdx++;
        }

        LOGGER.debug("[DiffViewer] Alignment complete: {} rows, {} left empty, {} right empty",
            alignedLeft.size(), leftEmpty.size(), rightEmpty.size());

        return new AlignedContent(String.join("\n", alignedLeft), String.join("\n", alignedRight),
            leftEmpty, rightEmpty, new java.util.HashSet<>());
    }

    private int parseHunkOldStart(String hunkHeader) {
        try {
            int minusIdx = hunkHeader.indexOf('-');
            if (minusIdx < 0) return -1;
            int end = hunkHeader.indexOf(',', minusIdx);
            if (end < 0) end = hunkHeader.indexOf(' ', minusIdx);
            if (end < 0) return -1;
            return Integer.parseInt(hunkHeader.substring(minusIdx + 1, end).trim()) - 1;
        } catch (Exception e) {
            return -1;
        }
    }

    private int parseHunkNewStart(String hunkHeader) {
        try {
            int plusIdx = hunkHeader.indexOf('+');
            if (plusIdx < 0) return -1;
            int end = hunkHeader.indexOf(',', plusIdx);
            if (end < 0) end = hunkHeader.indexOf(' ', plusIdx);
            if (end < 0) return -1;
            return Integer.parseInt(hunkHeader.substring(plusIdx + 1, end).trim()) - 1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static class AlignedContent {
        final String leftContent;
        final String rightContent;
        final java.util.Set<Integer> leftEmptyLines;
        final java.util.Set<Integer> rightEmptyLines;

        AlignedContent(String leftContent, String rightContent,
                      java.util.Set<Integer> leftEmptyLines, java.util.Set<Integer> rightEmptyLines,
                      java.util.Set<Integer> ignored) {
            this.leftContent = leftContent;
            this.rightContent = rightContent;
            this.leftEmptyLines = leftEmptyLines;
            this.rightEmptyLines = rightEmptyLines;
        }
    }

    private void highlightUnifiedDiff(LineNumberedTextPane pane, String afterContent, String unifiedDiff) {
        CleanedDiff cleaned = buildFullFileDiff(afterContent, unifiedDiff);
        pane.setText(cleaned.content);

        Theme theme = themeManager.getCurrentTheme();
        JTextPane textPane = pane.getTextPane();
        StyledDocument doc = textPane.getStyledDocument();

        Style addedStyle = textPane.addStyle("added", null);
        StyleConstants.setForeground(addedStyle, theme.getForegroundColor());
        StyleConstants.setBackground(addedStyle, theme.getAddedLineColor());

        Style defaultStyle = textPane.addStyle("default", null);
        StyleConstants.setForeground(defaultStyle, theme.getForegroundColor());

        String[] lines = cleaned.content.split("\n", -1);
        int offset = 0;
        int lineNumber = 1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int lineLength = line.length() + 1;

            if (cleaned.addedLines.contains(i)) {
                if (offset + lineLength <= doc.getLength()) {
                    doc.setCharacterAttributes(offset, lineLength, addedStyle, true);
                }
                pane.markLineAdded(lineNumber);
            } else {
                if (offset + lineLength <= doc.getLength()) {
                    doc.setCharacterAttributes(offset, lineLength, defaultStyle, true);
                }
            }

            offset += lineLength;
            lineNumber++;
        }

        applySyntaxHighlighting(textPane, cleaned.content);

        pushUnifiedAnnotations(cleaned);
    }

    private void pushUnifiedAnnotations(CleanedDiff cleaned) {
        if (unifiedScrollPane == null) return;
        int totalLines = cleaned.content.split("\n", -1).length;
        unifiedScrollPane.setChangeAnnotations(totalLines, toOneBased(cleaned.addedLines),
            toOneBased(cleaned.removedLines), Set.of());
        unifiedScrollPane.setCommentAnnotations(currentComments);
    }

    private CleanedDiff buildFullFileDiff(String afterContent, String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isEmpty()) {
            return new CleanedDiff(afterContent, Set.of(), Set.of());
        }

        Set<Integer> addedLineIndices = new HashSet<>();
        Pattern hunkPattern = Pattern.compile("\\+([0-9]+)");
        int newFileLine = -1;

        for (String line : unifiedDiff.split("\n")) {
            if (line.startsWith("diff --git") || line.startsWith("index ")
                    || line.startsWith("---") || line.startsWith("+++")
                    || line.startsWith("\\")) {
                continue;
            }
            if (line.startsWith("@@")) {
                Matcher m = hunkPattern.matcher(line);
                if (m.find()) {
                    newFileLine = Integer.parseInt(m.group(1));
                }
                continue;
            }
            if (newFileLine < 0) continue;
            if (line.startsWith("+")) {
                addedLineIndices.add(newFileLine - 1);
                newFileLine++;
            } else if (line.startsWith(" ")) {
                newFileLine++;
            }
        }

        return new CleanedDiff(afterContent, addedLineIndices, Set.of());
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
        currentComments = List.of();
        if (leftScrollPane != null) leftScrollPane.clearAnnotations();
        if (rightScrollPane != null) rightScrollPane.clearAnnotations();
        if (unifiedScrollPane != null) unifiedScrollPane.clearAnnotations();
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
        currentComments = new ArrayList<>(comments);
        leftPane.setComments(comments);
        rightPane.setComments(comments);
        unifiedPane.setComments(comments);
        if (leftScrollPane != null) leftScrollPane.setCommentAnnotations(currentComments);
        if (rightScrollPane != null) rightScrollPane.setCommentAnnotations(currentComments);
        if (unifiedScrollPane != null) unifiedScrollPane.setCommentAnnotations(currentComments);
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


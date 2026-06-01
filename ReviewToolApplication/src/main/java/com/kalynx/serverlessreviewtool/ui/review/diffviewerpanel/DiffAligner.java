package com.kalynx.serverlessreviewtool.ui.review.diffviewerpanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * DiffAligner provides pure logic for aligning before/after file content using a unified diff,
 * and for building full-file diffs with added-line markers.
 */
public final class DiffAligner {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiffAligner.class);

    private DiffAligner() {
    }

    /**
     * Aligns before/after content line-by-line using the unified diff as a guide.
     * Removed lines appear on the left with an empty placeholder on the right, and vice versa.
     *
     * @param beforeContent content of the file before changes
     * @param afterContent  content of the file after changes
     * @param unifiedDiff   unified diff output from git
     * @return aligned content with empty-line index sets
     */
    public static AlignedContent alignContentUsingDiff(String beforeContent, String afterContent, String unifiedDiff) {
        if (unifiedDiff == null || unifiedDiff.isEmpty() || unifiedDiff.startsWith("//")) {
            LOGGER.debug("[DiffAligner] No valid unified diff, returning unaligned content");
            return new AlignedContent(beforeContent, afterContent, new HashSet<>(), new HashSet<>());
        }

        String[] beforeLines = beforeContent.split("\n", -1);
        String[] afterLines = afterContent.split("\n", -1);

        List<String> alignedLeft = new ArrayList<>();
        List<String> alignedRight = new ArrayList<>();
        Set<Integer> leftEmpty = new HashSet<>();
        Set<Integer> rightEmpty = new HashSet<>();

        int beforeIdx = 0;
        int afterIdx = 0;
        int rowIdx = 0;

        for (String diffLine : unifiedDiff.split("\n")) {
            if (isHeaderLine(diffLine)) continue;

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

        LOGGER.debug("[DiffAligner] Alignment complete: {} rows, {} left empty, {} right empty",
                alignedLeft.size(), leftEmpty.size(), rightEmpty.size());

        return new AlignedContent(
                String.join("\n", alignedLeft),
                String.join("\n", alignedRight),
                leftEmpty,
                rightEmpty);
    }

    /**
     * Builds a display representation of the diff by interleaving removed lines, added lines,
     * and context lines from the unified diff. Lines before and after all hunks are taken from
     * {@code afterContent}. The returned content is longer than {@code afterContent} whenever
     * removed lines are present.
     *
     * @param afterContent content of the file after changes
     * @param unifiedDiff  unified diff output from git
     * @return cleaned diff with interleaved content and added/removed line index sets
     */
    public static CleanedDiff buildFullFileDiff(String afterContent, String unifiedDiff) {
        if (unifiedDiff == null || !unifiedDiff.contains("@@")) {
            return new CleanedDiff(afterContent, Set.of(), Set.of());
        }

        String[] afterLines = afterContent.split("\n", -1);
        List<String> displayLines = new ArrayList<>();
        Set<Integer> addedLineIndices = new HashSet<>();
        Set<Integer> removedLineIndices = new HashSet<>();

        int lastSyncedNewLine = 0;
        int currentNewLine = -1;

        for (String line : unifiedDiff.split("\n")) {
            if (isHeaderLine(line)) continue;

            if (line.startsWith("@@")) {
                int hunkNewStart = parseHunkNewStart(line);
                if (hunkNewStart >= 0) {
                    while (lastSyncedNewLine < hunkNewStart && lastSyncedNewLine < afterLines.length) {
                        displayLines.add(afterLines[lastSyncedNewLine]);
                        lastSyncedNewLine++;
                    }
                    currentNewLine = hunkNewStart;
                }
                continue;
            }

            if (currentNewLine < 0) continue;

            if (line.startsWith("-")) {
                removedLineIndices.add(displayLines.size());
                displayLines.add(line.length() > 1 ? line.substring(1) : "");
            } else if (line.startsWith("+")) {
                addedLineIndices.add(displayLines.size());
                displayLines.add(line.length() > 1 ? line.substring(1) : "");
                currentNewLine++;
                lastSyncedNewLine = currentNewLine;
            } else if (line.startsWith(" ")) {
                displayLines.add(line.length() > 1 ? line.substring(1) : "");
                currentNewLine++;
                lastSyncedNewLine = currentNewLine;
            }
        }

        while (lastSyncedNewLine < afterLines.length) {
            displayLines.add(afterLines[lastSyncedNewLine]);
            lastSyncedNewLine++;
        }

        return new CleanedDiff(String.join("\n", displayLines), addedLineIndices, removedLineIndices);
    }

    /**
     * Converts a set of zero-based line indices to one-based line numbers.
     *
     * @param zeroBased set of zero-based indices
     * @return set of one-based line numbers
     */
    public static Set<Integer> toOneBased(Set<Integer> zeroBased) {
        return zeroBased.stream().map(i -> i + 1).collect(Collectors.toSet());
    }

    private static boolean isHeaderLine(String line) {
        return line.startsWith("diff ") || line.startsWith("index ")
                || line.startsWith("---") || line.startsWith("+++")
                || line.startsWith("\\");
    }

    private static int parseHunkOldStart(String hunkHeader) {
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

    private static int parseHunkNewStart(String hunkHeader) {
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

    /**
     * Holds the result of aligning before/after content with empty placeholder rows.
     *
     * @param leftContent   aligned content for the left (before) pane
     * @param rightContent  aligned content for the right (after) pane
     * @param leftEmptyLines zero-based row indices where the left pane has a placeholder
     * @param rightEmptyLines zero-based row indices where the right pane has a placeholder
     */
    public record AlignedContent(
            String leftContent,
            String rightContent,
            Set<Integer> leftEmptyLines,
            Set<Integer> rightEmptyLines) {
    }

    /**
     * Holds full-file display content with sets indicating which line indices are added or removed.
     *
     * @param content      full file content to display
     * @param addedLines   zero-based line indices that are additions
     * @param removedLines zero-based line indices that are removals
     */
    public record CleanedDiff(
            String content,
            Set<Integer> addedLines,
            Set<Integer> removedLines) {
    }
}


package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

/**
 * Captures the user's viewport state (selected file and scroll position) before a review
 * reload so it can be restored afterwards.
 *
 * @param repositoryName the repository of the selected file, or {@code null} if none
 * @param filePath       the path of the selected file, or {@code null} if none
 * @param topVisibleLine 1-based line number at the top of the diff viewport, or {@code -1} if unavailable
 */
public record ViewportRestoreState(String repositoryName, String filePath, int topVisibleLine) {}


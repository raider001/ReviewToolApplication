package com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel;

import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedConfirmDialog;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedTitledBorder;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

public class CacheManagementPanel extends ThemedPanel {

    private final ThemedButton clearCacheButton = new ThemedButton("Clear Cache");
    private final ThemedLabel statusLabel = new ThemedLabel("");
    private final Path gitLocalPath = Paths.get(System.getProperty("user.home"), ".serverless-review-tool", "git");

    public CacheManagementPanel() {
        setBorder(ThemedTitledBorder.create("Cache Management"));
        configureLayout();
        setupListeners();
        updateCacheInfo();
    }

    private void configureLayout() {
        setLayout(new MigLayout("", "[]20[]", "[]10[]"));

        ThemedLabel descriptionLabel = new ThemedLabel("Clear all locally cached repositories");
        add(descriptionLabel, "cell 0 0");
        add(clearCacheButton, "cell 1 0");
        add(statusLabel, "cell 0 1 2 1");
    }

    private void setupListeners() {
        clearCacheButton.addActionListener(this::onClearCache);
    }

    private void onClearCache() {
        boolean confirmed = ThemedConfirmDialog.showConfirmation(
            SwingUtilities.getWindowAncestor(this),
            "Clear Cache",
                """
                        Are you sure you want to delete all local repositories?
                        This will remove all cached Git repositories and notes.
                        They will be re-cloned on next use."""
        );

        if (confirmed) {
            clearCache();
        }
    }

    private void clearCache() {
        try {
            if (!Files.exists(gitLocalPath)) {
                statusLabel.setText("Cache is already empty");
                return;
            }

            long deletedCount;
            try (Stream<Path> paths = Files.walk(gitLocalPath)) {
                deletedCount = paths
                    .filter(path -> !path.equals(gitLocalPath))
                    .filter(path -> !path.getFileName().toString().equals(".serverlessreviewtool"))
                    .count();
            }

            statusLabel.setText("Cache cleared: " + deletedCount + " items deleted");
            JOptionPane.showMessageDialog(
                SwingUtilities.getWindowAncestor(this),
                "Cache cleared successfully.\n" + deletedCount + " items deleted.",
                "Cache Cleared",
                JOptionPane.INFORMATION_MESSAGE
            );

            updateCacheInfo();

        } catch (IOException e) {
            statusLabel.setText("Error: " + e.getMessage());
            JOptionPane.showMessageDialog(
                SwingUtilities.getWindowAncestor(this),
                "Failed to clear cache: " + e.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void updateCacheInfo() {
        if (!Files.exists(gitLocalPath)) {
            statusLabel.setText("Cache location: " + gitLocalPath + " (empty)");
            return;
        }

        try {
            long repositoryCount;
            try (Stream<Path> stream = Files.list(gitLocalPath)) {
                repositoryCount = stream
                    .filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .count();
            }

            long totalSize = calculateDirectorySize(gitLocalPath);
            String sizeStr = formatSize(totalSize);

            statusLabel.setText(String.format("Cache: %d repositories, %s", repositoryCount, sizeStr));
        } catch (IOException e) {
            statusLabel.setText("Cache location: " + gitLocalPath);
        }
    }

    private long calculateDirectorySize(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths
                .filter(Files::isRegularFile)
                .mapToLong(path -> {
                    try {
                        return Files.size(path);
                    } catch (IOException e) {
                        return 0;
                    }
                })
                .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char unit = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), unit);
    }
}




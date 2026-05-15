package com.kalynx.serverlessreviewtool.ui.mainpanels;

import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedTitledBorder;
import com.kalynx.swingtheme.theme.LoadingStateManager;
import net.miginfocom.swing.MigLayout;

import java.util.concurrent.CompletableFuture;

/**
 * LoadingTrialPanel - Test panel to demonstrate the window frame loading indicator
 */
public class LoadingTrialPanel extends ThemedPanel {

    private final ThemedButton shortLoadButton = new ThemedButton("Short Load (2s)");
    private final ThemedButton mediumLoadButton = new ThemedButton("Medium Load (5s)");
    private final ThemedButton longLoadButton = new ThemedButton("Long Load (10s)");
    private final ThemedButton multipleOpsButton = new ThemedButton("Multiple Operations");
    private final ThemedButton cancelButton = new ThemedButton("Cancel All");

    private final ThemedLabel statusLabel = new ThemedLabel("Ready");
    private final ThemedLabel activeOpsLabel = new ThemedLabel("Active Operations: 0");

    private int operationCounter = 0;

    public LoadingTrialPanel() {
        setBorder(ThemedTitledBorder.create("Loading Indicator Testing"));
        configureLayout();
        setupListeners();
    }

    private void configureLayout() {
        setLayout(new MigLayout("", "[grow]", "[]10[]10[]10[]10[]20[][]"));

        ThemedLabel instructionsLabel = new ThemedLabel(
            "<html><b>Test the window frame loading indicator:</b><br>" +
            "Click any button to start a simulated loading operation.<br>" +
            "Watch the window border for the traveling segment animation.</html>"
        );

        add(instructionsLabel, "cell 0 0, growx, wrap");
        add(shortLoadButton, "cell 0 1, growx, wrap");
        add(mediumLoadButton, "cell 0 2, growx, wrap");
        add(longLoadButton, "cell 0 3, growx, wrap");
        add(multipleOpsButton, "cell 0 4, growx, wrap");
        add(cancelButton, "cell 0 5, growx, wrap");
        add(statusLabel, "cell 0 6, growx, wrap");
        add(activeOpsLabel, "cell 0 7, growx");
    }

    private void setupListeners() {
        shortLoadButton.addActionListener(e -> startLoadingOperation("short-load", 2000));
        mediumLoadButton.addActionListener(e -> startLoadingOperation("medium-load", 5000));
        longLoadButton.addActionListener(e -> startLoadingOperation("long-load", 10000));
        multipleOpsButton.addActionListener(e -> startMultipleOperations());
        cancelButton.addActionListener(e -> cancelAllOperations());

        LoadingStateManager.getInstance().addListener(this::updateStatus);
    }

    private void startLoadingOperation(String operationId, int durationMs) {
        String uniqueId = operationId + "-" + (++operationCounter);
        statusLabel.setText("Starting: " + uniqueId + " (" + (durationMs / 1000) + "s)");

        LoadingStateManager.getInstance().startLoading(uniqueId);
        updateActiveOperationsCount();

        CompletableFuture.delayedExecutor(durationMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .execute(() -> {
                LoadingStateManager.getInstance().stopLoading(uniqueId);
                statusLabel.setText("Completed: " + uniqueId);
                updateActiveOperationsCount();
            });
    }

    private void startMultipleOperations() {
        statusLabel.setText("Starting 3 concurrent operations...");
        startLoadingOperation("multi-1", 3000);
        startLoadingOperation("multi-2", 5000);
        startLoadingOperation("multi-3", 7000);
    }

    private void cancelAllOperations() {
        statusLabel.setText("Cancelled all operations");
        activeOpsLabel.setText("Active Operations: 0");
    }

    private void updateStatus() {
        if (LoadingStateManager.getInstance().isLoading()) {
            statusLabel.setText("Loading...");
        } else {
            statusLabel.setText("All operations complete");
        }
    }

    private void updateActiveOperationsCount() {
        int count = getActiveOperationsCount();
        activeOpsLabel.setText("Active Operations: " + count);
    }

    private int getActiveOperationsCount() {
        return 0;
    }
}


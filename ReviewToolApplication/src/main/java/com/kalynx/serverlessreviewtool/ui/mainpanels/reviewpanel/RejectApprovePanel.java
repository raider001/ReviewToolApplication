package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RejectApprovePanel - Contains action buttons for review approval workflow
 * Provides Request Changes and Approve buttons for review decisions
 */
public class RejectApprovePanel extends ThemedPanel {

    private final static Logger LOGGER = LoggerFactory.getLogger(RejectApprovePanel.class);

    private final ThemedButton requestChangesButton = new ThemedButton("Request Changes");
    private final ThemedButton approveButton = new ThemedButton("Approve");

    private Runnable onApproveAction;
    private Runnable onRequestChangesAction;

    public RejectApprovePanel() {
        configureLayout();
        setupButtons();
    }

    private void configureLayout() {
        setLayout(new MigLayout("fillx, insets 0", "[grow][][]", "[]"));

        add(new ThemedPanel(), "growx");
        add(requestChangesButton);
        add(approveButton);
    }

    private void setupButtons() {
        approveButton.setAccentStyle(true);

        requestChangesButton.addActionListener(this::onRequestChanges);
        approveButton.addActionListener(this::onApprove);
    }

    private void onRequestChanges() {
        if (onRequestChangesAction != null) {
            onRequestChangesAction.run();
        } else {
            LOGGER.info("Request Changes clicked (no action configured)");
        }
    }

    private void onApprove() {
        if (onApproveAction != null) {
            onApproveAction.run();
        } else {
            LOGGER.info("Approve clicked (no action configured)");
        }
    }

    public void setButtonsEnabled(boolean enabled) {
        requestChangesButton.setEnabled(enabled);
        approveButton.setEnabled(enabled);
    }

    public void setOnApproveAction(Runnable action) {
        this.onApproveAction = action;
    }

    public void setOnRequestChangesAction(Runnable action) {
        this.onRequestChangesAction = action;
    }
}


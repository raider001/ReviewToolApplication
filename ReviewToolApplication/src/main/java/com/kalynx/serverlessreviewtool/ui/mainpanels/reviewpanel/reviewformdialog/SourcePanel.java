package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.reviewformdialog;

import com.kalynx.swingtheme.ComponentModel;
import com.kalynx.swingtheme.themedcomponents.*;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class SourcePanel extends ThemedPanel {

    private static final int GAP = 12;
    private static final int FIELD_H = 28;

    private final ComponentModel<String> selectedBranchModel;
    private final ComponentModel<String> selectedBaseBranchModel;
    private final ThemedSearchableComboBox branchNameField;
    private final ThemedSearchableComboBox reviewAgainstBranchCombo;

    public SourcePanel(ComponentModel<List<String>> availableBranchesModel,
                      ComponentModel<String> selectedBranchModel,
                      ComponentModel<String> selectedBaseBranchModel) {
        this.selectedBranchModel = selectedBranchModel;
        this.selectedBaseBranchModel = selectedBaseBranchModel;

        setLayout(new MigLayout("fill, insets 10 12 12 12", "[grow,fill]", "[grow,fill]"));
        setBorder(ThemedTitledBorder.create("Source"));

        branchNameField = new ThemedSearchableComboBox(new ArrayList<>());
        branchNameField.setToolTipText("Search for a branch to review");
        branchNameField.setPreferredSize(new Dimension(0, themeManager.scale(FIELD_H)));
        branchNameField.bindTo(availableBranchesModel);

        reviewAgainstBranchCombo = new ThemedSearchableComboBox(new ArrayList<>());
        reviewAgainstBranchCombo.setToolTipText("Search for a branch to review against");
        reviewAgainstBranchCombo.setPreferredSize(new Dimension(0, themeManager.scale(FIELD_H)));
        reviewAgainstBranchCombo.bindTo(availableBranchesModel);

        configureLayout();
        setupListeners(availableBranchesModel);
    }

    private void configureLayout() {
        ThemedPanel branchPanel = new ThemedPanel();
        branchPanel.setLayout(new MigLayout(
            "insets 0, gap " + GAP + " 8",
            "[100!][grow,fill]" + GAP + "[110!][grow,fill]",
            "[]"
        ));

        branchPanel.add(rightLabel("Branch:"));
        branchPanel.add(branchNameField, "growx, wmin 0");
        branchPanel.add(rightLabel("Review against:"));
        branchPanel.add(reviewAgainstBranchCombo, "growx, wmin 0");

        add(branchPanel, "grow, wmin 0");
    }

    private void setupListeners(ComponentModel<List<String>> availableBranchesModel) {
        branchNameField.addActionListener(ignored -> {
            Object selected = branchNameField.getSelectedItem();
            if (selected != null) {
                selectedBranchModel.setValue(selected.toString());
            }
        });

        reviewAgainstBranchCombo.addActionListener(ignored -> {
            Object selected = reviewAgainstBranchCombo.getSelectedItem();
            if (selected != null) {
                selectedBaseBranchModel.setValue(selected.toString());
            }
        });

        availableBranchesModel.addChangeListener(branches -> SwingUtilities.invokeLater(() -> {
            if (branches != null && !branches.isEmpty()) {
                if (branchNameField.getSelectedItem() == null && branchNameField.getItemCount() > 0) {
                    branchNameField.setSelectedIndex(0);
                }
                if (reviewAgainstBranchCombo.getSelectedItem() == null && reviewAgainstBranchCombo.getItemCount() > 0) {
                    reviewAgainstBranchCombo.setSelectedIndex(0);
                }
            }
            syncSelectedModels();
        }));
    }

    private void syncSelectedModels() {
        Object branch = branchNameField.getSelectedItem();
        if (branch != null && !branch.toString().isBlank()
                && (selectedBranchModel.getValue() == null || selectedBranchModel.getValue().isBlank())) {
            selectedBranchModel.setValue(branch.toString());
        }
        Object baseBranch = reviewAgainstBranchCombo.getSelectedItem();
        if (baseBranch != null && !baseBranch.toString().isBlank()
                && (selectedBaseBranchModel.getValue() == null || selectedBaseBranchModel.getValue().isBlank())) {
            selectedBaseBranchModel.setValue(baseBranch.toString());
        }
    }

    private ThemedLabel rightLabel(String text) {
        ThemedLabel label = new ThemedLabel(text);
        label.setHorizontalAlignment(SwingConstants.RIGHT);
        return label;
    }

    public String getBranchName() {
        Object selected = branchNameField.getSelectedItem();
        return selected != null ? selected.toString() : "";
    }

    public String getReviewAgainstBranch() {
        return (String) reviewAgainstBranchCombo.getSelectedItem();
    }

    public void setBranchName(String branch) {
        if (branch != null && !branch.isEmpty()) {
            branchNameField.setSelectedItem(branch);
        }
    }

    public void setReviewAgainstBranch(String baseBranch) {
        if (baseBranch != null && !baseBranch.isEmpty()) {
            reviewAgainstBranchCombo.setSelectedItem(baseBranch);
        }
    }

    public void setEnabled(boolean enabled) {
        branchNameField.setEnabled(enabled);
        reviewAgainstBranchCombo.setEnabled(enabled);
    }
}

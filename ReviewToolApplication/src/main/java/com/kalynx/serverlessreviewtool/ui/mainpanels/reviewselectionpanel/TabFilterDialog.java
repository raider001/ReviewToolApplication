package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewselectionpanel;

import com.kalynx.serverlessreviewtool.configuration.AppSettings;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.managers.UserManager;
import com.kalynx.serverlessreviewtool.models.Repository;
import com.kalynx.serverlessreviewtool.models.User;
import com.kalynx.serverlessreviewtool.swingextensions.ComponentModel;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.*;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * TabFilterDialog - modal dialog for configuring a new (or editing an existing) review tab filter.
 * <p>
 * Repositories support wildcard patterns (e.g. {@code *bob*}).
 * Multiple statuses may be selected; empty selection means any status.
 * <p>
 * After {@link #setVisible(boolean) setVisible(true)} returns, check {@link #isConfirmed()} and
 * call {@link #buildTabConfig()} to retrieve the resulting configuration.
 */
public class TabFilterDialog extends ThemedPopupDialog {

    private final ThemedTextField nameField   = new ThemedTextField(24);
    private final ThemedTextField titleField  = new ThemedTextField(24);
    private final ThemedTextField authorField = new ThemedTextField(24);

    private final ThemedSearchableComboBox repoCombo;
    private final ThemedButton repoAddButton   = new ThemedButton("Add");
    private final ThemedPanel  repoBadgesPanel = new ThemedPanel();
    private final ComponentModel<List<String>> selectedRepos = new ComponentModel<>();

    private final ThemedSearchableComboBox reviewerCombo;
    private final ThemedButton reviewerAddButton   = new ThemedButton("Add");
    private final ThemedPanel  reviewerBadgesPanel = new ThemedPanel();
    private final ComponentModel<List<String>> selectedReviewers = new ComponentModel<>();

    private final ThemedCheckBox statusOpen              = new ThemedCheckBox("Open");
    private final ThemedCheckBox statusInProgress        = new ThemedCheckBox("In Progress");
    private final ThemedCheckBox statusChangesRequested  = new ThemedCheckBox("Changes Requested");
    private final ThemedCheckBox statusCompleted         = new ThemedCheckBox("Completed");
    private final ThemedCheckBox statusCancelled         = new ThemedCheckBox("Cancelled");

    private final ThemedComboBox<String> involvementCombo = new ThemedComboBox<>();

    private final ThemedButton confirmButton = new ThemedButton("Add Tab");
    private final ThemedButton cancelButton  = new ThemedButton("Cancel");

    private AppSettings.ReviewTabConfig existingConfig;
    private boolean confirmed = false;

    /**
     * Creates a TabFilterDialog.
     *
     * @param parent            the owner component (used to center the dialog)
     * @param repositoryManager provides available repositories for the search combo
     * @param userManager       provides known users/reviewers for the reviewer search combo
     */
    public TabFilterDialog(Component parent, RepositoryManager repositoryManager, UserManager userManager) {
        super(parent, "Configure Tab Filter");
        this.selectedRepos.setValue(new ArrayList<>());
        this.selectedReviewers.setValue(new ArrayList<>());

        List<String> repoNames = repositoryManager.getRepositories()
            .stream().map(Repository::getName).toList();
        repoCombo = new ThemedSearchableComboBox(new ArrayList<>(repoNames));

        List<String> userNames = userManager.getUsers()
            .stream().map(User::getName).toList();
        reviewerCombo = new ThemedSearchableComboBox(new ArrayList<>(userNames));

        setDialogSize(460, 550);
        configureLayout();
        setupListeners();
    }

    /**
     * Pre-populates all fields from an existing tab config for edit mode.
     *
     * @param config the config to edit
     */
    public void loadConfig(AppSettings.ReviewTabConfig config) {
        this.existingConfig = config;
        nameField.setText(config.getName());
        titleField.setText(config.getTitleContains());
        authorField.setText(config.getAuthorContains());

        selectedRepos.setValue(new ArrayList<>(config.getRepositories()));
        refreshRepoBadges();

        selectedReviewers.setValue(new ArrayList<>(config.getReviewerPatterns()));
        refreshReviewerBadges();

        List<String> statuses = config.getStatusFilters();
        statusOpen.setSelected(statuses.contains("OPEN") || statuses.contains("ACTIVE"));
        statusInProgress.setSelected(statuses.contains("IN_PROGRESS") || statuses.contains("ACTIVE"));
        statusChangesRequested.setSelected(statuses.contains("CHANGES_REQUESTED") || statuses.contains("ACTIVE"));
        statusCompleted.setSelected(statuses.contains("COMPLETED"));
        statusCancelled.setSelected(statuses.contains("CANCELLED"));

        involvementCombo.setSelectedItem(displayInvolvement(config.getInvolvementFilter()));
        confirmButton.setText("Save");
    }

    /**
     * Returns {@code true} if the user clicked "Add Tab" / "Save".
     *
     * @return whether the dialog was confirmed
     */
    public boolean isConfirmed() {
        return confirmed;
    }

    /**
     * Builds and returns the {@link AppSettings.ReviewTabConfig} based on the dialog input.
     * Only valid when {@link #isConfirmed()} returns {@code true}.
     *
     * @return the constructed tab filter configuration
     */
    public AppSettings.ReviewTabConfig buildTabConfig() {
        String id = existingConfig != null ? existingConfig.getId() : UUID.randomUUID().toString();

        List<String> statuses = new ArrayList<>();
        if (statusOpen.isSelected())             statuses.add("OPEN");
        if (statusInProgress.isSelected())       statuses.add("IN_PROGRESS");
        if (statusChangesRequested.isSelected()) statuses.add("CHANGES_REQUESTED");
        if (statusCompleted.isSelected())        statuses.add("COMPLETED");
        if (statusCancelled.isSelected())        statuses.add("CANCELLED");

        return new AppSettings.ReviewTabConfig(
            id,
            nameField.getText().trim(),
            titleField.getText().trim(),
            authorField.getText().trim(),
            new ArrayList<>(selectedReviewers.getValue()),
            new ArrayList<>(selectedRepos.getValue()),
            statuses,
            resolveInvolvement((String) involvementCombo.getSelectedItem())
        );
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void configureLayout() {
        JPanel content = getContentPanel();
        content.setLayout(new MigLayout("insets 8 10 8 10, gap 8 6", "[][grow]", ""));

        content.add(new ThemedLabel("Tab Name:"),        "");
        content.add(nameField,                           "growx, wrap");

        content.add(new ThemedLabel("Title contains:"),    "");
        content.add(titleField,                             "growx, wrap");

        content.add(new ThemedLabel("Author contains:"),   "");
        content.add(authorField,                           "growx, wrap");

        content.add(buildReviewerSection(), "span 2, growx, wrap");
        content.add(buildRepoSection(), "span 2, growx, wrap");
        content.add(buildStatusSection(), "span 2, growx, wrap");

        content.add(new ThemedLabel("Involvement:"),     "");
        content.add(involvementCombo,                    "growx, wrap 10px");
        for (String label : List.of("Anyone", "My Reviews", "Others' Reviews")) {
            involvementCombo.addItem(label);
        }

        ThemedPanel buttons = new ThemedPanel();
        buttons.setLayout(new MigLayout("insets 0", "[grow][]8[]", ""));
        buttons.add(new ThemedPanel(), "grow");
        buttons.add(cancelButton,  "");
        buttons.add(confirmButton, "");
        content.add(buttons, "span 2, growx");
    }

    private JPanel buildReviewerSection() {
        ThemedPanel panel = new ThemedPanel();
        panel.setLayout(new MigLayout("insets 0, gap 4 4", "[grow][]", "[][]"));
        panel.setBorder(ThemedTitledBorder.create("Reviewers (wildcards: *bob*)"));

        reviewerBadgesPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
        reviewerBadgesPanel.setOpaque(false);
        ThemedScrollPane badgeScroll = new ThemedScrollPane(reviewerBadgesPanel);
        badgeScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        badgeScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        badgeScroll.setBorder(BorderFactory.createEmptyBorder());
        badgeScroll.setPreferredSize(new Dimension(0, 36));

        reviewerCombo.setToolTipText("Search or type a wildcard pattern (e.g. *bob*)…");
        reviewerAddButton.setPreferredSize(new Dimension(70, 28));

        panel.add(badgeScroll,        "span 2, growx, wrap");
        panel.add(reviewerCombo,      "growx");
        panel.add(reviewerAddButton,  "");
        return panel;
    }

    private JPanel buildRepoSection() {
        ThemedPanel panel = new ThemedPanel();
        panel.setLayout(new MigLayout("insets 0, gap 4 4", "[grow][]", "[][]"));
        panel.setBorder(ThemedTitledBorder.create("Repositories (wildcards: *bob*)"));

        repoBadgesPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
        repoBadgesPanel.setOpaque(false);
        ThemedScrollPane badgeScroll = new ThemedScrollPane(repoBadgesPanel);
        badgeScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        badgeScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        badgeScroll.setBorder(BorderFactory.createEmptyBorder());
        badgeScroll.setPreferredSize(new Dimension(0, 36));

        repoCombo.setToolTipText("Search or type a wildcard pattern (e.g. *bob*)…");
        repoAddButton.setPreferredSize(new Dimension(70, 28));

        panel.add(badgeScroll, "span 2, growx, wrap");
        panel.add(repoCombo,   "growx");
        panel.add(repoAddButton, "");
        return panel;
    }

    private JPanel buildStatusSection() {
        ThemedPanel panel = new ThemedPanel();
        panel.setLayout(new MigLayout("insets 4 6 4 6, gap 10 4, wrap 3", "[][][]", "[][]"));
        panel.setBorder(ThemedTitledBorder.create("Status (empty = any)"));
        panel.add(statusOpen);
        panel.add(statusInProgress);
        panel.add(statusChangesRequested);
        panel.add(statusCompleted);
        panel.add(statusCancelled);
        return panel;
    }

    private void setupListeners() {
        repoAddButton.addActionListener(_ -> addToList(repoCombo, selectedRepos, this::refreshRepoBadges));
        repoCombo.setOnApply(item -> {
            if (item != null && !item.isBlank()) {
                repoCombo.setSelectedItem(item);
                addToList(repoCombo, selectedRepos, this::refreshRepoBadges);
            }
        });

        reviewerAddButton.addActionListener(_ -> addToList(reviewerCombo, selectedReviewers, this::refreshReviewerBadges));
        reviewerCombo.setOnApply(item -> {
            if (item != null && !item.isBlank()) {
                reviewerCombo.setSelectedItem(item);
                addToList(reviewerCombo, selectedReviewers, this::refreshReviewerBadges);
            }
        });

        confirmButton.addActionListener(_ -> {
            if (nameField.getText().isBlank()) {
                ThemedConfirmDialog.showMessage(
                    SwingUtilities.getWindowAncestor(this),
                    "Validation",
                    "Please enter a tab name."
                );
                return;
            }
            confirmed = true;
            dispose();
        });
        cancelButton.addActionListener(_ -> dispose());
    }

    private void addToList(ThemedSearchableComboBox combo, ComponentModel<List<String>> model, Runnable refresh) {
        Object sel = combo.getSelectedItem();
        if (sel == null) return;
        String value = sel.toString().trim();
        if (value.isEmpty()) return;
        List<String> current = new ArrayList<>(model.getValue());
        if (!current.contains(value)) {
            current.add(value);
            model.setValue(current);
            refresh.run();
        }
        combo.setSelectedIndex(-1);
        ((JTextField) combo.getEditor().getEditorComponent()).setText("");
    }

    private void refreshRepoBadges() {
        refreshBadgePanel(repoBadgesPanel, selectedRepos, this::refreshRepoBadges);
    }

    private void refreshReviewerBadges() {
        refreshBadgePanel(reviewerBadgesPanel, selectedReviewers, this::refreshReviewerBadges);
    }

    private void refreshBadgePanel(ThemedPanel panel, ComponentModel<List<String>> model, Runnable refresh) {
        panel.removeAll();
        List<String> items = model.getValue();
        if (items != null) {
            for (String item : items) {
                panel.add(new ThemedBadge(item, () -> {
                    List<String> updated = new ArrayList<>(model.getValue());
                    updated.remove(item);
                    model.setValue(updated);
                    refresh.run();
                }));
            }
        }
        panel.revalidate();
        panel.repaint();
    }

    private String resolveInvolvement(String display) {
        if (display == null) return "ANY";
        return switch (display) {
            case "My Reviews"      -> "MINE";
            case "Others' Reviews" -> "OTHERS";
            default                -> "ANY";
        };
    }

    private String displayInvolvement(String internal) {
        return switch (internal) {
            case "MINE"   -> "My Reviews";
            case "OTHERS" -> "Others' Reviews";
            default       -> "Anyone";
        };
    }
}






























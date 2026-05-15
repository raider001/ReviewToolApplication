package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.reviewformdialog;

import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.swingtheme.ComponentModel;
import com.kalynx.swingtheme.themedcomponents.*;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class ReviewersPanel extends ThemedPanel {

    private final ThemedSearchableComboBox reviewerSelector = new ThemedSearchableComboBox(new ArrayList<>());
    private final ThemedButton addButton = new ThemedButton("Add");
    private final ThemedPanel badgesPanel = new ThemedPanel();
    private ThemedScrollPane badgeScroll;

    private final ComponentModel<List<ReviewerInfo>> selectedReviewersModel;
    private final ComponentModel<List<String>> availableReviewersModel;

    public ReviewersPanel(ComponentModel<List<String>> availableReviewersModel,
                         ComponentModel<List<ReviewerInfo>> selectedReviewersModel) {
        this.availableReviewersModel = availableReviewersModel;
        this.selectedReviewersModel = selectedReviewersModel;

        configureLayout();
        setupBindings();
    }

    private void configureLayout() {
        setLayout(new MigLayout(
            "",
            "[grow,fill][]",
            "[]6[]"
        ));
        setBorder(ThemedTitledBorder.create("Reviewers"));
        badgesPanel.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 2));
        badgesPanel.setOpaque(false);
        badgeScroll = new ThemedScrollPane(badgesPanel);
        badgeScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        badgeScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
        int badgeRowH = themeManager.scale(34) + badgeScroll.getHorizontalScrollBar().getPreferredSize().height;
        badgeScroll.setPreferredSize(new Dimension(0, badgeRowH));
        badgeScroll.setBorder(BorderFactory.createEmptyBorder());
        add(badgeScroll, "growx, wmin 0, span, wrap");

        reviewerSelector.setToolTipText("Search to add reviewers…");
        addButton.setPreferredSize(new Dimension(themeManager.scale(70), themeManager.scale(28)));
        add(reviewerSelector, "growx, wmin 0");
        add(addButton, "");
    }

    private void setupBindings() {
        reviewerSelector.bindTo(availableReviewersModel);

        selectedReviewersModel.addChangeListener(ignored -> SwingUtilities.invokeLater(this::updateBadges));

        updateBadges();

        reviewerSelector.setOnApply(item -> {
            if (item != null && !item.trim().isEmpty()) {
                addReviewer(item);
                reviewerSelector.setSelectedIndex(-1);
            }
        });

        addButton.addActionListener(ignored -> {
            Object selected = reviewerSelector.getSelectedItem();
            if (selected != null && !selected.toString().trim().isEmpty()) {
                addReviewer(selected.toString());
                reviewerSelector.setSelectedIndex(-1);
            }
        });
    }

    private void addReviewer(String name) {
        List<ReviewerInfo> current = selectedReviewersModel.getValue();
        if (current == null) {
            current = new ArrayList<>();
        }

        boolean alreadyExists = current.stream()
            .anyMatch(reviewer -> reviewer.getName().equals(name));

        if (!alreadyExists) {
            List<ReviewerInfo> updated = new ArrayList<>(current);
            updated.add(new ReviewerInfo(name));
            selectedReviewersModel.setValue(updated);
        }
    }

    private void updateBadges() {
        badgesPanel.removeAll();
        List<ReviewerInfo> selected = selectedReviewersModel.getValue();
        if (selected != null) {
            for (ReviewerInfo reviewer : selected) {
                ThemedBadge badge = new ThemedBadge(reviewer.getName(), () -> removeReviewer(reviewer));
                badge.setCustomColor(reviewer.getStatus().getColor());
                badgesPanel.add(badge);
            }
        }
        badgesPanel.revalidate();
        badgesPanel.repaint();
        if (badgeScroll != null) {
            badgeScroll.revalidate();
            badgeScroll.repaint();
        }
    }

    private void removeReviewer(ReviewerInfo reviewer) {
        List<ReviewerInfo> current = selectedReviewersModel.getValue();
        if (current != null) {
            List<ReviewerInfo> updated = new ArrayList<>(current);
            updated.removeIf(r -> r.getName().equals(reviewer.getName()));
            selectedReviewersModel.setValue(updated);
        }
    }
}


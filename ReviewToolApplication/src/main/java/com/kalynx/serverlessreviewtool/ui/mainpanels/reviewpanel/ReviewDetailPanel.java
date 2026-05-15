package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.models.ReviewStatus;
import com.kalynx.serverlessreviewtool.models.ReviewerInfo;
import com.kalynx.serverlessreviewtool.models.ReviewerStatus;
import com.kalynx.swingtheme.themedcomponents.ThemedBadge;
import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedConfirmDialog;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewDetailModel;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Panel displaying the header information for a code review context.
 * Shows review title, status badge, author, summary, and reviewer badges.
 * Reviewer badges are clickable to change reviewer status.
 * Includes edit and close review buttons that can be configured with custom actions.
 */
public class ReviewDetailPanel extends ThemedPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewDetailPanel.class);

    private final ReviewDetailModel reviewDetailModel;

    private final ThemedBadge headerStatusBadge = new ThemedBadge("Status").setCustomColor(Color.DARK_GRAY);
    private final ThemedLabel titleLabel = new ThemedLabel("");
    private final ThemedPanel headerButtonPanel = new ThemedPanel();
    private final ThemedButton editReviewButton = new ThemedButton("Edit");
    private final ThemedButton closeReviewButton = new ThemedButton("Close Review");
    private final ThemedButton markInProgressButton = new ThemedButton("Mark In Progress");
    private final ThemedButton cancelReviewButton = new ThemedButton("Cancel Review");

    private final ThemedLabel authorLabel = new ThemedLabel("author..");
    private final ThemedLabel summaryLabel = new ThemedLabel("summary..");

    private final ThemedPanel reviewerPanel = new ThemedPanel();

    private Runnable onEditAction;
    private Consumer<Boolean> onReviewerStatusChanged;
    private Runnable onJoinReviewAction;
    private Runnable onLeaveReviewAction;
    private Runnable onCloseReviewAction;
    private Runnable onMarkInProgressAction;
    private Runnable onCancelReviewAction;
    private Consumer<String> onReRequestReview;

    private String currentUserName;
    private boolean isCurrentUserReviewer = false;
    private boolean isCurrentUserAuthor = false;
    private boolean hasCheckedReviewerStatus = false;
    private ReviewStatus currentStatus;

    public ReviewDetailPanel(SettingsManager settingsManager, ReviewDetailModel reviewDetailModel) {
        this.reviewDetailModel = reviewDetailModel;
        this.currentUserName = settingsManager.getCurrentUserName();

        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        authorLabel.setFont(authorLabel.getFont().deriveFont(Font.ITALIC));

        configureLayout();
        configureReviewContextListeners();
        setupListeners();
        updateButtonStates();
        settingsManager.addUserNameListener(this::onCurrentUserChanged);
    }

    private void configureLayout() {
        headerButtonPanel.setLayout(new MigLayout("insets 0, hidemode 3", "", ""));
        headerButtonPanel.add(editReviewButton);
        headerButtonPanel.add(closeReviewButton);
        headerButtonPanel.add(markInProgressButton);
        headerButtonPanel.add(cancelReviewButton);

        setLayout(new MigLayout("", "[]5lp[grow][]", "[]8lp[]6lp[]10lp[]"));
        add(headerStatusBadge, "cell 0 0");
        add(titleLabel, "cell 1 0");
        add(headerButtonPanel, "cell 2 0");

        add(authorLabel, "cell 0 1 3 1");
        add(summaryLabel, "cell 0 2 3 1");
        add(reviewerPanel, "cell 0 3 3 1");
    }

    private void configureReviewContextListeners() {

        reviewDetailModel.title.addChangeListener(val -> setLabelText(titleLabel, () -> val != null ? val : ""));
        reviewDetailModel.author.addChangeListener(val -> {
            setLabelText(authorLabel, () -> val != null ? "Authored By " + val : "");
            checkAuthorStatus(val);
        });
        reviewDetailModel.summary.addChangeListener(val -> setLabelText(summaryLabel, () -> val != null ? val : ""));
        reviewDetailModel.status.addChangeListener(status -> {
            currentStatus = status;
            updateStatusBadge(status);
            SwingUtilities.invokeLater(this::updateButtonStates);
        });

        reviewDetailModel.reviewers.addChangeListener(val -> {
            reviewerPanel.removeAll();
            if (val != null) {
                val.forEach(reviewer -> reviewerPanel.add(createReviewerBadge(reviewer)));
                checkReviewerStatus(val);
            } else {
                checkReviewerStatus(List.of());
            }
            reviewerPanel.revalidate();
            reviewerPanel.repaint();
        });
    }

    private ThemedBadge createReviewerBadge(ReviewerInfo reviewer) {
        ThemedBadge badge = new ThemedBadge(
            reviewer.getName() + "  ·  " + reviewer.getStatus().getDisplayName());
        badge.setCustomColor(reviewer.getStatus().getColor());

        if (reviewer.getStatus() != ReviewerStatus.REVIEWING) {
            badge.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            badge.setToolTipText("Click to request re-review from " + reviewer.getName());
            badge.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    onReRequestReviewBadgeClicked(reviewer);
                }
            });
        }

        return badge;
    }

    private void onReRequestReviewBadgeClicked(ReviewerInfo reviewer) {
        if (onReRequestReview == null) {
            LOGGER.warn("Re-request review action not configured");
            return;
        }
        Window ancestor = SwingUtilities.getWindowAncestor(this);
        boolean confirmed = ThemedConfirmDialog.showConfirmation(
            ancestor,
            "Request Re-review",
            "Request re-review from " + reviewer.getName() + "?"
        );
        if (confirmed) {
            onReRequestReview.accept(reviewer.getName());
        }
    }

    private void updateStatusBadge(ReviewStatus status) {
        SwingUtilities.invokeLater(() -> {
            if (status != null) {
                headerStatusBadge.setText(status.toString());
                headerStatusBadge.setCustomColor(status.getColor());
            } else {
                headerStatusBadge.setText("Unknown");
                headerStatusBadge.setCustomColor(Color.DARK_GRAY);
            }
        });
    }

    private void checkReviewerStatus(List<ReviewerInfo> reviewers) {
        boolean wasReviewer = isCurrentUserReviewer;
        isCurrentUserReviewer = reviewers.stream()
            .anyMatch(reviewer -> reviewer.getName().equals(currentUserName));

        if (!hasCheckedReviewerStatus || wasReviewer != isCurrentUserReviewer) {
            hasCheckedReviewerStatus = true;
            SwingUtilities.invokeLater(this::updateButtonStates);
            if (onReviewerStatusChanged != null) {
                onReviewerStatusChanged.accept(isCurrentUserReviewer);
            }
        }
    }

    private void onCurrentUserChanged(String currentUserName) {
        this.currentUserName = currentUserName != null ? currentUserName : "";
        checkAuthorStatus(reviewDetailModel.author.getValue());
        List<ReviewerInfo> reviewers = reviewDetailModel.reviewers.getValue();
        checkReviewerStatus(reviewers != null ? reviewers : List.of());
    }

    private void checkAuthorStatus(String author) {
        String normalizedAuthor = author == null ? "" : author.trim();
        String normalizedCurrentUser = currentUserName == null ? "" : currentUserName.trim();
        boolean wasAuthor = isCurrentUserAuthor;
        isCurrentUserAuthor = !normalizedAuthor.isEmpty() && normalizedAuthor.equals(normalizedCurrentUser);
        if (wasAuthor != isCurrentUserAuthor) {
            SwingUtilities.invokeLater(this::updateButtonStates);
        }
    }

    private void updateButtonStates() {
        boolean isTerminal = currentStatus == ReviewStatus.COMPLETED || currentStatus == ReviewStatus.CANCELLED;

        if (isCurrentUserReviewer && isCurrentUserAuthor) {
            editReviewButton.setEnabled(true);
            closeReviewButton.setText("Close Review");
            closeReviewButton.setEnabled(!isTerminal);
        } else if (isCurrentUserReviewer) {
            editReviewButton.setEnabled(true);
            closeReviewButton.setText("Leave Review");
            closeReviewButton.setEnabled(true);
        } else {
            editReviewButton.setEnabled(false);
            closeReviewButton.setText("Join Review");
            closeReviewButton.setEnabled(true);
        }

        markInProgressButton.setVisible(isCurrentUserAuthor);
        markInProgressButton.setEnabled(!isTerminal && currentStatus != ReviewStatus.IN_PROGRESS);

        cancelReviewButton.setVisible(isCurrentUserAuthor);
        cancelReviewButton.setEnabled(!isTerminal);
    }

    private void setupListeners() {
        editReviewButton.addActionListener(this::handleEditReview);
        closeReviewButton.addActionListener(this::handleCloseOrJoinReview);
        markInProgressButton.addActionListener(this::handleMarkInProgress);
        cancelReviewButton.addActionListener(this::handleCancelReview);
    }

    private void handleEditReview() {
        if (onEditAction != null) {
            onEditAction.run();
        } else {
            LOGGER.warn("Edit action not configured");
        }
    }

    private void handleCloseOrJoinReview() {
        if (isCurrentUserReviewer && isCurrentUserAuthor) {
            if (onCloseReviewAction != null) {
                onCloseReviewAction.run();
            } else {
                LOGGER.warn("Close review action not configured");
            }
        } else if (isCurrentUserReviewer) {
            if (onLeaveReviewAction != null) {
                onLeaveReviewAction.run();
            } else {
                LOGGER.warn("Leave review action not configured");
            }
        } else {
            if (onJoinReviewAction != null) {
                onJoinReviewAction.run();
            } else {
                LOGGER.warn("Join review action not configured");
            }
        }
    }

    private void handleMarkInProgress() {
        if (onMarkInProgressAction != null) {
            onMarkInProgressAction.run();
        } else {
            LOGGER.warn("Mark in progress action not configured");
        }
    }

    private void handleCancelReview() {
        if (onCancelReviewAction != null) {
            onCancelReviewAction.run();
        } else {
            LOGGER.warn("Cancel review action not configured");
        }
    }

    public void setOnEditAction(Runnable action) {
        this.onEditAction = action;
    }

    public void setOnJoinReviewAction(Runnable action) {
        this.onJoinReviewAction = action;
    }

    public void setOnLeaveReviewAction(Runnable action) {
        this.onLeaveReviewAction = action;
    }

    /**
     * Set callback for closing review when current user is both author and reviewer.
     *
     * @param action action to run when close review is clicked
     */
    public void setOnCloseReviewAction(Runnable action) {
        this.onCloseReviewAction = action;
    }

    /**
     * Set callback for marking the review as in progress. Available to the author only.
     *
     * @param action action to run when mark in progress is clicked
     */
    public void setOnMarkInProgressAction(Runnable action) {
        this.onMarkInProgressAction = action;
    }

    /**
     * Set callback for cancelling the review. Available to the author only.
     *
     * @param action action to run when cancel review is clicked
     */
    public void setOnCancelReviewAction(Runnable action) {
        this.onCancelReviewAction = action;
    }

    public void setOnReviewerStatusChanged(Consumer<Boolean> callback) {
        this.onReviewerStatusChanged = callback;
    }

    /**
     * Sets the callback invoked when the user requests a re-review from a reviewer.
     * The callback receives the name of the reviewer to reset.
     *
     * @param callback consumer receiving the reviewer's name
     */
    public void setOnReRequestReview(Consumer<String> callback) {
        this.onReRequestReview = callback;
    }

    private void setLabelText(ThemedLabel label, Supplier<String> textSetter) {
        SwingUtilities.invokeLater(() -> label.setText(textSetter.get()));
    }
}

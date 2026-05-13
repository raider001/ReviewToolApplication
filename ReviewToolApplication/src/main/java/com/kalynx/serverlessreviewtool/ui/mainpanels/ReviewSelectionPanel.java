package com.kalynx.serverlessreviewtool.ui.mainpanels;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.managers.ReviewItemManager;
import com.kalynx.serverlessreviewtool.managers.UserManager;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedButton;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedPanel;
import com.kalynx.serverlessreviewtool.theme.LoadingStateManager;
import com.kalynx.serverlessreviewtool.ui.Refreshable;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewselectionpanel.ReviewSelectionPanelModel;
import com.kalynx.serverlessreviewtool.ui.models.reviewpanel.reviewformdialog.ReviewFormModels;
import com.kalynx.serverlessreviewtool.ui.review.CreateReviewDialog;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewselectionpanel.ReviewTabsPanel;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.function.Consumer;

/**
 * ReviewSelectionPanel - Main UI for selecting and filtering code reviews.
 * <p>
 * Reviews are organised into user-configurable, persistent tabs managed by
 * {@link ReviewTabsPanel}.  Tabs are draggable, renameable, and deleteable;
 * new tabs can be added via the "+" button at the far right of the tab bar,
 * each with its own filter configuration.
 */
public class ReviewSelectionPanel extends ThemedPanel implements Refreshable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewSelectionPanel.class);

    private final ReviewTabsPanel reviewTabsPanel;
    private final ReviewFormModels reviewFormModels;
    private final RepositoryManager repositoryManager;
    private final ReviewItemManager reviewItemManager;
    private final Git git;

    private final ThemedButton createReviewButton = new ThemedButton("Create Review");

    public ReviewSelectionPanel(RepositoryManager repositoryManager,
                                ReviewItemManager reviewItemManager,
                                ReviewSelectionPanelModel reviewSelectionPanelModel,
                                ReviewFormModels reviewFormModels,
                                Git git,
                                SettingsManager settingsManager,
                                UserManager userManager) {
        this.reviewFormModels = reviewFormModels;
        this.repositoryManager = repositoryManager;
        this.reviewItemManager = reviewItemManager;
        this.git = git;
        this.reviewTabsPanel = new ReviewTabsPanel(reviewSelectionPanelModel, settingsManager, repositoryManager, userManager);
        configureLayout();
        configureActions();
    }

    private void configureLayout() {
        setLayout(new MigLayout("", "[grow]", "[grow][]"));
        setOpaque(true);
        add(reviewTabsPanel,    "cell 0 0, grow");
        add(createReviewButton, "cell 0 1, align right");
    }

    private void configureActions() {
        createReviewButton.addActionListener(ignored -> onCreateReview());
    }

    /**
     * Registers a callback invoked when the user double-clicks a review item.
     *
     * @param callback receives the selected {@link ReviewItem}
     */
    public void setOnReviewDoubleClick(Consumer<ReviewItem> callback) {
        reviewTabsPanel.setOnReviewDoubleClick(callback);
    }

    /**
     * Handle create review button click
     */
    private void onCreateReview() {
        CreateReviewDialog dialog = new CreateReviewDialog(
            SwingUtilities.getWindowAncestor(this),
            reviewFormModels,
            repositoryManager,
            git
        );
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            onRefresh();
        }
    }

    /**
     * Called when the panel is shown in the MainFrame.
     */
    public void onPanelShown() {
        onRefresh();
    }

    @Override
    public void onRefresh() {
        LoadingStateManager.getInstance().startLoading("review-refresh");
        reviewItemManager.refresh()
            .whenComplete((_, error) -> {
                LoadingStateManager.getInstance().stopLoading("review-refresh");
                if (error != null) {
                    LOGGER.error("Error refreshing reviews", error);
                }
            });
    }
}
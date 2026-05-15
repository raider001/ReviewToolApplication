package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewselectionpanel;

import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedScrollPane;
import com.kalynx.swingtheme.themedcomponents.ThemedTabbedPane;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewselectionpanel.ReviewSelectionPanelModel;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.util.List;
import java.util.function.Consumer;

public class ReviewListPanel extends ThemedPanel {

    private final ReviewSelectionPanelModel model;

    private final ThemedTabbedPane tabbedPane = new ThemedTabbedPane();
    private final ReviewList       allReviewsList = new ReviewList();
    private final ReviewList       myReviewsList  = new ReviewList();
    private final ReviewList       myOpenReviewsList = new ReviewList();
    private final ReviewList       completedReviewsList = new ReviewList();

    public ReviewListPanel(ReviewSelectionPanelModel model) {
        this.model = model;
         configureLayout();
         setupListeners();
    }

    private void configureLayout() {
         setLayout(new MigLayout("", "[grow]", "[grow]"));

         ThemedScrollPane myReviewsScrollPane = new ThemedScrollPane(myReviewsList);
         tabbedPane.addTab("My Reviews", myReviewsScrollPane);
         ThemedScrollPane myOpenReviewsScrollPane = new ThemedScrollPane(myOpenReviewsList);
         tabbedPane.addTab("Open Reviews", myOpenReviewsScrollPane);

         ThemedScrollPane completedReviewsScrollPane = new ThemedScrollPane(completedReviewsList);
         tabbedPane.addTab("Completed Reviews", completedReviewsScrollPane);

         ThemedScrollPane allReviewsScrollPane = new ThemedScrollPane(allReviewsList);
         tabbedPane.addTab("All Reviews", allReviewsScrollPane);

         add(tabbedPane, "cell 0 0, grow");
     }

    private void updateTabs() {
         tabbedPane.setTabTitleWithCount(0, "My Reviews", myReviewsList.getModel().getSize());
         tabbedPane.setTabTitleWithCount(1, "Open Reviews", myOpenReviewsList.getModel().getSize());
         tabbedPane.setTabTitleWithCount(2, "Completed", completedReviewsList.getModel().getSize());
         tabbedPane.setTabTitleWithCount(3, "All Reviews", allReviewsList.getModel().getSize());
     }

    private void setupListeners() {
        model.allReviewsFiltered.addChangeListener(reviews -> updateList(allReviewsList, reviews));
        model.myReviews.addChangeListener(reviews -> updateList(myReviewsList, reviews));
        model.openReviews.addChangeListener(reviews -> updateList(myOpenReviewsList, reviews));
        model.completedReviews.addChangeListener(reviews -> updateList(completedReviewsList, reviews));
    }

    public void setOnReviewDoubleClick(Consumer<ReviewItem> callback) {
        allReviewsList.onDoubleClick(callback);
        myReviewsList.onDoubleClick(callback);
        myOpenReviewsList.onDoubleClick(callback);
        completedReviewsList.onDoubleClick(callback);
    }

    public void applyFilters(String titleFilter, String authorFilter, List<String> repositoryFilter) {
        model.setFilters(titleFilter, authorFilter, repositoryFilter);
    }

    private void updateList(ReviewList list, List<ReviewItem> reviews) {
        SwingUtilities.invokeLater(() -> {
            DefaultListModel<ReviewItem> listModel = (DefaultListModel<ReviewItem>) list.getModel();
            listModel.removeAllElements();
            if (reviews != null) {
                reviews.forEach(listModel::addElement);
            }
            updateTabs();
        });
    }
}

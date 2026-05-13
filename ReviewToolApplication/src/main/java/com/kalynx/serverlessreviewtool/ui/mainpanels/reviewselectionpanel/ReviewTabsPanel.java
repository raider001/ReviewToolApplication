package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewselectionpanel;

import com.kalynx.serverlessreviewtool.configuration.AppSettings;
import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.managers.UserManager;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedPanel;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedScrollPane;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewselectionpanel.ReviewSelectionPanelModel;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * ReviewTabsPanel - dynamic tab panel driven by user-configurable {@link AppSettings.ReviewTabConfig} entries.
 * <p>
 * Each tab owns a {@link ReviewList} whose contents are filtered from the full review list whenever
 * {@code model.allReviews} changes.  Tab order, names, and filter settings are persisted via
 * {@link SettingsManager}.
 */
public class ReviewTabsPanel extends ThemedPanel {

    private final ReviewSelectionPanelModel model;
    private final SettingsManager settingsManager;
    private final RepositoryManager repositoryManager;
    private final UserManager userManager;

    private final DraggableTabbedPane tabbedPane = new DraggableTabbedPane();

    private final List<AppSettings.ReviewTabConfig> tabConfigs = new ArrayList<>();
    private final List<ReviewList> reviewLists = new ArrayList<>();
    private Consumer<ReviewItem> reviewDoubleClickCallback;

    public ReviewTabsPanel(ReviewSelectionPanelModel model,
                           SettingsManager settingsManager,
                           RepositoryManager repositoryManager,
                           UserManager userManager) {
        this.model = model;
        this.settingsManager = settingsManager;
        this.repositoryManager = repositoryManager;
        this.userManager = userManager;
        configureLayout();
        loadPersistedTabs();
        setupListeners();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Registers a double-click callback that is forwarded to every review list in all tabs.
     *
     * @param callback the consumer receiving the double-clicked review item
     */
    public void setOnReviewDoubleClick(Consumer<ReviewItem> callback) {
        this.reviewDoubleClickCallback = callback;
        reviewLists.forEach(list -> list.onDoubleClick(callback));
    }

    // -------------------------------------------------------------------------
    // Private implementation
    // -------------------------------------------------------------------------

    private void configureLayout() {
        setLayout(new MigLayout("", "[grow]", "[grow]"));
        add(tabbedPane, "cell 0 0, grow");
    }

    private void loadPersistedTabs() {
        tabConfigs.clear();
        reviewLists.clear();
        List<AppSettings.ReviewTabConfig> persisted = settingsManager.getSettings().getReviewTabs();
        persisted.forEach(this::appendTab);
    }

    private void reloadFromSettings() {
        while (tabbedPane.getUserTabCount() > 0) {
            tabbedPane.remove(0);
        }
        tabConfigs.clear();
        reviewLists.clear();
        settingsManager.getSettings().getReviewTabs().forEach(this::appendTab);
        refreshAllTabs();
    }

    private void setupListeners() {
        model.allReviews.addChangeListener(_ -> SwingUtilities.invokeLater(this::refreshAllTabs));
        settingsManager.addReviewTabsListener(() -> SwingUtilities.invokeLater(this::reloadFromSettings));

        tabbedPane.setOnAddRequested(this::onAddTabRequested);

        tabbedPane.setOnEditFilterRequested(index -> {
            if (index >= 0 && index < tabConfigs.size()) {
                onEditTabRequested(index);
            }
        });

        tabbedPane.setOnTabRenamed((index, newName) -> {
            if (index >= 0 && index < tabConfigs.size()) {
                tabConfigs.get(index).setName(newName);
                persistTabs();
            }
        });

        tabbedPane.setOnTabRemoved(index -> {
            if (index >= 0 && index < tabConfigs.size()) {
                tabConfigs.remove(index.intValue());
                reviewLists.remove(index.intValue());
                persistTabs();
            }
        });

        tabbedPane.setOnOrderChanged(this::onOrderChanged);
    }

    private void appendTab(AppSettings.ReviewTabConfig config) {
        tabConfigs.add(config);
        ReviewList list = new ReviewList();
        if (reviewDoubleClickCallback != null) {
            list.onDoubleClick(reviewDoubleClickCallback);
        }
        reviewLists.add(list);
        tabbedPane.addUserTab(config.getName(), new ThemedScrollPane(list));
    }

    private void refreshAllTabs() {
        for (int i = 0; i < tabConfigs.size(); i++) {
            AppSettings.ReviewTabConfig config = tabConfigs.get(i);
            List<ReviewItem> filtered = model.filterForTab(config);
            ReviewList list = reviewLists.get(i);
            DefaultListModel<ReviewItem> listModel = (DefaultListModel<ReviewItem>) list.getModel();
            listModel.removeAllElements();
            filtered.forEach(listModel::addElement);
            tabbedPane.setUserTabCount(i, config.getName(), filtered.size());
        }
    }

    private void onAddTabRequested() {
        TabFilterDialog dialog = new TabFilterDialog(this, repositoryManager, userManager);
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            AppSettings.ReviewTabConfig config = dialog.buildTabConfig();
            appendTab(config);
            persistTabs();
            refreshAllTabs();
            tabbedPane.setSelectedIndex(tabbedPane.getUserTabCount() - 1);
        }
    }

    private void onEditTabRequested(int index) {
        TabFilterDialog dialog = new TabFilterDialog(this, repositoryManager, userManager);
        dialog.loadConfig(tabConfigs.get(index));
        dialog.setVisible(true);

        if (dialog.isConfirmed()) {
            AppSettings.ReviewTabConfig updated = dialog.buildTabConfig();
            tabConfigs.set(index, updated);
            tabbedPane.renameUserTab(index, updated.getName());
            persistTabs();
            refreshAllTabs();
        }
    }

    private void onOrderChanged() {
        List<AppSettings.ReviewTabConfig> reordered = new ArrayList<>();
        List<ReviewList> reorderedLists = new ArrayList<>();

        for (int i = 0; i < tabbedPane.getUserTabCount(); i++) {
            String title = tabbedPane.getTitleAt(i);
            for (int j = 0; j < tabConfigs.size(); j++) {
                if (tabConfigs.get(j).getName().equals(stripCount(title))) {
                    reordered.add(tabConfigs.get(j));
                    reorderedLists.add(reviewLists.get(j));
                    break;
                }
            }
        }

        tabConfigs.clear();
        tabConfigs.addAll(reordered);
        reviewLists.clear();
        reviewLists.addAll(reorderedLists);

        persistTabs();
    }

    private void persistTabs() {
        settingsManager.getSettings().setReviewTabs(new ArrayList<>(tabConfigs));
        settingsManager.saveSettings();
    }

    private String stripCount(String title) {
        int idx = title.lastIndexOf(" (");
        return idx >= 0 ? title.substring(0, idx) : title;
    }
}
















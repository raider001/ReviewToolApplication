package com.kalynx.serverlessreviewtool.ui;

import com.kalynx.lwdi.DI;
import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.git.Git;
import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.managers.RepositoryManager;
import com.kalynx.serverlessreviewtool.managers.ReviewContextManager;
import com.kalynx.serverlessreviewtool.managers.ReviewItemManager;
import com.kalynx.serverlessreviewtool.managers.UserManager;
import com.kalynx.serverlessreviewtool.models.ReviewItem;
import com.kalynx.serverlessreviewtool.notifications.ReviewNotificationService;
import com.kalynx.serverlessreviewtool.notifications.ReviewStatusChangeCondition;
import com.kalynx.serverlessreviewtool.notifications.ReviewerAddedCondition;
import com.kalynx.serverlessreviewtool.notifications.SystemNotifier;
import com.kalynx.serverlessreviewtool.plugin.PluginPanel;
import com.kalynx.swingtheme.themedcomponents.QuickButton;
import com.kalynx.swingtheme.themedcomponents.ThemedFrame;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.theme.icons.AppIcon;
import com.kalynx.swingtheme.theme.icons.RefreshIcon;
import com.kalynx.serverlessreviewtool.ui.mainpanels.LoginPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.LogsPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.ReviewPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.ReviewSelectionPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.SettingsPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel.SwipeActionPanel;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewpanel.ReviewPanelModel;
import com.kalynx.serverlessreviewtool.ui.models.mainpanels.reviewselectionpanel.ReviewSelectionPanelModel;
import com.kalynx.serverlessreviewtool.ui.models.reviewpanel.reviewformdialog.ReviewFormModels;
import com.kalynx.serverlessreviewtool.utils.ConsoleLogBridge;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * MainFrame - The main application window.
 * All navigation panels — built-in and plugin-contributed — are registered as
 * {@link NavEntry} objects at initialisation time and sorted by priority when
 * the menu is (re)built.
 */
public class MainFrame extends ThemedFrame {

    private static final int PRIORITY_REVIEWS = 10;
    private static final int PRIORITY_REVIEW_CODE = 20;
    private static final int PRIORITY_SETTINGS = 30;
    private static final int PRIORITY_LOGS = 40;
    private static final int PRIORITY_HELP = 80;
    private static final int PRIORITY_LOGOUT = 90;

    private final SettingsManager settingsManager;
    private final PluginManager pluginManager;
    private final RepositoryManager repositoryManager;
    private final ReviewItemManager reviewItemManager;
    private final ReviewContextManager reviewContextManager;
    private final ReviewFormModels reviewFormModels;
    private final ReviewSelectionPanelModel reviewSelectionPanelModel;
    private final ReviewPanelModel reviewPanelModel;
    private final UserManager userManager;
    private final Git git;

    private LoginPanel loginPanel;
    private ReviewSelectionPanel reviewSelectionPanel;
    private ReviewPanel reviewPanel;
    private SwipeActionPanel swipeActionPanel;
    private SettingsPanel settingsPanel;
    private LogsPanel logsPanel;
    private HelpPanel helpPanel;
    private ThemedPanel currentPanel;
    private QuickButton refreshButton;

    private final List<NavEntry> registeredEntries = new ArrayList<>();

    @DI
    public MainFrame(
            SettingsManager settingsManager,
            PluginManager pluginManager,
            RepositoryManager repositoryManager,
            ReviewItemManager reviewItemManager,
            ReviewContextManager reviewContextManager,
            ReviewFormModels reviewFormModels,
            ReviewSelectionPanelModel reviewSelectionPanelModel,
            ReviewPanelModel reviewPanelModel,
            UserManager userManager,
            Git git) {
        super("Serverless Review Tool",
              settingsManager.getSettings().getWindow().getDefaultWidth(),
              settingsManager.getSettings().getWindow().getDefaultHeight());
        this.settingsManager = settingsManager;
        this.pluginManager = pluginManager;
        this.repositoryManager = repositoryManager;
        this.reviewItemManager = reviewItemManager;
        this.reviewContextManager = reviewContextManager;
        this.reviewFormModels = reviewFormModels;
        this.reviewSelectionPanelModel = reviewSelectionPanelModel;
        this.reviewPanelModel = reviewPanelModel;
        this.userManager = userManager;
        this.git = git;
        setApplicationIcon(AppIcon.createIconImages());
        initializePanels();
        setupMenuItems();
        setupRefreshButton();
        setupEasterEgg();
        setupReviewDoubleClickHandler();
        setupLoginStateListener();
        if (needsLogin()) {
            showLoginPanel();
        } else {
            showReviewPanel();
        }
    }

    private void setupLoginStateListener() {
        settingsManager.addUserNameListener(ignored -> {
            if (needsLogin()) {
                SwingUtilities.invokeLater(() -> {
                    setupMenuItems();
                    showLoginPanel();
                });
            } else {
                SwingUtilities.invokeLater(this::setupMenuItems);
            }
        });
    }

    private boolean needsLogin() {
        return pluginManager.hasUserPlugins() && !settingsManager.isLoggedIn();
    }

    private void showLoginPanel() {
        setupMenuItems();
        switchPanel(loginPanel);
        setWindowTitle("Serverless Review Tool - Login");
    }

    private void setupRefreshButton() {
        refreshButton = createRefreshButton();
        refreshButton.addActionListener(this::onRefresh);
        getTitleBar().addActionButton(refreshButton);
    }

    private void setupEasterEgg() {
        EasterEggSpritePanel spritePanel = new EasterEggSpritePanel();
        getTitleBar().add(spritePanel, BorderLayout.CENTER);
        new EasterEggController(spritePanel).initialize();
    }

    private void onRefresh() {
        if (currentPanel instanceof Refreshable) {
            ((Refreshable) currentPanel).onRefresh();
        }
    }

    private QuickButton createRefreshButton() {
        return new QuickButton(new RefreshIcon())
            .setTooltip("Refresh")
            .setAccentHover();
    }

    private void initializePanels() {
        loginPanel = new LoginPanel(settingsManager, pluginManager);
        loginPanel.setOnLoginSuccess(this::showReviewPanel);

        reviewSelectionPanel = new ReviewSelectionPanel(repositoryManager, reviewItemManager, reviewSelectionPanelModel, reviewFormModels, git, settingsManager, userManager);
        reviewPanel = new ReviewPanel(settingsManager, reviewContextManager, repositoryManager, reviewFormModels, reviewPanelModel, git, pluginManager);
        swipeActionPanel = new SwipeActionPanel(reviewPanel);

        swipeActionPanel.setOnApprove(reviewPanel::handleApprove);
        swipeActionPanel.setOnRequestChanges(reviewPanel::handleRequestChanges);

        reviewPanel.addReviewerStatusListener(swipeActionPanel::setEnabled);

        settingsPanel = new SettingsPanel(settingsManager, pluginManager);
        logsPanel = new LogsPanel();
        helpPanel = new HelpPanel();

        ConsoleLogBridge.attachLogsPanel(logsPanel);

        ReviewNotificationService notificationService = new ReviewNotificationService(
            SystemNotifier.getInstance(),
            settingsManager::getLoggedInUserName
        );
        notificationService.addCondition(new ReviewStatusChangeCondition());
        notificationService.addCondition(new ReviewerAddedCondition());
        reviewContextManager.addListener(notificationService::onContextChanged);

        registerNavEntries();
    }

    private void registerNavEntries() {
        registeredEntries.add(new NavEntry("Reviews",     PRIORITY_REVIEWS,     this::showReviewPanel));
        registeredEntries.add(new NavEntry("Review Code", PRIORITY_REVIEW_CODE, this::showCodeReviewPanel));
        registeredEntries.add(new NavEntry("Settings",    PRIORITY_SETTINGS,    this::showSettingsPanel));
        registeredEntries.add(new NavEntry("Logs",        PRIORITY_LOGS,        this::showLogsPanel));
        registeredEntries.add(new NavEntry("Help",        PRIORITY_HELP,        this::showHelpPanel));
        registeredEntries.add(new NavEntry("Log Out",     PRIORITY_LOGOUT,      this::onLogout,        settingsManager::isLoggedIn));

        for (PluginPanel pluginPanel : pluginManager.getPluginPanels()) {
            ThemedPanel wrapper = new ThemedPanel(new BorderLayout());
            wrapper.add(pluginPanel.panel(), BorderLayout.CENTER);
            String title = pluginPanel.title();
            registeredEntries.add(new NavEntry(title, pluginPanel.priority(),
                () -> showPluginPanel(wrapper, title)));
        }
    }

    private void setupReviewDoubleClickHandler() {
        reviewSelectionPanel.setOnReviewDoubleClick(this::onReviewDoubleClicked);
    }

    private void onReviewDoubleClicked(ReviewItem reviewItem) {
        reviewPanel.loadReview(reviewItem);
        showCodeReviewPanel();
    }

    private void setupMenuItems() {
        if (needsLogin()) {
            setMenuItems(new MenuItem("Login", this::showLoginPanel));
            return;
        }

        MenuItem[] items = registeredEntries.stream()
            .filter(e -> e.visible().getAsBoolean())
            .sorted(Comparator.comparingInt(NavEntry::priority))
            .map(e -> new MenuItem(e.title(), e.action()))
            .toArray(MenuItem[]::new);
        setMenuItems(items);
    }

    private void onLogout() {
        settingsManager.logoutUser();
    }

    private void showReviewPanel() {
        if (needsLogin()) {
            showLoginPanel();
            return;
        }
        setupMenuItems();
        switchPanel(reviewSelectionPanel);
        setWindowTitle("Serverless Review Tool - Reviews");
    }

    private void showCodeReviewPanel() {
        if (needsLogin()) {
            showLoginPanel();
            return;
        }
        switchPanel(swipeActionPanel);
        setWindowTitle("Serverless Review Tool - Code Review");
    }

    private void showSettingsPanel() {
        if (needsLogin()) {
            showLoginPanel();
            return;
        }
        switchPanel(settingsPanel);
        setWindowTitle("Serverless Review Tool - Settings");
    }

    private void showLogsPanel() {
        if (needsLogin()) {
            showLoginPanel();
            return;
        }
        switchPanel(logsPanel);
        setWindowTitle("Serverless Review Tool - Logs");
    }

    private void showHelpPanel() {
        if (needsLogin()) {
            showLoginPanel();
            return;
        }
        switchPanel(helpPanel);
        setWindowTitle("Serverless Review Tool - Help");
    }

    private void showPluginPanel(ThemedPanel panel, String title) {
        if (needsLogin()) {
            showLoginPanel();
            return;
        }
        switchPanel(panel);
        setWindowTitle("Serverless Review Tool - " + title);
    }

    private void switchPanel(ThemedPanel newPanel) {
        if (needsLogin() && newPanel != loginPanel) {
            newPanel = loginPanel;
        }

        if (currentPanel != null) {
            getContentPanel().remove(currentPanel);
        }

        if (newPanel instanceof SwipeActionPanel) {
            getContentPanel().setBorder(null);
        } else {
            getContentPanel().setBorder(BorderFactory.createEmptyBorder(
                themeManager.scale(10),
                themeManager.scale(10),
                themeManager.scale(10),
                themeManager.scale(10)
            ));
        }

        currentPanel = newPanel;
        getContentPanel().add(currentPanel, BorderLayout.CENTER);
        updateRefreshButtonVisibility();
        revalidate();
        repaint();
        if (newPanel instanceof ReviewSelectionPanel) {
            ((ReviewSelectionPanel) newPanel).onPanelShown();
        }
    }

    private void updateRefreshButtonVisibility() {
        refreshButton.setVisible(currentPanel instanceof Refreshable);
    }

    /**
     * Describes a single navigation entry in the slide-out menu.
     *
     * @param title    the menu label
     * @param priority sort order (1–100); lower values appear nearer the top
     * @param action   the action executed when the menu item is selected
     * @param visible  evaluated before each menu rebuild to determine whether this entry is shown
     */
    private record NavEntry(String title, int priority, Runnable action, BooleanSupplier visible) {

        /**
         * Convenience constructor for entries that are always visible.
         *
         * @param title    the menu label
         * @param priority sort order (1–100)
         * @param action   the action executed when the menu item is selected
         */
        NavEntry(String title, int priority, Runnable action) {
            this(title, priority, action, () -> true);
        }
    }
}


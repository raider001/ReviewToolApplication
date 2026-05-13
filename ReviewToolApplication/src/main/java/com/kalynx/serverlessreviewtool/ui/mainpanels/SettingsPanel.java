package com.kalynx.serverlessreviewtool.ui.mainpanels;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedPanel;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedScrollPane;
import com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel.CacheManagementPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel.NotificationServiceSettingsPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel.TabConfigSettingsPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel.UserSettingsPanel;
import com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel.WindowSettingsPanel;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;

public class SettingsPanel extends ThemedPanel {

    private final SettingsManager settingsManager;
    private final PluginManager pluginManager;

    public SettingsPanel(SettingsManager settingsManager, PluginManager pluginManager) {
        this.settingsManager = settingsManager;
        this.pluginManager = pluginManager;
        setLayout(new BorderLayout());
        initializeComponents();
    }

    private void initializeComponents() {
        JScrollPane scrollPane = new ThemedScrollPane(createContentPanel());
        add(scrollPane, BorderLayout.CENTER);
    }

    private JPanel createContentPanel() {
        ThemedPanel contentPanel = new ThemedPanel();
        contentPanel.setLayout(new MigLayout("", "[grow][grow]", "[][][][]"));

        // Create settings sections with DI
        UserSettingsPanel userSettings = new UserSettingsPanel(settingsManager, pluginManager);
        WindowSettingsPanel windowSettings = new WindowSettingsPanel(settingsManager);
        NotificationServiceSettingsPanel notificationService = new NotificationServiceSettingsPanel(settingsManager);
        CacheManagementPanel cacheManagement = new CacheManagementPanel();
        TabConfigSettingsPanel tabConfig = new TabConfigSettingsPanel(settingsManager);

        contentPanel.add(userSettings,       "cell 0 0 2 1, growx");
        contentPanel.add(windowSettings,     "cell 0 1, growx");
        contentPanel.add(notificationService,"cell 1 1, growx");
        contentPanel.add(tabConfig,          "cell 0 2 2 1, growx");
        contentPanel.add(cacheManagement,    "cell 0 3 2 1, growx");

        return contentPanel;
    }
}

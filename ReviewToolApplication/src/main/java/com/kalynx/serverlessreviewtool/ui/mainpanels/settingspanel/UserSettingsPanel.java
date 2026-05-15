package com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedTextField;
import com.kalynx.swingtheme.themedcomponents.ThemedTitledBorder;
import com.kalynx.swingtheme.theme.icons.AlertIcon;
import com.kalynx.swingtheme.theme.icons.CheckIcon;
import net.miginfocom.swing.MigLayout;

import java.awt.Color;

/**
 * Displays current identity information and login status.
 */
public class UserSettingsPanel extends ThemedPanel {

    private final SettingsManager settingsManager;
    private final PluginManager pluginManager;

    private final ThemedTextField userNameField = new ThemedTextField(24);
    private final ThemedButton switchUserButton = new ThemedButton("Switch User");
    private final ThemedLabel loginStatusLabel = new ThemedLabel("");

    /**
     * Creates the user settings panel.
     *
     * @param settingsManager manages persisted identity settings
     * @param pluginManager   manages user plugin state
     */
    public UserSettingsPanel(SettingsManager settingsManager, PluginManager pluginManager) {
        this.settingsManager = settingsManager;
        this.pluginManager = pluginManager;
        setBorder(ThemedTitledBorder.create("User Identity"));
        configureLayout();
        setupListeners();
        loadSettings();
        updateFieldStates();
    }

    private void configureLayout() {
        userNameField.setEditable(false);
        setLayout(new MigLayout("fillx", "[][grow][]", "[]10[]"));

        add(new ThemedLabel("Name:"), "cell 0 0");
        add(userNameField, "cell 1 0, growx");
        add(switchUserButton, "cell 2 0, align right");
        add(loginStatusLabel, "cell 0 1 3 1, growx");
    }

    private void loadSettings() {
        updateIdentityDisplay();
        updateLoginStatus();
    }

    private void setupListeners() {
        switchUserButton.addActionListener(settingsManager::logoutUser);
        settingsManager.addUserNameListener(ignored -> {
            updateIdentityDisplay();
            updateLoginStatus();
        });
    }

    private void updateFieldStates() {
        switchUserButton.setEnabled(pluginManager.hasUserPlugins() && settingsManager.isLoggedIn());
    }

    private void updateIdentityDisplay() {
        userNameField.setText(settingsManager.getCurrentUserName());
    }

    private void updateLoginStatus() {
        if (settingsManager.isLoggedIn()) {
            loginStatusLabel.setIcon(new CheckIcon(16, new Color(66, 184, 131)));
            loginStatusLabel.setText("Logged in as " + settingsManager.getLoggedInUserName());
        } else if (pluginManager.hasUserPlugins()) {
            loginStatusLabel.setIcon(new AlertIcon(16, new Color(220, 180, 80)));
            loginStatusLabel.setText("Not logged in. Current author: " + settingsManager.getCurrentUserName());
        } else {
            loginStatusLabel.setIcon(null);
            loginStatusLabel.setText("Authoring as " + settingsManager.getCurrentUserName());
        }
        updateFieldStates();
    }
}

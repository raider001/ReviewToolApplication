package com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedLabel;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedPanel;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedTextField;
import com.kalynx.serverlessreviewtool.swingextensions.themedcomponents.ThemedTitledBorder;
import com.kalynx.serverlessreviewtool.utils.Validator;
import net.miginfocom.swing.MigLayout;

/**
 * Panel for configuring the automatic notification service URL.
 */
public class NotificationServiceSettingsPanel extends ThemedPanel {

    private final SettingsManager settingsManager;

    private final ThemedLabel urlLabel = new ThemedLabel("Service URL:");
    private final ThemedTextField urlTextField = new ThemedTextField(30);

    /**
     * Creates a new {@code NotificationServiceSettingsPanel}.
     *
     * @param settingsManager application settings manager
     */
    public NotificationServiceSettingsPanel(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
        configureLayout();
        setupValidation();
        loadDefaults();
    }

    private void configureLayout() {
        setLayout(new MigLayout("", "[][grow]", "[][]"));
        setBorder(ThemedTitledBorder.create("Automatic Notification Service"));
        add(urlLabel, "cell 0 0, align right");
        add(urlTextField, "cell 1 0, growx");
    }

    private void setupValidation() {
        urlTextField.setupValidation(
            this::validateUrl,
            settingsManager::updateNotificationServiceUrl
        );
    }

    private void loadDefaults() {
        urlTextField.setText(settingsManager.getSettings().getNotificationServiceUrl());
    }


    private Validator.ValidationResult validateUrl(String urlString) {
        if (urlString.isEmpty()) {
            return Validator.ValidationResult.valid();
        }

        String urlPattern = "^(https?|wss?)://[a-zA-Z0-9.-]+(:[0-9]+)?(/.*)?$";

        return urlString.matches(urlPattern)
            ? Validator.ValidationResult.valid()
            : Validator.ValidationResult.invalid("Invalid URL format. Please enter a valid URL starting with http://, https://, ws://, or wss://");
    }
}

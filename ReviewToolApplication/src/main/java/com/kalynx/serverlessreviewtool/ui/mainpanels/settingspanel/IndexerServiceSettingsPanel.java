package com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedTextField;
import com.kalynx.swingtheme.themedcomponents.ThemedTitledBorder;
import com.kalynx.swingtheme.utils.Validator;
import net.miginfocom.swing.MigLayout;

/**
 * Panel for configuring the ReviewTool Central Indexer URL.
 */
public class IndexerServiceSettingsPanel extends ThemedPanel {

    private final SettingsManager settingsManager;

    private final ThemedLabel urlLabel = new ThemedLabel("Indexer URL:");
    private final ThemedTextField urlTextField = new ThemedTextField(30);

    public IndexerServiceSettingsPanel(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
        configureLayout();
        setupValidation();
        loadDefaults();
    }

    private void configureLayout() {
        setLayout(new MigLayout("", "[][grow]", "[]"));
        setBorder(ThemedTitledBorder.create("Central Indexer Service"));
        add(urlLabel, "cell 0 0, align right");
        add(urlTextField, "cell 1 0, growx");
    }

    private void setupValidation() {
        urlTextField.setupValidation(
            this::validateUrl,
            settingsManager::updateIndexerUrl
        );
    }

    private void loadDefaults() {
        urlTextField.setText(settingsManager.getIndexerUrl());
    }

    private Validator.ValidationResult validateUrl(String urlString) {
        if (urlString.isEmpty()) {
            return Validator.ValidationResult.valid();
        }
        String urlPattern = "^https?://[a-zA-Z0-9.-]+(:[0-9]+)?(/.*)?$";
        return urlString.matches(urlPattern)
            ? Validator.ValidationResult.valid()
            : Validator.ValidationResult.invalid("Invalid URL format. Please enter a valid URL starting with http:// or https://");
    }
}

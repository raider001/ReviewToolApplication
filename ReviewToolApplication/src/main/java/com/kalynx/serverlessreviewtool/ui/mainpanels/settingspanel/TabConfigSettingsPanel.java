package com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel;

import com.kalynx.serverlessreviewtool.configuration.AppSettings;
import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.swingtheme.themedcomponents.*;
import com.kalynx.serverlessreviewtool.utils.TabConfigCodec;
import com.kalynx.swingtheme.utils.Validator;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * TabConfigSettingsPanel - allows users to export and import review tab configurations
 * as a compact shareable Base64 string.
 * <p>
 * A single text field shows the encoded current configuration. The user may:
 * <ul>
 *   <li>Select-all / Ctrl+C to copy and share it</li>
 *   <li>Press "Copy" to copy directly to the clipboard</li>
 *   <li>Paste someone else's string and press "Apply" to import it</li>
 * </ul>
 * The field performs inline validation; the Apply button is only enabled when
 * the content passes {@link TabConfigCodec#validate}.
 */
public class TabConfigSettingsPanel extends ThemedPanel {

    private final SettingsManager settingsManager;

    private final ThemedTextField configField = new ThemedTextField(50);
    private final ThemedButton copyButton  = new ThemedButton("Copy");
    private final ThemedButton applyButton = new ThemedButton("Apply");

    /**
     * Creates the panel.
     *
     * @param settingsManager the settings manager providing current tab configuration
     */
    public TabConfigSettingsPanel(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
        configureLayout();
        setupValidation();
        setupListeners();
        loadCurrentConfig();
    }

    private void configureLayout() {
        setLayout(new MigLayout("insets 6 8 6 8, gap 6 4", "[grow][]8[]", "[]4[]"));
        setBorder(ThemedTitledBorder.create("Review Tab Sharing"));

        ThemedLabel hint = new ThemedLabel(
            "Copy to share your tab layout, or paste a received string and click Apply.");
        hint.setFont(hint.getFont().deriveFont(hint.getFont().getSize2D() - 1f));

        add(hint,         "span 3, wrap");
        add(configField,  "growx");
        add(copyButton,   "");
        add(applyButton,  "");
    }

    private void setupValidation() {
        applyButton.setEnabled(false);

        configField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { onFieldChanged(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { onFieldChanged(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { onFieldChanged(); }
        });
    }

    private void setupListeners() {
        copyButton.addActionListener(_ -> copyToClipboard());
        applyButton.addActionListener(_ -> applyImport());
    }

    private void onFieldChanged() {
        String text = configField.getText().trim();
        String current = encodeCurrentTabs();

        if (text.equals(current)) {
            configField.clearValidationState();
            applyButton.setEnabled(false);
            return;
        }

        Validator.ValidationResult result = TabConfigCodec.validate(text);
        if (result.isValid()) {
            configField.clearValidationState();
            applyButton.setEnabled(true);
        } else {
            configField.setValidationState(false, result.getErrorMessage());
            applyButton.setEnabled(false);
        }
    }

    private void copyToClipboard() {
        String text = configField.getText().trim();
        if (text.isEmpty()) return;
        Toolkit.getDefaultToolkit()
            .getSystemClipboard()
            .setContents(new StringSelection(text), null);
    }

    private void applyImport() {
        String text = configField.getText().trim();
        Validator.ValidationResult result = TabConfigCodec.validate(text);
        if (!result.isValid()) {
            configField.setValidationState(false, result.getErrorMessage());
            return;
        }

        List<AppSettings.ReviewTabConfig> imported = TabConfigCodec.decode(text);
        settingsManager.updateReviewTabs(imported);

        loadCurrentConfig();
        applyButton.setEnabled(false);
    }

    private void loadCurrentConfig() {
        String encoded = encodeCurrentTabs();
        configField.setText(encoded);
        configField.clearValidationState();
        applyButton.setEnabled(false);
    }

    private String encodeCurrentTabs() {
        return TabConfigCodec.encode(settingsManager.getSettings().getReviewTabs());
    }
}



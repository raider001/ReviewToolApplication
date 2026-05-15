package com.kalynx.serverlessreviewtool.ui.mainpanels.settingspanel;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedSpinner;
import com.kalynx.swingtheme.themedcomponents.ThemedTitledBorder;
import com.kalynx.serverlessreviewtool.utils.ListenerFactory;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.event.FocusEvent;

public class WindowSettingsPanel extends ThemedPanel {

    private final SettingsManager settingsManager;

    private final ThemedLabel defaultWidthLabel = new ThemedLabel("Default Width:");
    private final ThemedSpinner defaultWidthSpinner = new ThemedSpinner(new SpinnerNumberModel(1000, 800, 3840, 10));

    private final ThemedLabel defaultHeightLabel = new ThemedLabel("Default Height:");
    private final ThemedSpinner defaultHeightSpinner = new ThemedSpinner(new SpinnerNumberModel(700, 600, 2160, 10));

    public WindowSettingsPanel(SettingsManager settingsManager) {
        this.settingsManager = settingsManager;
        configureLayout();
        setupListeners();
        loadWindowDefaults();
    }

    private void configureLayout() {
        setLayout(new MigLayout("", "[][]30lp[][]", "[]"));
        setBorder(ThemedTitledBorder.create("Window Settings"));
        add(defaultWidthLabel, "cell 0 0");
        add(defaultWidthSpinner, "cell 1 0");
        add(defaultHeightLabel, "cell 2 0");
        add(defaultHeightSpinner, "cell 3 0");
    }

    private void loadWindowDefaults() {
        defaultWidthSpinner.setValue(settingsManager.getSettings().getWindow().getDefaultWidth());
        defaultHeightSpinner.setValue(settingsManager.getSettings().getWindow().getDefaultHeight());
    }

    private void setupListeners() {
        defaultWidthSpinner.addFocusListener(ListenerFactory.createFocusLostAdapter(this::updateOnFocusLost));
        defaultHeightSpinner.addFocusListener(ListenerFactory.createFocusLostAdapter(this::updateOnFocusLost));
    }

    private void updateOnFocusLost(FocusEvent event) {
        settingsManager.updateWindowDefaults((int) defaultWidthSpinner.getValue(), (int) defaultHeightSpinner.getValue());
    }
}
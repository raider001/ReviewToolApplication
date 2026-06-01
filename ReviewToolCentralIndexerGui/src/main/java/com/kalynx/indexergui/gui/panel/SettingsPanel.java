package com.kalynx.indexergui.gui.panel;

import com.kalynx.indexergui.client.MetricsPoller;
import com.kalynx.indexergui.settings.GuiSettings;
import com.kalynx.indexergui.settings.GuiSettingsManager;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.themedcomponents.*;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Settings panel for configuring the Central Indexer connection (host and port).
 * Persists settings on connect and fires a callback so the GUI can reconnect.
 */
public final class SettingsPanel extends ThemedPanel {

    private static final Color COLOR_CONNECTED    = new Color(76, 175, 80);
    private static final Color COLOR_DISCONNECTED = new Color(229, 57, 53);
    private static final Color COLOR_UNKNOWN      = new Color(158, 158, 158);

    private final GuiSettingsManager settingsManager = GuiSettingsManager.getInstance();
    private final ThemeManager       tm              = ThemeManager.getInstance();
    private final MetricsPoller      poller;
    private final Consumer<GuiSettings> onConnect;

    private final ThemedTextField hostField   = new ThemedTextField();
    private final ThemedSpinner   portSpinner = new ThemedSpinner(new SpinnerNumberModel(8765, 1, 65535, 1));
    private final ThemedButton    connectButton = new ThemedButton("Connect");
    private final ThemedLabel     statusDot     = new ThemedLabel("●");
    private final ThemedLabel     statusLabel   = new ThemedLabel("Unknown");

    private Timer statusTimer;

    /**
     * Constructs a {@code SettingsPanel}.
     *
     * @param poller    metrics poller used to check live connection status
     * @param onConnect callback invoked with the new {@link GuiSettings} when the user clicks Connect
     */
    public SettingsPanel(MetricsPoller poller, Consumer<GuiSettings> onConnect) {
        this.poller    = poller;
        this.onConnect = onConnect;
        configureLayout();
        setupListeners();
        loadSettings();
    }

    private void configureLayout() {
        setLayout(new MigLayout("insets 30, gap 12", "[][grow, 280lp]", "[][]20[][]20[]"));

        add(new ThemedLabel("Host:"),  "cell 0 0");
        add(hostField,                 "cell 1 0, growx");

        add(new ThemedLabel("Port:"),  "cell 0 1");
        add(portSpinner,               "cell 1 1, w 100lp");

        ThemedPanel statusRow = new ThemedPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        statusRow.add(statusDot);
        statusRow.add(statusLabel);
        add(new ThemedLabel("Status:"), "cell 0 2");
        add(statusRow,                  "cell 1 2");

        add(connectButton, "cell 1 3");

        ThemedPanel hint = new ThemedPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        ThemedLabel hintLabel = new ThemedLabel(
                "Changes take effect after clicking Connect.");
        hintLabel.setFont(tm.getBaseFont().deriveFont(Font.ITALIC, tm.scale(11)));
        hintLabel.setForeground(tm.getCurrentTheme().getSecondaryTextColor());
        tm.addThemeChangeListener(() ->
                hintLabel.setForeground(tm.getCurrentTheme().getSecondaryTextColor()));
        hint.add(hintLabel);
        add(hint, "cell 1 4");
    }

    private void setupListeners() {
        connectButton.addActionListener(e -> onConnectClicked());
    }

    private void loadSettings() {
        GuiSettings s = settingsManager.getSettings();
        hostField.setText(s.getHost());
        portSpinner.setValue(s.getPort());
    }

    private void onConnectClicked() {
        String host = hostField.getText().trim();
        if (host.isEmpty()) {
            setStatus(false, "Host cannot be empty");
            return;
        }
        int port = (Integer) portSpinner.getValue();
        GuiSettings s = settingsManager.getSettings();
        s.setHost(host);
        s.setPort(port);
        settingsManager.save();
        onConnect.accept(s);
    }

    /**
     * Starts the periodic connection status refresh while the panel is visible.
     */
    public void startRefresh() {
        updateStatus();
        statusTimer = new Timer(1000, e -> updateStatus());
        statusTimer.start();
    }

    /**
     * Stops the periodic connection status refresh.
     */
    public void stopRefresh() {
        if (statusTimer != null) {
            statusTimer.stop();
        }
    }

    private void updateStatus() {
        SwingUtilities.invokeLater(() -> {
            if (poller.isConnected()) {
                setStatus(true, "Connected to " + settingsManager.getSettings().getHost()
                        + ":" + settingsManager.getSettings().getPort());
            } else {
                Theme theme = tm.getCurrentTheme();
                boolean hasSettings = !settingsManager.getSettings().getHost().isBlank();
                if (hasSettings) {
                    setStatus(false, "Unable to reach "
                            + settingsManager.getSettings().getHost()
                            + ":" + settingsManager.getSettings().getPort());
                } else {
                    statusDot.setForeground(COLOR_UNKNOWN);
                    statusLabel.setText("Not configured");
                    statusLabel.setForeground(theme.getSecondaryTextColor());
                }
            }
        });
    }

    private void setStatus(boolean connected, String message) {
        Theme theme = tm.getCurrentTheme();
        Color dotColor = connected ? COLOR_CONNECTED : COLOR_DISCONNECTED;
        statusDot.setForeground(dotColor);
        statusLabel.setText(message);
        statusLabel.setForeground(connected
                ? COLOR_CONNECTED
                : theme.getErrorColor() != null ? theme.getErrorColor() : COLOR_DISCONNECTED);
    }
}


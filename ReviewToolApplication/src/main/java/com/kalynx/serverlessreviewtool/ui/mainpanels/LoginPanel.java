package com.kalynx.serverlessreviewtool.ui.mainpanels;

import com.kalynx.serverlessreviewtool.configuration.SettingsManager;
import com.kalynx.serverlessreviewtool.managers.PluginManager;
import com.kalynx.swingtheme.themedcomponents.ThemedButton;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedPasswordField;
import com.kalynx.swingtheme.themedcomponents.ThemedTextField;
import com.kalynx.swingtheme.theme.icons.AppIcon;
import net.miginfocom.swing.MigLayout;

import javax.swing.ImageIcon;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.util.Arrays;

/**
 * LoginPanel displays a centered login form within the main application window.
 * Shown on startup when user plugins are present and no user is logged in.
 */
public class LoginPanel extends ThemedPanel {

    private static final int LOGIN_ICON_SIZE = 128;

    private final SettingsManager settingsManager;
    private final PluginManager pluginManager;

    private final ThemedTextField usernameField = new ThemedTextField(20);
    private final ThemedPasswordField validationField = new ThemedPasswordField(20);
    private final ThemedButton loginButton = new ThemedButton("Log In");
    private final ThemedLabel statusLabel = new ThemedLabel("");

    private Runnable onLoginSuccess;

    /**
     * Creates the login panel.
     *
     * @param settingsManager manages persisted identity settings
     * @param pluginManager   used to validate user credentials
     */
    public LoginPanel(SettingsManager settingsManager, PluginManager pluginManager) {
        this.settingsManager = settingsManager;
        this.pluginManager = pluginManager;
        setLayout(new BorderLayout());
        add(buildCenteredForm(), BorderLayout.CENTER);
        setupListeners();
    }

    /**
     * Sets the callback invoked after a successful login.
     *
     * @param listener callback to run on successful login
     */
    public void setOnLoginSuccess(Runnable listener) {
        this.onLoginSuccess = listener;
    }

    private ThemedPanel buildCenteredForm() {
        ThemedPanel wrapper = new ThemedPanel(new GridBagLayout());

        ThemedPanel form = new ThemedPanel();
        form.setLayout(new MigLayout("insets 40, fillx", "[right][grow, fill]", "[]10[]15[]15[]20[]10[]"));

        ThemedLabel iconLabel = new ThemedLabel("");
        iconLabel.setIcon(new ImageIcon(AppIcon.createIcon(LOGIN_ICON_SIZE)));

        ThemedLabel appTitle = new ThemedLabel("Serverless Review Tool");
        appTitle.setFont(appTitle.getFont().deriveFont(Font.BOLD, 22f));

        ThemedLabel subtitle = new ThemedLabel("Please log in to continue");

        loginButton.setAccentStyle(true);
        validationField.setFont(usernameField.getFont());

        form.add(iconLabel, "span 2, align center, wrap 10");
        form.add(appTitle, "span 2, align center, wrap 5");
        form.add(subtitle, "span 2, align center, wrap 20");
        form.add(new ThemedLabel("Username:"), "");
        form.add(usernameField, "growx, wrap");
        form.add(new ThemedLabel("Password:"), "");
        form.add(validationField, "growx, wrap");
        form.add(loginButton, "span 2, align center, wrap 5");
        form.add(statusLabel, "span 2, align center");

        wrapper.add(form);
        return wrapper;
    }

    private void setupListeners() {
        loginButton.addActionListener(ignored -> onLogin());
        usernameField.addActionListener(ignored -> onLogin());
        validationField.addActionListener(ignored -> onLogin());
    }

    private void onLogin() {
        String username = usernameField.getText() != null ? usernameField.getText().trim() : "";
        char[] passwordChars = validationField.getPassword();
        String validation = new String(passwordChars).trim();
        Arrays.fill(passwordChars, '\0');

        if (username.isEmpty()) {
            statusLabel.setText("Please enter your username.");
            return;
        }

        if (!pluginManager.validateUser(username, validation)) {
            statusLabel.setText("Invalid credentials. Please try again.");
            return;
        }

        settingsManager.loginUser(username, "");
        statusLabel.setText("");
        usernameField.setText("");
        validationField.setText("");

        if (onLoginSuccess != null) {
            onLoginSuccess.run();
        }
    }
}

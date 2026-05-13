package com.kalynx.serverlessreviewtool.swingextensions.themedcomponents;

import java.io.Serial;

import com.kalynx.serverlessreviewtool.theme.Theme;
import com.kalynx.serverlessreviewtool.theme.ThemeManager;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;

/**
 * A themed confirmation dialog with custom title bar
 */
public class ThemedConfirmDialog extends JDialog {
    @Serial
    private static final long serialVersionUID = 1L;
    
    private transient final ThemeManager themeManager = ThemeManager.getInstance();
    private boolean confirmed = false;

    public ThemedConfirmDialog(Window owner, String title, String message, boolean messageOnly) {
        super(owner, ModalityType.APPLICATION_MODAL);
        setUndecorated(true);
        initComponents(title, message, messageOnly);
        applyTheme();
    }
    
    public ThemedConfirmDialog(Window owner, String title, String message) {
        this(owner, title, message, false);
    }

    private void initComponents(String title, String message, boolean messageOnly) {
        ThemedPanel contentPanel = new ThemedPanel();
        contentPanel.setLayout(new MigLayout("fill, insets 0", "[grow]", "[][grow][]"));
        
        Theme theme = themeManager.getCurrentTheme();
        contentPanel.setBorder(BorderFactory.createLineBorder(theme.getBorderColor(), 1));

        CustomTitleBar titleBar = new CustomTitleBar(this, title);
        contentPanel.add(titleBar, "cell 0 0, grow, wrap");
        
        ThemedPanel messagePanel = new ThemedPanel();
        messagePanel.setLayout(new MigLayout("fill, insets 20", "[grow]", "[]"));
        ThemedLabel messageLabel = new ThemedLabel(message);
        messagePanel.add(messageLabel, "cell 0 0, grow");
        contentPanel.add(messagePanel, "cell 0 1, grow, wrap");
        
        ThemedPanel buttonPanel = new ThemedPanel();

        if (messageOnly) {
            buttonPanel.setLayout(new MigLayout("insets 10 20 20 20", "[grow][]", "[]"));
            ThemedButton okButton = new ThemedButton("OK");
            okButton.addActionListener(_ -> dispose());
            buttonPanel.add(okButton, "cell 1 0, width 80!");
        } else {
            buttonPanel.setLayout(new MigLayout("insets 10 20 20 20", "[grow][]10[]", "[]"));
            ThemedButton yesButton = new ThemedButton("Yes");
            ThemedButton noButton = new ThemedButton("No");

            yesButton.addActionListener(_ -> {
                confirmed = true;
                dispose();
            });

            noButton.addActionListener(_ -> {
                confirmed = false;
                dispose();
            });

            buttonPanel.add(yesButton, "cell 1 0, width 80!");
            buttonPanel.add(noButton, "cell 2 0, width 80!");
        }

        contentPanel.add(buttonPanel, "cell 0 2, grow");
        
        setContentPane(contentPanel);
        pack();
        setSize(400, 180);
        setLocationRelativeTo(getOwner());
    }
    
    private void applyTheme() {
        Theme theme = themeManager.getCurrentTheme();
        getContentPane().setBackground(theme.getBackgroundColor());
    }
    
    public boolean isConfirmed() {
        return confirmed;
    }
    
    /**
     * Show a confirmation dialog and return the result
     * 
     * @param parent Parent window
     * @param title Dialog title
     * @param message Confirmation message
     * @return true if user confirmed, false otherwise
     */
    public static boolean showConfirmation(Window parent, String title, String message) {
        ThemedConfirmDialog dialog = new ThemedConfirmDialog(parent, title, message);
        dialog.setVisible(true);
        return dialog.isConfirmed();
    }

    /**
     * Show a message dialog with an OK button
     *
     * @param parent Parent window
     * @param title Dialog title
     * @param message Message to display
     */
    public static void showMessage(Window parent, String title, String message) {
        ThemedConfirmDialog dialog = new ThemedConfirmDialog(parent, title, message, true);
        dialog.setVisible(true);
    }
}



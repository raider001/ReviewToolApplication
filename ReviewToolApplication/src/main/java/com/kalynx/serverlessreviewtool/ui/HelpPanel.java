package com.kalynx.serverlessreviewtool.ui;

import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;

import javax.swing.*;
import java.awt.*;

/**
 * HelpPanel - Application help and documentation
 * Placeholder for future help content
 */
public class HelpPanel extends ThemedPanel {

    public HelpPanel() {
        setLayout(new BorderLayout());
        initializeComponents();
    }

    private void initializeComponents() {
        // Title
        ThemedLabel titleLabel = new ThemedLabel("Help");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 24));
        titleLabel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        // Placeholder content
        ThemedPanel contentPanel = new ThemedPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        ThemedLabel placeholderLabel = new ThemedLabel("Help documentation will be added here.");
        placeholderLabel.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        contentPanel.add(placeholderLabel);

        ThemedLabel versionLabel = new ThemedLabel("Version: 1.0.0");
        versionLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        versionLabel.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
        contentPanel.add(versionLabel);

        // Add components
        add(titleLabel, BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);
    }
}



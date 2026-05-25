package com.kalynx.serverlessreviewtool.plugin;

import javax.swing.JPanel;

public record PluginPanel(String title, JPanel panel, int priority) {

    public PluginPanel {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title cannot be null or blank");
        }
        if (panel == null) {
            throw new IllegalArgumentException("Panel cannot be null");
        }
    }
}


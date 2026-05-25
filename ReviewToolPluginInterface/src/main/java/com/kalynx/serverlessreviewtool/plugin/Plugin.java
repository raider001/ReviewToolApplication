package com.kalynx.serverlessreviewtool.plugin;

/**
 * Base interface for all Serverless Review Tool plugins.
 * All plugin interfaces extend this to ensure they are discoverable
 * by the PluginRegistry via ServiceLoader.
 */
public interface Plugin {

    /**
     * Called by the PluginRegistry immediately after the plugin is loaded.
     * Implementations should perform any setup required before the plugin is used.
     */
    void initialize();

    /**
     * Returns a UI panel to be added to the application menu, or null if this plugin
     * does not contribute UI.
     *
     * @return plugin panel descriptor, or null
     */
    default PluginPanel getUI() {
        return null;
    }
}


package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.plugin.NotificationPlugin;
import com.kalynx.serverlessreviewtool.plugin.Plugin;
import com.kalynx.serverlessreviewtool.plugin.PluginPanel;
import com.kalynx.serverlessreviewtool.plugin.PluginRegistry;
import com.kalynx.serverlessreviewtool.plugin.SyntaxHighlighterPlugin;
import com.kalynx.serverlessreviewtool.plugin.UserPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages plugin lifecycle and provides typed access to registered plugin instances.
 *
 * <p>Only one {@link UserPlugin} and one {@link NotificationPlugin} may be active at a time;
 * if more than one of either type is found, the first is used and a warning is logged.
 * {@link SyntaxHighlighterPlugin} allows multiple registrations (one per file extension).
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Call {@link #load()} — discovers plugins and makes them accessible via the getters.</li>
 *   <li>Attach listeners directly to the returned plugin instances.</li>
 *   <li>Call {@link #start()} — initialises plugins so they fire initial events to
 *       already-attached listeners.</li>
 * </ol>
 */
public class PluginManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginManager.class);

    private final PluginRegistry pluginRegistry = new PluginRegistry();

    private Optional<UserPlugin> userPlugin = Optional.empty();
    private Optional<NotificationPlugin> notificationPlugin = Optional.empty();

    private boolean loaded;
    private boolean started;

    /**
     * Phase 1: discovers plugins and populates the typed plugin references.
     * After this returns, plugins are accessible via the getters and listeners may be
     * attached directly before {@link #start()} is called.
     * Safe to call multiple times; only the first call performs loading.
     */
    public synchronized void load() {
        if (loaded) return;
        pluginRegistry.load();

        userPlugin = pickSingle(UserPlugin.class);
        notificationPlugin = pickSingle(NotificationPlugin.class);

        loaded = true;
        LOGGER.info("PluginManager loaded — userPlugin={} notificationPlugin={} syntaxHighlighters={}",
            userPlugin.map(p -> p.getClass().getSimpleName()).orElse("none"),
            notificationPlugin.map(p -> p.getClass().getSimpleName()).orElse("none"),
            pluginRegistry.getPlugins(SyntaxHighlighterPlugin.class).size());
    }

    /**
     * Phase 2: calls {@code initialize()} on all registered plugins.
     * Must be called after all listeners have been attached.
     * Implicitly calls {@link #load()} if not already done.
     * Safe to call multiple times; only the first call starts plugins.
     */
    public synchronized void start() {
        if (started) return;
        if (!loaded) load();
        pluginRegistry.initializePlugins();
        started = true;
        LOGGER.info("PluginManager started");
    }

    /**
     * Returns the registered {@link UserPlugin}, if any.
     *
     * @return optional user plugin
     */
    public Optional<UserPlugin> getUserPlugin() {
        return userPlugin;
    }

    /**
     * Returns the registered {@link NotificationPlugin}, if any.
     *
     * @return optional notification plugin
     */
    public Optional<NotificationPlugin> getNotificationPlugin() {
        return notificationPlugin;
    }

    /**
     * Returns the registered syntax highlighter for the given file extension, if any.
     *
     * @param fileExtension lower-case file extension without dot (e.g. {@code "java"})
     * @return optional syntax highlighter plugin for this extension
     */
    public Optional<SyntaxHighlighterPlugin> getSyntaxHighlighterFor(String fileExtension) {
        return pluginRegistry.getPlugins(SyntaxHighlighterPlugin.class).stream()
            .filter(plugin -> fileExtension.equalsIgnoreCase(plugin.getFileExtension()))
            .findFirst();
    }

    /**
     * Returns all {@link PluginPanel} instances contributed by registered plugins.
     * Plugins that throw or return {@code null} from {@code getUI()} are silently skipped.
     *
     * @return list of plugin panels, may be empty
     */
    public List<PluginPanel> getPluginPanels() {
        return pluginRegistry.getAllPlugins().stream()
            .map(plugin -> {
                try {
                    return plugin.getUI();
                } catch (Exception e) {
                    LOGGER.warn("Plugin {} threw from getUI()", plugin.getClass().getName(), e);
                    return null;
                }
            })
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * Releases plugin classloader resources.
     */
    public synchronized void shutdown() {
        if (!loaded && !started) return;
        pluginRegistry.close();
        userPlugin = Optional.empty();
        notificationPlugin = Optional.empty();
        started = false;
        loaded = false;
        LOGGER.info("PluginManager shut down");
    }

    private <T extends Plugin> Optional<T> pickSingle(Class<T> type) {
        List<T> found = pluginRegistry.getPlugins(type);
        if (found.isEmpty()) return Optional.empty();
        if (found.size() > 1) {
            LOGGER.warn("Multiple {} plugins registered — only '{}' will be used",
                type.getSimpleName(), found.getFirst().getClass().getSimpleName());
        }
        return Optional.of(found.getFirst());
    }
}
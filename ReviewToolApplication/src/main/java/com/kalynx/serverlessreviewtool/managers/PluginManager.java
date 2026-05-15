package com.kalynx.serverlessreviewtool.managers;

import com.kalynx.serverlessreviewtool.plugin.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * PluginManager - Application facade for plugin discovery and access.
 * Loads plugins at startup and provides typed access to registered plugins.
 */
public class PluginManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginManager.class);

    private final PluginRegistry pluginRegistry = new PluginRegistry();
    private boolean initialized;

    private final List<Runnable> pendingUserListeners = new ArrayList<>();
    private final List<Runnable> pendingNotificationListeners = new ArrayList<>();

    /**
     * Loads plugins from the configured plugins directory.
     * Applies any pre-registered listeners before calling plugin initialize(),
     * ensuring plugins fire initial events to already-attached listeners.
     * Safe to call multiple times; only first call performs loading.
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        pluginRegistry.load();
        pendingUserListeners.forEach(Runnable::run);
        pendingUserListeners.clear();
        pendingNotificationListeners.forEach(Runnable::run);
        pendingNotificationListeners.clear();
        pluginRegistry.initializePlugins();
        initialized = true;
        LOGGER.info("PluginManager initialized");
    }

    /**
     * Registers a listener for user plugin events.
     * If called before {@link #initialize()}, the listener is queued and applied
     * after plugins are discovered but before they are initialized.
     *
     * @param type     the notification type to listen for
     * @param listener the listener to register
     */
    public void addListenerToUserPlugins(UserPlugin.NotificationType type, Consumer<String[]> listener) {
        if (!initialized) {
            pendingUserListeners.add(() ->
                pluginRegistry.getPlugins(UserPlugin.class).forEach(plugin -> plugin.addListener(type, listener))
            );
        } else {
            pluginRegistry.getPlugins(UserPlugin.class).forEach(plugin -> plugin.addListener(type, listener));
        }
    }

    /**
     * Registers a listener for notification plugin events.
     * If called before {@link #initialize()}, the listener is queued and applied
     * after plugins are discovered but before they are initialized.
     *
     * @param type the notification event type to listen for
     * @param listener the listener to register
     */
    public void addListenerToNotificationPlugins(
        NotificationPlugin.NotificationType type,
        Consumer<ReviewListUpdate[]> listener) {
        if (!initialized) {
            pendingNotificationListeners.add(() ->
                pluginRegistry.getPlugins(NotificationPlugin.class)
                    .forEach(plugin -> plugin.addListener(type, payloads -> {
                        ReviewListUpdate[] updates = extractReviewUpdates(payloads);
                        if (updates.length > 0) {
                            listener.accept(updates);
                        }
                    }))
            );
        } else {
            pluginRegistry.getPlugins(NotificationPlugin.class)
                .forEach(plugin -> plugin.addListener(type, payloads -> {
                    ReviewListUpdate[] updates = extractReviewUpdates(payloads);
                    if (updates.length > 0) {
                        listener.accept(updates);
                    }
                }));
        }
    }

    /**
     * Registers a listener for notification plugin repository list updates.
     *
     * @param listener repository update listener
     */
    public void addListenerToNotificationRepositoryUpdates(Consumer<RepositoryListUpdate[]> listener) {
        if (!initialized) {
            pendingNotificationListeners.add(() ->
                pluginRegistry.getPlugins(NotificationPlugin.class)
                    .forEach(plugin -> plugin.addListener(NotificationPlugin.NotificationType.REPOSITORIES_UPDATED, payloads -> {
                        RepositoryListUpdate[] updates = extractRepositoryUpdates(payloads);
                        if (updates.length > 0) {
                            listener.accept(updates);
                        }
                    }))
            );
        } else {
            pluginRegistry.getPlugins(NotificationPlugin.class)
                .forEach(plugin -> plugin.addListener(NotificationPlugin.NotificationType.REPOSITORIES_UPDATED, payloads -> {
                    RepositoryListUpdate[] updates = extractRepositoryUpdates(payloads);
                    if (updates.length > 0) {
                        listener.accept(updates);
                    }
                }));
        }
    }

    /**
     * Indicates whether any user plugins are currently registered.
     *
     * @return true when at least one user plugin is available
     */
    public boolean hasUserPlugins() {
        return !pluginRegistry.getPlugins(UserPlugin.class).isEmpty();
    }

    /**
     * Validates a user against the registered user plugins.
     *
     * @param user the user to validate
     * @param validationString the validation value supplied by the user
     * @return true when any registered user plugin accepts the identity
     */
    public boolean validateUser(String user, String validationString) {
        return pluginRegistry.getPlugins(UserPlugin.class).stream()
            .anyMatch(plugin -> plugin.validateUser(user, validationString));
    }

    /**
     * Returns the registered syntax highlighter plugin for the given file extension, if any.
     * Each plugin handles exactly one extension, so the match is precise.
     *
     * @param fileExtension lower-case file extension without dot (e.g. {@code "java"})
     * @return optional containing the plugin for this extension, or empty if none registered
     */
    public Optional<SyntaxHighlighterPlugin> getSyntaxHighlighterFor(String fileExtension) {
        return pluginRegistry.getPlugins(SyntaxHighlighterPlugin.class).stream()
            .filter(plugin -> fileExtension.equalsIgnoreCase(plugin.getFileExtension()))
            .findFirst();
    }

    /**
     * Returns all {@link PluginPanel} instances contributed by registered plugins.
     * Plugins that return {@code null} from {@link com.kalynx.serverlessreviewtool.plugin.Plugin#getUI()}
     * are silently skipped.
     *
     * @return list of plugin panels, may be empty
     */
    public List<PluginPanel> getPluginPanels() {
        return pluginRegistry.getAllPlugins().stream()
            .map(plugin -> {
                try {
                    return plugin.getUI();
                } catch (Exception e) {
                    LOGGER.warn("Plugin {} threw an exception from getUI()", plugin.getClass().getName(), e);
                    return null;
                }
            })
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    /**
     * Releases plugin classloader resources.
     */
    public synchronized void shutdown() {
        if (!initialized) {
            return;
        }
        pluginRegistry.close();
        initialized = false;
        LOGGER.info("PluginManager shut down");
    }

    private ReviewListUpdate[] extractReviewUpdates(com.kalynx.serverlessreviewtool.plugin.NotificationPayload[] payloads) {
        return java.util.Arrays.stream(payloads)
            .filter(ReviewListUpdate.class::isInstance)
            .map(ReviewListUpdate.class::cast)
            .toArray(ReviewListUpdate[]::new);
    }

    private RepositoryListUpdate[] extractRepositoryUpdates(com.kalynx.serverlessreviewtool.plugin.NotificationPayload[] payloads) {
        return java.util.Arrays.stream(payloads)
            .filter(RepositoryListUpdate.class::isInstance)
            .map(RepositoryListUpdate.class::cast)
            .toArray(RepositoryListUpdate[]::new);
    }
}




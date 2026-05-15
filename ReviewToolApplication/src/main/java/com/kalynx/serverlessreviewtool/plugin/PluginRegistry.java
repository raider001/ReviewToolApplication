package com.kalynx.serverlessreviewtool.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Discovers and manages plugin implementations loaded from the plugins directory.
 * Any JAR placed in the plugins directory is automatically scanned for plugin
 * implementations at startup. No configuration changes to the tool are required.
 *
 * <p>The plugins directory defaults to {@code ./plugins} relative to the working
 * directory and can be overridden via the system property {@code srt.plugins.dir}.
 */
public class PluginRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginRegistry.class);
    private static final String PLUGINS_DIR_PROPERTY = "srt.plugins.dir";
    private static final String DEFAULT_PLUGINS_DIR = "plugins";

    private final Map<Class<?>, List<Plugin>> registry = new HashMap<>();
    private URLClassLoader pluginClassLoader;

    /**
     * Discovers and registers plugins from the plugins directory without initializing them.
     * Call {@link #initializePlugins()} after attaching any listeners.
     */
    public void load() {
        Path pluginsDir = resolvePluginsDir();
        List<URL> jarUrls = collectJarUrls(pluginsDir);

        if (jarUrls.isEmpty()) {
            LOGGER.info("No plugin JARs found in: {}", pluginsDir);
            return;
        }

        LOGGER.info("Loading {} plugin JAR(s) from: {}", jarUrls.size(), pluginsDir);
        pluginClassLoader = new URLClassLoader(jarUrls.toArray(new URL[0]), getClass().getClassLoader());
        ServiceLoader.load(Plugin.class, pluginClassLoader)
            .forEach(this::register);

        LOGGER.info("Plugin registry loaded. Registered types: {}", registry.keySet().stream()
            .map(Class::getSimpleName).toList());
    }

    /**
     * Calls {@code initialize()} on all registered plugins.
     * Must be called after listeners have been attached via the PluginManager.
     */
    public void initializePlugins() {
        registry.values().stream()
            .flatMap(List::stream)
            .forEach(plugin -> {
                try {
                    plugin.initialize();
                    LOGGER.info("Initialized plugin: {}", plugin.getClass().getName());
                } catch (Exception e) {
                    LOGGER.error("Plugin initialization failed: {}", plugin.getClass().getName(), e);
                }
            });
    }

    /**
     * Returns every registered plugin regardless of type.
     *
     * @return flat list of all registered plugin instances
     */
    public List<Plugin> getAllPlugins() {
        return registry.values().stream()
            .flatMap(List::stream)
            .toList();
    }

    /**
     * Returns all registered plugins implementing the given interface.
     *
     * @param type the plugin interface class
     * @param <T>  the plugin type
     * @return list of implementations, empty if none registered
     */
    public <T extends Plugin> List<T> getPlugins(Class<T> type) {
        return registry.getOrDefault(type, List.of())
            .stream()
            .filter(type::isInstance)
            .map(type::cast)
            .toList();
    }

    /**
     * Closes the plugin class loader, releasing JAR file handles.
     * Should be called on application shutdown.
     */
    public void close() {
        if (pluginClassLoader != null) {
            try {
                pluginClassLoader.close();
            } catch (Exception e) {
                LOGGER.warn("Failed to close plugin class loader", e);
            }
        }
    }

    private void register(Plugin plugin) {
        Class<?> pluginType = resolvePluginInterface(plugin);
        registry.computeIfAbsent(pluginType, ignored -> new ArrayList<>()).add(plugin);
        LOGGER.info("Registered plugin: {} as {}", plugin.getClass().getName(), pluginType.getSimpleName());
    }

    private Class<?> resolvePluginInterface(Plugin plugin) {
        Class<?> cls = plugin.getClass();
        Class<?> resolvedSuperclassType = null;
        while (!cls.equals(Object.class)) {
            Class<?> superClass = cls.getSuperclass();
            if (superClass == null) {
                break;
            }
            if (Plugin.class.isAssignableFrom(superClass) && !superClass.equals(Plugin.class)) {
                resolvedSuperclassType = superClass;
            }
            cls = superClass;
        }
        if (resolvedSuperclassType != null) {
            return resolvedSuperclassType;
        }
        for (Class<?> iface : plugin.getClass().getInterfaces()) {
            if (Plugin.class.isAssignableFrom(iface) && !iface.equals(Plugin.class)) {
                return iface;
            }
        }
        return Plugin.class;
    }

    private Path resolvePluginsDir() {
        String dirProperty = System.getProperty(PLUGINS_DIR_PROPERTY, DEFAULT_PLUGINS_DIR);
        return Path.of(dirProperty).toAbsolutePath().normalize();
    }

    private List<URL> collectJarUrls(Path pluginsDir) {
        List<URL> urls = new ArrayList<>();
        if (!Files.isDirectory(pluginsDir)) {
            LOGGER.info("Plugins directory does not exist, no plugins loaded: {}", pluginsDir);
            return urls;
        }
        File[] jars = pluginsDir.toFile().listFiles(f -> f.isFile() && f.getName().endsWith(".jar"));
        if (jars == null) return urls;
        for (File jar : jars) {
            try {
                urls.add(jar.toURI().toURL());
                LOGGER.debug("Found plugin JAR: {}", jar.getName());
            } catch (Exception e) {
                LOGGER.warn("Skipping unreadable plugin JAR: {}", jar.getName(), e);
            }
        }
        return urls;
    }
}


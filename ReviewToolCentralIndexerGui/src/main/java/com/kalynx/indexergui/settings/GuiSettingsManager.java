package com.kalynx.indexergui.settings;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Singleton that loads and saves {@link GuiSettings} to
 * {@code ~/.indexer-gui/settings.json}.
 */
public final class GuiSettingsManager {

    private static final Logger log = LoggerFactory.getLogger(GuiSettingsManager.class);
    private static final Path SETTINGS_PATH =
            Path.of(System.getProperty("user.home"), ".indexer-gui", "settings.json");
    private static final GuiSettingsManager INSTANCE = new GuiSettingsManager();

    private final Gson        gson     = new Gson();
    private       GuiSettings settings;

    private GuiSettingsManager() {
        settings = load();
    }

    /**
     * Returns the singleton instance.
     *
     * @return singleton {@code GuiSettingsManager}
     */
    public static GuiSettingsManager getInstance() {
        return INSTANCE;
    }

    /**
     * Returns the currently loaded settings.
     *
     * @return current {@link GuiSettings}
     */
    public GuiSettings getSettings() {
        return settings;
    }

    /**
     * Persists the current settings to disk.
     * Creates the parent directory if it does not exist.
     */
    public void save() {
        try {
            Files.createDirectories(SETTINGS_PATH.getParent());
            Files.writeString(SETTINGS_PATH, gson.toJson(settings), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Failed to save GUI settings: {}", e.getMessage());
        }
    }

    private GuiSettings load() {
        if (!Files.exists(SETTINGS_PATH)) {
            return new GuiSettings();
        }
        try {
            String json = Files.readString(SETTINGS_PATH, StandardCharsets.UTF_8);
            GuiSettings loaded = gson.fromJson(json, GuiSettings.class);
            return loaded != null ? loaded : new GuiSettings();
        } catch (IOException e) {
            log.warn("Failed to load GUI settings, using defaults: {}", e.getMessage());
            return new GuiSettings();
        }
    }
}



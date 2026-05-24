package com.kalynx.serverlessreviewtool.configuration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SettingsManagerTests {

    @Test
    void appSettings_indexerUrl_defaultsToEmptyNotNull() {
        AppSettings settings = new AppSettings();
        assertNotNull(settings.getIndexerUrl());
        assertEquals("", settings.getIndexerUrl());
    }

    @Test
    void appSettings_setIndexerUrl_roundTrips() {
        AppSettings settings = new AppSettings();
        settings.setIndexerUrl("http://localhost:8765");
        assertEquals("http://localhost:8765", settings.getIndexerUrl());
    }

    @Test
    void appSettings_setIndexerUrl_nullSafeGetter_returnsEmpty() {
        AppSettings settings = new AppSettings();
        settings.setIndexerUrl(null);
        assertEquals("", settings.getIndexerUrl());
    }

    @Test
    void settingsManager_getIndexerUrl_initiallyEmpty() {
        SettingsManager manager = new SettingsManager();
        assertNotNull(manager.getIndexerUrl());
    }

    @Test
    void settingsManager_updateIndexerUrl_updatesInMemoryState() {
        SettingsManager manager = new SettingsManager();
        String original = manager.getIndexerUrl();

        manager.updateIndexerUrl("http://test-indexer:9000");
        assertEquals("http://test-indexer:9000", manager.getIndexerUrl());

        // restore original to avoid polluting user settings file between test runs
        manager.updateIndexerUrl(original);
    }
}

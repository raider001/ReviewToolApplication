package com.kalynx.serverlessreviewtool.configuration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SettingsManagerTests {

    @Test
    void settingsManager_getIndexerUrl_initiallyEmpty() {
        SettingsManager manager = new SettingsManager();
        assertNotNull(manager.getIndexerUrl());
        assertEquals("", manager.getIndexerUrl());
    }
}

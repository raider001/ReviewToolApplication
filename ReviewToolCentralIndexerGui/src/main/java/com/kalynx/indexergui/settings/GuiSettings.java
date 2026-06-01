package com.kalynx.indexergui.settings;

/**
 * Persisted connection settings for the Central Indexer GUI.
 * Holds the host and port used to reach the indexer HTTP API.
 */
public final class GuiSettings {

    private String host = "localhost";
    private int    port = 8765;

    /**
     * Returns the indexer API host.
     *
     * @return host name or IP address
     */
    public String getHost() {
        return host;
    }

    /**
     * Sets the indexer API host.
     *
     * @param host host name or IP address
     */
    public void setHost(String host) {
        this.host = host;
    }

    /**
     * Returns the indexer API port.
     *
     * @return TCP port number (1–65535)
     */
    public int getPort() {
        return port;
    }

    /**
     * Sets the indexer API port.
     *
     * @param port TCP port number (1–65535)
     */
    public void setPort(int port) {
        this.port = port;
    }
}


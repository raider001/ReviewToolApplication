package com.kalynx.indexergui;

import com.kalynx.indexergui.client.IndexerClient;
import com.kalynx.indexergui.client.MetricsPoller;
import com.kalynx.indexergui.gui.IndexerGuiClient;

import javax.swing.*;

/**
 * Entry point for the standalone Central Indexer GUI.
 *
 * <p>Usage: {@code java -jar central-indexer-gui.jar [--host <host>] [--port <port>]}
 * <ul>
 *   <li>{@code --host} — indexer hostname (default: {@code localhost})</li>
 *   <li>{@code --port} — indexer port (default: {@code 8765})</li>
 * </ul>
 */
public final class Main {

    public static void main(String[] args) {
        String host = "localhost";
        int    port = 8765;

        for (int i = 0; i < args.length - 1; i++) {
            if ("--host".equals(args[i])) host = args[i + 1];
            if ("--port".equals(args[i])) {
                try { port = Integer.parseInt(args[i + 1]); }
                catch (NumberFormatException e) {
                    System.err.println("Invalid --port value: " + args[i + 1]);
                    System.exit(1);
                }
            }
        }

        String finalHost = host;
        int    finalPort = port;

        SwingUtilities.invokeLater(() -> {
            IndexerClient    client = new IndexerClient(finalHost, finalPort);
            MetricsPoller    poller = new MetricsPoller(client);
            IndexerGuiClient gui    = new IndexerGuiClient(poller, client, finalHost, finalPort);

            gui.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            gui.setVisible(true);
            poller.start();
            gui.startRefresh();
        });
    }
}

package com.kalynx.indexergui.gui;

import com.kalynx.indexergui.client.IndexerClient;
import com.kalynx.indexergui.client.MetricsPoller;
import com.kalynx.indexergui.gui.panel.CategoryMetricsPanel;
import com.kalynx.indexergui.gui.panel.ConnectionsPanel;
import com.kalynx.indexergui.gui.panel.QueryPanel;
import com.kalynx.indexergui.gui.panel.StatisticsPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedFrame;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Main window for the standalone Central Indexer GUI.
 */
public final class IndexerGuiClient extends ThemedFrame {

    private final MetricsPoller poller;
    private final IndexerClient client;

    private StatisticsPanel     statisticsPanel;
    private ConnectionsPanel    connectionsPanel;
    private CategoryMetricsPanel eventsPanel;
    private CategoryMetricsPanel webhooksPanel;
    private CategoryMetricsPanel restPanel;
    private ThemedPanel         currentPanel;

    private final Map<String, QueryPanel> queryPanels = new HashMap<>();

    public IndexerGuiClient(MetricsPoller poller, IndexerClient client, String host, int port) {
        super("Central Indexer — " + host + ":" + port, 1100, 720);
        this.poller = poller;
        this.client = client;
        buildUi();
    }

    private void buildUi() {
        setMenuItems(
                new MenuItem("Dashboard",    this::showDashboard),
                new MenuItem("Connections",  this::showConnections),
                new MenuItem("Events",       this::showEvents),
                new MenuItem("Webhooks",     this::showWebhooks),
                new MenuItem("REST Calls",   this::showRest),
                new MenuItem("Reviews",      () -> showQueryPanel("Reviews")),
                new MenuItem("Branches",     () -> showQueryPanel("Branches")),
                new MenuItem("Repositories", () -> showQueryPanel("Repositories")),
                new MenuItem("Comments",     () -> showQueryPanel("Comments"))
        );

        statisticsPanel = new StatisticsPanel(poller);

        connectionsPanel = new ConnectionsPanel(poller);

        eventsPanel  = new CategoryMetricsPanel("Event Notifications by Type",
                poller::getSseEventBuffers);
        webhooksPanel = new CategoryMetricsPanel("Webhook Calls by Type",
                poller::getWebhookBuffers);
        restPanel    = new CategoryMetricsPanel("REST Calls by Endpoint",
                poller::getRestCallBuffers);

        contentPanel.setLayout(new BorderLayout());
        // Show the dashboard without starting the refresh timer — Main.startRefresh() does that.
        switchTo(statisticsPanel);
        setWindowTitle("Central Indexer — Dashboard");
    }

    private void showDashboard() {
        stopLivePanels();
        switchTo(statisticsPanel);
        statisticsPanel.startRefresh();
        setWindowTitle("Central Indexer — Dashboard");
    }

    private void showConnections() {
        stopLivePanels();
        switchTo(connectionsPanel);
        connectionsPanel.startRefresh();
        setWindowTitle("Central Indexer — Connections");
    }

    private void showEvents() {
        stopLivePanels();
        switchTo(eventsPanel);
        eventsPanel.startRefresh();
        setWindowTitle("Central Indexer — Events");
    }

    private void showWebhooks() {
        stopLivePanels();
        switchTo(webhooksPanel);
        webhooksPanel.startRefresh();
        setWindowTitle("Central Indexer — Webhooks");
    }

    private void showRest() {
        stopLivePanels();
        switchTo(restPanel);
        restPanel.startRefresh();
        setWindowTitle("Central Indexer — REST Calls");
    }

    private void showQueryPanel(String table) {
        stopLivePanels();
        QueryPanel qp = queryPanels.computeIfAbsent(table, t -> {
            QueryPanel panel = new QueryPanel(client);
            panel.selectTable(t);
            return panel;
        });
        switchTo(qp);
        setWindowTitle("Central Indexer — " + table);
    }

    private void stopLivePanels() {
        statisticsPanel.stopRefresh();
        connectionsPanel.stopRefresh();
        eventsPanel.stopRefresh();
        webhooksPanel.stopRefresh();
        restPanel.stopRefresh();
    }

    private void switchTo(ThemedPanel panel) {
        if (currentPanel != null) contentPanel.remove(currentPanel);
        currentPanel = panel;
        contentPanel.add(currentPanel, BorderLayout.CENTER);
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    public void startRefresh()  { statisticsPanel.startRefresh(); }
    public void stopRefresh()   { stopLivePanels(); }
}

package com.kalynx.indexergui.gui;

import com.kalynx.indexergui.client.IndexerClient;
import com.kalynx.indexergui.client.MetricsPoller;
import com.kalynx.indexergui.gui.panel.QueryPanel;
import com.kalynx.indexergui.gui.panel.StatisticsPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedFrame;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Main window for the standalone Central Indexer GUI.
 * Same menu structure as the embedded version — Dashboard + one item per query table.
 */
public final class IndexerGuiClient extends ThemedFrame {

    private final MetricsPoller poller;
    private final IndexerClient client;

    private StatisticsPanel statisticsPanel;
    private ThemedPanel     currentPanel;

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
                new MenuItem("Reviews",      () -> showQueryPanel("Reviews")),
                new MenuItem("Branches",     () -> showQueryPanel("Branches")),
                new MenuItem("Repositories", () -> showQueryPanel("Repositories"))
        );

        statisticsPanel = new StatisticsPanel(poller);
        contentPanel.setLayout(new BorderLayout());
        showDashboard();
    }

    private void showDashboard() {
        switchTo(statisticsPanel);
        setWindowTitle("Central Indexer — Dashboard");
    }

    private void showQueryPanel(String table) {
        QueryPanel qp = queryPanels.computeIfAbsent(table, t -> {
            QueryPanel panel = new QueryPanel(client);
            panel.selectTable(t);
            return panel;
        });
        switchTo(qp);
        setWindowTitle("Central Indexer — " + table);
    }

    private void switchTo(ThemedPanel panel) {
        if (currentPanel != null) contentPanel.remove(currentPanel);
        currentPanel = panel;
        contentPanel.add(currentPanel, BorderLayout.CENTER);
        contentPanel.revalidate();
        contentPanel.repaint();
    }

    public void startRefresh()  { statisticsPanel.startRefresh(); }
    public void stopRefresh()   { statisticsPanel.stopRefresh(); }
}

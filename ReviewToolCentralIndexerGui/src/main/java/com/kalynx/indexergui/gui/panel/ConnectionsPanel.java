package com.kalynx.indexergui.gui.panel;

import com.kalynx.indexergui.client.MetricsPoller;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedScrollPane;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.Map;

/**
 * Panel showing active SSE client connections grouped by client IP address.
 * Refreshed every second while visible.
 */
public final class ConnectionsPanel extends ThemedPanel {

    private final MetricsPoller  poller;
    private final ThemeManager   tm = ThemeManager.getInstance();
    private final ThemedLabel    statusLabel;
    private final DefaultTableModel tableModel;
    private final JTable         table;
    private Timer                refreshTimer;

    public ConnectionsPanel(MetricsPoller poller) {
        super(new MigLayout("insets 12, gap 8, flowy", "[grow]", "[][grow]"));
        this.poller = poller;

        statusLabel = new ThemedLabel("Active Connections (by Client IP)");
        statusLabel.setFont(tm.getBaseFont().deriveFont(Font.BOLD, tm.scale(13)));
        add(statusLabel, "growx");

        tableModel = new DefaultTableModel(new String[]{"Client IP", "Connections"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        table = buildTable();
        add(new ThemedScrollPane(table), "growx, growy");
    }

    public void startRefresh() {
        refreshTimer = new Timer(1000, e -> refresh());
        refreshTimer.start();
    }

    public void stopRefresh() {
        if (refreshTimer != null) refreshTimer.stop();
    }

    private void refresh() {
        Map<String, Integer> ips = poller.getConnectedClientIps();
        tableModel.setRowCount(0);
        ips.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> tableModel.addRow(new Object[]{e.getKey(), e.getValue()}));
        if (ips.isEmpty()) {
            statusLabel.setText("Active Connections (by Client IP) — none");
        } else {
            statusLabel.setText("Active Connections (by Client IP) — " + ips.size() + " client(s)");
        }
    }

    private JTable buildTable() {
        JTable t = new JTable(tableModel) {
            @Override
            public Component prepareRenderer(TableCellRenderer renderer, int row, int col) {
                Component c = super.prepareRenderer(renderer, row, col);
                if (!isRowSelected(row)) {
                    c.setBackground(row % 2 == 0
                            ? tm.getCurrentTheme().getBackgroundColor()
                            : tm.getCurrentTheme().getButtonBackground());
                }
                c.setForeground(tm.getCurrentTheme().getForegroundColor());
                return c;
            }
        };
        t.setFont(tm.getBaseFont().deriveFont(Font.PLAIN, tm.scale(12)));
        t.getTableHeader().setFont(tm.getBaseFont().deriveFont(Font.BOLD, tm.scale(12)));
        t.setRowHeight(tm.scale(24));
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        t.setFillsViewportHeight(true);
        applyTableTheme(t);
        tm.addThemeChangeListener(() -> applyTableTheme(t));
        return t;
    }

    private void applyTableTheme(JTable t) {
        Theme theme = tm.getCurrentTheme();
        t.setBackground(theme.getBackgroundColor());
        t.setForeground(theme.getForegroundColor());
        t.setSelectionBackground(theme.getAccentColor());
        t.setSelectionForeground(theme.getButtonForeground());
        t.setGridColor(theme.getBorderColor());
        t.getTableHeader().setDefaultRenderer((table, value, isSelected, hasFocus, row, col) -> {
            Theme th = tm.getCurrentTheme();
            JLabel lbl = new JLabel(value == null ? "" : value.toString());
            lbl.setOpaque(true);
            lbl.setBackground(th.getBorderColor());
            lbl.setForeground(th.getForegroundColor());
            lbl.setFont(tm.getBaseFont().deriveFont(Font.BOLD, tm.scale(12)));
            lbl.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 0, 1, th.getBorderColor().darker()),
                    BorderFactory.createEmptyBorder(4, 8, 4, 8)));
            return lbl;
        });
    }
}

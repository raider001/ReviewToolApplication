package com.kalynx.indexergui.gui.panel;

import com.kalynx.indexergui.client.LocalTimeSeriesBuffer;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.themedcomponents.*;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Generic panel that displays per-category counts over a selectable time window.
 * Used for SSE event notifications, webhook calls, and REST call monitoring.
 *
 * <p>Data is provided by a {@link Supplier} returning a live map of
 * {@code category → LocalTimeSeriesBuffer}. The panel sums each buffer over the
 * selected window and renders the result in a sortable table.
 */
public final class CategoryMetricsPanel extends ThemedPanel {

    private static final String[] WINDOW_LABELS = {
        "Last 1 second", "Last 60 seconds", "Last 60 minutes", "Last 24 hours"
    };
    private static final long[] WINDOW_MS = {
        1_000L, 60_000L, 3_600_000L, 86_400_000L
    };

    private final ThemeManager  tm = ThemeManager.getInstance();
    private final Supplier<Map<String, LocalTimeSeriesBuffer>> bufferSource;

    private final ThemedComboBox<String> windowCombo;
    private final DefaultTableModel      tableModel;
    private final JTable                 table;
    private Timer                        refreshTimer;

    public CategoryMetricsPanel(String title, Supplier<Map<String, LocalTimeSeriesBuffer>> bufferSource) {
        super(new MigLayout("insets 12, gap 8, flowy", "[grow]", "[][][grow]"));
        this.bufferSource = bufferSource;

        ThemedLabel titleLabel = new ThemedLabel(title);
        titleLabel.setFont(tm.getBaseFont().deriveFont(Font.BOLD, tm.scale(13)));
        add(titleLabel, "growx");

        ThemedPanel selectorRow = new ThemedPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        selectorRow.add(new ThemedLabel("Time window:"));
        windowCombo = new ThemedComboBox<>();
        for (String lbl : WINDOW_LABELS) windowCombo.addItem(lbl);
        windowCombo.setSelectedIndex(1);
        selectorRow.add(windowCombo);
        add(selectorRow, "growx");

        tableModel = new DefaultTableModel(new String[]{"Category", "Count"}, 0) {
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
        long windowMs = WINDOW_MS[Math.max(0, windowCombo.getSelectedIndex())];
        Map<String, LocalTimeSeriesBuffer> buffers = bufferSource.get();

        tableModel.setRowCount(0);
        buffers.entrySet().stream()
                .map(e -> new Object[]{e.getKey(), (long) e.getValue().sum(windowMs)})
                .sorted((a, b) -> Long.compare((Long) b[1], (Long) a[1]))
                .forEach(tableModel::addRow);
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

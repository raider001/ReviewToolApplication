package com.kalynx.indexergui.gui.panel;

import com.kalynx.indexergui.client.LocalTimeSeriesBuffer;
import com.kalynx.indexergui.client.LocalTimeSeriesBuffer.Sample;
import com.kalynx.indexergui.client.MetricsPoller;
import com.kalynx.swingtheme.theme.Theme;
import com.kalynx.swingtheme.theme.ThemeManager;
import com.kalynx.swingtheme.themedcomponents.ThemedComboBox;
import com.kalynx.swingtheme.themedcomponents.ThemedLabel;
import com.kalynx.swingtheme.themedcomponents.ThemedPanel;
import com.kalynx.swingtheme.themedcomponents.ThemedTabbedPane;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Path2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Dashboard panel: connection status, CPU block with per-core grid, storage/memory/connections row,
 * and tabbed charts. Data comes from {@link MetricsPoller} which polls the indexer over HTTP.
 */
public final class StatisticsPanel extends ThemedPanel {

    private static final String[] WINDOW_LABELS = {
        "Last 1 second", "Last 60 seconds", "Last 60 minutes", "Last 24 hours"
    };
    private static final long[] WINDOW_MS = {
        1_000L, 60_000L, 3_600_000L, 86_400_000L
    };

    private final MetricsPoller poller;
    private final ThemeManager  tm = ThemeManager.getInstance();

    private final ThemedLabel statusLabel;

    // CPU block (full width)
    private final ThemedLabel   cpuTotalLabel;
    private final ThemedPanel   coreGridPanel;
    private JLabel[]            coreLabels = new JLabel[0];
    private int                 lastCoreColCount = -1;

    // Summary cards (3-column row)
    private final MetricCard storageCard;
    private final MetricCard memCard;
    private final MetricCard connCard;

    // Chart tabs
    private final ScaledLineChart cpuChart;
    private final ScaledLineChart memChart;
    private final ScaledLineChart connChart;
    private final ScaledLineChart apiChart;

    private final ThemedComboBox<String> windowCombo;
    private Timer refreshTimer;

    public StatisticsPanel(MetricsPoller poller) {
        super(new MigLayout("insets 12, gap 8",
                            "[grow]",
                            "[][][][100::][grow]"));
        this.poller = poller;
        setOpaque(true);

        // --- connection status row -----------------------------------------------
        statusLabel = new ThemedLabel("Connectingâ€¦");
        statusLabel.setFont(tm.getBaseFont().deriveFont(Font.BOLD, tm.scale(11)));
        add(statusLabel, "growx, wrap");

        // --- time-window selector ------------------------------------------------
        ThemedPanel selectorRow = new ThemedPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        selectorRow.add(new ThemedLabel("Time window:"));
        windowCombo = new ThemedComboBox<>();
        for (String label : WINDOW_LABELS) windowCombo.addItem(label);
        selectorRow.add(windowCombo);
        add(selectorRow, "growx, wrap");

        // --- CPU block (full width) ----------------------------------------------
        ThemedPanel cpuBlock = new ThemedPanel(new MigLayout("insets 8, gap 4", "[grow]", "[][]"));
        tm.addThemeChangeListener(() -> applyBorder(cpuBlock, new Color(58, 150, 221)));
        applyBorder(cpuBlock, new Color(58, 150, 221));

        cpuTotalLabel = new ThemedLabel("Total CPU Usage: â€”");
        cpuTotalLabel.setFont(tm.getBaseFont().deriveFont(Font.BOLD, tm.scale(13)));
        cpuTotalLabel.setForeground(new Color(58, 150, 221));
        cpuBlock.add(cpuTotalLabel, "growx, wrap");

        coreGridPanel = new ThemedPanel(new MigLayout("insets 0, gap 6 2", "", ""));
        cpuBlock.add(coreGridPanel, "growx");

        add(cpuBlock, "growx, wrap");

        // --- summary cards row (Storage | Memory | Active Connections) -----------
        ThemedPanel cardsRow = new ThemedPanel(new MigLayout("insets 0, gap 8", "[grow][grow][grow]", "[]"));

        storageCard = new MetricCard("Storage",           new Color( 80, 200, 120));
        memCard     = new MetricCard("Memory Usage",      new Color(150,  80, 221));
        connCard    = new MetricCard("Active Connections", new Color( 58, 150, 221));

        cardsRow.add(storageCard, "grow");
        cardsRow.add(memCard,     "grow");
        cardsRow.add(connCard,    "grow");

        add(cardsRow, "growx, wrap");

        // --- chart tabs ----------------------------------------------------------
        cpuChart  = new ScaledLineChart(new Color( 58, 150, 221), "%",  100.0);
        memChart  = new ScaledLineChart(new Color(150,  80, 221), "MB", 0);
        connChart = new ScaledLineChart(new Color( 80, 200, 120), "",   0);
        apiChart  = new ScaledLineChart(new Color(221, 150,  58), "",   0);

        ThemedTabbedPane chartTabs = new ThemedTabbedPane();
        chartTabs.addTab("CPU",         wrapChart(cpuChart));
        chartTabs.addTab("Memory",      wrapChart(memChart));
        chartTabs.addTab("Connections", wrapChart(connChart));
        chartTabs.addTab("API Calls",   wrapChart(apiChart));
        add(chartTabs, "growx, growy");
    }

    private void applyBorder(JPanel panel, Color accent) {
        Theme t = tm.getCurrentTheme();
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(accent.darker(), 1),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        panel.setBackground(t.getBackgroundColor());
    }

    private static ThemedPanel wrapChart(ScaledLineChart chart) {
        ThemedPanel p = new ThemedPanel(new BorderLayout());
        p.add(chart, BorderLayout.CENTER);
        return p;
    }

    // --- lifecycle ---------------------------------------------------------------

    public void startRefresh() {
        refreshTimer = new Timer(1000, e -> refresh());
        refreshTimer.start();
    }

    public void stopRefresh() {
        if (refreshTimer != null) refreshTimer.stop();
    }

    private void refresh() {
        long windowMs = WINDOW_MS[Math.max(0, windowCombo.getSelectedIndex())];

        if (poller.isConnected()) {
            statusLabel.setText("Connected");
            statusLabel.setForeground(new Color(80, 200, 120));
        } else {
            statusLabel.setText("Disconnected â€” retryingâ€¦");
            statusLabel.setForeground(new Color(221, 80, 80));
        }

        // --- CPU block -----------------------------------------------------------
        double totalCpu = poller.getCpuSamples().average(windowMs);
        cpuTotalLabel.setText(String.format("Total CPU Usage: %.1f%%", totalCpu));

        List<LocalTimeSeriesBuffer> coreBufs = poller.getPerCoreSamples();
        int n = coreBufs.size();
        if (n > 0) {
            Font coreFont = tm.getBaseFont().deriveFont(Font.PLAIN, tm.scale(10));
            FontMetrics fm = getFontMetrics(coreFont);
            // Sample covers 3-digit core numbers (100+ cores) plus the gap between columns
            int gap    = tm.scale(6);
            int labelW = fm.stringWidth("CPU 000: 000.0%") + gap;
            int panelW = coreGridPanel.getWidth();
            // Account for inter-column gaps: each cell = (panelW - gap*(cols-1)) / cols â‰¥ labelW - gap
            // â†’ cols â‰¤ (panelW + gap) / labelW.  Fall back to 4 if not yet laid out.
            int cols = Math.max(1, panelW > 0 ? Math.max(1, (panelW + gap) / labelW) : 4);

            if (coreLabels.length != n || cols != lastCoreColCount) {
                lastCoreColCount = cols;
                coreGridPanel.removeAll();
                StringBuilder colSpec = new StringBuilder();
                for (int c = 0; c < cols; c++) colSpec.append("[grow,fill]");
                coreGridPanel.setLayout(new MigLayout("insets 0, gap 6 2, wrap " + cols, colSpec.toString()));
                coreLabels = new JLabel[n];
                for (int i = 0; i < n; i++) {
                    JLabel lbl = new JLabel();
                    lbl.setFont(coreFont);
                    coreLabels[i] = lbl;
                    coreGridPanel.add(lbl, "growx");
                }
                // Revalidate the whole panel so the outer MigLayout re-measures the CPU
                // block's new preferred height and shrinks the chart area accordingly.
                revalidate();
            }
            for (int i = 0; i < n; i++) {
                double pct   = coreBufs.get(i).average(windowMs);
                Color  color = Color.getHSBColor((float) i / n, 0.75f, 0.9f);
                coreLabels[i].setForeground(color);
                coreLabels[i].setText(String.format("CPU %d: %05.1f%%", i, pct));
            }
        }

        // --- summary cards -------------------------------------------------------
        long   freeMb  = poller.getDiskFreeMb();
        long   totalMb = poller.getDiskTotalMb();
        storageCard.update(formatDisk(freeMb, totalMb));

        memCard.update(String.format("%.0f MB", poller.getMemorySamples().average(windowMs)));
        connCard.update(String.format("%.0f", poller.getConnectionSamples().average(windowMs)));

        // --- charts --------------------------------------------------------------
        List<List<Sample>> coreWindows = coreBufs.stream()
                .map(buf -> buf.getWindow(windowMs))
                .toList();
        cpuChart.setDataSets(coreWindows);
        memChart.setData(poller.getMemorySamples().getWindow(windowMs));
        connChart.setData(poller.getConnectionSamples().getWindow(windowMs));
        apiChart.setData(poller.getApiCallSamples().getWindow(windowMs));
    }

    private static String formatDisk(long freeMb, long totalMb) {
        if (totalMb <= 0) return "â€”";
        return formatMb(freeMb) + " free\nof " + formatMb(totalMb);
    }

    private static String formatMb(long mb) {
        if (mb >= 1024 * 1024) return String.format("%.1f TB", mb / (1024.0 * 1024));
        if (mb >= 1024)        return String.format("%.0f GB", mb / 1024.0);
        return mb + " MB";
    }

    // =====================================================================
    // MetricCard â€” compact summary card with a single value
    // =====================================================================

    private final class MetricCard extends ThemedPanel {

        private final Color       accentColor;
        private final ThemedLabel titleLabel;
        private final ThemedLabel valueLabel;

        MetricCard(String title, Color accent) {
            super(new MigLayout("insets 10, gap 4, flowy", "[grow]", "[][]"));
            this.accentColor = accent;
            setOpaque(true);

            titleLabel = new ThemedLabel(title);
            titleLabel.setFont(tm.getBaseFont().deriveFont(Font.BOLD, tm.scale(11)));

            valueLabel = new ThemedLabel("â€”");
            valueLabel.setFont(tm.getBaseFont().deriveFont(Font.BOLD, (float) tm.scale(20)));
            valueLabel.setForeground(accent);

            add(titleLabel, "growx");
            add(valueLabel, "growx");

            tm.addThemeChangeListener(this::applyCardBorder);
            applyCardBorder();
        }

        void update(String value) {
            // support newline-separated multi-line values via HTML
            if (value.contains("\n")) {
                valueLabel.setText("<html>" + value.replace("\n", "<br>") + "</html>");
            } else {
                valueLabel.setText(value);
            }
        }

        private void applyCardBorder() {
            Theme t = tm.getCurrentTheme();
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(accentColor.darker(), 1),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)));
            setBackground(t.getBackgroundColor());
            if (titleLabel != null) titleLabel.setForeground(t.getSecondaryTextColor());
        }
    }

    // =====================================================================
    // ScaledLineChart
    // =====================================================================

    private static final class ScaledLineChart extends JPanel {

        private static final int LEFT_PAD  = 60;
        private static final int RIGHT_PAD = 12;
        private static final int TOP_PAD   = 10;
        private static final int BOT_PAD   = 10;
        private static final int GRID_DIVS = 4;

        private final Color  lineColor;
        private final String unit;
        private final double fixedMax;

        private List<List<Sample>> datasets   = List.of();
        private double             dynamicMax = 10;

        private int hoverX = -1;
        private int hoverY = -1;

        ScaledLineChart(Color lineColor, String unit, double fixedMax) {
            this.lineColor = lineColor;
            this.unit      = unit;
            this.fixedMax  = fixedMax;
            setOpaque(true);
            ThemeManager tm = ThemeManager.getInstance();
            tm.addThemeChangeListener(() -> {
                setBackground(tm.getCurrentTheme().getBackgroundColor());
                repaint();
            });
            setBackground(tm.getCurrentTheme().getBackgroundColor());
            setupHoverListeners();
        }

        private void setupHoverListeners() {
            addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    hoverX = e.getX();
                    hoverY = e.getY();
                    repaint();
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseExited(MouseEvent e) {
                    hoverX = -1;
                    repaint();
                }
            });
        }

        void setData(List<Sample> data) { setDataSets(List.of(data)); }

        void setDataSets(List<List<Sample>> newDatasets) {
            this.datasets = newDatasets;
            if (fixedMax <= 0 && !newDatasets.isEmpty()) {
                double max = newDatasets.stream()
                        .flatMap(Collection::stream)
                        .mapToDouble(Sample::value).max().orElse(0);
                dynamicMax = Math.max(dynamicMax, Math.max(max * 1.2, 10));
            }
            repaint();
        }

        private Color datasetColor(int i, int n) {
            if (n == 1) return lineColor;
            return Color.getHSBColor((float) i / n, 0.75f, 1.0f);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Theme theme = ThemeManager.getInstance().getCurrentTheme();

            int w = getWidth(), h = getHeight();
            if (w < LEFT_PAD + RIGHT_PAD + 20 || h < TOP_PAD + BOT_PAD + 20) return;

            int chartX = LEFT_PAD, chartY = TOP_PAD;
            int chartW = w - LEFT_PAD - RIGHT_PAD;
            int chartH = h - TOP_PAD  - BOT_PAD;

            double yMax = fixedMax > 0 ? fixedMax : dynamicMax;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Font        labelFont = ThemeManager.getInstance().getBaseFont().deriveFont(Font.PLAIN, 10f);
            g2.setFont(labelFont);
            FontMetrics fm = g2.getFontMetrics();

            Color gridColor  = new Color(theme.getForegroundColor().getRed(),
                                         theme.getForegroundColor().getGreen(),
                                         theme.getForegroundColor().getBlue(), 35);
            Color labelColor = theme.getSecondaryTextColor();

            for (int i = 0; i <= GRID_DIVS; i++) {
                double frac  = (double) i / GRID_DIVS;
                int    lineY = chartY + chartH - (int) (frac * chartH);
                g2.setColor(gridColor);
                g2.setStroke(new BasicStroke(0.5f));
                g2.drawLine(chartX, lineY, chartX + chartW, lineY);
                String lbl = formatLabel(frac * yMax);
                int    lw  = fm.stringWidth(lbl);
                g2.setColor(labelColor);
                g2.drawString(lbl, chartX - lw - 4, lineY + fm.getAscent() / 2 - 1);
            }

            g2.setColor(gridColor);
            g2.setStroke(new BasicStroke(0.5f));
            g2.drawRect(chartX, chartY, chartW, chartH);
            g2.clipRect(chartX, chartY, chartW, chartH);

            boolean single = datasets.size() == 1;

            for (int di = 0; di < datasets.size(); di++) {
                List<Sample> data  = datasets.get(di);
                Color        color = datasetColor(di, datasets.size());

                if (data.size() >= 2) {
                    long tMin = data.getFirst().timestampMs();
                    long tMax = data.getLast().timestampMs();
                    if (tMax == tMin) tMax = tMin + 1;

                    Path2D path  = new Path2D.Float();
                    boolean first = true;
                    for (Sample s : data) {
                        float x = chartX + (float)(s.timestampMs() - tMin) / (tMax - tMin) * chartW;
                        float y = chartY + chartH - (float)(Math.min(s.value(), yMax) / yMax) * chartH;
                        if (first) { path.moveTo(x, y); first = false; }
                        else         path.lineTo(x, y);
                    }

                    if (single) {
                        Path2D fill = new Path2D.Float(path);
                        Sample last = data.getLast();
                        float  lx   = chartX + (float)(last.timestampMs() - tMin) / (tMax - tMin) * chartW;
                        fill.lineTo(lx, chartY + chartH);
                        fill.lineTo(chartX, chartY + chartH);
                        fill.closePath();
                        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
                        g2.fill(fill);
                    }

                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(path);

                } else if (data.size() == 1) {
                    float y = chartY + chartH - (float)(Math.min(data.getFirst().value(), yMax) / yMax) * chartH;
                    g2.setColor(color);
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawLine(chartX, (int) y, chartX + chartW, (int) y);
                }
            }

            if (datasets.isEmpty() || datasets.stream().allMatch(List::isEmpty)) {
                g2.setColor(labelColor);
                g2.setFont(labelFont.deriveFont(Font.ITALIC));
                String msg = "No data";
                int    mw  = g2.getFontMetrics().stringWidth(msg);
                g2.drawString(msg, chartX + (chartW - mw) / 2, chartY + chartH / 2);
            }

            g2.dispose();

            if (hoverX >= chartX && hoverX <= chartX + chartW) {
                Graphics2D og = (Graphics2D) g.create();
                og.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
                og.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                og.setFont(labelFont);
                drawHoverOverlay(og, chartX, chartY, chartW, chartH, yMax, og.getFontMetrics(), labelFont, theme);
                og.dispose();
            }
        }

        private void drawHoverOverlay(Graphics2D g2, int chartX, int chartY, int chartW, int chartH,
                                      double yMax, FontMetrics fm, Font labelFont, Theme theme) {
            List<Sample> refDataset = datasets.stream().filter(d -> d.size() >= 2).findFirst().orElse(null);
            if (refDataset == null) return;

            long tMin = refDataset.getFirst().timestampMs();
            long tMax = refDataset.getLast().timestampMs();
            if (tMax == tMin) return;

            long tHover = tMin + (long)((double)(hoverX - chartX) / chartW * (tMax - tMin));

            Color fgColor = theme.getForegroundColor();
            g2.setColor(new Color(fgColor.getRed(), fgColor.getGreen(), fgColor.getBlue(), 100));
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 0, new float[]{4, 3}, 0));
            g2.drawLine(hoverX, chartY, hoverX, chartY + chartH);

            HoverPoint hp = resolveHoverPoint(chartY, chartH, yMax, tHover);
            if (hp == null) return;

            drawHoverDot(g2, chartY, chartH, yMax, hp);

            List<String> lines = buildTooltipLines(tHover, hp);
            drawTooltipBox(g2, lines, fm, labelFont, chartX, chartY, chartW, chartH, theme, hp.color());
        }

        private HoverPoint resolveHoverPoint(int chartY, int chartH, double yMax, long tHover) {
            if (datasets.isEmpty()) return null;
            if (datasets.size() == 1) {
                double value = interpolateValue(datasets.getFirst(), tHover);
                return Double.isNaN(value) ? null : new HoverPoint(0, value, lineColor);
            }
            int    closestIdx   = -1;
            double closestValue = Double.NaN;
            double minDist      = Double.MAX_VALUE;
            for (int di = 0; di < datasets.size(); di++) {
                double v = interpolateValue(datasets.get(di), tHover);
                if (Double.isNaN(v)) continue;
                float dotY = chartY + chartH - (float)(Math.min(v, yMax) / yMax) * chartH;
                double dist = Math.abs(dotY - hoverY);
                if (dist < minDist) {
                    minDist      = dist;
                    closestIdx   = di;
                    closestValue = v;
                }
            }
            if (closestIdx < 0) return null;
            return new HoverPoint(closestIdx, closestValue, datasetColor(closestIdx, datasets.size()));
        }

        private void drawHoverDot(Graphics2D g2, int chartY, int chartH, double yMax, HoverPoint hp) {
            float dotY = chartY + chartH - (float)(Math.min(hp.value(), yMax) / yMax) * chartH;
            if (dotY < chartY || dotY > chartY + chartH) return;
            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(hp.color());
            g2.fillOval(hoverX - 4, (int) dotY - 4, 8, 8);
            g2.setColor(Color.WHITE);
            g2.drawOval(hoverX - 4, (int) dotY - 4, 8, 8);
        }

        private List<String> buildTooltipLines(long tHover, HoverPoint hp) {
            List<String> lines = new ArrayList<>();
            ZonedDateTime zdt  = Instant.ofEpochMilli(tHover).atZone(ZoneId.systemDefault());
            lines.add(String.format("%02d:%02d:%02d", zdt.getHour(), zdt.getMinute(), zdt.getSecond()));
            String valueText = datasets.size() > 1
                    ? "CPU " + hp.datasetIndex() + ": " + formatLabel(hp.value())
                    : formatLabel(hp.value());
            lines.add(valueText);
            return lines;
        }

        private void drawTooltipBox(Graphics2D g2, List<String> lines, FontMetrics plainFm, Font labelFont,
                                    int chartX, int chartY, int chartW, int chartH,
                                    Theme theme, Color valueColor) {
            Font        boldFont   = labelFont.deriveFont(Font.BOLD);
            FontMetrics boldFm     = g2.getFontMetrics(boldFont);
            int         padding    = 6;
            int         lineH      = Math.max(plainFm.getHeight(), boldFm.getHeight());

            int boxW = 0;
            for (int i = 0; i < lines.size(); i++) {
                FontMetrics fmForLine = i == 0 ? plainFm : boldFm;
                boxW = Math.max(boxW, fmForLine.stringWidth(lines.get(i)));
            }
            boxW += padding * 2;
            int boxH = lines.size() * lineH + padding * 2;

            int tipX = hoverX + 12;
            if (tipX + boxW > chartX + chartW) tipX = hoverX - boxW - 12;
            int tipY = chartY + 4;
            if (tipY + boxH > chartY + chartH) tipY = chartY + chartH - boxH - 4;

            Color bg = theme.getBackgroundColor();
            g2.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 220));
            g2.fillRoundRect(tipX, tipY, boxW, boxH, 6, 6);

            Color fg = theme.getForegroundColor();
            g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 80));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(tipX, tipY, boxW, boxH, 6, 6);

            int textX = tipX + padding;
            int textY = tipY + padding + plainFm.getAscent();
            for (int i = 0; i < lines.size(); i++) {
                if (i == 0) {
                    g2.setColor(theme.getSecondaryTextColor());
                    g2.setFont(labelFont);
                } else {
                    g2.setColor(valueColor);
                    g2.setFont(boldFont);
                }
                g2.drawString(lines.get(i), textX, textY + i * lineH);
            }
        }

        private double interpolateValue(List<Sample> data, long tHover) {
            if (data.isEmpty()) return Double.NaN;
            if (data.size() == 1) return data.getFirst().value();
            for (int i = 0; i < data.size() - 1; i++) {
                Sample a = data.get(i);
                Sample b = data.get(i + 1);
                if (tHover >= a.timestampMs() && tHover <= b.timestampMs()) {
                    double t = (double)(tHover - a.timestampMs()) / (b.timestampMs() - a.timestampMs());
                    return a.value() + t * (b.value() - a.value());
                }
            }
            return tHover < data.getFirst().timestampMs()
                    ? data.getFirst().value()
                    : data.getLast().value();
        }

        private String formatLabel(double value) {
            if ("%".equals(unit))  return String.format("%.1f%%", value);
            if ("MB".equals(unit)) {
                if (value >= 1024) return String.format("%.1f GB", value / 1024);
                return String.format("%.0f MB", value);
            }
            if (value >= 1_000_000) return String.format("%.1fM", value / 1_000_000);
            if (value >= 1_000)     return String.format("%.0fK", value / 1_000);
            return String.format("%.1f", value);
        }

        private record HoverPoint(int datasetIndex, double value, Color color) {}
    }
}

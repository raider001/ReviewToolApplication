package com.kalynx.serverlessreviewtool.ui.mainpanels;

import com.kalynx.swingtheme.themedcomponents.*;
import com.kalynx.swingtheme.theme.ThemeManager;
import net.miginfocom.swing.MigLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * LogsPanel displays application logs in real-time with color-coded severity levels.
 * Automatically scrolls to show the latest log entries.
 */
public class LogsPanel extends ThemedPanel {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogsPanel.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_LOG_LINES = 10000;

    private final JTextPane logTextPane;
    private final StyledDocument document;
    private final ThemedScrollPane scrollPane;
    private final ThemedButton clearButton;
    private final ThemedComboBox<LogLevel> displayLevelComboBox;
    private final ThemeManager themeManager;
    private final List<LogEntry> logEntries;
    private LogLevel activeDisplayLevel;

    private Style infoStyle;
    private Style warnStyle;
    private Style errorStyle;
    private Style debugStyle;
    private Style timestampStyle;

    public LogsPanel() {
        this.themeManager = ThemeManager.getInstance();
        this.logTextPane = new JTextPane();
        this.document = logTextPane.getStyledDocument();
        this.scrollPane = new ThemedScrollPane(logTextPane);
        this.clearButton = new ThemedButton("Clear Logs");
        this.displayLevelComboBox = new ThemedComboBox<>(LogLevel.values());
        this.logEntries = new ArrayList<>();
        this.activeDisplayLevel = LogLevel.INFO;

        configureLogPane();
        initializeStyles();
        configureLayout();
        setupListeners();
    }

    private void configureLogPane() {
        logTextPane.setEditable(false);
        logTextPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        updateLogPaneColors();
    }

    private void updateLogPaneColors() {
        logTextPane.setBackground(themeManager.getCurrentTheme().getBackgroundColor());
        logTextPane.setForeground(themeManager.getCurrentTheme().getForegroundColor());
        logTextPane.setCaretColor(themeManager.getCurrentTheme().getForegroundColor());
    }

    private void initializeStyles() {
        timestampStyle = document.addStyle("timestamp", null);
        StyleConstants.setForeground(timestampStyle, new Color(128, 128, 128));
        StyleConstants.setBold(timestampStyle, false);

        infoStyle = document.addStyle("info", null);
        StyleConstants.setForeground(infoStyle, new Color(70, 130, 180));
        StyleConstants.setBold(infoStyle, false);

        warnStyle = document.addStyle("warn", null);
        StyleConstants.setForeground(warnStyle, new Color(255, 165, 0));
        StyleConstants.setBold(warnStyle, true);

        errorStyle = document.addStyle("error", null);
        StyleConstants.setForeground(errorStyle, new Color(220, 50, 47));
        StyleConstants.setBold(errorStyle, true);

        debugStyle = document.addStyle("debug", null);
        StyleConstants.setForeground(debugStyle, new Color(150, 150, 150));
        StyleConstants.setBold(debugStyle, false);
    }

    private void configureLayout() {
        setLayout(new MigLayout("fill, insets 0", "[grow]", "[][grow]"));

        ThemedPanel headerPanel = new ThemedPanel();
        headerPanel.setLayout(new MigLayout("insets 5", "[grow][][][]", "[]"));
        headerPanel.setBorder(ThemedTitledBorder.create("Application Logs"));

        ThemedLabel levelLabel = new ThemedLabel("Level:");
        displayLevelComboBox.setSelectedItem(activeDisplayLevel);

        headerPanel.add(levelLabel);
        headerPanel.add(displayLevelComboBox, "w 90!");
        headerPanel.add(clearButton, "align right");

        add(headerPanel, "growx, wrap");
        add(scrollPane, "grow");
    }

    private void setupListeners() {
        clearButton.addActionListener(this::clearLogs);
        displayLevelComboBox.addActionListener(this::onDisplayLevelChanged);
    }

    /**
     * Appends a log message with INFO level.
     *
     * @param message the log message
     */
    public void appendInfo(String message) {
        appendLog(LogLevel.INFO, message);
    }

    /**
     * Appends a log message with WARN level.
     *
     * @param message the log message
     */
    public void appendWarn(String message) {
        appendLog(LogLevel.WARN, message);
    }

    /**
     * Appends a log message with ERROR level.
     *
     * @param message the log message
     */
    public void appendError(String message) {
        appendLog(LogLevel.ERROR, message);
    }

    /**
     * Appends a log message with DEBUG level.
     *
     * @param message the log message
     */
    public void appendDebug(String message) {
        appendLog(LogLevel.DEBUG, message);
    }

    private void appendLog(LogLevel level, String message) {
        SwingUtilities.invokeLater(() -> {
            try {
                String timestamp = LocalDateTime.now().format(TIME_FORMATTER);
                LogEntry entry = new LogEntry(timestamp, level, message);
                logEntries.add(entry);
                trimLogIfNeeded();

                if (shouldDisplay(level)) {
                    appendEntry(entry);
                    scrollToBottom();
                }

            } catch (BadLocationException e) {
                LOGGER.error("Failed to append log entry to document", e);
            }
        });
    }

    private void appendEntry(LogEntry entry) throws BadLocationException {
        document.insertString(document.getLength(), entry.timestamp() + " ", timestampStyle);
        document.insertString(document.getLength(), String.format("%-5s", entry.level().name()) + " ", getStyleForLevel(entry.level()));
        document.insertString(document.getLength(), entry.message() + "\n", null);
    }

    private Style getStyleForLevel(LogLevel level) {
        return switch (level) {
            case INFO -> infoStyle;
            case WARN -> warnStyle;
            case ERROR -> errorStyle;
            case DEBUG -> debugStyle;
        };
    }

    private void trimLogIfNeeded() {
        if (logEntries.size() > MAX_LOG_LINES) {
            int itemsToRemove = logEntries.size() - MAX_LOG_LINES;
            logEntries.subList(0, itemsToRemove).clear();
        }
    }

    private void scrollToBottom() {
        logTextPane.setCaretPosition(document.getLength());
    }

    private void clearLogs() {
        try {
            logEntries.clear();
            document.remove(0, document.getLength());
        } catch (BadLocationException e) {
            LOGGER.error("Failed to clear log document", e);
        }
    }

    private void onDisplayLevelChanged() {
        LogLevel selectedLevel = (LogLevel) displayLevelComboBox.getSelectedItem();
        if (selectedLevel == null || selectedLevel == activeDisplayLevel) {
            return;
        }

        activeDisplayLevel = selectedLevel;
        renderFilteredLogs();
    }

    private void renderFilteredLogs() {
        try {
            document.remove(0, document.getLength());
            for (LogEntry entry : logEntries) {
                if (shouldDisplay(entry.level())) {
                    appendEntry(entry);
                }
            }
            scrollToBottom();
        } catch (BadLocationException e) {
            LOGGER.error("Failed to render filtered log entries", e);
        }
    }

    private boolean shouldDisplay(LogLevel level) {
        return switch (activeDisplayLevel) {
            case ERROR -> level == LogLevel.ERROR;
            case WARN -> level == LogLevel.WARN || level == LogLevel.ERROR;
            case INFO -> level == LogLevel.INFO || level == LogLevel.WARN || level == LogLevel.ERROR;
            case DEBUG -> true;
        };
    }

    private enum LogLevel {
        DEBUG("Debug"),
        INFO("Info"),
        WARN("Warn"),
        ERROR("Error");

        private final String label;

        LogLevel(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private record LogEntry(String timestamp, LogLevel level, String message) {}
}


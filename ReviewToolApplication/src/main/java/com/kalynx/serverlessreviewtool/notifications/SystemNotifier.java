package com.kalynx.serverlessreviewtool.notifications;

import com.kalynx.serverlessreviewtool.theme.icons.AppIcon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.TrayIcon.MessageType;
import java.io.IOException;

/**
 * SystemNotifier provides cross-platform desktop notification support.
 *
 * <p>On Windows, notifications are delivered via a PowerShell Windows Runtime
 * toast using a registered App User Model ID (AUMID) so the notification header
 * shows "Review Tool" rather than the JVM process name. On Linux,
 * {@code notify-send} is used. AWT {@link SystemTray} is used as a final
 * fallback when neither native path is available.
 */
public class SystemNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemNotifier.class);
    private static final SystemNotifier INSTANCE = new SystemNotifier();
    private static final String APP_ID = "ReviewTool";
    private static final String APP_DISPLAY_NAME = "Review Tool";

    private TrayIcon trayIcon;
    private boolean aumidRegistered = false;

    private SystemNotifier() {}

    /**
     * Returns the singleton instance of {@code SystemNotifier}.
     *
     * @return shared {@code SystemNotifier} instance
     */
    public static SystemNotifier getInstance() {
        return INSTANCE;
    }

    /**
     * Sends a desktop notification with the supplied title and message.
     *
     * <p>On Windows the notification is delivered via a PowerShell Windows
     * Runtime toast backed by a registered AUMID so the system displays
     * "Review Tool" as the sender. On Linux {@code notify-send} is used.
     * An AWT {@link SystemTray} balloon is used as a last resort when neither
     * native path succeeds.
     *
     * @param title   notification title
     * @param message notification body text
     */
    public void sendNotification(String title, String message) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            sendWindowsToast(title, message);
        } else if (os.contains("nix") || os.contains("nux") || os.contains("linux")) {
            try {
                sendLinuxNotification(title, message);
            } catch (IOException | InterruptedException e) {
                LOGGER.error("Failed to send Linux notification", e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        } else {
            trySendViaTray(title, message);
        }
    }

    private void sendWindowsToast(String title, String message) {
        try {
            ensureAumidRegistered();
            sendWindowsNotification(title, message);
        } catch (IOException | InterruptedException e) {
            LOGGER.error("Failed to send Windows toast notification", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            trySendViaTray(title, message);
        }
    }

    private void ensureAumidRegistered() throws IOException, InterruptedException {
        if (aumidRegistered) {
            return;
        }
        String registryScript = String.format(
            "$null = New-Item 'Registry::HKCU\\SOFTWARE\\Classes\\AppUserModelId\\%s' -Force;" +
            "$null = New-ItemProperty 'Registry::HKCU\\SOFTWARE\\Classes\\AppUserModelId\\%s' -Name 'DisplayName' -Value '%s' -PropertyType String -Force;",
            APP_ID, APP_ID, escapeForPowerShell(APP_DISPLAY_NAME)
        );
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", registryScript);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.waitFor();
        aumidRegistered = true;
    }

    private void trySendViaTray(String title, String message) {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            TrayIcon icon = getOrCreateTrayIcon();
            icon.displayMessage(title, message, MessageType.INFO);
        } catch (Exception e) {
            LOGGER.warn("SystemTray notification failed", e);
        }
    }

    private TrayIcon getOrCreateTrayIcon() throws AWTException {
        if (trayIcon != null) {
            return trayIcon;
        }
        SystemTray tray = SystemTray.getSystemTray();
        Image image = AppIcon.createIcon(tray.getTrayIconSize().width);
        trayIcon = new TrayIcon(image, APP_DISPLAY_NAME);
        trayIcon.setImageAutoSize(true);
        tray.add(trayIcon);
        return trayIcon;
    }

    private void sendWindowsNotification(String title, String message) throws IOException, InterruptedException {
        String script = String.format(
            "[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime] | Out-Null;" +
            "$template = [Windows.UI.Notifications.ToastNotificationManager]::GetTemplateContent([Windows.UI.Notifications.ToastTemplateType]::ToastText02);" +
            "$nodes = $template.GetElementsByTagName('text');" +
            "$nodes.Item(0).AppendChild($template.CreateTextNode('%s')) | Out-Null;" +
            "$nodes.Item(1).AppendChild($template.CreateTextNode('%s')) | Out-Null;" +
            "$toast = [Windows.UI.Notifications.ToastNotification]::new($template);" +
            "[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier('%s').Show($toast);",
            escapeForPowerShell(title), escapeForPowerShell(message), APP_ID
        );
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.waitFor();
    }

    private void sendLinuxNotification(String title, String message) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("notify-send", title, message);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        process.waitFor();
    }

    private String escapeForPowerShell(String value) {
        return value.replace("'", "''");
    }
}


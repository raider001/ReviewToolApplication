package com.kalynx.serverlessreviewtool.ui.mainpanels.reviewpanel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.IllegalComponentStateException;
import java.awt.Point;
import java.awt.Window;

/**
 * Manages a fading toast-style notification window anchored near a parent component.
 */
public class UpdateToastWindow {

    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateToastWindow.class);

    private static final int TOTAL_DURATION_MS = 5000;
    private static final int FADE_DURATION_MS = 1000;
    private static final float INITIAL_OPACITY = 0.95f;

    private final Component parent;
    private JWindow currentToastWindow;

    /**
     * @param parent the component used to anchor the toast position and resolve the owner window
     */
    public UpdateToastWindow(Component parent) {
        this.parent = parent;
    }

    /**
     * Displays a toast notification with the given message. Any existing toast is dismissed first.
     *
     * @param message text to display in the toast
     */
    public void show(String message) {
        SwingUtilities.invokeLater(() -> {
            Window owner = SwingUtilities.getWindowAncestor(parent);
            if (owner == null || !owner.isDisplayable()) {
                LOGGER.warn("Cannot show update toast: owner window unavailable");
                return;
            }

            dismissCurrentToast();

            JWindow toast = buildToastWindow(owner, message);
            positionToast(toast, owner);
            toast.setAlwaysOnTop(true);
            setWindowOpacity(toast, INITIAL_OPACITY);
            toast.setVisible(true);
            startFadeTimer(toast);

            currentToastWindow = toast;
            LOGGER.info("Showing update toast: {}", message);
        });
    }

    private void dismissCurrentToast() {
        if (currentToastWindow != null) {
            currentToastWindow.dispose();
            currentToastWindow = null;
        }
    }

    private JWindow buildToastWindow(Window owner, String message) {
        JLabel label = new JLabel(message);
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(13f));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
        panel.setBackground(new Color(45, 45, 45));
        panel.add(label, BorderLayout.CENTER);

        JWindow toast = new JWindow(owner);
        toast.setContentPane(panel);
        toast.pack();
        return toast;
    }

    private void positionToast(JWindow toast, Window owner) {
        Point anchor;
        try {
            anchor = parent.getLocationOnScreen();
        } catch (IllegalComponentStateException e) {
            anchor = owner.getLocationOnScreen();
        }
        toast.setLocation(anchor.x + 16, anchor.y + 16);
    }

    private void startFadeTimer(JWindow toast) {
        int fadeStartMs = TOTAL_DURATION_MS - FADE_DURATION_MS;
        long startedAt = System.currentTimeMillis();

        Timer timer = new Timer(50, event -> {
            long elapsed = System.currentTimeMillis() - startedAt;
            if (elapsed >= TOTAL_DURATION_MS) {
                ((Timer) event.getSource()).stop();
                toast.dispose();
                if (currentToastWindow == toast) {
                    currentToastWindow = null;
                }
                return;
            }
            if (elapsed >= fadeStartMs) {
                float fadeProgress = (elapsed - fadeStartMs) / (float) FADE_DURATION_MS;
                setWindowOpacity(toast, Math.max(0.0f, INITIAL_OPACITY * (1.0f - fadeProgress)));
            }
        });
        timer.setRepeats(true);
        timer.start();
    }

    private void setWindowOpacity(Window window, float opacity) {
        try {
            window.setOpacity(Math.max(0.0f, Math.min(1.0f, opacity)));
        } catch (UnsupportedOperationException ignored) {
        }
    }
}


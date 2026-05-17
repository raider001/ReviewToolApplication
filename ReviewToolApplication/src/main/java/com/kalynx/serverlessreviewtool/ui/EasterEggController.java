package com.kalynx.serverlessreviewtool.ui;

import com.kalynx.swingtheme.theme.ThemeManager;

import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * EasterEggController manages the two activation conditions for the title bar Easter egg:
 * <ol>
 *   <li>The current day of the month is the 15th.</li>
 *   <li>The theme-toggle button is clicked five times within five seconds.</li>
 * </ol>
 * When either condition is met, the managed {@link EasterEggSpritePanel} is activated.
 */
public class EasterEggController {

    private static final int REQUIRED_TOGGLES = 5;
    private static final long WINDOW_MS = 5_000;

    private final EasterEggSpritePanel panel;
    private final Deque<Long> recentToggles = new ArrayDeque<>();

    /**
     * Creates a controller for the given sprite panel.
     *
     * @param panel the Easter egg panel to activate when conditions are met
     */
    public EasterEggController(EasterEggSpritePanel panel) {
        this.panel = panel;
    }

    /**
     * Evaluates the date condition immediately and registers the theme-toggle
     * listener for the click-count condition.
     */
    public void initialize() {
        if (LocalDate.now().getDayOfMonth() == 15) {
            panel.activate();
        }
        ThemeManager.getInstance().addThemeChangeListener(this::onThemeToggled);
    }

    private void onThemeToggled() {
        long now = System.currentTimeMillis();
        recentToggles.addLast(now);

        while (!recentToggles.isEmpty() && now - recentToggles.peekFirst() > WINDOW_MS) {
            recentToggles.pollFirst();
        }

        if (recentToggles.size() >= REQUIRED_TOGGLES) {
            recentToggles.clear();
            panel.activate();
        }
    }
}


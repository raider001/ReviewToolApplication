package com.kalynx.serverlessreviewtool.notifications;

/**
 * An immutable value object representing a desktop notification to be displayed to the user.
 *
 * @param title   the short heading shown at the top of the notification
 * @param message the body text of the notification
 */
public record Notification(String title, String message) {
}


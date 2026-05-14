package com.kalynx.serverlessreviewtool.notifications;

import com.kalynx.serverlessreviewtool.models.ReviewContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Evaluates a collection of {@link ReviewNotificationCondition} instances whenever the active
 * {@link ReviewContext} changes and dispatches any resulting notifications via {@link SystemNotifier}.
 *
 * <p>Register this service as a listener on {@code ReviewContextManager} by passing
 * {@code notificationService::onContextChanged}. The first call is treated as the
 * baseline – no notifications are fired until a subsequent context change arrives.
 *
 * <p>Additional conditions can be registered at any time via
 * {@link #addCondition(ReviewNotificationCondition)}.
 */
public class ReviewNotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewNotificationService.class);

    private final SystemNotifier notifier;
    private final Supplier<String> currentUserSupplier;
    private final List<ReviewNotificationCondition> conditions = new ArrayList<>();

    private ReviewContext previousContext;
    private boolean initialised = false;

    /**
     * Constructs a {@code ReviewNotificationService}.
     *
     * @param notifier            the notifier used to dispatch desktop notifications
     * @param currentUserSupplier supplier that returns the current logged-in user name
     */
    public ReviewNotificationService(SystemNotifier notifier, Supplier<String> currentUserSupplier) {
        this.notifier = notifier;
        this.currentUserSupplier = currentUserSupplier;
    }

    /**
     * Registers an additional notification condition. The condition will be evaluated
     * on every subsequent context change.
     *
     * @param condition the condition to add
     */
    public void addCondition(ReviewNotificationCondition condition) {
        conditions.add(condition);
    }

    /**
     * Called whenever the active {@link ReviewContext} changes. On the first invocation the
     * supplied context is stored as the baseline; from the second invocation onwards all
     * registered conditions are evaluated and any resulting notifications are sent.
     *
     * <p>This method is thread-safe and may be called from any thread.
     *
     * @param newContext the incoming review context, or {@code null} if no review is active
     */
    public synchronized void onContextChanged(ReviewContext newContext) {
        if (!initialised) {
            previousContext = newContext;
            initialised = true;
            return;
        }
        String currentUser = currentUserSupplier.get();
        for (ReviewNotificationCondition condition : conditions) {
            condition.evaluate(previousContext, newContext, currentUser)
                .ifPresent(notification -> {
                    LOGGER.debug("Sending notification: {} — {}", notification.title(), notification.message());
                    notifier.sendNotification(notification.title(), notification.message());
                });
        }
        previousContext = newContext;
    }
}


package com.larvalabs.brace;

/**
 * Receives a notification when a new error kind (a regression) first appears since the
 * process started. Implementations should be non-blocking or self-dispatch to a
 * background thread — they run on the error-recording path. See {@link LogNotifier},
 * {@link WebhookNotifier}, {@link MailerNotifier}.
 */
public interface Notifier {
    void notifyRegression(RegressionTracker.Regression regression);
}

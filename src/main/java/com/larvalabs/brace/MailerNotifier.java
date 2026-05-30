package com.larvalabs.brace;

/** Sends a plain-text email per regression via the app's {@link Mailer}. */
public class MailerNotifier implements Notifier {

    private final Mailer mailer;
    private final String to;

    public MailerNotifier(Mailer mailer, String to) {
        this.mailer = mailer;
        this.to = to;
    }

    @Override
    public void notifyRegression(RegressionTracker.Regression r) {
        if (mailer == null || to == null || to.isEmpty()) return;
        String subject = "[brace] New error since startup: " + r.type() + " @ " + r.route();
        String body = "A new error kind appeared after startup.\n\n"
            + "Type:       " + r.type() + "\n"
            + "Route:      " + r.route() + "\n"
            + "Message:    " + r.message() + "\n"
            + "First seen: " + r.firstSeen() + "\n";
        Thread.startVirtualThread(() -> {
            try {
                mailer.to(to).subject(subject).text(body).send();
            } catch (Exception e) {
                Log.warn("regression email failed: " + e.getMessage());
            }
        });
    }
}

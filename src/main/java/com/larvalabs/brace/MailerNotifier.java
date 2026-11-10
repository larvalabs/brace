package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.List;

/**
 * Sends a plain-text email per regression via the app's {@link Mailer}, one message per
 * recipient.
 *
 * <p>Recipients are validated when the notifier is built, each as exactly one address (as
 * {@link Mailer#to} requires since L14), so a bad one fails at startup instead of in the
 * background thread of every notification. A recipient whose send fails does not stop the
 * others. A null or empty recipient string configures no recipients (the notifier does
 * nothing), as before.
 */
public class MailerNotifier implements Notifier {

    private final Mailer mailer;
    private final List<String> recipients;

    /**
     * @param to one address, or several separated by commas
     *           ({@code "ops@example.com, \"On Call\" <oncall@example.com>"}). A comma inside a
     *           quoted display name is part of that address.
     * @throws IllegalArgumentException if any address is invalid, a group or a route address
     */
    public MailerNotifier(Mailer mailer, String to) {
        this(mailer, split(to));
    }

    /** One message to each address. */
    public MailerNotifier(Mailer mailer, String... to) {
        this(mailer, List.of(to));
    }

    /** One message to each address. */
    public MailerNotifier(Mailer mailer, List<String> to) {
        this.mailer = mailer;
        var checked = new ArrayList<String>();
        for (String address : to) {
            checked.add(Mailer.singleAddress(address, "MailerNotifier recipient").toString());
        }
        this.recipients = List.copyOf(checked);
    }

    /** Splits a comma-separated list strictly, honouring quoted display names. */
    private static List<String> split(String to) {
        if (to == null || to.isBlank()) return List.of();
        try {
            var parsed = jakarta.mail.internet.InternetAddress.parse(to, true);
            var out = new ArrayList<String>();
            for (var address : parsed) out.add(address.toString());
            return out;
        } catch (jakarta.mail.internet.AddressException e) {
            throw new IllegalArgumentException("MailerNotifier recipients are not a valid address list", e);
        }
    }

    /** The validated recipients, for tests. */
    List<String> recipients() {
        return recipients;
    }

    @Override
    public void notifyRegression(RegressionTracker.Regression r) {
        if (mailer == null || recipients.isEmpty()) return;
        String subject = "[brace] New error since startup: " + r.type() + " @ " + r.route();
        String body = "A new error kind appeared after startup.\n\n"
            + "Type:       " + r.type() + "\n"
            + "Route:      " + r.route() + "\n"
            + "Message:    " + r.message() + "\n"
            + "First seen: " + r.firstSeen() + "\n";
        Thread.startVirtualThread(() -> {
            for (String to : recipients) {
                try {
                    mailer.to(to).subject(subject).text(body).send();
                } catch (Exception e) {
                    Log.warn("regression email failed: " + Redactor.redactMessage(e.getMessage()));
                }
            }
        });
    }
}

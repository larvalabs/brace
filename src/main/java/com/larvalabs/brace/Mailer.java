package com.larvalabs.brace;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;

public class Mailer {

    /** Dev-mode capture bound: drop-oldest beyond this, so a long-running dev server can't leak. */
    static final int CAPTURE_LIMIT = 500;

    /**
     * Default SMTP timeouts. Jakarta Mail defaults every one of these to <em>infinite</em>, so
     * without them a blackholed or wedged SMTP host hangs {@code Transport.send} forever. That is
     * far worse than a slow send: {@link EmailBuilder#send()} is synchronous, and the common case
     * is sending from a {@link DurableJob}, where the job holds both its {@code JobPoller}
     * execution slot and a pooled DB connection for the whole hang. Enough of those and the job
     * system wedges permanently with no recovery short of a restart. Bounded timeouts turn that
     * into an ordinary job failure, which the queue already retries with backoff.
     *
     * <p>Values match {@link Http}'s defaults (10s connect, 30s per-operation).
     */
    static final java.time.Duration DEFAULT_CONNECT_TIMEOUT = java.time.Duration.ofSeconds(10);
    static final java.time.Duration DEFAULT_TIMEOUT = java.time.Duration.ofSeconds(30);

    private final String smtpUrl;
    private String defaultFrom;
    private String defaultReplyTo;
    private java.time.Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private java.time.Duration timeout = DEFAULT_TIMEOUT;
    private final List<CapturedEmail> captured = Collections.synchronizedList(new ArrayList<>());
    private final LongAdder sentCount = new LongAdder();
    private final LongAdder failCount = new LongAdder();

    public Mailer(String smtpUrl) {
        this.smtpUrl = smtpUrl;
    }

    public Mailer from(String from) { this.defaultFrom = from; return this; }
    public Mailer replyTo(String replyTo) { this.defaultReplyTo = replyTo; return this; }

    /** How long to wait for the SMTP TCP connect. Default 10s. */
    public Mailer connectTimeout(java.time.Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    /**
     * How long to wait on any single SMTP read or write once connected — applied as both
     * {@code mail.smtp.timeout} and {@code mail.smtp.writetimeout}. Default 30s. Raise it for a
     * slow relay or large attachments; it bounds each socket operation, not the whole send.
     */
    public Mailer timeout(java.time.Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public EmailBuilder to(String address) {
        return new EmailBuilder(this, address);
    }

    public List<CapturedEmail> sent() { return List.copyOf(captured); }
    public CapturedEmail last() {
        synchronized (captured) {
            return captured.isEmpty() ? null : captured.get(captured.size() - 1);
        }
    }
    public void clearCaptured() {
        captured.clear();
        sentCount.reset();
    }
    public long sentCount() { return sentCount.sum(); }
    public long failCount() { return failCount.sum(); }
    public long drainFailCount() { return failCount.sumThenReset(); }

    void sendAsync(EmailBuilder email) {
        Thread.startVirtualThread(() -> {
            try {
                send(email);
            } catch (Exception e) {
                // send() already counted the failure in failCount
                Log.warn("async email to " + email.to + " failed: " + e.getMessage());
            }
        });
    }

    void send(EmailBuilder email) {
        var from = email.from != null ? email.from : defaultFrom;

        if (smtpUrl == null) {
            // Dev mode: capture instead of sending, bounded drop-oldest. Capture is dev-only —
            // with SMTP configured, retaining every sent body would leak without bound.
            synchronized (captured) {
                captured.add(new CapturedEmail(email.to, email.cc, email.subject, email.textBody, email.htmlBody, from));
                if (captured.size() > CAPTURE_LIMIT) {
                    captured.remove(0);
                }
            }
            sentCount.increment();
            return;
        }

        try {
            sendSmtp(email, from);
            sentCount.increment();
        } catch (RuntimeException e) {
            failCount.increment();
            throw e;
        }
    }

    private void sendSmtp(EmailBuilder email, String from) {
        try {
            // Parse smtpUrl: smtp://user:pass@host:port or smtps://user:pass@host:port
            var url = new java.net.URI(smtpUrl);
            var host = url.getHost();
            var port = url.getPort() > 0 ? url.getPort() : 587;
            var scheme = url.getScheme(); // "smtp" or "smtps"
            String user = null;
            String pass = null;
            if (url.getUserInfo() != null) {
                var parts = url.getUserInfo().split(":", 2);
                user = parts[0];
                pass = parts.length > 1 ? parts[1] : null;
            }

            var props = smtpProperties(host, port, scheme);

            jakarta.mail.Session session;
            if (user != null) {
                props.put("mail.smtp.auth", "true");
                final String u = user;
                final String p = pass;
                session = jakarta.mail.Session.getInstance(props, new jakarta.mail.Authenticator() {
                    protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                        return new jakarta.mail.PasswordAuthentication(u, p);
                    }
                });
            } else {
                session = jakarta.mail.Session.getInstance(props);
            }

            var message = new jakarta.mail.internet.MimeMessage(session);
            if (from != null) message.setFrom(new jakarta.mail.internet.InternetAddress(from));
            message.setRecipients(jakarta.mail.Message.RecipientType.TO,
                jakarta.mail.internet.InternetAddress.parse(email.to));
            if (email.cc != null) {
                message.setRecipients(jakarta.mail.Message.RecipientType.CC,
                    jakarta.mail.internet.InternetAddress.parse(email.cc));
            }
            message.setSubject(email.subject);

            if (email.htmlBody != null) {
                message.setContent(email.htmlBody, "text/html; charset=UTF-8");
            } else if (email.textBody != null) {
                message.setText(email.textBody, "UTF-8");
            }

            jakarta.mail.Transport.send(message);
        } catch (Exception e) {
            throw new RuntimeException("Failed to send email to " + email.to, e);
        }
    }

    /**
     * Build the SMTP session properties, including the timeouts that keep a wedged relay from
     * hanging the caller forever (see {@link #DEFAULT_CONNECT_TIMEOUT}). Package-private so
     * {@code MailerTest} can assert the timeouts are present without standing up an SMTP server.
     *
     * <p>All keys use the {@code mail.smtp.*} prefix even for {@code smtps://}: this class selects
     * TLS with {@code mail.smtp.ssl.enable} rather than by switching Jakarta Mail's protocol to
     * {@code smtps}, so the {@code mail.smtps.*} variants would be read by nothing.
     */
    Properties smtpProperties(String host, int port, String scheme) {
        var props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));

        if ("smtps".equals(scheme)) {
            props.put("mail.smtp.ssl.enable", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
        }

        props.put("mail.smtp.connectiontimeout", String.valueOf(connectTimeout.toMillis()));
        props.put("mail.smtp.timeout", String.valueOf(timeout.toMillis()));
        props.put("mail.smtp.writetimeout", String.valueOf(timeout.toMillis()));

        return props;
    }

    public record CapturedEmail(String to, String cc, String subject, String text, String html, String from) {}

    public static class EmailBuilder {
        private final Mailer mailer;
        String to;
        String cc;
        String from;
        String subject;
        String textBody;
        String htmlBody;

        EmailBuilder(Mailer mailer, String to) {
            this.mailer = mailer;
            this.to = to;
        }

        public EmailBuilder cc(String cc) { this.cc = cc; return this; }
        public EmailBuilder from(String from) { this.from = from; return this; }
        public EmailBuilder subject(String subject) { this.subject = subject; return this; }
        public EmailBuilder text(String body) { this.textBody = body; return this; }
        public EmailBuilder html(String html) { this.htmlBody = html; return this; }

        public void send() { mailer.send(this); }

        /**
         * Send on a background virtual thread and return immediately. Failures are logged
         * and counted in {@code failCount()} instead of thrown. Prefer this from request
         * handlers: {@link #send()} does synchronous SMTP (connect + STARTTLS + auth,
         * commonly 100ms–2s) on the calling thread, holding the request's transaction
         * and pooled connection open the whole time.
         */
        public void sendAsync() { mailer.sendAsync(this); }
    }
}

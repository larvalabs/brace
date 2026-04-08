package io.brace;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;

public class Mailer {

    private final String smtpUrl;
    private String defaultFrom;
    private String defaultReplyTo;
    private final List<CapturedEmail> captured = Collections.synchronizedList(new ArrayList<>());
    private final LongAdder failCount = new LongAdder();

    public Mailer(String smtpUrl) {
        this.smtpUrl = smtpUrl;
    }

    public Mailer from(String from) { this.defaultFrom = from; return this; }
    public Mailer replyTo(String replyTo) { this.defaultReplyTo = replyTo; return this; }

    public EmailBuilder to(String address) {
        return new EmailBuilder(this, address);
    }

    public List<CapturedEmail> sent() { return List.copyOf(captured); }
    public CapturedEmail last() { return captured.isEmpty() ? null : captured.get(captured.size() - 1); }
    public void clearCaptured() { captured.clear(); }
    public int sentCount() { return captured.size(); }
    public long failCount() { return failCount.sum(); }
    public long drainFailCount() { return failCount.sumThenReset(); }

    void send(EmailBuilder email) {
        var from = email.from != null ? email.from : defaultFrom;
        captured.add(new CapturedEmail(email.to, email.cc, email.subject, email.textBody, email.htmlBody, from));

        if (smtpUrl != null) {
            try {
                sendSmtp(email, from);
            } catch (RuntimeException e) {
                failCount.increment();
                throw e;
            }
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

            var props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", String.valueOf(port));

            if ("smtps".equals(scheme)) {
                props.put("mail.smtp.ssl.enable", "true");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }

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
    }
}

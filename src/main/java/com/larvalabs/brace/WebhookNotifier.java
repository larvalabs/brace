package com.larvalabs.brace;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * Posts each regression as a JSON {@code {"text": "..."}} payload to a webhook URL —
 * the shape Slack / Mattermost incoming webhooks accept. Fire-and-forget on a virtual
 * thread so a slow or unreachable webhook never blocks error recording.
 */
public class WebhookNotifier implements Notifier {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5)).build();

    private final String url;

    public WebhookNotifier(String url) {
        this.url = url;
    }

    /** Longest exception message posted (L12). */
    static final int MAX_MESSAGE_CHARS = 500;

    /**
     * Slack and Mattermost parse {@code <!channel>} and {@code <https://evil|View incident>} in
     * incoming-webhook text, so every interpolated field is escaped ({@code & < >}) and the
     * message is bounded: a web user who controls part of an exception message can otherwise
     * phish or spam the ops channel (L12).
     */
    static String formatText(RegressionTracker.Regression r) {
        String message = r.message();
        if (message != null && message.length() > MAX_MESSAGE_CHARS) {
            message = message.substring(0, MAX_MESSAGE_CHARS) + "…";
        }
        return "🚨 New error since startup: " + escape(r.type()) + " @ " + escape(r.route())
            + (message != null ? " — " + escape(message) : "")
            + " (first seen " + escape(String.valueOf(r.firstSeen())) + ")";
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    @Override
    public void notifyRegression(RegressionTracker.Regression r) {
        if (url == null || url.isEmpty()) return;
        String text = formatText(r);
        Thread.startVirtualThread(() -> {
            try {
                String body = Json.mapper().writeValueAsString(Map.of("text", text));
                HTTP.send(HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                Log.warn("regression webhook failed: " + Redactor.redactMessage(e.getMessage()));
            }
        });
    }
}

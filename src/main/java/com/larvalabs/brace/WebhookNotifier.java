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

    @Override
    public void notifyRegression(RegressionTracker.Regression r) {
        if (url == null || url.isEmpty()) return;
        String text = "🚨 New error since startup: " + r.type() + " @ " + r.route()
            + (r.message() != null ? " — " + r.message() : "")
            + " (first seen " + r.firstSeen() + ")";
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
                Log.warn("regression webhook failed: " + e.getMessage());
            }
        });
    }
}

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
     * The webhook text. A web user who controls part of an exception message could otherwise
     * phish or spam the ops channel (L12): Slack parses {@code <!channel>} and
     * {@code <https://evil|View incident>}, Mattermost renders markdown links, images
     * ({@code ![](https://evil/px.png)}, a tracking pixel) and {@code @channel} mentions.
     *
     * <p>Every interpolated field (type, route, message) goes through {@link #literal}: the text
     * is put in an inline code span, which both Slack and Mattermost render literally, so link,
     * image, emphasis and heading syntax is inert without per-character escaping. (Backslash
     * escapes would not do: Slack does not treat backslash as an escape.) What a code span does
     * not neutralise is replaced with a look-alike, keeping the message readable: a backtick
     * (which would close the span) with {@code ˋ}, {@code <} {@code >} {@code &} (Slack control
     * sequences and entities) with {@code ‹} {@code ›} {@code ＆}, and {@code @} with the
     * full-width {@code ＠}, which no mention syntax matches. Line breaks and other control
     * characters become spaces. The message is truncated to {@value #MAX_MESSAGE_CHARS}
     * characters before wrapping, never splitting a surrogate pair.
     */
    static String formatText(RegressionTracker.Regression r) {
        String message = r.message();
        if (message != null && message.length() > MAX_MESSAGE_CHARS) {
            int end = MAX_MESSAGE_CHARS;
            if (Character.isHighSurrogate(message.charAt(end - 1))) end--;
            message = message.substring(0, end) + "…";
        }
        return "🚨 New error since startup: " + literal(r.type()) + " at " + literal(r.route())
            + (message != null ? " — " + literal(message) : "")
            + " (first seen " + neutralise(String.valueOf(r.firstSeen())) + ")";
    }

    /** {@code s} as one inline code span that Slack and Mattermost both render literally. */
    static String literal(String s) {
        String body = neutralise(s == null ? "" : s);
        return "`" + (body.isBlank() ? "-" : body) + "`";
    }

    /** Replaces every character a code span would not make inert; see {@link #formatText}. */
    static String neutralise(String s) {
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '`' -> sb.append('ˋ');
                case '<' -> sb.append('‹');
                case '>' -> sb.append('›');
                case '&' -> sb.append('＆');
                case '@' -> sb.append('＠');
                default -> sb.append(Character.isISOControl(c) || c == ' ' || c == ' ' ? ' ' : c);
            }
        }
        return sb.toString();
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

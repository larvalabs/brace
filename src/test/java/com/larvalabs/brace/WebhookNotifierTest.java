package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WebhookNotifierTest {

    private static RegressionTracker.Regression regression(String type, String route, String message) {
        return new RegressionTracker.Regression("id", type, route, message, Instant.EPOCH, 1, null);
    }

    @Test
    void slackMarkupInTheMessageIsEscaped() {
        String text = WebhookNotifier.formatText(regression("java.lang.Boom", "GET /x",
            "<!channel> see <https://evil.example|View incident> a&b"));
        assertFalse(text.contains("<!channel>"), text);
        assertFalse(text.contains("<https"), text);
        assertTrue(text.contains("&lt;!channel&gt;"), text);
        assertTrue(text.contains("a&amp;b"), text);
    }

    @Test
    void typeAndRouteAreEscapedToo() {
        String text = WebhookNotifier.formatText(regression("T<x>", "GET /<!here>", "m"));
        assertFalse(text.contains("<!here>"), text);
        assertFalse(text.contains("<x>"), text);
    }

    @Test
    void longMessageIsTruncated() {
        String text = WebhookNotifier.formatText(regression("T", "r", "x".repeat(5000)));
        assertTrue(text.length() < 700, "length " + text.length());
    }
}

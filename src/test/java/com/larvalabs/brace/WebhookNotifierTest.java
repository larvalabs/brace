package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WebhookNotifierTest {

    private static RegressionTracker.Regression regression(String type, String route, String message) {
        return new RegressionTracker.Regression("id", type, route, message, Instant.EPOCH, 1, null);
    }

    private static long count(String s, char c) {
        return s.chars().filter(ch -> ch == c).count();
    }

    @Test
    void slackMarkupInTheMessageIsNeutralised() {
        String text = WebhookNotifier.formatText(regression("java.lang.Boom", "GET /x",
            "<!channel> see <https://evil.example|View incident> a&b"));
        assertFalse(text.contains("<"), text);
        assertFalse(text.contains(">"), text);
        assertFalse(text.contains("&"), text);
        assertTrue(text.contains("‹!channel›"), "still readable: " + text);
    }

    @Test
    void typeAndRouteAreNeutralisedToo() {
        String text = WebhookNotifier.formatText(regression("T<x>", "GET /<!here> @here", "m"));
        assertFalse(text.contains("<"), text);
        assertFalse(text.contains("@here"), text);
        assertTrue(text.contains("`T‹x›`"), text);
    }

    @Test
    void mattermostMarkdownMentionsLinksAndImagesAreInert() {
        // The review's payload: a mention, a disguised link and a tracking image in Mattermost
        // markdown. Each interpolated field is one code span, which Slack and Mattermost both
        // render literally, and '@' is replaced so no mention can fire.
        String payload = "@channel see [View incident](https://evil.example) ![](https://evil.example/px.png)";
        String text = WebhookNotifier.formatText(regression("java.lang.Boom", "GET /x", payload));
        assertFalse(text.contains("@"), text);
        String expected = "`＠channel see [View incident](https://evil.example) ![](https://evil.example/px.png)`";
        assertTrue(text.contains(expected), text);
        assertEquals(6, count(text, '`'), "exactly one code span per field: " + text);
    }

    @Test
    void backticksInTheMessageCannotCloseTheCodeSpan() {
        String text = WebhookNotifier.formatText(regression("T", "GET /x",
            "a`b ``` [x](https://evil.example) `@channel`"));
        assertEquals(6, count(text, '`'), text);
        assertFalse(text.contains("@"), text);
        assertTrue(text.contains("`aˋb ˋˋˋ [x](https://evil.example) ˋ＠channelˋ`"), text);
    }

    @Test
    void newlinesAndControlCharactersDoNotBreakOutOfTheSpan() {
        String text = WebhookNotifier.formatText(regression("T", "GET /x", "line1\n# heading\r\n> quote\u0007"));
        assertFalse(text.contains("\n"), text);
        assertFalse(text.contains("\r"), text);
        assertFalse(text.contains("\u0007"), text);
    }

    @Test
    void longMessageIsTruncatedBeforeWrappingWithoutSplittingASurrogatePair() {
        String text = WebhookNotifier.formatText(regression("T", "r", "x".repeat(5000)));
        assertTrue(text.length() < 700, "length " + text.length());
        assertEquals(6, count(text, '`'), "the span is still closed after truncation");

        // A surrogate pair straddling the limit is dropped whole.
        String message = "x".repeat(WebhookNotifier.MAX_MESSAGE_CHARS - 1) + "😀" + "tail";
        String t = WebhookNotifier.formatText(regression("T", "r", message));
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < t.length() && Character.isLowSurrogate(t.charAt(i + 1)), "split pair at " + i);
            } else if (Character.isLowSurrogate(c)) {
                assertTrue(i > 0 && Character.isHighSurrogate(t.charAt(i - 1)), "orphan low surrogate at " + i);
            }
        }
        assertFalse(t.contains("tail"), t);
    }
}

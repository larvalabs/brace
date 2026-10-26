package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RedactorTest {

    @Test
    void isSensitiveMatchesCommonCredentialNames() {
        assertTrue(Redactor.isSensitive("Authorization"));
        assertTrue(Redactor.isSensitive("Cookie"));
        assertTrue(Redactor.isSensitive("x-api-key"));
        assertTrue(Redactor.isSensitive("api_key"));
        assertTrue(Redactor.isSensitive("password"));
        assertTrue(Redactor.isSensitive("sessionId"));
        assertTrue(Redactor.isSensitive("csrf-token"));
        assertTrue(Redactor.isSensitive("X-Auth-Token"));
    }

    @Test
    void isSensitiveLeavesOrdinaryNamesAlone() {
        assertFalse(Redactor.isSensitive("username"));
        assertFalse(Redactor.isSensitive("count"));
        assertFalse(Redactor.isSensitive("path"));
        assertFalse(Redactor.isSensitive("author"));
        assertFalse(Redactor.isSensitive("monkey"));
        assertFalse(Redactor.isSensitive(null));
    }

    @Test
    void redactReplacesSensitiveValuesOnly() {
        var redacted = Redactor.redact(Map.of("password", "hunter2", "username", "bob"));
        assertEquals(Redactor.PLACEHOLDER, redacted.get("password"));
        assertEquals("bob", redacted.get("username"));
    }

    @Test
    void redactRecursesIntoMapsAndLists() {
        var nested = Map.<String, Object>of(
            "user", "bob",
            "headers", Map.of("authorization", "Bearer abc", "accept", "json"),
            "events", List.of(Map.of("token", "xyz", "kind", "login")));
        var redacted = Redactor.redact(nested);

        @SuppressWarnings("unchecked")
        var headers = (Map<String, Object>) redacted.get("headers");
        assertEquals(Redactor.PLACEHOLDER, headers.get("authorization"));
        assertEquals("json", headers.get("accept"));

        @SuppressWarnings("unchecked")
        var events = (List<Map<String, Object>>) redacted.get("events");
        assertEquals(Redactor.PLACEHOLDER, events.get(0).get("token"));
        assertEquals("login", events.get(0).get("kind"));
    }

    @Test
    void redactQueryRedactsSensitiveParams() {
        assertEquals("a=1&token=[REDACTED]&b=2", Redactor.redactQuery("a=1&token=xyz&b=2"));
        assertEquals("password=[REDACTED]", Redactor.redactQuery("password=hunter2"));
        assertEquals("q=hello", Redactor.redactQuery("q=hello"));
        assertEquals("flag", Redactor.redactQuery("flag"));
        assertNull(Redactor.redactQuery(null));
    }

    @Test
    void logEntriesAreRedactedInTheRingBuffer() {
        // /ops/logs serves LogTap.snapshot(), so redaction at the log choke point protects it.
        LogTap.clear();
        Log.info("login attempt", Map.of("password", "secret123", "user", "bob"));
        var entry = LogTap.snapshot().stream()
            .filter(e -> "login attempt".equals(e.fields().get("message")))
            .findFirst().orElseThrow();
        assertEquals(Redactor.PLACEHOLDER, entry.fields().get("password"));
        assertEquals("bob", entry.fields().get("user"));
    }

    // ---- redactPath tests ----

    @Test
    void redactPathLeaves32HexTokenRedacted() {
        // 32-char hex token with mixed letters+digits — should be redacted
        String path = "/password-reset/a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String result = Redactor.redactPath(path);
        assertEquals("/password-reset/[redacted]", result);
    }

    @Test
    void redactPathLeavesNumericIdUntouched() {
        assertEquals("/users/42/profile", Redactor.redactPath("/users/42/profile"));
    }

    @Test
    void redactPathLeavesShortSlugUntouched() {
        assertEquals("/blog/hello-world", Redactor.redactPath("/blog/hello-world"));
    }

    @Test
    void redactPathLeavesUuidUntouched() {
        // UUIDs are identifiers, not secrets — needed for debugging
        String path = "/items/550e8400-e29b-41d4-a716-446655440000";
        assertEquals(path, Redactor.redactPath(path));
    }

    @Test
    void redactPathRedactsJwtShapedSegment() {
        // JWT: header.payload.signature
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String path = "/api/" + jwt;
        String result = Redactor.redactPath(path);
        assertEquals("/api/[redacted]", result);
    }

    @Test
    void redactPathHandlesRootAndEmptyPath() {
        assertEquals("/", Redactor.redactPath("/"));
        assertNull(Redactor.redactPath(null));
        assertEquals("", Redactor.redactPath(""));
    }

    @Test
    void redactPathRedactsBase64urlToken() {
        // 24-char base64url token (invite token style): mixed letters+digits
        String path = "/invite/abc123DEF456ghi789JKL0";
        assertEquals("/invite/[redacted]", Redactor.redactPath(path));
    }

    @Test
    void redactPathLeavesNormalWordSegmentsUntouched() {
        assertEquals("/admin/dashboard", Redactor.redactPath("/admin/dashboard"));
        assertEquals("/health", Redactor.redactPath("/health"));
    }

    // ---- redactMessage tests ----

    @Test
    void redactMessageRedactsBearerTokenInMessage() {
        // Simulated exception message containing a long token
        String token = "a3f9bc2d8ef14a5b6c7d8e9f01234567";   // 32-char hex
        String message = "Failed to validate token: " + token;
        String result = Redactor.redactMessage(message);
        assertFalse(result.contains(token), "token should be redacted");
        assertTrue(result.contains("[redacted]"));
        assertTrue(result.contains("Failed"));
    }

    @Test
    void redactMessageLeavesNormalMessageUntouched() {
        String message = "User not found for email: bob@example.com";
        // No high-entropy token here — email address contains dots and @ which are
        // outside the base64url charset, so it won't match the heuristic.
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void redactMessageRedactsJwtInMessage() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String message = "Invalid JWT: " + jwt;
        String result = Redactor.redactMessage(message);
        assertFalse(result.contains(jwt), "JWT should be redacted from message");
        assertTrue(result.contains("[redacted]"));
    }

    @Test
    void redactMessageHandlesNullAndEmpty() {
        assertNull(Redactor.redactMessage(null));
        assertEquals("", Redactor.redactMessage(""));
    }

    @Test
    void redactMessageLeavesShortTokensUntouched() {
        String message = "NullPointerException: field was null";
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void redactMessageKeepsPunctuationWhenNothingIsRedacted() {
        // "IllegalArgumentException" is long enough to be a candidate, but isn't secret-shaped
        // (no digit). The message used to come back rebuilt with single spaces, losing the
        // parentheses, commas and colon: "placeholder s but 1 path argument s".
        String message = "Too many params for pattern: /catalog/list — it has 0 placeholder(s) but "
            + "1 path argument(s) were passed. Pass Url.query(name, value, ...) instead "
            + "(IllegalArgumentException).";
        assertEquals(message, Redactor.redactMessage(message));
    }

    @Test
    void redactMessageReplacesOnlyTheSecretAndKeepsTheRest() {
        String token = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        assertEquals("Bad token (key=[redacted], user: bob).",
            Redactor.redactMessage("Bad token (key=" + token + ", user: bob)."));
        assertEquals("  [redacted]\n[redacted]:", Redactor.redactMessage("  " + token + "\n" + token + ":"));
    }

    // ---- isSecretShaped tests ----

    @Test
    void isSecretShapedReturnsTrueForLongMixedToken() {
        assertTrue(Redactor.isSecretShaped("a3f9bc2d8ef14a5b6c7d8e9f01234567"));
    }

    @Test
    void isSecretShapedReturnsFalseForNumericId() {
        assertFalse(Redactor.isSecretShaped("1234567890123456")); // all digits
    }

    @Test
    void isSecretShapedReturnsFalseForPurelyAlphaSlug() {
        assertFalse(Redactor.isSecretShaped("thisisaverylongslugwithnumbers")); // no digits
        // actually has no digits so should return false
    }

    @Test
    void isSecretShapedReturnsFalseForUuid() {
        assertFalse(Redactor.isSecretShaped("550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test
    void isSecretShapedReturnsTrueForJwt() {
        assertTrue(Redactor.isSecretShaped(
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyMTIzIn0.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"));
    }

    @Test
    void isSecretShapedReturnsFalseForShortToken() {
        assertFalse(Redactor.isSecretShaped("abc123")); // below MIN_SECRET_LENGTH
    }

    // ---- redactStackTrace tests ----

    private static String traceOf(Throwable t) {
        var sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    @Test
    void redactStackTraceRedactsEveryMessageLine() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String other = "9f8e7d6c5b4a39281706f5e4d3c2b1a0";
        var suppressed = new IllegalStateException("suppressed key " + other);
        var root = new java.sql.SQLException("connect failed password=" + secret);
        var top = new RuntimeException("outer", new RuntimeException("middle", root));
        top.addSuppressed(suppressed);

        String trace = traceOf(top);
        assertTrue(trace.contains(secret) && trace.contains(other), "precondition: raw trace has both");

        String redacted = Redactor.redactStackTrace(trace);
        assertFalse(redacted.contains(secret), "Caused by: message must be redacted");
        assertFalse(redacted.contains(other), "Suppressed: message must be redacted");
        assertTrue(redacted.contains("Caused by: java.sql.SQLException: connect failed password=[redacted]"), redacted);
        assertTrue(redacted.contains("RuntimeException: outer"));
    }

    @Test
    void redactStackTraceRedactsTheFirstLine() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String redacted = Redactor.redactStackTrace(traceOf(new RuntimeException("bad token " + secret)));
        assertFalse(redacted.contains(secret));
        assertTrue(redacted.startsWith("java.lang.RuntimeException: bad token [redacted]"), redacted);
    }

    @Test
    void redactStackTraceRedactsContinuationLinesOfAMultiLineMessage() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String redacted = Redactor.redactStackTrace(
            traceOf(new RuntimeException("line one\nsecond line " + secret + "\nthird")));
        assertFalse(redacted.contains(secret));
        assertTrue(redacted.contains("line one\nsecond line [redacted]\nthird"), redacted);
    }

    @Test
    void redactStackTraceLeavesFrameLinesUntouched() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String trace = "java.lang.RuntimeException: boom " + secret + "\n"
            + "\tat com.example.Foo.bar(Foo.java:12)\n"
            + "\tat com.example.Foo.longMethodName0123456789abcdef(Foo.java:99)\n"
            + "Caused by: java.lang.Error: root " + secret + "\n"
            + "\tat com.example.Baz.qux(Baz.java:7)\n"
            + "\t... 3 more\n";
        assertEquals(trace.replace(secret, "[redacted]"), Redactor.redactStackTrace(trace));
    }

    @Test
    void redactStackTraceKeepsLineEndingsAndReturnsCleanTracesUnchanged() {
        String clean = "java.lang.RuntimeException: user not found\r\n\tat a.B.c(B.java:1)\r\n";
        assertSame(clean, Redactor.redactStackTrace(clean));
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        assertEquals("java.lang.RuntimeException: x [redacted]\r\n\tat a.B.c(B.java:1)\r\n",
            Redactor.redactStackTrace("java.lang.RuntimeException: x " + secret + "\r\n\tat a.B.c(B.java:1)\r\n"));
    }

    @Test
    void redactStackTraceHandlesNullAndEmpty() {
        assertNull(Redactor.redactStackTrace(null));
        assertEquals("", Redactor.redactStackTrace(""));
    }
}

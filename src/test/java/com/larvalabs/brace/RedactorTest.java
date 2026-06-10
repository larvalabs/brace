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
        // All tokens here are well below MIN_SECRET_LENGTH — nothing should be redacted.
        // Note: the colon after "NullPointerException" is a delimiter, so the reconstructed
        // message won't contain it, but no token should be replaced with [redacted].
        String message = "NullPointerException: field was null";
        String result = Redactor.redactMessage(message);
        assertFalse(result.contains("[redacted]"), "no token should be redacted in: " + result);
        // All original words should still be present
        assertTrue(result.contains("NullPointerException"));
        assertTrue(result.contains("field"));
        assertTrue(result.contains("null"));
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
}

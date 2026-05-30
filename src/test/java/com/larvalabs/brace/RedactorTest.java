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
}

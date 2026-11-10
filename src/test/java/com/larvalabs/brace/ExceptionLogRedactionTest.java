package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L13 (2026-11 review, post-merge): the warnings Brace logs with an exception's text appended
 * ({@code "..." + e}) bypassed value-shaped redaction, because {@code Log.warn}'s name-based pass
 * never looks inside the message. They now go through {@link Redactor#redactMessage}.
 */
class ExceptionLogRedactionTest {

    private static final String SECRET = "AbCdEf0123456789GhIjKl";

    @Test
    void uploadTempFileReleaseFailureIsRedacted() {
        var req = new Request("POST", "/upload", Map.of(), Map.of(), Map.of(),
            () -> new Request.BodyContent("", Map.of(), () -> {
                throw new IOException("could not delete https://alice:hunter2@files.example.com/tmp/part?token="
                    + SECRET + " (" + SECRET + ")");
            }),
            "127.0.0.1", null);
        req.resolveBody();
        LogTap.clear();
        req.releaseUploads();

        List<String> warnings = LogTap.snapshot().stream()
            .map(e -> String.valueOf(e.fields().get("message")))
            .filter(m -> m.startsWith("failed to release upload temp files"))
            .toList();
        assertEquals(1, warnings.size(), LogTap.snapshot().toString());
        String message = warnings.get(0);
        assertFalse(message.contains(SECRET), message);
        assertFalse(message.contains("hunter2"), message);
        assertTrue(message.contains("IOException"), message);
        assertTrue(message.contains("files.example.com"), message);
    }
}

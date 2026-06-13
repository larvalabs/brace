package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimum-level filtering (H1). Asserts through LogTap, which is written synchronously
 * on the caller's thread — the async stdout writer is irrelevant to these tests.
 */
public class LogLevelTest {

    @Test
    public void entriesBelowMinLevelAreSkippedEntirely() {
        LogTap.clear();
        try {
            Log.level("ERROR");
            Log.debug("nope");
            Log.info("nope", Map.of("k", "v"));
            Log.warn("nope");
            Log.event("custom.event", Map.of("k", "v"));
            assertTrue(LogTap.snapshot().isEmpty(), "below-level entries must not reach the tap");

            Log.error("kept");
            var entries = LogTap.snapshot();
            assertEquals(1, entries.size());
            assertEquals("kept", entries.get(0).fields().get("message"));
        } finally {
            Log.level("DEBUG");
        }
    }

    @Test
    public void defaultLevelLogsEverything() {
        LogTap.clear();
        Log.debug("d");
        Log.info("i");
        Log.warn("w");
        Log.error("e");
        assertEquals(4, LogTap.snapshot().size());
    }

    @Test
    public void invalidLevelIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Log.level("VERBOSE"));
    }
}

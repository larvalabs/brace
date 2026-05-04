package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LogTapWiringTest {

    @BeforeEach
    void reset() { LogTap.clear(); }

    @Test
    void infoFlowsIntoLogTap() {
        Log.info("hello world");
        var snap = LogTap.snapshot();
        assertEquals(1, snap.size());
        assertEquals("hello world", snap.get(0).fields().get("message"));
        assertEquals("INFO", snap.get(0).fields().get("level"));
    }

    @Test
    void warnFlowsIntoLogTap() {
        Log.warn("careful");
        var snap = LogTap.snapshot();
        assertEquals(1, snap.size());
        assertEquals("WARN", snap.get(0).fields().get("level"));
    }

    @Test
    void errorWithThrowableFlowsIntoLogTap() {
        Log.error("boom", new RuntimeException("kaboom"));
        var snap = LogTap.snapshot();
        assertEquals(1, snap.size());
        assertEquals("ERROR", snap.get(0).fields().get("level"));
        assertEquals("RuntimeException", snap.get(0).fields().get("error"));
        assertEquals("kaboom", snap.get(0).fields().get("errorMessage"));
    }

    @Test
    void eventWithDataFlowsIntoLogTap() {
        Log.event("user.created", Map.of("userId", 42));
        var snap = LogTap.snapshot();
        assertEquals(1, snap.size());
        assertEquals("user.created", snap.get(0).fields().get("event"));
        assertEquals(42, snap.get(0).fields().get("userId"));
    }
}

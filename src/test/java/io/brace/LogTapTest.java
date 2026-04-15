package io.brace;

import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LogTapTest {

    @BeforeEach
    void reset() {
        LogTap.clear();
        LogTap.setCapacity(1000);
    }

    @Test
    void appendAndSnapshotPreservesOrder() {
        LogTap.append(Map.of("level", "INFO", "message", "first"));
        LogTap.append(Map.of("level", "WARN", "message", "second"));

        var snap = LogTap.snapshot();
        assertEquals(2, snap.size());
        assertEquals("first", snap.get(0).fields().get("message"));
        assertEquals("second", snap.get(1).fields().get("message"));
    }

    @Test
    void idsAreMonotonicAndUnique() {
        LogTap.append(Map.of("message", "a"));
        LogTap.append(Map.of("message", "b"));
        var snap = LogTap.snapshot();
        assertTrue(snap.get(1).id() > snap.get(0).id());
    }

    @Test
    void evictsOldestWhenOverCapacity() {
        LogTap.setCapacity(3);
        for (int i = 0; i < 5; i++) LogTap.append(Map.of("n", i));

        var snap = LogTap.snapshot();
        assertEquals(3, snap.size());
        assertEquals(2, snap.get(0).fields().get("n"));
        assertEquals(4, snap.get(2).fields().get("n"));
    }

    @Test
    void sinceIdReturnsOnlyNewer() {
        LogTap.append(Map.of("n", "a"));
        LogTap.append(Map.of("n", "b"));
        LogTap.append(Map.of("n", "c"));
        long firstId = LogTap.snapshot().get(0).id();

        var after = LogTap.since(firstId);
        assertEquals(2, after.size());
        assertEquals("b", after.get(0).fields().get("n"));
    }

    @Test
    void sinceTimestampFiltersByTsField() {
        LogTap.append(Map.of("ts", "2026-04-14T10:00:00Z", "n", "a"));
        LogTap.append(Map.of("ts", "2026-04-14T10:05:00Z", "n", "b"));
        LogTap.append(Map.of("ts", "2026-04-14T10:10:00Z", "n", "c"));

        var after = LogTap.sinceTimestamp(Instant.parse("2026-04-14T10:03:00Z"));
        assertEquals(2, after.size());
        assertEquals("b", after.get(0).fields().get("n"));
    }

    @Test
    void concurrentAppendsAllRecorded() throws Exception {
        int threads = 10, perThread = 100;
        var pool = Executors.newFixedThreadPool(threads);
        var latch = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) LogTap.append(Map.of("m", "x"));
                latch.countDown();
            });
        }
        latch.await();
        pool.shutdown();
        assertEquals(threads * perThread, LogTap.snapshot().size());
    }
}

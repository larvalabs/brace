package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GcStatsTest {

    private static final long MS = 1_000_000;

    @Test
    void concurrentCyclePauseComesFromSumOfPausesNotDuration() {
        // A G1Old concurrent marking cycle: 120ms wall clock, but only its remark/cleanup
        // steps stop the world (6ms total, longest 4ms). Pre-0.1.10 this read as a 120ms pause.
        var stats = new GcStats();
        stats.record(Instant.parse("2026-10-02T12:00:00Z"), "G1Old", "G1 Evacuation Pause",
            120 * MS, 6 * MS, 4 * MS);

        var gc = stats.snapshot();
        assertEquals(1L, gc.get("totalCount"));
        assertEquals(6L, gc.get("totalPauseMs"));
        assertEquals(6.0, gc.get("avgPauseMs"));
        assertEquals(4.0, gc.get("maxPauseMs"));
        assertEquals(0L, gc.get("fullCount"));

        var p = recent(gc).getFirst();
        assertEquals(6.0, p.get("durationMs"));
        assertEquals(4.0, p.get("longestPauseMs"));
        assertEquals(120.0, p.get("cycleMs"));
        assertEquals("G1Old", p.get("collector"));
        assertNull(p.get("full"), "a concurrent cycle is not a full GC");

        assertEquals(6, stats.totalPauseMs());
        assertEquals(4.0, stats.maxRecentPauseMs());
    }

    @Test
    void averagesStopTheWorldTimeAcrossCollections() {
        var stats = new GcStats();
        var t = Instant.parse("2026-10-02T12:00:00Z");
        stats.record(t, "G1New", "G1 Evacuation Pause", 10 * MS, 10 * MS, 10 * MS);
        stats.record(t.plusSeconds(1), "G1Old", "G1 Evacuation Pause", 120 * MS, 6 * MS, 4 * MS);
        stats.record(t.plusSeconds(2), "G1New", "G1 Evacuation Pause", 8 * MS, 8 * MS, 8 * MS);

        var gc = stats.snapshot();
        assertEquals(3L, gc.get("totalCount"));
        assertEquals(24L, gc.get("totalPauseMs"));
        assertEquals(8.0, gc.get("avgPauseMs"));  // was (10+120+8)/3 = 46ms
        assertEquals(10.0, gc.get("maxPauseMs"));
        // Most recent first
        assertEquals(List.of(8.0, 6.0, 10.0), recent(gc).stream().map(m -> m.get("durationMs")).toList());
    }

    @Test
    void fullCollectionsAreFlagged() {
        var stats = new GcStats();
        var t = Instant.parse("2026-10-02T12:00:00Z");
        stats.record(t, "G1New", "G1 Evacuation Pause", 5 * MS, 5 * MS, 5 * MS);
        stats.record(t.plusSeconds(1), "G1Full", "G1 Compaction Pause", 900 * MS, 900 * MS, 900 * MS);

        var gc = stats.snapshot();
        assertEquals(1L, gc.get("fullCount"));
        var full = recent(gc).getFirst();
        assertEquals("G1Full", full.get("collector"));
        assertEquals(true, full.get("full"));
        assertEquals(900.0, full.get("cycleMs"));
        assertNull(recent(gc).get(1).get("full"));
    }

    @Test
    void emptyStatsReportZeros() {
        var gc = new GcStats().snapshot();
        assertEquals(0L, gc.get("totalCount"));
        assertEquals(0.0, gc.get("avgPauseMs"));
        assertEquals(0.0, gc.get("maxPauseMs"));
        assertTrue(recent(gc).isEmpty());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recent(Map<String, Object> gc) {
        return (List<Map<String, Object>>) gc.get("recentPauses");
    }
}

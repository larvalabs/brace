package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JfrProfilerTest {

    static JfrProfiler profiler;

    @BeforeAll
    static void start() throws Exception {
        profiler = new JfrProfiler();
        // Give JFR time to deliver initial events
        Thread.sleep(2000);
    }

    @AfterAll
    static void stop() {
        profiler.close();
    }

    @Test
    void snapshotContainsHeapData() {
        var snap = profiler.snapshot();
        var heap = (Map<String, Object>) snap.get("heap");
        assertNotNull(heap, "snapshot must contain heap section");
        assertTrue((long) heap.get("usedMB") > 0, "heap used must be positive");
        assertTrue((long) heap.get("maxMB") > 0, "heap max must be positive");
        assertNotNull(heap.get("committedMB"), "heap committed must be present");
    }

    @Test
    void snapshotContainsCpuData() {
        var snap = profiler.snapshot();
        var cpu = (Map<String, Object>) snap.get("cpu");
        assertNotNull(cpu, "snapshot must contain cpu section");
        assertTrue((double) cpu.get("jvmUser") >= 0);
        assertTrue((double) cpu.get("jvmSystem") >= 0);
        assertTrue((double) cpu.get("machineTotal") >= 0);
    }

    @Test
    void snapshotContainsThreadData() {
        var snap = profiler.snapshot();
        var threads = (Map<String, Object>) snap.get("threads");
        assertNotNull(threads, "snapshot must contain threads section");
        assertTrue((long) threads.get("active") > 0, "must have at least 1 active thread");
        assertNotNull(threads.get("daemon"));
        assertNotNull(threads.get("peak"));
    }

    @Test
    void snapshotContainsGcSection() {
        var snap = profiler.snapshot();
        var gc = (Map<String, Object>) snap.get("gc");
        assertNotNull(gc, "snapshot must contain gc section");
        assertTrue((long) gc.get("totalCount") >= 0);
        assertTrue((long) gc.get("totalPauseMs") >= 0);
        assertTrue((double) gc.get("avgPauseMs") >= 0);
        assertNotNull(gc.get("recentPauses"));
        assertInstanceOf(List.class, gc.get("recentPauses"));
    }

    @Test
    void snapshotContainsProfilingSection() {
        var snap = profiler.snapshot();
        var profiling = (Map<String, Object>) snap.get("profiling");
        assertNotNull(profiling, "snapshot must contain profiling section");
        assertEquals(300, profiling.get("windowSeconds"));
        assertNotNull(profiling.get("hotMethods"));
        assertNotNull(profiling.get("topAllocations"));
    }

    @Test
    void resetProfilingClearsMaps() {
        profiler.resetProfiling();
        var methods = profiler.topMethods(10);
        var allocs = profiler.topAllocations(10);
        assertTrue(methods.isEmpty(), "methods should be empty after reset");
        assertTrue(allocs.isEmpty(), "allocations should be empty after reset");
    }
}

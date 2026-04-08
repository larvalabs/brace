package io.brace;

import org.junit.jupiter.api.*;
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
}

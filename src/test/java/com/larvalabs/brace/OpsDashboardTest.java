package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Renders {@link OpsDashboard#html} directly and checks the markup the browser will lay out. */
class OpsDashboardTest {

    static final String LONG_METHOD =
        "com.example.billing.internal.reconciliation.LedgerReconciliationService$PendingBatchProcessor.reconcileOutstandingEntries";
    static final String LONG_CLASS =
        "[Lcom.example.billing.internal.reconciliation.LedgerReconciliationService$PendingEntry;";

    /** Real JVM data (heap, cpu, gc) with a fixed profiling block of long names and big numbers. */
    static class FakeProfiler extends JfrProfiler {
        @Override
        public Map<String, Object> snapshot(boolean includeProfiling) {
            var data = super.snapshot(includeProfiling);
            if (includeProfiling) {
                var profiling = new LinkedHashMap<String, Object>();
                profiling.put("windowSeconds", 300);
                profiling.put("hotMethods", List.of(
                    Map.of("method", LONG_METHOD, "samples", 1_234_567L),
                    Map.of("method", "java.lang.String.hashCode", "samples", 42L)));
                profiling.put("topAllocations", List.of(
                    Map.of("class", LONG_CLASS, "bytes", 481_296_384L),
                    Map.of("class", "[B", "bytes", 2048L)));
                data.put("profiling", profiling);
            }
            return data;
        }
    }

    static FakeProfiler profiler;

    @BeforeAll
    static void start() {
        profiler = new FakeProfiler();
    }

    @AfterAll
    static void stop() {
        profiler.close();
    }

    static String render(Stats stats, JfrProfiler p) {
        return OpsDashboard.html("tok", OpsScope.READ, stats, null, null, null, null, p);
    }

    @Test
    void numericCellsUseSharedNowrapClass() {
        var stats = new Stats();
        stats.recordRequestPattern("GET", "/users/{id}", 200, 1500, 0, 0);
        var html = render(stats, profiler);

        assertTrue(html.contains("td.num { text-align: right; white-space: nowrap;"),
            "value cells must not wrap (\"459 / MB\")");
        assertFalse(html.contains("<td style=\"text-align:right"), "inline right-align styles replaced by .num");
        assertTrue(html.contains("<td class=\"num c-purple\">459 MB</td>"), html);
        assertTrue(html.contains("<td class=\"num c-amber\">1234567</td>"));
        assertTrue(html.contains("<th class=\"num\">Size</th>"));
    }
}

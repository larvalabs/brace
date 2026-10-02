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

    @Test
    void longNamesAreLeftTruncatedOnOneLine() {
        var html = render(new Stats(), profiler);

        // CSS: one line, clipped on the left with an ellipsis, the cell giving up width first.
        assertTrue(html.contains("td.name { max-width: 0; width: 100%; }"));
        assertTrue(html.contains(".lt { display: block; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; direction: rtl;"));
        assertTrue(html.contains(".lt > bdi { direction: ltr; unicode-bidi: isolate; }"));
        assertFalse(html.contains("pkg-wrap"), "the fixed 30ch package clip is gone");

        // Method: package muted, Class$Inner.method bold, full name in the tooltip.
        assertTrue(html.contains("<td class=\"name\"><span class=\"lt\" title=\"" + LONG_METHOD + "\">"
            + "<bdi dir=\"ltr\"><span class=\"pkg\">com.example.billing.internal.reconciliation</span>"
            + "<span class=\"method\">.LedgerReconciliationService$PendingBatchProcessor.reconcileOutstandingEntries</span></bdi></span></td>"), html);

        // Object array descriptor: friendly name with [] inside the LTR isolate (no bidi reordering).
        assertTrue(html.contains("title=\"com.example.billing.internal.reconciliation.LedgerReconciliationService$PendingEntry[]\">"
            + "<bdi dir=\"ltr\"><span class=\"pkg\">com.example.billing.internal.reconciliation</span>"
            + "<span class=\"method\">.LedgerReconciliationService$PendingEntry[]</span></bdi>"));
        assertTrue(html.contains("title=\"byte[]\"><bdi dir=\"ltr\"><span class=\"method\">byte[]</span></bdi>"));
    }

    @Test
    void requestsCardShowsRateNotLifetimeTotal() {
        var stats = new Stats();
        for (int i = 0; i < 3; i++) stats.recordRequestPattern("GET", "/a", 200, 100, 0, 0);
        assertTrue(render(stats, null).contains("<div class=\"label\">Req / Min</div><div class=\"value c-blue\">-</div><div class=\"detail\">first minute pending</div>"));

        stats.snapshot();
        for (int i = 0; i < 1500; i++) stats.recordRequestPattern("GET", "/a", 200, 100, 0, 0);
        stats.snapshot();
        var html = render(stats, null);
        assertTrue(html.contains("<div class=\"label\">Req / Min</div><div class=\"value c-blue\">1,500</div><div class=\"detail\">avg 752 · 2m</div>"), html);
        assertFalse(html.contains("<div class=\"label\">Requests</div>"));
    }

    @Test
    void topRoutesSitNextToSlowestRoutesInBothLayouts() {
        var stats = new Stats();
        for (int i = 0; i < 30; i++) stats.recordRequestPattern("GET", "/users/{id}", 200, 1000, 0, 0);
        for (int i = 0; i < 10; i++) stats.recordRequest("GET", "/wp-login.php", 404, 100, 0, 0);
        stats.snapshot();

        for (var html : List.of(render(stats, profiler), render(stats, null))) {
            int top = html.indexOf("Top Routes");
            int slow = html.indexOf("Slowest Routes");
            assertTrue(top > 0 && slow > top, "Top Routes precedes Slowest Routes");
            assertEquals(html.lastIndexOf("<div class=\"two-col\">", top), html.lastIndexOf("<div class=\"two-col\">", slow),
                "both tables share one two-col row");
            assertTrue(html.contains("<th class=\"num\">Req/Min</th><th class=\"num\">Share</th>"));
            assertTrue(html.contains("<td class=\"route\"><span class=\"c-green\">GET</span>&nbsp;/users/{id}</td>"
                + "<td class=\"num c-blue\">30</td><td class=\"num c-muted\">75%</td>"), html);
            assertTrue(html.contains("<td class=\"route c-muted\">(unmatched)</td><td class=\"num c-blue\">10</td>"));
            assertFalse(html.contains("wp-login.php</td><td class=\"num c-blue\">"), "scanner paths never rank");
        }
    }
}

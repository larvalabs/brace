package io.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")

public class OpsDashboard {

    public static String html(String opsSecret, Stats stats, JobScheduler jobScheduler,
                              Mailer mailer, ErrorStore errorStore, Cache cache, JfrProfiler profiler) {
        var sb = new StringBuilder();
        var now = Instant.now();

        // Gather data
        var statusCodes = stats.statusCodeCounts();
        long totalReqs = statusCodes.values().stream().mapToLong(Long::longValue).sum();
        long errCount = statusCodes.getOrDefault(500, 0L) + statusCodes.getOrDefault(502, 0L) + statusCodes.getOrDefault(503, 0L);
        String errRate = totalReqs > 0 ? String.format("%.1f", (errCount * 100.0) / totalReqs) : "0.0";
        // JVM data
        Map<String, Object> jvmSnap = profiler != null ? profiler.snapshot() : null;
        long heapUsed, heapMax;
        if (jvmSnap != null) {
            var heap = (Map<String, Object>) jvmSnap.get("heap");
            heapUsed = (long) heap.get("usedMB");
            heapMax = (long) heap.get("maxMB");
        } else {
            var runtime = Runtime.getRuntime();
            heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
            heapMax = runtime.maxMemory() / (1024 * 1024);
        }
        var uptime = formatDuration(Duration.between(stats.startedAt(), now));
        var routeStats = stats.routeStats().entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue().avgLatencyMs(), a.getValue().avgLatencyMs()))
            .limit(5).toList();
        var minutes = stats.minuteSnapshots();
        var recentErrors = stats.recentErrors();
        var jobStatuses = jobScheduler != null ? jobScheduler.getStatuses() : List.<JobScheduler.JobStatus>of();
        var rateLimiterStats = RateLimiter.allStats();
        List<Map<String, Object>> unresolvedErrors = List.of();
        List<Map<String, Object>> resolvedErrors = List.of();
        if (errorStore != null) {
            unresolvedErrors = errorStore.list(null);
            resolvedErrors = errorStore.list("resolved");
        }

        // Page head
        sb.append("""
            <!DOCTYPE html>
            <html>
            <head>
            <title>Brace Dashboard</title>
            <meta charset="UTF-8">
            <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { background: #1a1a2e; color: #e0e0e0; font-family: 'Menlo', 'Consolas', monospace; font-size: 13px; padding: 20px; }
            h1 { color: #e94560; font-size: 20px; margin-bottom: 4px; }
            h2 { color: #0f3460; background: #e94560; display: inline-block; padding: 2px 10px; margin: 16px 0 8px 0; font-size: 13px; }
            .header { margin-bottom: 16px; border-bottom: 1px solid #333; padding-bottom: 12px; }
            .header span { color: #888; margin-right: 20px; }
            .stats-row { display: flex; gap: 16px; margin-bottom: 16px; flex-wrap: wrap; }
            .stat-card { background: #16213e; border: 1px solid #0f3460; padding: 12px 20px; min-width: 140px; }
            .stat-card .label { color: #888; font-size: 11px; text-transform: uppercase; }
            .stat-card .value { color: #e94560; font-size: 22px; font-weight: bold; margin-top: 4px; }
            table { border-collapse: collapse; width: 100%; margin-bottom: 16px; }
            th { text-align: left; color: #e94560; border-bottom: 1px solid #333; padding: 6px 12px; font-size: 11px; text-transform: uppercase; }
            td { padding: 5px 12px; border-bottom: 1px solid #222; }
            tr:hover td { background: #16213e; }
            .sparkline { display: flex; align-items: flex-end; gap: 2px; height: 60px; margin: 8px 0; }
            .sparkline .bar { background: #e94560; min-width: 6px; flex: 1; }
            .sparkline .bar:hover { background: #ff6b81; }
            .error-text { color: #ff6b6b; }
            .ok-text { color: #51cf66; }
            .muted { color: #666; }
            .two-col { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
            @media (max-width: 800px) { .two-col { grid-template-columns: 1fr; } }
            .btn { background: #0f3460; color: #e0e0e0; border: 1px solid #e94560; padding: 4px 12px; cursor: pointer; font-family: inherit; font-size: 12px; }
            .btn:hover { background: #e94560; color: #0f3460; }
            .btn-sm { padding: 2px 8px; font-size: 11px; }
            .section-header { display: flex; align-items: center; gap: 12px; }
            .stack-trace { max-height: 200px; overflow-y: auto; background: #111; padding: 8px; margin-top: 4px; font-size: 11px; white-space: pre-wrap; word-break: break-all; border: 1px solid #222; }
            .expandable { cursor: pointer; }
            .expandable:hover { color: #e94560; }
            .tab-bar { display: flex; gap: 0; margin-bottom: 0; }
            .tab { background: #16213e; border: 1px solid #333; border-bottom: none; padding: 4px 16px; cursor: pointer; color: #888; }
            .tab.active { background: #1a1a2e; color: #e94560; border-color: #e94560; border-bottom: 1px solid #1a1a2e; margin-bottom: -1px; z-index: 1; position: relative; }
            .tab-content { border-top: 1px solid #e94560; padding-top: 8px; }
            </style>
            <script src="/__brace/htmx.min.js"></script>
            </head>
            <body>
            """);

        // Dashboard content — this is the div that htmx polls and replaces
        sb.append("<div id=\"dashboard-content\" hx-get=\"/ops/dashboard?key=").append(esc(opsSecret))
          .append("\" hx-select=\"#dashboard-content\" hx-target=\"this\" hx-swap=\"outerHTML\" hx-trigger=\"every 5s\">\n");

        // Header
        sb.append("<div class=\"header\">");
        sb.append("<h1>Brace Dashboard</h1>");
        sb.append("<span>Uptime: ").append(esc(uptime)).append("</span>");
        sb.append("<span>Java: ").append(esc(System.getProperty("java.version"))).append("</span>");
        sb.append("<span>Started: ").append(esc(stats.startedAt().toString())).append("</span>");
        sb.append("</div>\n");

        // Stat cards
        sb.append("<div class=\"stats-row\">");
        statCard(sb, "Requests", String.valueOf(totalReqs));
        statCard(sb, "Error Rate", errRate + "%");
        statCard(sb, "Heap", heapUsed + " / " + heapMax + " MB");
        if (jvmSnap != null) {
            var cpu = (Map<String, Object>) jvmSnap.get("cpu");
            var threads = (Map<String, Object>) jvmSnap.get("threads");
            var gc = (Map<String, Object>) jvmSnap.get("gc");
            statCard(sb, "CPU", String.format("%.0f%%", (double) cpu.get("jvmUser") * 100));
            statCard(sb, "Threads", String.valueOf(threads.get("active")));
            double avgGc = (double) gc.get("avgPauseMs");
            statCard(sb, "GC", String.format("%.1f ms avg", avgGc));
        } else {
            var threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
            statCard(sb, "CPU", "-");
            statCard(sb, "Threads", String.valueOf(threadBean.getThreadCount()));
            statCard(sb, "GC", "-");
        }
        if (cache != null) {
            statCard(sb, "Cache", cache.size() + " entries");
            long hits = cache.hits(), misses = cache.misses();
            String hitRate = (hits + misses) > 0 ? ((hits * 100) / (hits + misses)) + "%" : "-";
            statCard(sb, "Hit Rate", hitRate);
        }
        if (mailer != null) {
            statCard(sb, "Emails", String.valueOf(mailer.sentCount()));
            if (mailer.failCount() > 0) {
                statCard(sb, "Mail Failures", String.valueOf(mailer.failCount()));
            }
        }
        sb.append("</div>\n");

        // Sparkline
        if (!minutes.isEmpty()) {
            sb.append("<h2>Requests / Minute</h2>\n");
            long maxReq = Math.max(1, minutes.stream().mapToLong(Stats.MinuteSnapshot::requests).max().orElse(1));
            sb.append("<div class=\"sparkline\">");
            for (var m : minutes) {
                double pct = (m.requests() * 100.0) / maxReq;
                sb.append("<div class=\"bar\" style=\"height:").append(String.format("%.0f", Math.max(2, pct)))
                  .append("%\" title=\"").append(m.requests()).append(" reqs, ")
                  .append(String.format("%.1f", m.avgLatencyMs())).append(" ms avg @ ")
                  .append(m.ts()).append("\"></div>");
            }
            sb.append("</div>\n");
        }

        // JVM profiling tables
        sb.append("<h2>JVM</h2>\n");
        if (jvmSnap != null) {
            sb.append("<div class=\"two-col\">\n");

            // Hot methods
            var profiling = (Map<String, Object>) jvmSnap.get("profiling");
            var hotMethods = (List<Map<String, Object>>) profiling.get("hotMethods");
            sb.append("<div><h2>Hot Methods</h2>");
            if (hotMethods.isEmpty()) {
                sb.append("<p class=\"muted\">No samples yet</p>");
            } else {
                sb.append("<table><tr><th>Method</th><th>Samples</th></tr>");
                for (var m : hotMethods) {
                    String method = (String) m.get("method");
                    String display = method.length() > 60 ? method.substring(method.length() - 60) : method;
                    sb.append("<tr><td title=\"").append(esc(method)).append("\">").append(esc(display))
                      .append("</td><td>").append(m.get("samples")).append("</td></tr>");
                }
                sb.append("</table>");
            }
            sb.append("</div>\n");

            // Top allocations
            var topAllocs = (List<Map<String, Object>>) profiling.get("topAllocations");
            sb.append("<div><h2>Top Allocations</h2>");
            if (topAllocs.isEmpty()) {
                sb.append("<p class=\"muted\">No allocation data yet</p>");
            } else {
                sb.append("<table><tr><th>Class</th><th>Allocated</th></tr>");
                for (var a : topAllocs) {
                    sb.append("<tr><td>").append(esc((String) a.get("class")))
                      .append("</td><td>").append(formatBytes((long) a.get("bytes"))).append("</td></tr>");
                }
                sb.append("</table>");
            }
            sb.append("</div>\n");

            sb.append("</div>\n"); // two-col

            // Recent GC pauses
            var gc = (Map<String, Object>) jvmSnap.get("gc");
            var pauses = (List<Map<String, Object>>) gc.get("recentPauses");
            if (!pauses.isEmpty()) {
                sb.append("<h2>Recent GC Pauses</h2>");
                sb.append("<table><tr><th>Time</th><th>Duration</th><th>Collector</th><th>Cause</th></tr>");
                for (var p : pauses) {
                    String ts = (String) p.get("ts");
                    String time = ts.length() > 19 ? ts.substring(11, 19) : ts;
                    double durationMs = (double) p.get("durationMs");
                    String cls = durationMs > 100 ? "error-text" : "";
                    sb.append("<tr class=\"").append(cls).append("\"><td>").append(esc(time))
                      .append("</td><td>").append(String.format("%.1f ms", durationMs))
                      .append("</td><td>").append(esc((String) p.get("collector")))
                      .append("</td><td>").append(esc((String) p.get("cause"))).append("</td></tr>");
                }
                sb.append("</table>\n");
            }
        }

        sb.append("<div class=\"two-col\">\n");

        // Slowest routes
        sb.append("<div><h2>Slowest Routes</h2>");
        sb.append("<table><tr><th>Route</th><th>Count</th><th>Avg ms</th></tr>");
        for (var e : routeStats) {
            sb.append("<tr><td>").append(esc(e.getKey())).append("</td><td>")
              .append(e.getValue().count()).append("</td><td>")
              .append(String.format("%.2f", e.getValue().avgLatencyMs())).append("</td></tr>");
        }
        sb.append("</table></div>\n");

        // Recent in-memory errors
        sb.append("<div><h2>Recent Errors (In-Memory)</h2>");
        if (recentErrors.isEmpty()) {
            sb.append("<p class=\"ok-text\">No errors</p>");
        } else {
            sb.append("<table><tr><th>Type</th><th>Route</th><th>Count</th><th>Last Seen</th></tr>");
            for (var e : recentErrors) {
                sb.append("<tr><td class=\"error-text\">").append(esc(e.type)).append("</td><td>")
                  .append(esc(e.route != null ? e.route : "-")).append("</td><td>")
                  .append(e.count).append("</td><td>")
                  .append(esc(e.lastSeen != null ? e.lastSeen.toString() : "-")).append("</td></tr>");
            }
            sb.append("</table>");
        }
        sb.append("</div>\n");

        sb.append("</div>\n"); // two-col

        // Persisted error tracking
        if (errorStore != null) {
            sb.append("<div class=\"section-header\"><h2>Error Tracking</h2></div>\n");
            sb.append("<div class=\"tab-bar\">");
            sb.append("<div class=\"tab active\" onclick=\"showErrorTab('unresolved')\">Unresolved (")
              .append(unresolvedErrors.size()).append(")</div>");
            sb.append("<div class=\"tab\" onclick=\"showErrorTab('resolved')\">Resolved (")
              .append(resolvedErrors.size()).append(")</div>");
            sb.append("</div>\n");

            sb.append("<div id=\"tab-unresolved\" class=\"tab-content\" style=\"display:block\">");
            renderPersistedErrors(sb, unresolvedErrors, opsSecret, false);
            sb.append("</div>\n");

            sb.append("<div id=\"tab-resolved\" class=\"tab-content\" style=\"display:none\">");
            renderPersistedErrors(sb, resolvedErrors, opsSecret, true);
            sb.append("</div>\n");
        }

        // Scheduled jobs
        if (!jobStatuses.isEmpty()) {
            sb.append("<h2>Scheduled Jobs</h2>");
            sb.append("<table><tr><th>Name</th><th>Schedule</th><th>Status</th><th>Last Run</th><th>Duration</th><th>Failures</th></tr>");
            for (var j : jobStatuses) {
                String statusCls = "ok".equals(j.lastStatus()) ? "ok-text" : "error".equals(j.lastStatus()) ? "error-text" : "muted";
                sb.append("<tr><td>").append(esc(j.name())).append("</td><td>").append(esc(j.schedule())).append("</td>");
                sb.append("<td class=\"").append(statusCls).append("\">").append(esc(j.lastStatus() != null ? j.lastStatus() : "pending")).append("</td>");
                sb.append("<td>").append(j.lastRun() != null ? esc(j.lastRun().toString()) : "-").append("</td>");
                sb.append("<td>").append(j.lastDurationMs()).append(" ms</td>");
                sb.append("<td>").append(j.failCount()).append("</td></tr>");
            }
            sb.append("</table>\n");
        }

        // Rate limiters
        if (!rateLimiterStats.isEmpty()) {
            sb.append("<h2>Rate Limiters</h2>");
            sb.append("<table><tr><th>Limiter</th><th>Allowed</th><th>Blocked</th><th>Active Windows</th><th>Limit</th></tr>");
            for (var rl : rateLimiterStats) {
                long allowed = ((Number) rl.get("allowed")).longValue();
                long blocked = ((Number) rl.get("blocked")).longValue();
                String blockPct = (allowed + blocked) > 0 ? String.format("%.1f", (blocked * 100.0) / (allowed + blocked)) : "0.0";
                sb.append("<tr><td>").append(esc((String) rl.get("label"))).append("</td>");
                sb.append("<td>").append(allowed).append("</td>");
                sb.append("<td class=\"").append(blocked > 0 ? "error-text" : "").append("\">")
                  .append(blocked).append(" (").append(blockPct).append("%)</td>");
                sb.append("<td>").append(rl.get("activeWindows")).append("</td>");
                sb.append("<td>").append(rl.get("maxRequests")).append("/").append(rl.get("windowSeconds")).append("s</td></tr>");
            }
            sb.append("</table>\n");
        }

        // Cache details
        if (cache != null) {
            sb.append("<div class=\"section-header\"><h2>Cache</h2>");
            sb.append("<button class=\"btn btn-sm\" hx-post=\"/ops/cache/clear?key=").append(esc(opsSecret))
              .append("\" hx-target=\"#dashboard-content\" hx-select=\"#dashboard-content\" hx-swap=\"outerHTML\">Clear All</button></div>");
            sb.append("<div class=\"stats-row\">");
            statCard(sb, "Entries", String.valueOf(cache.size()));
            statCard(sb, "Counters", String.valueOf(cache.counterCount()));
            statCard(sb, "Tags", String.valueOf(cache.tagCount()));
            statCard(sb, "Hits", String.valueOf(cache.hits()));
            statCard(sb, "Misses", String.valueOf(cache.misses()));
            statCard(sb, "Evictions", String.valueOf(cache.evictions()));
            sb.append("</div>\n");
        }

        // Status codes
        sb.append("<h2>Status Codes</h2>");
        sb.append("<table><tr><th>Code</th><th>Count</th></tr>");
        statusCodes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
            sb.append("<tr><td>").append(e.getKey()).append("</td><td>").append(e.getValue()).append("</td></tr>")
        );
        sb.append("</table>\n");

        sb.append("</div>\n"); // dashboard-content

        // Minimal JS for tab switching and stack trace expand/collapse
        sb.append("""
            <script>
            function showErrorTab(tab) {
                document.getElementById('tab-unresolved').style.display = tab === 'unresolved' ? 'block' : 'none';
                document.getElementById('tab-resolved').style.display = tab === 'resolved' ? 'block' : 'none';
                document.querySelectorAll('.tab').forEach((el, i) => {
                    el.classList.toggle('active', (i === 0 && tab === 'unresolved') || (i === 1 && tab === 'resolved'));
                });
            }
            function toggleTrace(el) {
                var row = el.closest('tr').nextElementSibling;
                row.style.display = row.style.display === 'none' ? 'table-row' : 'none';
            }
            </script>
            """);

        sb.append("</body></html>");
        return sb.toString();
    }

    private static void renderPersistedErrors(StringBuilder sb, List<Map<String, Object>> errors,
                                               String opsSecret, boolean resolved) {
        if (errors.isEmpty()) {
            sb.append("<p class=\"").append(resolved ? "muted" : "ok-text").append("\">None</p>");
            return;
        }
        sb.append("<table><tr><th>Type</th><th>Route</th><th>Count</th><th>First Seen</th><th>Last Seen</th><th></th></tr>");
        for (var e : errors) {
            long id = ((Number) e.get("id")).longValue();
            sb.append("<tr>");
            sb.append("<td class=\"error-text expandable\" onclick=\"toggleTrace(this)\">")
              .append(esc(str(e.get("errorType")))).append("</td>");
            sb.append("<td>").append(esc(str(e.get("route"), "-"))).append("</td>");
            sb.append("<td>").append(e.get("occurrenceCount")).append("</td>");
            sb.append("<td>").append(esc(str(e.get("firstSeen"), "-"))).append("</td>");
            sb.append("<td>").append(esc(str(e.get("lastSeen"), "-"))).append("</td>");
            if (!resolved) {
                sb.append("<td><button class=\"btn btn-sm\" hx-post=\"/ops/errors/").append(id)
                  .append("/resolve?key=").append(esc(opsSecret))
                  .append("\" hx-target=\"#dashboard-content\" hx-select=\"#dashboard-content\" hx-swap=\"outerHTML\">Resolve</button></td>");
            } else {
                sb.append("<td class=\"muted\">").append(esc(str(e.get("resolvedAt"), ""))).append("</td>");
            }
            sb.append("</tr>");
            sb.append("<tr style=\"display:none\"><td colspan=\"6\"><div class=\"stack-trace\">")
              .append(esc(str(e.get("stackTrace"), "No stack trace")))
              .append("</div><div style=\"margin-top:4px;color:#888\">")
              .append(esc(str(e.get("message"), ""))).append("</div></td></tr>");
        }
        sb.append("</table>");
    }

    private static void statCard(StringBuilder sb, String label, String value) {
        sb.append("<div class=\"stat-card\"><div class=\"label\">").append(esc(label))
          .append("</div><div class=\"value\">").append(esc(value)).append("</div></div>");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String str(Object o) {
        return o != null ? o.toString() : "";
    }

    private static String str(Object o, String fallback) {
        return o != null ? o.toString() : fallback;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private static String formatDuration(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long mins = d.toMinutesPart();
        if (days > 0) return days + "d " + hours + "h " + mins + "m";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }
}

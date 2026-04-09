package io.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unchecked")

public class OpsDashboard {

    public static String html(String token, Stats stats, JobScheduler jobScheduler,
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
            <title>Brace Ops</title>
            <meta charset="UTF-8">
            <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body { background: #0d1117; color: #c9d1d9; font-family: 'JetBrains Mono', Menlo, Consolas, monospace; font-size: 12px; padding: 16px; }
            .header { display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid #30363d; padding-bottom: 10px; margin-bottom: 12px; }
            .header .title { color: #7aa2f7; font-weight: bold; font-size: 14px; }
            .header .meta { color: #565f89; }
            .stats-row { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
            .stat-card { flex: 1; border: 1px solid #30363d; padding: 8px; min-width: 120px; }
            .stat-card .label { color: #565f89; font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px; }
            .stat-card .value { font-size: 20px; font-weight: bold; margin-top: 2px; }
            .stat-card .detail { color: #565f89; font-size: 10px; margin-top: 1px; }
            .section { border: 1px solid #30363d; padding: 10px; margin-bottom: 14px; }
            .section-head { font-size: 10px; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 8px; border-bottom: 1px solid #30363d; padding-bottom: 6px; }
            .two-col { display: flex; gap: 10px; margin-bottom: 14px; }
            .two-col > div { flex: 1; }
            @media (max-width: 800px) { .two-col { flex-direction: column; } }
            table { border-collapse: collapse; width: 100%; }
            th { text-align: left; color: #565f89; font-size: 9px; text-transform: uppercase; padding: 3px 0; }
            td { padding: 3px 0; }
            .sparkline { display: flex; align-items: flex-end; gap: 1px; height: 45px; }
            .sparkline .bar { flex: 1; min-width: 4px; }
            .sparkline .bar-lo { background: #238636; }
            .sparkline .bar-md { background: #2ea043; }
            .sparkline .bar-hi { background: #3fb950; }
            .sparkline-sm { height: 25px; }
            .sparkline .bar-err { background: #f7768e; }
            .sparkline .bar-heap { background: #bb9af7; }
            .c-blue { color: #7aa2f7; }
            .c-green { color: #9ece6a; }
            .c-purple { color: #bb9af7; }
            .c-amber { color: #e0af68; }
            .c-red { color: #f7768e; }
            .c-cyan { color: #7dcfff; }
            .c-muted { color: #565f89; }
            .pkg-wrap { display: inline-flex; max-width: 30ch; overflow: hidden; justify-content: flex-end; vertical-align: bottom; }
            .pkg { color: #565f89; white-space: nowrap; flex-shrink: 0; }
            .method { color: #c9d1d9; font-weight: bold; }
            .ok-dot { color: #9ece6a; }
            .err-dot { color: #f7768e; }
            .btn { background: transparent; color: #7aa2f7; border: 1px solid #30363d; padding: 2px 8px; cursor: pointer; font-family: inherit; font-size: 11px; }
            .btn:hover { border-color: #7aa2f7; }
            .btn-resolve { color: #9ece6a; }
            .btn-resolve:hover { border-color: #9ece6a; }
            .btn-danger { color: #f7768e; }
            .btn-danger:hover { border-color: #f7768e; }
            .tab-bar { display: flex; gap: 0; margin-bottom: 0; }
            .tab { background: transparent; border: 1px solid #30363d; border-bottom: none; padding: 4px 16px; cursor: pointer; color: #565f89; font-family: inherit; font-size: 11px; }
            .tab.active { color: #7aa2f7; border-color: #7aa2f7; border-bottom: 1px solid #0d1117; margin-bottom: -1px; z-index: 1; position: relative; }
            .tab-content { border: 1px solid #30363d; border-top: 1px solid #7aa2f7; padding: 10px; margin-bottom: 14px; }
            .stack-trace { max-height: 200px; overflow-y: auto; background: #161b22; padding: 8px; margin-top: 4px; font-size: 11px; white-space: pre-wrap; word-break: break-all; border: 1px solid #30363d; color: #c9d1d9; }
            </style>
            <script src="/__brace/htmx.min.js"></script>
            </head>
            <body>
            """);

        // Dashboard content — this is the div that htmx polls and replaces
        sb.append("<div id=\"dashboard-content\" hx-get=\"/ops/dashboard\"")
          .append(" hx-headers='{\"Authorization\": \"Bearer ").append(esc(token)).append("\"}'")
          .append(" hx-select=\"#dashboard-content\" hx-target=\"this\" hx-swap=\"outerHTML\" hx-trigger=\"every 5s\">\n");

        // Header
        sb.append("<div class=\"header\">");
        sb.append("<span class=\"title\">┌ BRACE</span>");
        sb.append("<span class=\"meta\">↑ ").append(esc(uptime))
          .append(" │ Java ").append(esc(System.getProperty("java.version")))
          .append(" │ started ").append(esc(stats.startedAt().toString().substring(0, 16).replace("T", " ")))
          .append(" │ 5s refresh</span>");
        sb.append("</div>\n");

        // Stat cards
        sb.append("<div class=\"stats-row\">");
        statCard(sb, "Requests", String.valueOf(totalReqs), "", "c-blue");
        statCard(sb, "Error Rate", errRate + "%", errCount + " total", Double.parseDouble(errRate) > 5 ? "c-red" : "c-green");
        statCard(sb, "Heap", heapUsed + "M", "/ " + heapMax + "M", "c-purple");
        if (jvmSnap != null) {
            var cpu = (Map<String, Object>) jvmSnap.get("cpu");
            var threads = (Map<String, Object>) jvmSnap.get("threads");
            var gc = (Map<String, Object>) jvmSnap.get("gc");
            double cpuPct = (double) cpu.get("jvmUser") * 100;
            statCard(sb, "CPU", String.format("%.0f%%", cpuPct), "", cpuPct > 80 ? "c-red" : cpuPct > 50 ? "c-amber" : "c-amber");
            statCard(sb, "Threads", String.valueOf(threads.get("active")), threads.get("daemon") + " daemon", "c-cyan");
            double avgGc = (double) gc.get("avgPauseMs");
            statCard(sb, "GC Avg", String.format("%.0fms", avgGc), gc.get("totalCount") + " pauses", avgGc > 100 ? "c-red" : avgGc > 10 ? "c-amber" : "c-green");
        } else {
            var threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
            statCard(sb, "CPU", "-", "", "c-amber");
            statCard(sb, "Threads", String.valueOf(threadBean.getThreadCount()), "", "c-cyan");
            statCard(sb, "GC", "-", "", "c-green");
        }
        if (cache != null) {
            long hits = cache.hits(), misses = cache.misses();
            String hitRate = (hits + misses) > 0 ? ((hits * 100) / (hits + misses)) + "%" : "-";
            statCard(sb, "Cache", cache.size() + " entries", "hit rate " + hitRate, "c-amber");
        }
        if (mailer != null) {
            String mailDetail = mailer.failCount() > 0 ? mailer.failCount() + " failed" : "";
            statCard(sb, "Emails", String.valueOf(mailer.sentCount()), mailDetail, "c-cyan");
        }
        sb.append("</div>\n");

        // Sparklines
        int SPARKLINE_SLOTS = 60;
        if (!minutes.isEmpty()) {
            int emptySlots = SPARKLINE_SLOTS - minutes.size();

            // Req/min sparkline
            sb.append("<div class=\"section\">");
            long maxReq = Math.max(1, minutes.stream().mapToLong(Stats.MinuteSnapshot::requests).max().orElse(1));
            sb.append("<div style=\"color:#565f89;font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;\">")
              .append("Requests / Minute <span style=\"float:right\">last ").append(minutes.size()).append(" / 60 min</span></div>");
            sb.append("<div style=\"display:flex;align-items:stretch;gap:6px\">");
            sb.append("<div style=\"display:flex;flex-direction:column;justify-content:space-between;color:#565f89;font-size:9px;min-width:32px;text-align:right;\">")
              .append("<span>").append(maxReq).append("</span><span>0</span></div>");
            sb.append("<div class=\"sparkline\" style=\"flex:1\">");
            for (int i = 0; i < emptySlots; i++) sb.append("<div class=\"bar\"></div>");
            for (var m : minutes) {
                double pct = (m.requests() * 100.0) / maxReq;
                String barClass = pct > 75 ? "bar-hi" : pct > 40 ? "bar-md" : "bar-lo";
                sb.append("<div class=\"bar ").append(barClass).append("\" style=\"height:")
                  .append(String.format("%.0f", Math.max(2, pct)))
                  .append("%\" title=\"").append(m.requests()).append(" reqs, ")
                  .append(String.format("%.1f", m.avgLatencyMs())).append(" ms avg @ ")
                  .append(m.ts()).append("\"></div>");
            }
            sb.append("</div></div>\n");

            // Error rate sparkline
            long maxErr = minutes.stream().mapToLong(Stats.MinuteSnapshot::errors).max().orElse(0);
            if (maxErr > 0) {
                sb.append("<div style=\"color:#565f89;font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-top:10px;margin-bottom:8px;\">")
                  .append("Errors / Minute</div>");
                sb.append("<div style=\"display:flex;align-items:stretch;gap:6px\">");
                sb.append("<div style=\"display:flex;flex-direction:column;justify-content:space-between;color:#565f89;font-size:9px;min-width:32px;text-align:right;\">")
                  .append("<span>").append(maxErr).append("</span><span>0</span></div>");
                sb.append("<div class=\"sparkline sparkline-sm\" style=\"flex:1\">");
                for (int i = 0; i < emptySlots; i++) sb.append("<div class=\"bar\"></div>");
                for (var m : minutes) {
                    double pct = (m.errors() * 100.0) / maxErr;
                    sb.append("<div class=\"bar bar-err\" style=\"height:")
                      .append(String.format("%.0f", Math.max(pct > 0 ? 4 : 0, pct)))
                      .append("%\" title=\"").append(m.errors()).append(" errors @ ").append(m.ts())
                      .append("\"></div>");
                }
                sb.append("</div></div>\n");
            }

            // Heap sparkline
            long maxHeap = Math.max(1, minutes.stream().mapToLong(Stats.MinuteSnapshot::heapUsedMB).max().orElse(1));
            long minHeap = minutes.stream().mapToLong(Stats.MinuteSnapshot::heapUsedMB).min().orElse(0);
            sb.append("<div style=\"color:#565f89;font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-top:10px;margin-bottom:8px;\">")
              .append("Heap MB</div>");
            sb.append("<div style=\"display:flex;align-items:stretch;gap:6px\">");
            sb.append("<div style=\"display:flex;flex-direction:column;justify-content:space-between;color:#565f89;font-size:9px;min-width:32px;text-align:right;\">")
              .append("<span>").append(maxHeap).append("</span><span>").append(minHeap).append("</span></div>");
            sb.append("<div class=\"sparkline sparkline-sm\" style=\"flex:1\">");
            for (int i = 0; i < emptySlots; i++) sb.append("<div class=\"bar\"></div>");
            for (var m : minutes) {
                double pct = (m.heapUsedMB() * 100.0) / maxHeap;
                sb.append("<div class=\"bar bar-heap\" style=\"height:")
                  .append(String.format("%.0f", Math.max(4, pct)))
                  .append("%\" title=\"").append(m.heapUsedMB()).append(" MB @ ").append(m.ts())
                  .append("\"></div>");
            }
            sb.append("</div></div>\n");

            sb.append("</div>\n"); // close section
        }

        // Custom metrics sparklines
        {
            // Collect all metric names across snapshots
            var counterNames = new java.util.LinkedHashSet<String>();
            var gaugeNames = new java.util.LinkedHashSet<String>();
            var timerNames = new java.util.LinkedHashSet<String>();
            for (var m : minutes) {
                counterNames.addAll(m.counterDeltas().keySet());
                gaugeNames.addAll(m.gaugeValues().keySet());
                timerNames.addAll(m.timerValues().keySet());
            }

            int METRIC_SLOTS = 60;
            int emptySlots = minutes.isEmpty() ? METRIC_SLOTS : METRIC_SLOTS - minutes.size();

            if (!counterNames.isEmpty() || !gaugeNames.isEmpty() || !timerNames.isEmpty()) {
                sb.append("<div class=\"section\">");
                sb.append("<div class=\"section-head c-cyan\">Custom Metrics</div>");

                // Counter sparklines (cyan)
                for (var name : counterNames) {
                    long maxVal = Math.max(1, minutes.stream()
                        .mapToLong(m -> m.counterDeltas().getOrDefault(name, 0L)).max().orElse(1));
                    sb.append("<div style=\"color:#565f89;font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;margin-top:10px;\">")
                      .append(esc(name)).append(" <span class=\"c-cyan\" style=\"font-size:9px\">(counter)</span></div>");
                    sb.append("<div style=\"display:flex;align-items:stretch;gap:6px\">");
                    sb.append("<div style=\"display:flex;flex-direction:column;justify-content:space-between;color:#565f89;font-size:9px;min-width:32px;text-align:right;\">")
                      .append("<span>").append(maxVal).append("</span><span>0</span></div>");
                    sb.append("<div class=\"sparkline sparkline-sm\" style=\"flex:1\">");
                    for (int i = 0; i < emptySlots; i++) sb.append("<div class=\"bar\"></div>");
                    for (var m : minutes) {
                        long val = m.counterDeltas().getOrDefault(name, 0L);
                        double pct = (val * 100.0) / maxVal;
                        sb.append("<div class=\"bar\" style=\"height:").append(String.format("%.0f", Math.max(val > 0 ? 4 : 0, pct)))
                          .append("%;background:#7dcfff\" title=\"").append(val).append(" @ ").append(m.ts()).append("\"></div>");
                    }
                    sb.append("</div></div>\n");
                }

                // Gauge sparklines (amber)
                for (var name : gaugeNames) {
                    long maxVal = Math.max(1, minutes.stream()
                        .mapToLong(m -> m.gaugeValues().getOrDefault(name, 0L)).max().orElse(1));
                    long minVal = minutes.stream()
                        .mapToLong(m -> m.gaugeValues().getOrDefault(name, 0L)).min().orElse(0);
                    sb.append("<div style=\"color:#565f89;font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;margin-top:10px;\">")
                      .append(esc(name)).append(" <span class=\"c-amber\" style=\"font-size:9px\">(gauge)</span></div>");
                    sb.append("<div style=\"display:flex;align-items:stretch;gap:6px\">");
                    sb.append("<div style=\"display:flex;flex-direction:column;justify-content:space-between;color:#565f89;font-size:9px;min-width:32px;text-align:right;\">")
                      .append("<span>").append(maxVal).append("</span><span>").append(minVal).append("</span></div>");
                    sb.append("<div class=\"sparkline sparkline-sm\" style=\"flex:1\">");
                    for (int i = 0; i < emptySlots; i++) sb.append("<div class=\"bar\"></div>");
                    for (var m : minutes) {
                        long val = m.gaugeValues().getOrDefault(name, 0L);
                        double pct = (val * 100.0) / maxVal;
                        sb.append("<div class=\"bar\" style=\"height:").append(String.format("%.0f", Math.max(4, pct)))
                          .append("%;background:#e0af68\" title=\"").append(val).append(" @ ").append(m.ts()).append("\"></div>");
                    }
                    sb.append("</div></div>\n");
                }

                // Timer sparklines (blue — avg ms)
                for (var name : timerNames) {
                    double maxAvg = Math.max(1, minutes.stream()
                        .mapToDouble(m -> {
                            var t = m.timerValues().get(name);
                            return t != null ? t.avgMs() : 0;
                        }).max().orElse(1));
                    sb.append("<div style=\"color:#565f89;font-size:10px;text-transform:uppercase;letter-spacing:0.5px;margin-bottom:8px;margin-top:10px;\">")
                      .append(esc(name)).append(" <span class=\"c-blue\" style=\"font-size:9px\">(timer avg ms)</span></div>");
                    sb.append("<div style=\"display:flex;align-items:stretch;gap:6px\">");
                    sb.append("<div style=\"display:flex;flex-direction:column;justify-content:space-between;color:#565f89;font-size:9px;min-width:32px;text-align:right;\">")
                      .append("<span>").append(String.format("%.0f", maxAvg)).append("</span><span>0</span></div>");
                    sb.append("<div class=\"sparkline sparkline-sm\" style=\"flex:1\">");
                    for (int i = 0; i < emptySlots; i++) sb.append("<div class=\"bar\"></div>");
                    for (var m : minutes) {
                        var t = m.timerValues().get(name);
                        double avg = t != null ? t.avgMs() : 0;
                        double pct = (avg * 100.0) / maxAvg;
                        sb.append("<div class=\"bar\" style=\"height:").append(String.format("%.0f", Math.max(avg > 0 ? 4 : 0, pct)))
                          .append("%;background:#7aa2f7\" title=\"").append(t != null ? String.format("%.1fms avg, %d max, %d calls", t.avgMs(), t.maxMs(), t.count()) : "0")
                          .append(" @ ").append(m.ts()).append("\"></div>");
                    }
                    sb.append("</div></div>\n");
                }

                sb.append("</div>\n"); // close section
            }
        }

        // JVM profiling
        if (jvmSnap != null) {
            // Hot Methods + Slowest Routes
            sb.append("<div class=\"two-col\">\n");

            var profiling = (Map<String, Object>) jvmSnap.get("profiling");
            var hotMethods = (List<Map<String, Object>>) profiling.get("hotMethods");
            sb.append("<div class=\"section\">");
            sb.append("<div class=\"section-head c-blue\">Hot Methods <span class=\"c-muted\" style=\"font-weight:normal\">— 5 min window</span></div>");
            if (hotMethods.isEmpty()) {
                sb.append("<p class=\"c-muted\">No samples yet</p>");
            } else {
                sb.append("<table><tr><th>Method</th><th style=\"text-align:right\">Samples</th></tr>");
                for (var m : hotMethods) {
                    String method = (String) m.get("method");
                    sb.append("<tr><td title=\"").append(esc(method)).append("\">").append(formatMethod(method))
                      .append("</td><td style=\"text-align:right\" class=\"c-amber\">").append(m.get("samples")).append("</td></tr>");
                }
                sb.append("</table>");
            }
            sb.append("</div>\n");

            // Slowest routes
            sb.append("<div class=\"section\">");
            sb.append("<div class=\"section-head c-blue\">Slowest Routes <span class=\"c-muted\" style=\"font-weight:normal\">— avg latency</span></div>");
            sb.append("<table><tr><th>Route</th><th style=\"text-align:right\">Avg</th><th style=\"text-align:right\">Calls</th></tr>");
            for (var e : routeStats) {
                String[] parts = e.getKey().split(" ", 2);
                String httpMethod = parts[0];
                String path = parts.length > 1 ? parts[1] : e.getKey();
                String methodColor = switch (httpMethod) {
                    case "GET" -> "c-green";
                    case "POST" -> "c-amber";
                    case "PUT" -> "c-blue";
                    case "DELETE" -> "c-red";
                    default -> "c-muted";
                };
                sb.append("<tr><td><span class=\"").append(methodColor).append("\">").append(esc(httpMethod))
                  .append("</span> ").append(esc(path)).append("</td>");
                sb.append("<td style=\"text-align:right\" class=\"c-amber\">").append(String.format("%.0fms", e.getValue().avgLatencyMs())).append("</td>");
                sb.append("<td style=\"text-align:right\" class=\"c-muted\">").append(String.format("%,d", e.getValue().count())).append("</td></tr>");
            }
            sb.append("</table>");
            sb.append("</div>\n");

            sb.append("</div>\n"); // two-col

            // Allocations + GC Pauses
            sb.append("<div class=\"two-col\">\n");

            var topAllocs = (List<Map<String, Object>>) profiling.get("topAllocations");
            sb.append("<div class=\"section\">");
            sb.append("<div class=\"section-head c-purple\">Top Allocations <span class=\"c-muted\" style=\"font-weight:normal\">— 5 min window</span></div>");
            if (topAllocs.isEmpty()) {
                sb.append("<p class=\"c-muted\">No allocation data yet</p>");
            } else {
                sb.append("<table><tr><th>Class</th><th style=\"text-align:right\">Size</th></tr>");
                for (var a : topAllocs) {
                    String className = (String) a.get("class");
                    sb.append("<tr><td title=\"").append(esc(className)).append("\">").append(formatClassName(className))
                      .append("</td><td style=\"text-align:right\" class=\"c-purple\">").append(formatBytes((long) a.get("bytes"))).append("</td></tr>");
                }
                sb.append("</table>");
            }
            sb.append("</div>\n");

            // GC pauses
            var gc = (Map<String, Object>) jvmSnap.get("gc");
            var pauses = (List<Map<String, Object>>) gc.get("recentPauses");
            sb.append("<div class=\"section\">");
            sb.append("<div class=\"section-head c-red\">Recent GC Pauses</div>");
            if (pauses.isEmpty()) {
                sb.append("<p class=\"c-muted\">No GC pauses recorded</p>");
            } else {
                sb.append("<table><tr><th>Time</th><th>Collector</th><th>Cause</th><th style=\"text-align:right\">Duration</th></tr>");
                for (var p : pauses) {
                    String ts = (String) p.get("ts");
                    String time = ts.length() > 19 ? ts.substring(11, 19) : ts;
                    double durationMs = (double) p.get("durationMs");
                    String durColor = durationMs > 100 ? "c-red" : durationMs > 10 ? "c-amber" : "c-green";
                    String weight = durationMs > 100 ? "font-weight:bold" : "";
                    sb.append("<tr><td class=\"c-muted\">").append(esc(time))
                      .append("</td><td>").append(esc((String) p.get("collector")))
                      .append("</td><td class=\"c-muted\">").append(esc((String) p.get("cause")))
                      .append("</td><td style=\"text-align:right;").append(weight).append("\" class=\"").append(durColor).append("\">")
                      .append(String.format("%.0fms", durationMs)).append("</td></tr>");
                }
                sb.append("</table>");
            }
            sb.append("</div>\n");

            sb.append("</div>\n"); // two-col
        }

        // Slowest routes (standalone — when JFR is not active)
        if (jvmSnap == null && !routeStats.isEmpty()) {
            sb.append("<div class=\"section\">");
            sb.append("<div class=\"section-head c-blue\">Slowest Routes <span class=\"c-muted\" style=\"font-weight:normal\">— avg latency</span></div>");
            sb.append("<table><tr><th>Route</th><th style=\"text-align:right\">Avg</th><th style=\"text-align:right\">Calls</th></tr>");
            for (var e : routeStats) {
                String[] parts = e.getKey().split(" ", 2);
                String httpMethod = parts[0];
                String path = parts.length > 1 ? parts[1] : e.getKey();
                String methodColor = switch (httpMethod) {
                    case "GET" -> "c-green";
                    case "POST" -> "c-amber";
                    case "PUT" -> "c-blue";
                    case "DELETE" -> "c-red";
                    default -> "c-muted";
                };
                sb.append("<tr><td><span class=\"").append(methodColor).append("\">").append(esc(httpMethod))
                  .append("</span> ").append(esc(path)).append("</td>");
                sb.append("<td style=\"text-align:right\" class=\"c-amber\">").append(String.format("%.0fms", e.getValue().avgLatencyMs())).append("</td>");
                sb.append("<td style=\"text-align:right\" class=\"c-muted\">").append(String.format("%,d", e.getValue().count())).append("</td></tr>");
            }
            sb.append("</table>");
            sb.append("</div>\n");
        }

        // Recent in-memory errors
        if (!recentErrors.isEmpty()) {
            sb.append("<div class=\"section\">");
            sb.append("<div class=\"section-head c-red\">Recent Errors <span class=\"c-muted\" style=\"font-weight:normal\">— in-memory, ").append(recentErrors.size()).append(" tracked</span></div>");
            sb.append("<table><tr><th>Type</th><th>Route</th><th style=\"text-align:right\">Count</th><th style=\"text-align:right\">Last Seen</th></tr>");
            for (var e : recentErrors) {
                sb.append("<tr><td class=\"c-red\">").append(esc(e.type)).append("</td><td>")
                  .append(esc(e.route != null ? e.route : "-")).append("</td><td style=\"text-align:right\">")
                  .append(e.count).append("</td><td style=\"text-align:right\" class=\"c-muted\">")
                  .append(esc(e.lastSeen != null ? e.lastSeen.toString().substring(11, 19) : "-")).append("</td></tr>");
            }
            sb.append("</table>");
            sb.append("</div>\n");
        }

        // Persisted error tracking
        if (errorStore != null) {
            sb.append("<div class=\"tab-bar\">");
            sb.append("<div class=\"tab active\" onclick=\"showErrorTab('unresolved')\">Unresolved (")
              .append(unresolvedErrors.size()).append(")</div>");
            sb.append("<div class=\"tab\" onclick=\"showErrorTab('resolved')\">Resolved (")
              .append(resolvedErrors.size()).append(")</div>");
            sb.append("</div>\n");

            sb.append("<div id=\"tab-unresolved\" class=\"tab-content\" style=\"display:block\">");
            renderPersistedErrors(sb, unresolvedErrors, token, false);
            sb.append("</div>\n");

            sb.append("<div id=\"tab-resolved\" class=\"tab-content\" style=\"display:none\">");
            renderPersistedErrors(sb, resolvedErrors, token, true);
            sb.append("</div>\n");
        }

        // Jobs + Cache (two-column)
        boolean hasJobs = !jobStatuses.isEmpty();
        boolean hasCache = cache != null;
        if (hasJobs || hasCache) {
            sb.append("<div class=\"two-col\">\n");

            if (hasJobs) {
                sb.append("<div class=\"section\">");
                sb.append("<div class=\"section-head c-cyan\">Scheduled Jobs</div>");
                sb.append("<table><tr><th>Name</th><th>Schedule</th><th>Status</th><th style=\"text-align:right\">Last Run</th></tr>");
                for (var j : jobStatuses) {
                    String statusDot = "ok".equals(j.lastStatus()) ? "ok-dot" : "error".equals(j.lastStatus()) ? "err-dot" : "c-muted";
                    String statusLabel = j.lastStatus() != null ? j.lastStatus() : "pending";
                    String lastRun = "-";
                    if (j.lastRun() != null) {
                        String ago = formatDuration(Duration.between(j.lastRun(), now));
                        lastRun = ago + " ago (" + j.lastDurationMs() + "ms)";
                    }
                    sb.append("<tr><td>").append(esc(j.name())).append("</td><td class=\"c-muted\">").append(esc(j.schedule())).append("</td>");
                    sb.append("<td><span class=\"").append(statusDot).append("\">● </span>").append(esc(statusLabel)).append("</td>");
                    sb.append("<td style=\"text-align:right\" class=\"c-muted\">").append(esc(lastRun)).append("</td></tr>");
                }
                sb.append("</table>");
                sb.append("</div>\n");
            }

            if (hasCache) {
                sb.append("<div class=\"section\">");
                sb.append("<div class=\"section-head c-amber\">Cache <span style=\"float:right;font-weight:normal;text-transform:none;letter-spacing:0\">");
                sb.append("<button class=\"btn btn-danger\" hx-post=\"/ops/cache/clear\"")
                  .append(" hx-headers='{\"Authorization\": \"Bearer ").append(esc(token)).append("\"}'")
                  .append(" hx-target=\"#dashboard-content\" hx-select=\"#dashboard-content\" hx-swap=\"outerHTML\">[clear all]</button>");
                sb.append("</span></div>");
                long hits = cache.hits(), misses = cache.misses();
                String hitRate = (hits + misses) > 0 ? ((hits * 100) / (hits + misses)) + "%" : "-";
                sb.append("<div style=\"display:flex;gap:16px;margin-bottom:4px;\">");
                sb.append("<div><span class=\"c-muted\" style=\"font-size:9px\">ENTRIES</span><br/><span class=\"c-amber\" style=\"font-weight:bold\">").append(cache.size()).append("</span></div>");
                sb.append("<div><span class=\"c-muted\" style=\"font-size:9px\">HIT RATE</span><br/><span class=\"c-green\" style=\"font-weight:bold\">").append(hitRate).append("</span></div>");
                sb.append("<div><span class=\"c-muted\" style=\"font-size:9px\">MISSES</span><br/><span class=\"c-muted\" style=\"font-weight:bold\">").append(misses).append("</span></div>");
                sb.append("<div><span class=\"c-muted\" style=\"font-size:9px\">EVICTIONS</span><br/><span class=\"c-muted\" style=\"font-weight:bold\">").append(cache.evictions()).append("</span></div>");
                sb.append("</div>");
                sb.append("</div>\n");
            }

            sb.append("</div>\n"); // two-col
        }

        // Rate limiters
        if (!rateLimiterStats.isEmpty()) {
            sb.append("<div class=\"section\">");
            sb.append("<div class=\"section-head c-blue\">Rate Limiters</div>");
            sb.append("<table><tr><th>Limiter</th><th style=\"text-align:right\">Allowed</th><th style=\"text-align:right\">Blocked</th><th style=\"text-align:right\">Active</th><th style=\"text-align:right\">Limit</th></tr>");
            for (var rl : rateLimiterStats) {
                long allowed = ((Number) rl.get("allowed")).longValue();
                long blocked = ((Number) rl.get("blocked")).longValue();
                String blockPct = (allowed + blocked) > 0 ? String.format("%.1f%%", (blocked * 100.0) / (allowed + blocked)) : "0.0%";
                sb.append("<tr><td>").append(esc((String) rl.get("label"))).append("</td>");
                sb.append("<td style=\"text-align:right\" class=\"c-green\">").append(allowed).append("</td>");
                sb.append("<td style=\"text-align:right\" class=\"").append(blocked > 0 ? "c-red" : "c-muted").append("\">")
                  .append(blocked).append(" (").append(blockPct).append(")</td>");
                sb.append("<td style=\"text-align:right\">").append(rl.get("activeWindows")).append("</td>");
                sb.append("<td style=\"text-align:right\" class=\"c-muted\">").append(rl.get("maxRequests")).append("/").append(rl.get("windowSeconds")).append("s</td></tr>");
            }
            sb.append("</table>");
            sb.append("</div>\n");
        }

        // Status codes
        sb.append("<div class=\"section\">");
        sb.append("<div class=\"section-head c-muted\">Status Codes</div>");
        sb.append("<table><tr><th>Code</th><th style=\"text-align:right\">Count</th></tr>");
        statusCodes.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            String codeColor = e.getKey() < 300 ? "c-green" : e.getKey() < 400 ? "c-blue" : e.getKey() < 500 ? "c-amber" : "c-red";
            sb.append("<tr><td class=\"").append(codeColor).append("\">").append(e.getKey())
              .append("</td><td style=\"text-align:right\">").append(e.getValue()).append("</td></tr>");
        });
        sb.append("</table>");
        sb.append("</div>\n");

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
                                               String token, boolean resolved) {
        if (errors.isEmpty()) {
            sb.append("<p class=\"").append(resolved ? "c-muted" : "c-green").append("\">None</p>");
            return;
        }
        sb.append("<table><tr><th>Type</th><th>Route</th><th style=\"text-align:right\">Count</th><th>First Seen</th><th>Last Seen</th><th></th></tr>");
        for (var e : errors) {
            long id = ((Number) e.get("id")).longValue();
            sb.append("<tr>");
            sb.append("<td class=\"c-red\" style=\"cursor:pointer\" onclick=\"toggleTrace(this)\">")
              .append(esc(str(e.get("errorType")))).append("</td>");
            sb.append("<td>").append(esc(str(e.get("route"), "-"))).append("</td>");
            sb.append("<td style=\"text-align:right\">").append(e.get("occurrenceCount")).append("</td>");
            sb.append("<td class=\"c-muted\">").append(esc(str(e.get("firstSeen"), "-"))).append("</td>");
            sb.append("<td class=\"c-muted\">").append(esc(str(e.get("lastSeen"), "-"))).append("</td>");
            if (!resolved) {
                sb.append("<td><button class=\"btn btn-resolve\" hx-post=\"/ops/errors/").append(id)
                  .append("/resolve\"")
                  .append(" hx-headers='{\"Authorization\": \"Bearer ").append(esc(token)).append("\"}'")
                  .append(" hx-target=\"#dashboard-content\" hx-select=\"#dashboard-content\" hx-swap=\"outerHTML\">resolve</button></td>");
            } else {
                sb.append("<td class=\"c-muted\">").append(esc(str(e.get("resolvedAt"), ""))).append("</td>");
            }
            sb.append("</tr>");
            sb.append("<tr style=\"display:none\"><td colspan=\"6\"><div class=\"stack-trace\">")
              .append(esc(str(e.get("stackTrace"), "No stack trace")))
              .append("</div><div style=\"margin-top:4px\" class=\"c-muted\">")
              .append(esc(str(e.get("message"), ""))).append("</div></td></tr>");
        }
        sb.append("</table>");
    }

    private static void statCard(StringBuilder sb, String label, String value, String detail, String colorClass) {
        sb.append("<div class=\"stat-card\"><div class=\"label\">").append(esc(label))
          .append("</div><div class=\"value ").append(colorClass).append("\">").append(esc(value))
          .append("</div><div class=\"detail\">").append(esc(detail)).append("</div></div>");
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

    private static String formatMethod(String method) {
        int lastDot = method.lastIndexOf('.');
        if (lastDot <= 0) return "<span class=\"method\">" + esc(method) + "</span>";
        int secondLastDot = method.lastIndexOf('.', lastDot - 1);
        if (secondLastDot <= 0) return "<span class=\"method\">" + esc(method) + "</span>";
        String pkg = method.substring(0, secondLastDot);
        String classMethod = method.substring(secondLastDot);
        return "<span class=\"pkg-wrap\" title=\"" + esc(method) + "\"><span class=\"pkg\">" + esc(pkg)
            + "</span></span><span class=\"method\">" + esc(classMethod) + "</span>";
    }

    private static String formatClassName(String className) {
        // JVM primitive array descriptors
        String friendly = switch (className) {
            case "[B" -> "byte[]";
            case "[I" -> "int[]";
            case "[J" -> "long[]";
            case "[S" -> "short[]";
            case "[C" -> "char[]";
            case "[F" -> "float[]";
            case "[D" -> "double[]";
            case "[Z" -> "boolean[]";
            default -> null;
        };
        if (friendly != null) {
            return "<span class=\"method\">" + friendly + "</span>";
        }
        // JVM object array descriptor: [Ljava.lang.String; → String[]
        if (className.startsWith("[L") && className.endsWith(";")) {
            className = className.substring(2, className.length() - 1);
            String suffix = "[]";
            int lastDot2 = className.lastIndexOf('.');
            if (lastDot2 <= 0) return "<span class=\"method\">" + esc(className) + suffix + "</span>";
            String pkg2 = className.substring(0, lastDot2);
            String name2 = className.substring(lastDot2);
            return "<span class=\"pkg-wrap\" title=\"" + esc(className) + "[]\"><span class=\"pkg\">" + esc(pkg2)
                + "</span></span><span class=\"method\">" + esc(name2) + suffix + "</span>";
        }
        int lastDot = className.lastIndexOf('.');
        if (lastDot <= 0) return "<span class=\"method\">" + esc(className) + "</span>";
        String pkg = className.substring(0, lastDot);
        String name = className.substring(lastDot);
        return "<span class=\"pkg-wrap\" title=\"" + esc(className) + "\"><span class=\"pkg\">" + esc(pkg)
            + "</span></span><span class=\"method\">" + esc(name) + "</span>";
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

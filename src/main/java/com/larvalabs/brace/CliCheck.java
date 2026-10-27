package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class CliCheck {

    private CliCheck() {}

    public record CheckResult(boolean healthy, String summary, List<Check> checks) {}

    public record Check(String name, String status, String message,
                        List<Map<String, Object>> details, String followUp) {
        public Check(String name, String status, String message) {
            this(name, status, message, null, null);
        }
    }

    public static int run(Path projectDir, String[] args) throws Exception {
        var cfg = CliConfig.load(projectDir, args);
        var thresholds = cfg.checkThresholds();
        var mode = CliOutput.autoMode(
            CliCommands.hasFlag(args, "--json"),
            CliCommands.hasFlag(args, "--pretty"));

        // Fetch all three endpoints
        JsonNode status, errors, logs;
        try {
            status = fetchJson(cfg, projectDir, cfg.url() + "/ops/status");
        } catch (Exception e) {
            if (mode == CliOutput.Mode.JSON) {
                var result = new CheckResult(false, "App unreachable: " + e.getMessage(), List.of(
                    new Check("reachability", "fail", "Cannot connect: " + e.getMessage())
                ));
                System.out.println(CliOutput.json(result));
            } else {
                CliOutput.printError("Cannot reach " + cfg.url() + ": " + e.getMessage());
            }
            return 2;
        }

        try {
            errors = fetchJson(cfg, projectDir, cfg.url() + "/ops/errors");
        } catch (Exception e) {
            errors = Json.mapper().valueToTree(List.of());
        }

        try {
            Instant since = Instant.now().minusSeconds(thresholds.logWindowMinutes() * 60L);
            logs = fetchJson(cfg, projectDir, cfg.url() + "/ops/logs?level=warn&since_ts=" + since.toString());
        } catch (Exception e) {
            logs = Json.mapper().valueToTree(List.of());
        }

        var result = evaluate(status, errors, logs, thresholds, cfg.env(), checkContentSecurityPolicy(probeRoot(cfg.url())));

        if (mode == CliOutput.Mode.JSON) {
            System.out.println(CliOutput.json(result));
        } else {
            renderHuman(result);
        }

        return result.healthy() ? 0 : 1;
    }

    private static JsonNode fetchJson(CliConfig cfg, Path projectDir, String url) throws Exception {
        var response = CliAuth.sendAuthenticated(cfg, projectDir,
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET());
        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP " + response.statusCode());
        }
        return Json.mapper().readTree(response.body());
    }

    private static void renderHuman(CheckResult result) {
        System.out.println();
        for (var check : result.checks()) {
            String icon = switch (check.status()) {
                case "pass" -> "\u2713";
                case "warn" -> "\u26A0";
                case "fail" -> "\u2717";
                default -> "?";
            };
            System.out.printf("%s %-15s %s%n", icon, check.name(), check.message());
            if (check.followUp() != null && !"pass".equals(check.status())) {
                System.out.printf("  %-15s \u2192 %s%n", "", check.followUp());
            }
        }
        System.out.println();
        long issues = result.checks().stream()
            .filter(c -> !"pass".equals(c.status())).count();
        if (issues == 0) {
            System.out.println("All checks passed");
        } else {
            System.out.println(issues + " issue" + (issues == 1 ? "" : "s") + " found");
        }
    }

    public static CheckResult evaluate(JsonNode status, JsonNode errors, JsonNode logs,
                                       CheckThresholds thresholds) {
        return evaluate(status, errors, logs, thresholds, "prod");
    }

    public static CheckResult evaluate(JsonNode status, JsonNode errors, JsonNode logs,
                                       CheckThresholds thresholds, String env) {
        return evaluate(status, errors, logs, thresholds, env, null);
    }

    /** As above, plus {@code csp} (from {@link #checkContentSecurityPolicy}) when it is non-null. */
    public static CheckResult evaluate(JsonNode status, JsonNode errors, JsonNode logs,
                                       CheckThresholds thresholds, String env, Check csp) {
        var checks = new ArrayList<Check>();

        checks.add(checkReachability(status));
        checks.add(checkErrors(status, errors, env));
        checks.add(checkHttp5xx(status));
        checks.add(checkSlowRoutes(status, thresholds));
        checks.add(checkHeap(status, thresholds));
        checks.add(checkGcPressure(status, thresholds, env));
        checks.add(checkJobs(status));
        checks.add(checkCache(status, thresholds));
        checks.add(checkRecentLogs(logs, thresholds, env));
        if (csp != null) checks.add(csp);

        boolean healthy = checks.stream().noneMatch(c -> "fail".equals(c.status()));
        String summary = buildSummary(checks);
        return new CheckResult(healthy, summary, checks);
    }

    /** The status and Content-Security-Policy of an unauthenticated {@code GET /}. */
    record RootProbe(int status, String contentSecurityPolicy) {}

    private static final HttpClient probeClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    /**
     * Fetch {@code GET /} without credentials, or null if that fails. The app's after-middleware
     * runs on every response, redirects and 404s included, so a login redirect still shows the
     * headers the app sets.
     */
    static RootProbe probeRoot(String baseUrl) {
        try {
            var response = probeClient.send(
                HttpRequest.newBuilder().uri(URI.create(baseUrl + "/")).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            return new RootProbe(response.statusCode(),
                response.headers().firstValue("Content-Security-Policy").orElse(null));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * M6: warn when the app sends no Content-Security-Policy, or one whose scripts allow
     * {@code 'unsafe-inline'} (which lets any injected inline script run). Null when the probe
     * failed: reachability is reported by the other checks.
     */
    static Check checkContentSecurityPolicy(RootProbe probe) {
        if (probe == null) return null;
        // The fix is a code change, not a command, so it rides in the message (followUp is a
        // command agents run).
        String fix = ": use app.after(SecurityHeaders.strict())";
        String csp = probe.contentSecurityPolicy();
        if (csp == null || csp.isBlank()) {
            return new Check("csp", "warn", "No Content-Security-Policy on GET /" + fix);
        }
        String scripts = null, fallback = null;
        for (String directive : csp.split(";")) {
            String[] parts = directive.strip().split("\\s+", 2);
            String name = parts[0].toLowerCase(Locale.ROOT);
            String value = parts.length > 1 ? parts[1] : "";
            if (name.equals("script-src") && scripts == null) scripts = value;
            if (name.equals("default-src") && fallback == null) fallback = value;
        }
        String sources = scripts != null ? scripts : fallback;
        if (sources == null) {
            return new Check("csp", "warn", "Content-Security-Policy on GET / doesn't restrict scripts" + fix);
        }
        String lower = sources.toLowerCase(Locale.ROOT);
        // A nonce or hash makes CSP2+ browsers ignore 'unsafe-inline' (it's only a CSP1 fallback).
        boolean inlineAllowed = lower.contains("'unsafe-inline'")
            && !lower.contains("'nonce-") && !lower.contains("'sha256-") && !lower.contains("'sha384-")
            && !lower.contains("'sha512-");
        if (inlineAllowed) {
            return new Check("csp", "warn", "Content-Security-Policy on GET / allows inline scripts ('unsafe-inline')" + fix);
        }
        return new Check("csp", "pass", "Content-Security-Policy set");
    }

    static Check checkReachability(JsonNode status) {
        var app = status.path("app");
        String uptime = app.path("uptime").asText("unknown");
        String javaVersion = app.path("javaVersion").asText("unknown");

        String startedAt = app.path("startedAt").asText("");
        if (!startedAt.isEmpty()) {
            try {
                var started = Instant.parse(startedAt);
                long seconds = Duration.between(started, Instant.now()).getSeconds();
                if (seconds < 300) {
                    return new Check("reachability", "warn",
                        "App up for " + uptime + " (recent restart), Java " + javaVersion,
                        null, null);
                }
            } catch (Exception ignored) {}
        }
        return new Check("reachability", "pass", "App up for " + uptime + ", Java " + javaVersion);
    }

    static Check checkErrors(JsonNode status, JsonNode errors, String env) {
        int count = errors.size();
        if (count == 0) {
            return new Check("errors", "pass", "No unresolved errors");
        }
        var details = new ArrayList<Map<String, Object>>();
        for (var e : errors) {
            var d = new LinkedHashMap<String, Object>();
            d.put("type", e.path("errorType").asText(e.path("type").asText("?")));
            d.put("route", e.path("route").asText("?"));
            d.put("count", e.path("occurrenceCount").asInt(e.path("count").asInt(1)));
            if (e.has("id")) d.put("id", e.path("id").asText());
            details.add(d);
        }
        return new Check("errors", "fail",
            count + " unresolved error" + (count == 1 ? "" : "s"),
            details, "brace errors --env " + env + " --json");
    }

    static Check checkHttp5xx(JsonNode status) {
        var codes = status.path("http").path("statusCodes");
        int total5xx = 0;
        int totalRequests = 0;
        var it = codes.fields();
        while (it.hasNext()) {
            var entry = it.next();
            int code = Integer.parseInt(entry.getKey());
            int count = entry.getValue().asInt(0);
            totalRequests += count;
            if (code >= 500) total5xx += count;
        }
        if (total5xx > 0) {
            return new Check("http_5xx", "fail",
                total5xx + " server error" + (total5xx == 1 ? "" : "s") + " in " + totalRequests + " requests");
        }
        return new Check("http_5xx", "pass",
            "0 server errors in " + totalRequests + " request" + (totalRequests == 1 ? "" : "s"));
    }

    static Check checkSlowRoutes(JsonNode status, CheckThresholds thresholds) {
        var routes = status.path("http").path("slowestRoutes");
        var slow = new ArrayList<Map<String, Object>>();
        for (var r : routes) {
            double avgMs = r.path("avgMs").asDouble(0);
            if (avgMs > thresholds.slowRouteMs()) {
                slow.add(Map.of(
                    "route", r.path("route").asText(),
                    "avgMs", avgMs,
                    "count", r.path("count").asInt()
                ));
            }
        }
        if (!slow.isEmpty()) {
            return new Check("slow_routes", "warn",
                slow.size() + " route" + (slow.size() == 1 ? "" : "s") + " over " + thresholds.slowRouteMs() + "ms",
                slow, null);
        }
        return new Check("slow_routes", "pass",
            "All routes under " + thresholds.slowRouteMs() + "ms");
    }

    static Check checkHeap(JsonNode status, CheckThresholds thresholds) {
        var heap = status.path("jvm").path("heap");
        long usedMB = heap.path("usedMB").asLong(0);
        long maxMB = heap.path("maxMB").asLong(1);
        int percent = maxMB > 0 ? (int) (usedMB * 100 / maxMB) : 0;
        String msg = usedMB + "MB / " + maxMB + "MB (" + percent + "%)";
        if (percent >= thresholds.heapFailPercent()) {
            return new Check("heap", "fail", msg);
        }
        if (percent >= thresholds.heapWarnPercent()) {
            return new Check("heap", "warn", msg);
        }
        return new Check("heap", "pass", msg);
    }

    static Check checkGcPressure(JsonNode status, CheckThresholds thresholds, String env) {
        var gc = status.path("jvm").path("gc");
        // Stop-the-world time per collection. Pre-0.1.10 servers counted the whole span of
        // concurrent cycles (G1Old, ZGC, Shenandoah) as pause time, inflating this figure.
        double avgPause = gc.path("avgPauseMs").asDouble(0);
        String msg = String.format(Locale.ROOT, "Avg pause %.1fms", avgPause);
        // Allocation profiling shows what is filling the heap.
        String followUp = "brace status --env " + env + " --include profiling --json";
        if (avgPause > thresholds.gcPauseMs()) {
            return new Check("gc_pressure", "fail", msg, null, followUp);
        }
        // A G1 full collection means concurrent marking fell behind (often humongous
        // allocations or an undersized heap). SerialOld/ParallelOld are those collectors'
        // normal old-gen collections, judged by the pause threshold above.
        var fullGcs = new ArrayList<Map<String, Object>>();
        for (var p : gc.path("recentPauses")) {
            if ("G1Full".equals(p.path("collector").asText())) {
                var d = new LinkedHashMap<String, Object>();
                d.put("ts", p.path("ts").asText());
                d.put("cause", p.path("cause").asText());
                d.put("pauseMs", p.path("durationMs").asDouble());
                fullGcs.add(d);
            }
        }
        if (!fullGcs.isEmpty()) {
            return new Check("gc_pressure", "warn",
                fullGcs.size() + " G1 full GC" + (fullGcs.size() == 1 ? "" : "s")
                    + " in recent collections; " + msg.toLowerCase(),
                fullGcs, followUp);
        }
        return new Check("gc_pressure", "pass", msg);
    }

    static Check checkJobs(JsonNode status) {
        var scheduled = status.path("jobs").path("scheduled");
        if (scheduled.isMissingNode() || scheduled.size() == 0) {
            return new Check("jobs", "pass", "No scheduled jobs");
        }
        int total = scheduled.size();
        var failing = new ArrayList<Map<String, Object>>();
        var warned = new ArrayList<Map<String, Object>>();
        for (var j : scheduled) {
            String lastStatus = j.path("lastStatus").asText("ok");
            int failCount = j.path("failCount").asInt(0);
            if (!"ok".equals(lastStatus)) {
                var d = new LinkedHashMap<String, Object>();
                d.put("name", j.path("name").asText());
                d.put("lastStatus", lastStatus);
                d.put("failCount", failCount);
                String lastError = j.path("lastError").asText(null);
                if (lastError != null) d.put("lastError", lastError);
                failing.add(d);
            } else if (failCount > 0) {
                var d = new LinkedHashMap<String, Object>();
                d.put("name", j.path("name").asText());
                d.put("failCount", failCount);
                warned.add(d);
            }
        }
        if (!failing.isEmpty()) {
            return new Check("jobs", "fail",
                failing.size() + " of " + total + " job" + (total == 1 ? "" : "s") + " failing",
                failing, null);
        }
        if (!warned.isEmpty()) {
            return new Check("jobs", "warn",
                warned.size() + " job" + (warned.size() == 1 ? "" : "s") + " with prior failures (currently ok)",
                warned, null);
        }
        return new Check("jobs", "pass",
            total + " job" + (total == 1 ? "" : "s") + ", all ok");
    }

    static Check checkCache(JsonNode status, CheckThresholds thresholds) {
        var cache = status.path("cache");
        if (cache.isMissingNode()) {
            return new Check("cache", "pass", "Cache not configured");
        }
        long hits = cache.path("hits").asLong(0);
        long misses = cache.path("misses").asLong(0);
        long total = hits + misses;
        if (total == 0) {
            return new Check("cache", "pass", "Cache active, no requests yet");
        }
        double hitRate = (double) hits / total;
        String msg = "Hit rate " + Math.round(hitRate * 100) + "% (" + hits + " hits / " + misses + " misses)";
        if (hitRate < thresholds.cacheHitRate()) {
            return new Check("cache", "warn", msg);
        }
        return new Check("cache", "pass", msg);
    }

    static Check checkRecentLogs(JsonNode logs, CheckThresholds thresholds, String env) {
        int errorCount = 0;
        int warnCount = 0;
        for (var entry : logs) {
            String level = entry.path("level").asText("INFO").toUpperCase();
            if ("ERROR".equals(level)) errorCount++;
            else if ("WARN".equals(level) || "WARNING".equals(level)) warnCount++;
        }
        String window = thresholds.logWindowMinutes() + "m";
        if (errorCount > 0) {
            return new Check("recent_logs", "fail",
                errorCount + " error-level entr" + (errorCount == 1 ? "y" : "ies") + " in last " + window,
                null, "brace logs --env " + env + " --since " + window + " --level error --json");
        }
        if (warnCount > 0) {
            return new Check("recent_logs", "warn",
                warnCount + " warn-level entr" + (warnCount == 1 ? "y" : "ies") + " in last " + window,
                null, "brace logs --env " + env + " --since " + window + " --level warn --json");
        }
        return new Check("recent_logs", "pass",
            "0 error-level entries in last " + window,
            null, null);
    }

    static String buildSummary(List<Check> checks) {
        var issues = new ArrayList<String>();
        for (var c : checks) {
            if ("fail".equals(c.status()) || "warn".equals(c.status())) {
                issues.add(c.message());
            }
        }
        if (issues.isEmpty()) return "All checks passed";
        return issues.size() + " issue" + (issues.size() == 1 ? "" : "s") + ": " + String.join(", ", issues);
    }
}

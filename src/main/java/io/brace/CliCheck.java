package io.brace;

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

        var result = evaluate(status, errors, logs, thresholds, cfg.env());

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
        var checks = new ArrayList<Check>();

        checks.add(checkReachability(status));
        checks.add(checkErrors(status, errors, env));
        checks.add(checkHttp5xx(status));
        checks.add(checkSlowRoutes(status, thresholds));
        checks.add(checkHeap(status, thresholds));
        checks.add(checkGcPressure(status, thresholds));
        checks.add(checkJobs(status));
        checks.add(checkCache(status, thresholds));
        checks.add(checkRecentLogs(logs, thresholds, env));

        boolean healthy = checks.stream().noneMatch(c -> "fail".equals(c.status()));
        String summary = buildSummary(checks);
        return new CheckResult(healthy, summary, checks);
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

    static Check checkGcPressure(JsonNode status, CheckThresholds thresholds) {
        double avgPause = status.path("jvm").path("gc").path("avgPauseMs").asDouble(0);
        String msg = "Avg pause " + (int) avgPause + "ms";
        if (avgPause > thresholds.gcPauseMs()) {
            return new Check("gc_pressure", "fail", msg);
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

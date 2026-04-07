package io.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class OpsHandler {

    private final Stats stats;
    private final JobScheduler jobScheduler;
    private final Mailer mailer;
    private final Router router;
    private final String opsSecret;

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, String opsSecret) {
        this.stats = stats;
        this.jobScheduler = jobScheduler;
        this.mailer = mailer;
        this.router = router;
        this.opsSecret = opsSecret;
    }

    public Result status(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");

        var data = new LinkedHashMap<String, Object>();

        // App info
        var app = new LinkedHashMap<String, Object>();
        app.put("framework", "brace");
        app.put("uptime", formatDuration(Duration.between(stats.startedAt(), Instant.now())));
        app.put("startedAt", stats.startedAt().toString());
        app.put("javaVersion", System.getProperty("java.version"));
        data.put("app", app);

        // HTTP stats
        var http = new LinkedHashMap<String, Object>();
        http.put("statusCodes", stats.statusCodeCounts());
        // Slowest routes (top 5 by avg latency)
        var routeList = new ArrayList<Map<String, Object>>();
        stats.routeStats().entrySet().stream()
            .sorted((a, b) -> Double.compare(b.getValue().avgLatencyMs(), a.getValue().avgLatencyMs()))
            .limit(5)
            .forEach(e -> {
                var r = new LinkedHashMap<String, Object>();
                r.put("route", e.getKey());
                r.put("count", e.getValue().count());
                r.put("avgMs", Math.round(e.getValue().avgLatencyMs() * 100.0) / 100.0);
                routeList.add(r);
            });
        http.put("slowestRoutes", routeList);
        data.put("http", http);

        // Memory
        var memory = new LinkedHashMap<String, Object>();
        var runtime = Runtime.getRuntime();
        memory.put("heapUsedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
        memory.put("heapMaxMB", runtime.maxMemory() / (1024 * 1024));
        data.put("memory", memory);

        // Errors
        var errors = new LinkedHashMap<String, Object>();
        var recentErrors = new ArrayList<Map<String, Object>>();
        for (var err : stats.recentErrors()) {
            var e = new LinkedHashMap<String, Object>();
            e.put("type", err.type);
            e.put("message", err.message);
            e.put("route", err.route);
            e.put("count", err.count);
            e.put("firstSeen", err.firstSeen.toString());
            e.put("lastSeen", err.lastSeen.toString());
            e.put("stackTrace", err.stackTrace);
            recentErrors.add(e);
        }
        errors.put("recent", recentErrors);
        data.put("errors", errors);

        // Jobs
        var jobs = new LinkedHashMap<String, Object>();
        if (jobScheduler != null) {
            var scheduled = new ArrayList<Map<String, Object>>();
            for (var js : jobScheduler.getStatuses()) {
                var j = new LinkedHashMap<String, Object>();
                j.put("name", js.name());
                j.put("schedule", js.schedule());
                j.put("lastRun", js.lastRun() != null ? js.lastRun().toString() : null);
                j.put("lastDurationMs", js.lastDurationMs());
                j.put("lastStatus", js.lastStatus());
                j.put("lastError", js.lastError());
                j.put("failCount", js.failCount());
                scheduled.add(j);
            }
            jobs.put("scheduled", scheduled);
        }
        data.put("jobs", jobs);

        // Mailer
        if (mailer != null) {
            var mailerData = new LinkedHashMap<String, Object>();
            mailerData.put("sentCount", mailer.sentCount());
            data.put("mailer", mailerData);
        }

        // Timeseries
        var timeseries = new LinkedHashMap<String, Object>();
        var minutes = new ArrayList<Map<String, Object>>();
        for (var snap : stats.minuteSnapshots()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("ts", snap.ts().toString());
            m.put("requests", snap.requests());
            m.put("errors", snap.errors());
            m.put("avgMs", Math.round(snap.avgLatencyMs() * 100.0) / 100.0);
            minutes.add(m);
        }
        timeseries.put("minutes", minutes);
        data.put("timeseries", timeseries);

        return Json.of(data);
    }

    public Result dashboard(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");
        return Result.html(OpsDashboard.html(opsSecret));
    }

    public Result routes(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");

        var routeList = new ArrayList<Map<String, Object>>();
        for (var route : router.routes()) {
            var r = new LinkedHashMap<String, Object>();
            r.put("method", route.method());
            r.put("pattern", route.pattern());
            routeList.add(r);
        }
        return Json.of(routeList);
    }

    private boolean authorize(Request req) {
        if (opsSecret == null) return false;
        var headerKey = req.header("X-Ops-Key");
        if (headerKey != null) return opsSecret.equals(headerKey);
        return opsSecret.equals(req.param("key"));
    }

    private String formatDuration(Duration d) {
        long days = d.toDays();
        long hours = d.toHoursPart();
        long mins = d.toMinutesPart();
        if (days > 0) return days + "d " + hours + "h " + mins + "m";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }
}

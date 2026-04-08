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
    private final ErrorStore errorStore;
    private final Cache cache;
    private final JfrProfiler profiler;

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, String opsSecret) {
        this(stats, jobScheduler, mailer, router, opsSecret, null, null, null);
    }

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, String opsSecret, ErrorStore errorStore) {
        this(stats, jobScheduler, mailer, router, opsSecret, errorStore, null, null);
    }

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, String opsSecret, ErrorStore errorStore, Cache cache) {
        this(stats, jobScheduler, mailer, router, opsSecret, errorStore, cache, null);
    }

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, String opsSecret, ErrorStore errorStore, Cache cache,
                      JfrProfiler profiler) {
        this.stats = stats;
        this.jobScheduler = jobScheduler;
        this.mailer = mailer;
        this.router = router;
        this.opsSecret = opsSecret;
        this.errorStore = errorStore;
        this.cache = cache;
        this.profiler = profiler;
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

        // JVM (from JFR profiler or fallback to runtime)
        if (profiler != null) {
            data.put("jvm", profiler.snapshot());
        } else {
            var jvm = new LinkedHashMap<String, Object>();
            var heap = new LinkedHashMap<String, Object>();
            var runtime = Runtime.getRuntime();
            heap.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
            heap.put("maxMB", runtime.maxMemory() / (1024 * 1024));
            jvm.put("heap", heap);

            var cpu = new LinkedHashMap<String, Object>();
            cpu.put("jvmUser", 0.0);
            cpu.put("jvmSystem", 0.0);
            cpu.put("machineTotal", 0.0);
            jvm.put("cpu", cpu);

            var threads = new LinkedHashMap<String, Object>();
            var threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
            threads.put("active", threadBean.getThreadCount());
            threads.put("daemon", threadBean.getDaemonThreadCount());
            threads.put("peak", threadBean.getPeakThreadCount());
            jvm.put("threads", threads);

            var gc = new LinkedHashMap<String, Object>();
            gc.put("totalCount", 0L);
            gc.put("totalPauseMs", 0L);
            gc.put("avgPauseMs", 0.0);
            gc.put("recentPauses", List.of());
            jvm.put("gc", gc);

            var profiling = new LinkedHashMap<String, Object>();
            profiling.put("windowSeconds", 0);
            profiling.put("hotMethods", List.of());
            profiling.put("topAllocations", List.of());
            jvm.put("profiling", profiling);

            data.put("jvm", jvm);
        }

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
            mailerData.put("failCount", mailer.failCount());
            data.put("mailer", mailerData);
        }

        // Cache
        if (cache != null) {
            var cacheData = new LinkedHashMap<String, Object>();
            cacheData.put("entries", cache.size());
            cacheData.put("counters", cache.counterCount());
            cacheData.put("tags", cache.tagCount());
            cacheData.put("hits", cache.hits());
            cacheData.put("misses", cache.misses());
            cacheData.put("evictions", cache.evictions());
            data.put("cache", cacheData);
        }

        // Rate limiters
        var rateLimiterStats = RateLimiter.allStats();
        if (!rateLimiterStats.isEmpty()) {
            data.put("rateLimiters", rateLimiterStats);
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
        return Result.html(OpsDashboard.html(opsSecret, stats, jobScheduler, mailer, errorStore, cache));
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

    public Result errors(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");
        if (errorStore == null) return Json.of(List.of());
        String status = req.param("status");
        return Json.of(errorStore.list(status));
    }

    public Result resolveError(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");
        if (errorStore == null) return Result.notFound();
        long id = req.longParam("id");
        errorStore.resolve(id);
        return dashboard(req);
    }

    public Result clearCache(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");
        if (cache == null) return Result.notFound();
        cache.clear();
        return dashboard(req);
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

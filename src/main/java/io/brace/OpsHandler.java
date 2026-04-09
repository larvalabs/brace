package io.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class OpsHandler {

    private final Stats stats;
    private final JobScheduler jobScheduler;
    private final Mailer mailer;
    private final Router router;
    private final Set<String> authorizedKeys;
    private final String tokenSecret;
    private final ErrorStore errorStore;
    private final Cache cache;
    private final JfrProfiler profiler;

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, Set<String> authorizedKeys, String tokenSecret) {
        this(stats, jobScheduler, mailer, router, authorizedKeys, tokenSecret, null, null, null);
    }

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, Set<String> authorizedKeys, String tokenSecret,
                      ErrorStore errorStore) {
        this(stats, jobScheduler, mailer, router, authorizedKeys, tokenSecret, errorStore, null, null);
    }

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, Set<String> authorizedKeys, String tokenSecret,
                      ErrorStore errorStore, Cache cache) {
        this(stats, jobScheduler, mailer, router, authorizedKeys, tokenSecret, errorStore, cache, null);
    }

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, Set<String> authorizedKeys, String tokenSecret,
                      ErrorStore errorStore, Cache cache, JfrProfiler profiler) {
        this.stats = stats;
        this.jobScheduler = jobScheduler;
        this.mailer = mailer;
        this.router = router;
        this.authorizedKeys = authorizedKeys;
        this.tokenSecret = tokenSecret;
        this.errorStore = errorStore;
        this.cache = cache;
        this.profiler = profiler;
    }

    /**
     * POST /ops/auth — validate signed timestamp, issue short-lived token.
     * Body: JSON with "publicKey", "timestamp", "signature" fields.
     */
    public Result auth(Request req) {
        try {
            String body = req.body();
            if (body == null || body.isEmpty()) return Result.unauthorized("Missing request body");

            // Simple JSON parsing (no Jackson dependency in OpsHandler)
            String publicKey = jsonField(body, "publicKey");
            String timestamp = jsonField(body, "timestamp");
            String signature = jsonField(body, "signature");

            if (publicKey == null || timestamp == null || signature == null) {
                return Result.unauthorized("Missing required fields");
            }

            // Check public key is authorized
            if (!authorizedKeys.contains(publicKey)) {
                return Result.unauthorized("Unknown public key");
            }

            // Check timestamp is not stale (within ±30 seconds)
            java.time.Instant ts;
            try {
                ts = java.time.Instant.parse(timestamp);
            } catch (Exception e) {
                return Result.unauthorized("Invalid timestamp");
            }
            var now = java.time.Instant.now();
            if (Math.abs(java.time.Duration.between(ts, now).getSeconds()) > 30) {
                return Result.unauthorized("Stale timestamp");
            }

            // Verify signature
            if (!OpsKeys.verify(timestamp, signature, publicKey)) {
                return Result.unauthorized("Invalid signature");
            }

            // Issue token — check for client-requested TTL
            String ttlField = jsonField(body, "ttlSeconds");
            int requestedTtl = 3600; // default API TTL: 1 hour
            if (ttlField != null) {
                try { requestedTtl = Integer.parseInt(ttlField); } catch (NumberFormatException ignored) {}
            }
            int ttl = Math.min(requestedTtl, 86400); // cap at 24 hours
            String token = OpsToken.create(tokenSecret, ttl);
            var expiresAt = java.time.Instant.now().plusSeconds(ttl).toString();
            return Json.of(Map.of("token", token, "expiresAt", expiresAt));
        } catch (Exception e) {
            return Result.unauthorized("Authentication failed");
        }
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

        // Custom metrics
        var counterTotalMap = stats.counterTotals();
        var gaugeValueMap = stats.currentGaugeValues();
        var timerValueMap = stats.lastTimerValues();
        if (!counterTotalMap.isEmpty() || !gaugeValueMap.isEmpty() || !timerValueMap.isEmpty()) {
            var metrics = new LinkedHashMap<String, Object>();
            if (!counterTotalMap.isEmpty()) {
                var countersJson = new LinkedHashMap<String, Object>();
                var latestSnapshots = stats.minuteSnapshots();
                var lastSnap = latestSnapshots.isEmpty() ? null : latestSnapshots.get(latestSnapshots.size() - 1);
                for (var entry : counterTotalMap.entrySet()) {
                    var c = new LinkedHashMap<String, Object>();
                    c.put("total", entry.getValue());
                    long rate = lastSnap != null && lastSnap.counterDeltas().containsKey(entry.getKey())
                            ? lastSnap.counterDeltas().get(entry.getKey()) : 0;
                    c.put("rate", rate);
                    countersJson.put(entry.getKey(), c);
                }
                metrics.put("counters", countersJson);
            }
            if (!gaugeValueMap.isEmpty()) {
                var gaugesJson = new LinkedHashMap<String, Object>();
                for (var entry : gaugeValueMap.entrySet()) {
                    gaugesJson.put(entry.getKey(), Map.of("value", entry.getValue()));
                }
                metrics.put("gauges", gaugesJson);
            }
            if (!timerValueMap.isEmpty()) {
                var timersJson = new LinkedHashMap<String, Object>();
                for (var entry : timerValueMap.entrySet()) {
                    var t = new LinkedHashMap<String, Object>();
                    t.put("count", entry.getValue().count());
                    t.put("avgMs", Math.round(entry.getValue().avgMs() * 100.0) / 100.0);
                    t.put("maxMs", entry.getValue().maxMs());
                    timersJson.put(entry.getKey(), t);
                }
                metrics.put("timers", timersJson);
            }
            data.put("metrics", metrics);
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
        // Generate a dashboard token with 2h TTL for htmx polling
        String dashboardToken = OpsToken.create(tokenSecret, 7200);
        return Result.html(OpsDashboard.html(dashboardToken, stats, jobScheduler, mailer, errorStore, cache, profiler));
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
        if (tokenSecret == null) return false;
        // Check Authorization: Bearer <token> header
        var authHeader = req.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (OpsToken.validate(token, tokenSecret)) return true;
        }
        // Check ?token= query param
        var tokenParam = req.param("token");
        if (tokenParam != null) {
            return OpsToken.validate(tokenParam, tokenSecret);
        }
        return false;
    }

    /** Simple JSON field extraction (no dependency on Jackson). */
    private static String jsonField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
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

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

    // In-memory store for single-use login tokens (token -> expiry timestamp)
    private final Map<String, Instant> loginTokens = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String OPS_COOKIE_NAME = "__brace_ops_session";

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

    public record OpsAuthRequest(String publicKey, String timestamp, String signature, Integer ttlSeconds) {}

    /**
     * POST /ops/auth — validate signed timestamp, issue short-lived token.
     * Body: JSON with "publicKey", "timestamp", "signature", and optional "ttlSeconds" fields.
     */
    public Result auth(Request req) {
        try {
            // Parse request with Jackson
            OpsAuthRequest auth = req.bodyAs(OpsAuthRequest.class);
            if (auth == null || auth.publicKey == null || auth.timestamp == null || auth.signature == null) {
                return Result.unauthorized("Missing required fields");
            }

            // Check public key is authorized
            if (!authorizedKeys.contains(auth.publicKey)) {
                return Result.unauthorized("Unknown public key");
            }

            // Check timestamp is not stale (within ±30 seconds)
            java.time.Instant ts;
            try {
                ts = java.time.Instant.parse(auth.timestamp);
            } catch (Exception e) {
                return Result.unauthorized("Invalid timestamp");
            }
            var now = java.time.Instant.now();
            if (Math.abs(java.time.Duration.between(ts, now).getSeconds()) > 30) {
                return Result.unauthorized("Stale timestamp");
            }

            // Verify signature
            if (!OpsKeys.verify(auth.timestamp, auth.signature, auth.publicKey)) {
                return Result.unauthorized("Invalid signature");
            }

            // Issue token — check for client-requested TTL
            int requestedTtl = auth.ttlSeconds != null ? auth.ttlSeconds : 3600; // default: 1 hour
            int ttl = Math.min(requestedTtl, 86400); // cap at 24 hours
            String token = OpsToken.create(tokenSecret, ttl);
            var expiresAt = java.time.Instant.now().plusSeconds(ttl).toString();
            return Json.of(Map.of("token", token, "expiresAt", expiresAt));
        } catch (Exception e) {
            return Result.unauthorized("Authentication failed");
        }
    }

    /**
     * POST /ops/auth/login-token — issue a short-lived, single-use browser login token.
     * Requires valid Bearer token authentication.
     */
    public Result loginToken(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");

        // Generate a random login token (5 minute TTL)
        String loginToken = java.util.UUID.randomUUID().toString();
        Instant expiry = Instant.now().plusSeconds(300); // 5 minutes
        loginTokens.put(loginToken, expiry);

        // Clean up expired tokens
        cleanupExpiredLoginTokens();

        return Json.of(Map.of(
            "loginToken", loginToken,
            "expiresAt", expiry.toString(),
            "exchangeUrl", "/ops/auth/exchange?token=" + loginToken
        ));
    }

    /**
     * GET /ops/auth/exchange?token=... — exchange login token for session cookie.
     * Validates the single-use token, sets httpOnly cookie, redirects to dashboard.
     */
    public Result exchange(Request req) {
        String loginToken = req.queryParam("token");
        if (loginToken == null) {
            return Result.badRequest("Missing token parameter");
        }

        // Check if token exists and is not expired
        Instant expiry = loginTokens.get(loginToken);
        if (expiry == null || Instant.now().isAfter(expiry)) {
            return Result.unauthorized("Invalid or expired login token");
        }

        // Consume the single-use token
        loginTokens.remove(loginToken);

        // Create a long-lived ops session token (24 hours)
        String sessionToken = OpsToken.create(tokenSecret, 86400);

        // Set httpOnly cookie and redirect to dashboard
        var result = Redirect.to("/ops/dashboard");
        result.cookie(OPS_COOKIE_NAME, sessionToken, 86400, true, true, "Strict");
        return result;
    }

    private void cleanupExpiredLoginTokens() {
        Instant now = Instant.now();
        loginTokens.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
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

    public Result logs(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");

        String sinceStr = req.queryParam("since");
        String sinceTsStr = req.queryParam("since_ts");
        String level = req.queryParam("level");
        String limitStr = req.queryParam("limit");

        int limit;
        try {
            limit = limitStr != null ? Integer.parseInt(limitStr) : 200;
        } catch (NumberFormatException e) {
            return Result.badRequest("Invalid limit: " + limitStr);
        }
        limit = Math.max(1, Math.min(limit, 1000));

        List<LogTap.LogEntry> entries;
        try {
            if (sinceStr != null) {
                entries = LogTap.since(Long.parseLong(sinceStr));
            } else if (sinceTsStr != null) {
                entries = LogTap.sinceTimestamp(Instant.parse(sinceTsStr));
            } else {
                entries = LogTap.snapshot();
            }
        } catch (NumberFormatException e) {
            return Result.badRequest("Invalid since: " + sinceStr);
        } catch (java.time.format.DateTimeParseException e) {
            return Result.badRequest("Invalid since_ts: " + sinceTsStr);
        }

        if (level != null) {
            int minRank = levelRank(level);
            var filtered = new ArrayList<LogTap.LogEntry>();
            for (var e : entries) {
                Object lvl = e.fields().get("level");
                String lvlStr = lvl instanceof String s ? s : null;
                if (levelRank(lvlStr) >= minRank) filtered.add(e);
            }
            entries = filtered;
        }

        if (entries.size() > limit) entries = entries.subList(entries.size() - limit, entries.size());

        var out = new ArrayList<Map<String, Object>>();
        for (var e : entries) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", e.id());
            m.putAll(e.fields());
            out.add(m);
        }
        return Json.of(out);
    }

    public Result errors(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");
        if (errorStore == null) return Json.of(List.of());
        String status = req.queryParam("status");
        String since = req.queryParam("since");
        Instant sinceTs = null;
        if (since != null) {
            try { sinceTs = Instant.parse(since); }
            catch (java.time.format.DateTimeParseException e) { return Result.badRequest("Invalid since timestamp"); }
        }
        return Json.of(errorStore.list(status, sinceTs));
    }

    public Result resolveError(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");
        if (errorStore == null) return Result.notFound();
        long id = req.longPathParam("id");
        errorStore.resolve(id);
        if (wantsJson(req)) {
            return Json.of(Map.of("resolved", true, "id", id));
        }
        return dashboard(req);
    }

    public Result clearCache(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");
        if (cache == null) return Result.notFound();
        cache.clear();
        if (wantsJson(req)) {
            return Json.of(Map.of("cleared", true));
        }
        return dashboard(req);
    }

    public Result cacheStats(Request req) {
        if (!authorize(req)) return Result.unauthorized("Invalid ops key");
        var out = new LinkedHashMap<String, Object>();
        if (cache == null) {
            out.put("enabled", false);
            return Json.of(out);
        }
        long hits = cache.hits();
        long misses = cache.misses();
        long total = hits + misses;
        out.put("enabled", true);
        out.put("size", cache.size());
        out.put("hits", hits);
        out.put("misses", misses);
        out.put("hitRate", total == 0 ? 0.0 : (double) hits / total);
        out.put("evictions", cache.evictions());
        return Json.of(out);
    }

    private boolean wantsJson(Request req) {
        String accept = req.header("Accept");
        return accept != null && accept.contains("application/json");
    }

    private boolean authorize(Request req) {
        if (tokenSecret == null) return false;

        // Check Authorization: Bearer <token> header
        var authHeader = req.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (OpsToken.validate(token, tokenSecret)) return true;
        }

        // Check ops session cookie (set by /ops/auth/exchange)
        String cookieToken = req.cookie(OPS_COOKIE_NAME);
        if (cookieToken != null && OpsToken.validate(cookieToken, tokenSecret)) {
            return true;
        }

        // Check ?token= query param (fallback for direct access, but discouraged)
        var tokenParam = req.queryParam("token");
        if (tokenParam != null) {
            return OpsToken.validate(tokenParam, tokenSecret);
        }

        return false;
    }

    private static int levelRank(String level) {
        if (level == null) return 0;
        return switch (level.toUpperCase()) {
            case "DEBUG" -> 0;
            case "INFO"  -> 1;
            case "WARN"  -> 2;
            case "ERROR" -> 3;
            default      -> 0;
        };
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

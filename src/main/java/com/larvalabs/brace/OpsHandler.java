package com.larvalabs.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

public class OpsHandler {

    private final Stats stats;
    private final JobScheduler jobScheduler;
    private final Mailer mailer;
    private final Router router;
    private final Map<String, OpsScope> authorizedKeys;
    private final String tokenSecret;
    private final ErrorStore errorStore;
    private final Cache cache;
    private final JfrProfiler profiler;
    private RegressionTracker regressionTracker;
    private volatile String instanceId = "unknown";

    private static final String OPS_COOKIE_NAME = "__brace_ops_session";
    // Browser login tokens are stateless, short-lived HMAC tokens (no server-side store), so the
    // issue/exchange handshake works behind a load balancer where the two calls may hit different
    // instances (B5). Kept very short since the CLI hands the URL straight to the browser.
    private static final int LOGIN_TOKEN_TTL_SECONDS = 60;
    // Session TTL: one workday. Shorter than the former 24h; with H1 scope-preservation the
    // session is already bounded to the caller's ceiling, so 8h is sufficient and limits the
    // damage window of a stolen cookie.
    private static final int SESSION_TTL_SECONDS = 28800; // 8 hours

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, Map<String, OpsScope> authorizedKeys, String tokenSecret) {
        this(stats, jobScheduler, mailer, router, authorizedKeys, tokenSecret, null, null, null);
    }

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, Map<String, OpsScope> authorizedKeys, String tokenSecret,
                      ErrorStore errorStore) {
        this(stats, jobScheduler, mailer, router, authorizedKeys, tokenSecret, errorStore, null, null);
    }

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, Map<String, OpsScope> authorizedKeys, String tokenSecret,
                      ErrorStore errorStore, Cache cache) {
        this(stats, jobScheduler, mailer, router, authorizedKeys, tokenSecret, errorStore, cache, null);
    }

    public OpsHandler(Stats stats, JobScheduler jobScheduler, Mailer mailer,
                      Router router, Map<String, OpsScope> authorizedKeys, String tokenSecret,
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

    public record OpsAuthRequest(String v, String publicKey, String timestamp, String signature,
                                 String nonce, Integer ttlSeconds, String scope) {}

    // Replay suppression for v2 auth nonces. Per-instance, in-memory, best-effort: ops must work
    // without shared fleet state (B5 — no database, no cross-instance store), so this CANNOT be
    // fleet-global. Residual window: a captured v2 auth request can still be replayed against a
    // DIFFERENT instance behind the load balancer within the ±30s timestamp window. Documented in
    // docs/SECURITY.md ("Ops Endpoints"). TTL is 2 minutes — comfortably above the 60s total
    // acceptance window — and the map is size-bounded (entries are only recorded after a valid
    // signature, so only holders of an authorized key can occupy slots).
    private final NonceCache seenNonces = new NonceCache(120_000, 100_000);

    /**
     * POST /ops/auth — validate a signed auth request, issue a short-lived bearer token.
     *
     * <p>Protocol v2 (current): body carries {@code v:"2"}, {@code publicKey}, {@code timestamp},
     * a per-attempt random {@code nonce} (base64url, 16+ bytes), and a {@code signature} over
     * {@link OpsKeys#v2AuthMessage publicKey + "\n" + timestamp + "\n" + nonce}. Binding the key
     * into the signed message kills cross-key confusion; the nonce (rejected on reuse, see
     * {@link #seenNonces}) makes a captured tuple single-purpose on this instance.
     *
     * <p>Protocol v1 (deprecated, shipped in 0.1.6): no {@code v}/{@code nonce}, signature over
     * the timestamp alone — replayable within the ±30s window. Accepted this release with a
     * deprecation warning; will be rejected in a future release (see the 0.1.6→0.1.7 migration
     * guide). Optional fields for both: {@code ttlSeconds}, {@code scope}.
     */
    public Result auth(Request req) {
        try {
            // Parse request with Jackson
            OpsAuthRequest auth = req.bodyAs(OpsAuthRequest.class);
            if (auth == null || auth.publicKey == null || auth.timestamp == null || auth.signature == null) {
                return Result.unauthorized("Missing required fields");
            }

            // Check public key is authorized
            if (!authorizedKeys.containsKey(auth.publicKey)) {
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

            // Verify signature per protocol version
            if ("2".equals(auth.v)) {
                if (auth.nonce == null || auth.nonce.length() < 16 || auth.nonce.length() > 256) {
                    return Result.unauthorized("Missing or invalid nonce (base64url of 16+ random bytes required)");
                }
                String message = OpsKeys.v2AuthMessage(auth.publicKey, auth.timestamp, auth.nonce);
                if (!OpsKeys.verify(message, auth.signature, auth.publicKey)) {
                    return Result.unauthorized("Invalid signature");
                }
                // Replay check last, so only validly-signed requests consume nonce slots.
                if (!seenNonces.checkAndRecord(auth.nonce, System.currentTimeMillis())) {
                    return Result.unauthorized("Nonce already used");
                }
            } else if (auth.v == null || "1".equals(auth.v)) {
                // Legacy v1: signature over the timestamp only — not bound to the key, no nonce,
                // replayable within the ±30s window. Kept for one release because v1 shipped in
                // 0.1.6; removal is documented in the migration guide.
                Log.warn("ops auth protocol v1 is deprecated and will be rejected in a future release"
                    + " — upgrade the brace CLI (key " + OpsKeys.fingerprint(auth.publicKey) + ")");
                if (!OpsKeys.verify(auth.timestamp, auth.signature, auth.publicKey)) {
                    return Result.unauthorized("Invalid signature");
                }
            } else {
                return Result.unauthorized("Unsupported ops auth protocol version \"" + auth.v
                    + "\" — this server accepts v2 (and v1, deprecated)");
            }

            // Issue token — check for client-requested TTL
            int requestedTtl = auth.ttlSeconds != null ? auth.ttlSeconds : 3600; // default: 1 hour
            int ttl = Math.min(requestedTtl, 86400); // cap at 24 hours

            // Scope: cap the requested scope at the key's ceiling. A read-only key can
            // never mint a control token, so the grant is min(requested, ceiling). When
            // the client requests no scope, it gets the full ceiling.
            OpsScope ceiling = authorizedKeys.getOrDefault(auth.publicKey, OpsScope.CONTROL);
            OpsScope requested = OpsScope.parse(auth.scope, ceiling);
            OpsScope granted = requested.min(ceiling);
            String kid = OpsKeys.fingerprint(auth.publicKey);

            String token = OpsToken.create(tokenSecret, ttl, granted, kid);
            var expiresAt = java.time.Instant.now().plusSeconds(ttl).toString();
            return Json.of(Map.of("token", token, "expiresAt", expiresAt, "scope", granted.wire()));
        } catch (Exception e) {
            return Result.unauthorized("Authentication failed");
        }
    }

    /**
     * POST /ops/auth/login-token — issue a short-lived, single-use browser login token.
     * Requires valid Bearer token authentication.
     */
    public Result loginToken(Request req) {
        var claims = authorizedClaims(req, OpsScope.READ);
        if (claims == null) return Result.unauthorized("Invalid ops key");

        // Stateless, short-lived HMAC token — no server-side store, so exchange works on any
        // instance behind a load balancer (B5). It conveys exactly the access the caller
        // already holds: minted at the caller's scope and key id (H1), which exchange()
        // preserves into the browser session cookie — a read key yields a read-only,
        // attributed browser session. READ-gated because the dashboard itself is READ;
        // no escalation is possible since the minted token never exceeds the caller's scope.
        String loginToken = OpsToken.create(tokenSecret, LOGIN_TOKEN_TTL_SECONDS, claims.scope(), claims.kid());
        Instant expiry = Instant.now().plusSeconds(LOGIN_TOKEN_TTL_SECONDS);

        return Json.of(Map.of(
            "loginToken", loginToken,
            "expiresAt", expiry.toString(),
            "exchangeUrl", "/ops/auth/exchange?token=" + loginToken
        ));
    }

    /**
     * GET /ops/auth/exchange?token=... — exchange a login token for a session cookie.
     * Verifies the token's HMAC + expiry (stateless), sets the httpOnly cookie, redirects to the
     * dashboard. Reusable within the token's short TTL; replay protection is the TTL, not a store —
     * single-use would need fleet-wide shared state that ops (which can run without a database)
     * can't assume. See docs/migrations and the multi-server plan (B5).
     *
     * <p>Why {@code ?token=} survives here and nowhere else: the exchange endpoint is the
     * browser-redirect handoff — the CLI hands the URL straight to the browser, and there is no
     * other channel that can carry a credential into a plain GET redirect. The token is
     * short-lived (60s, {@link #LOGIN_TOKEN_TTL_SECONDS}) and scope-capped at the caller's
     * ceiling (H1), so the exposure window is narrow. All other ops endpoints accept credentials
     * via {@code Authorization: Bearer} header or the session cookie only — see
     * {@link #authenticate}. The response carries {@code Referrer-Policy: no-referrer} and
     * {@code Cache-Control: no-store} so the token-bearing URL is not forwarded to any
     * outbound link on the dashboard and is not stored in proxy or browser caches.
     */
    public Result exchange(Request req) {
        String loginToken = req.queryParam("token");
        if (loginToken == null) {
            return Result.badRequest("Missing token parameter");
        }

        var claims = OpsToken.verify(loginToken, tokenSecret);
        if (claims == null) {
            return Result.unauthorized("Invalid or expired login token");
        }

        // Mint an 8h ops session token (one workday), preserving the login token's scope (H1).
        // 8h rather than 24h: with scope-preservation the session is already bound to the
        // caller's ceiling; shorter TTL limits the damage window of a stolen cookie further.
        String sessionToken = OpsToken.create(tokenSecret, SESSION_TTL_SECONDS, claims.scope(), claims.kid());

        var result = Redirect.to("/ops/dashboard");
        result.cookie(OPS_COOKIE_NAME, sessionToken, SESSION_TTL_SECONDS, true, true, "Strict");
        // no-referrer: the token is in our URL — prevent it leaking to any outbound link the
        // dashboard renders. no-store: prevent proxy / browser caches from recording the URL.
        result.header("Referrer-Policy", "no-referrer");
        result.header("Cache-Control", "no-store");
        return result;
    }

    /**
     * GET /ops/status — system snapshot: app info, HTTP stats, JVM, error summary, jobs,
     * mailer, cache, rate limiters, custom metrics.
     *
     * <p>The {@code errors} block is {@code {count, recent}}: {@code count} is the unresolved
     * error count (DB-backed via {@link ErrorStore} when a database is configured, else the
     * in-memory recent-error list), and {@code recent} is the top 5 most recent — summary
     * fields only, no stack traces. Full detail lives at {@code /ops/errors/{id}}.
     *
     * <p>Bulky blocks are opt-in via {@code ?include=timeseries,profiling}: {@code timeseries}
     * adds the per-minute snapshot ring (60 entries), {@code profiling} adds
     * {@code jvm.profiling} (JFR hot methods + top allocations, 20 each). Neither is emitted
     * by default — they dominated the payload while most pollers only read the scalars.
     */
    public Result status(Request req) {
        if (!authorize(req, OpsScope.READ)) return Result.unauthorized("Invalid ops key");

        var include = includeParams(req);

        var data = new LinkedHashMap<String, Object>();

        // App info
        var app = new LinkedHashMap<String, Object>();
        app.put("framework", "brace");
        app.put("instanceId", instanceId);   // which box served this snapshot (P3, fleet visibility)
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

        // JVM (from JFR profiler or fallback to runtime). The profiling block (hot methods +
        // top allocations) is opt-in via ?include=profiling; without a profiler there is no
        // cpu/gc/profiling data, so those keys are simply absent (no all-zeros stubs).
        if (profiler != null) {
            // The profiling block is built only when requested — H6 trimmed it from the
            // default JSON; this also skips the sort/map work behind it.
            data.put("jvm", profiler.snapshot(include.contains("profiling")));
        } else {
            var jvm = new LinkedHashMap<String, Object>();
            var heap = new LinkedHashMap<String, Object>();
            var runtime = Runtime.getRuntime();
            heap.put("usedMB", (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024));
            heap.put("maxMB", runtime.maxMemory() / (1024 * 1024));
            jvm.put("heap", heap);

            var threads = new LinkedHashMap<String, Object>();
            var threadBean = java.lang.management.ManagementFactory.getThreadMXBean();
            threads.put("active", threadBean.getThreadCount());
            threads.put("daemon", threadBean.getDaemonThreadCount());
            threads.put("peak", threadBean.getPeakThreadCount());
            jvm.put("threads", threads);

            data.put("jvm", jvm);
        }

        // Errors: unresolved count + top-5 recent summaries (no stack traces — detail is at
        // /ops/errors/{id}). DB-backed when an ErrorStore exists (that's what "unresolved"
        // means across restarts); the in-memory Stats list is the no-database fallback.
        var errors = new LinkedHashMap<String, Object>();
        long errorCount;
        var recentErrors = new ArrayList<Map<String, Object>>();
        if (errorStore != null) {
            errorCount = errorStore.countUnresolved();
            recentErrors.addAll(errorStore.recentUnresolved(5));
        } else {
            var inMemory = stats.recentErrors();
            errorCount = inMemory.size();
            // Same row shape as the no-database /ops/errors list — one builder for
            // every in-memory summary row.
            inMemory.stream()
                .sorted((a, b) -> b.lastSeen.compareTo(a.lastSeen))
                .limit(5)
                .forEach(err -> recentErrors.add(inMemorySummary(err)));
        }
        errors.put("count", errorCount);
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
                j.put("lastMessage", js.lastMessage());
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
            cacheData.put("shared", cache.shared());
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

        // Timeseries — opt-in (?include=timeseries): 60 per-minute snapshots that only a
        // charting consumer wants.
        if (include.contains("timeseries")) {
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
        }

        return Json.of(data);
    }

    public Result dashboard(Request req) {
        var claims = authorizedClaims(req, OpsScope.READ);
        if (claims == null) return Result.unauthorized("Invalid ops key");
        // Generate a dashboard token with 2h TTL for htmx polling. Minted at the caller's
        // own scope and key id — never above it (H1: this token is embedded in the page
        // HTML, so a CONTROL default would hand every read-only caller a control token).
        String dashboardToken = OpsToken.create(tokenSecret, 7200, claims.scope(), claims.kid());
        return Result.html(OpsDashboard.html(dashboardToken, claims.scope(), stats, jobScheduler, mailer, errorStore, cache, profiler));
    }

    public Result routes(Request req) {
        if (!authorize(req, OpsScope.READ)) return Result.unauthorized("Invalid ops key");

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
        if (!authorize(req, OpsScope.READ)) return Result.unauthorized("Invalid ops key");

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

    /**
     * GET /ops/errors — one summary row per error: {@code id, errorType, message, route,
     * occurrenceCount, firstSeen, lastSeen} plus {@code at}, the first app frame of the
     * stored trace. Full detail (stackTrace, requestDetail, queriesBefore, requestHeaders)
     * via {@code ?include=detail} (the pre-0.1.7 shape) or per-error via /ops/errors/{id}.
     * Same opt-in grammar as {@code /ops/status?include=...} — one verbosity convention
     * across the ops endpoints.
     */
    public Result errors(Request req) {
        if (!authorize(req, OpsScope.READ)) return Result.unauthorized("Invalid ops key");
        String status = req.queryParam("status");
        String since = req.queryParam("since");
        Instant sinceTs = null;
        if (since != null) {
            try { sinceTs = Instant.parse(since); }
            catch (java.time.format.DateTimeParseException e) { return Result.badRequest("Invalid since timestamp"); }
        }
        boolean full = includeParams(req).contains("detail");
        // No database: serve the in-memory Stats records. Resolving removes them, so
        // everything tracked is unresolved (status=resolved is by definition empty).
        if (errorStore == null) {
            if ("resolved".equals(status)) return Json.of(List.of());
            var out = new ArrayList<Map<String, Object>>();
            for (var rec : inMemoryErrors(sinceTs)) {
                out.add(full ? inMemoryDetail(rec) : inMemorySummary(rec));
            }
            return Json.of(out);
        }
        var rows = errorStore.list(status, sinceTs);
        if (full) return Json.of(rows);
        var out = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", row.get("id"));
            m.put("errorType", row.get("errorType"));
            m.put("message", row.get("message"));
            m.put("route", row.get("route"));
            m.put("occurrenceCount", row.get("occurrenceCount"));
            m.put("firstSeen", row.get("firstSeen"));
            m.put("lastSeen", row.get("lastSeen"));
            String at = Log.appFrame((String) row.get("stackTrace"));
            if (at != null) m.put("at", at);
            out.add(m);
        }
        return Json.of(out);
    }

    /** GET /ops/errors/{id} — full detail for one tracked error; 404 for unknown ids. */
    public Result errorDetail(Request req) {
        if (!authorize(req, OpsScope.READ)) return Result.unauthorized("Invalid ops key");
        long id;
        try {
            id = req.longPathParam("id");
        } catch (NumberFormatException e) {
            return Result.notFound();
        }
        if (errorStore == null) {
            var rec = stats.findError(id);
            if (rec == null) return Result.notFound();
            return Json.of(inMemoryDetail(rec));
        }
        var detail = errorStore.find(id);
        if (detail == null) return Result.notFound();
        return Json.of(detail);
    }

    public Result resolveError(Request req) {
        if (!authorize(req, OpsScope.CONTROL)) return Result.unauthorized("Invalid ops key");
        long id;
        try {
            id = req.longPathParam("id");
        } catch (NumberFormatException e) {
            // Like errorDetail: a malformed id is a 404, not a 500 that records a fresh
            // framework error — re-reddening the count this endpoint exists to clear.
            return Result.notFound();
        }
        if (errorStore == null) {
            // No database: resolving removes the in-memory record, so errors.count (and
            // the brace status exit code) recovers instead of staying red until restart.
            var rec = stats.resolveError(id);
            if (wantsJson(req)) {
                if (rec == null) return Result.notFound();
                var resolved = inMemoryDetail(rec);
                resolved.put("resolvedAt", Instant.now());
                return Json.of(resolved);
            }
            return dashboard(req);
        }
        var resolved = errorStore.resolve(id);
        if (wantsJson(req)) {
            if (resolved == null) return Result.notFound();
            return Json.of(resolved);
        }
        return dashboard(req);
    }

    /**
     * The {@code ?include=a,b} opt-in sections — the one verbosity grammar across ops
     * endpoints ({@code /ops/status?include=timeseries,profiling},
     * {@code /ops/errors?include=detail}).
     */
    private static java.util.Set<String> includeParams(Request req) {
        var include = new HashSet<String>();
        String includeParam = req.queryParam("include");
        if (includeParam != null) {
            for (var part : includeParam.split(",")) include.add(part.trim());
        }
        return include;
    }

    /**
     * In-memory error records for the no-database /ops/errors fallback, newest first.
     * {@code since} filters on {@code firstSeen}, matching the DB path's
     * {@code first_seen >= ?} — both modes answer "errors that appeared after X".
     */
    private List<Stats.ErrorRecord> inMemoryErrors(Instant since) {
        return stats.recentErrors().stream()
            .filter(r -> since == null || !r.firstSeen.isBefore(since))
            .sorted((a, b) -> b.lastSeen.compareTo(a.lastSeen))
            .toList();
    }

    /** Summary shape matching the DB-backed /ops/errors rows. */
    private static Map<String, Object> inMemorySummary(Stats.ErrorRecord r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", r.id);
        m.put("errorType", r.type);
        m.put("message", r.message);
        m.put("route", r.route);
        m.put("occurrenceCount", r.count);
        m.put("firstSeen", r.firstSeen);
        m.put("lastSeen", r.lastSeen);
        String at = Log.appFrame(r.stackTrace);
        if (at != null) m.put("at", at);
        return m;
    }

    /** Full-detail shape matching the DB-backed /ops/errors/{id} (no requestHeaders — not captured in Stats). */
    private static LinkedHashMap<String, Object> inMemoryDetail(Stats.ErrorRecord r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", r.id);
        m.put("errorType", r.type);
        m.put("message", r.message);
        m.put("stackTrace", r.stackTrace);
        m.put("route", r.route);
        m.put("requestDetail", r.requestDetail);
        m.put("firstSeen", r.firstSeen);
        m.put("lastSeen", r.lastSeen);
        m.put("occurrenceCount", r.count);
        m.put("resolvedAt", null);
        m.put("queriesBefore", r.queriesBefore);
        return m;
    }

    public void setRegressionTracker(RegressionTracker tracker) {
        this.regressionTracker = tracker;
    }

    /** Identify which instance is serving ops responses (set once the server has bound — P3). */
    public void setInstanceId(String instanceId) {
        if (instanceId != null) this.instanceId = instanceId;
    }

    /** GET /ops/regressions — new error kinds since startup (the /ops/errors shape + acknowledged). */
    public Result regressions(Request req) {
        if (!authorize(req, OpsScope.READ)) return Result.unauthorized("Invalid ops key");
        if (regressionTracker == null) return Json.of(List.of());
        var out = new ArrayList<Map<String, Object>>();
        for (var r : regressionTracker.list()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", r.id());
            m.put("errorType", r.type());
            m.put("route", r.route());
            m.put("message", r.message());
            m.put("firstSeen", r.firstSeen().toString());
            m.put("count", r.count());
            m.put("acknowledged", r.acknowledged());
            out.add(m);
        }
        return Json.of(out);
    }

    /** POST /ops/regressions/{id}/acknowledge — stop flagging a regression (control action). */
    public Result acknowledgeRegression(Request req) {
        if (!authorize(req, OpsScope.CONTROL)) return Result.unauthorized("Invalid ops key");
        if (regressionTracker == null) return Result.notFound();
        String id = req.pathParam("id");
        if (id == null || !regressionTracker.acknowledge(id)) return Result.notFound();
        return Json.of(Map.of("acknowledged", true, "id", id));
    }

    public Result clearCache(Request req) {
        if (!authorize(req, OpsScope.CONTROL)) return Result.unauthorized("Invalid ops key");
        if (cache == null) return Result.notFound();
        cache.clear();
        if (wantsJson(req)) {
            // A shared backend clears the whole fleet in one call (TRUNCATE); the in-process
            // default clears only this instance.
            return Json.of(Map.of("cleared", true, "scope", cache.shared() ? "fleet" : "instance"));
        }
        return dashboard(req);
    }

    public Result cacheStats(Request req) {
        if (!authorize(req, OpsScope.READ)) return Result.unauthorized("Invalid ops key");
        var out = new LinkedHashMap<String, Object>();
        if (cache == null) {
            out.put("enabled", false);
            return Json.of(out);
        }
        long hits = cache.hits();
        long misses = cache.misses();
        long total = hits + misses;
        out.put("enabled", true);
        out.put("shared", cache.shared());
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

    /**
     * Verify the request's token and return its claims, or {@code null} if unauthenticated.
     * Two credential channels are accepted:
     * <ol>
     *   <li>{@code Authorization: Bearer <token>} header — the standard machine-to-machine
     *       channel used by the CLI and the dashboard's htmx polling.</li>
     *   <li>The {@code __brace_ops_session} httpOnly cookie — set by the
     *       {@code /ops/auth/exchange} browser handoff.</li>
     * </ol>
     * {@code ?token=} query-param credentials are intentionally NOT accepted here. Tokens in
     * URLs leak via proxy access logs, browser history, and the {@code Referer} header on
     * outbound links. The exchange endpoint ({@code /ops/auth/exchange}) is the only endpoint
     * that reads {@code ?token=} because it is the browser-redirect handoff — there is no other
     * channel to carry a credential into a plain GET redirect — and the login token it accepts
     * is short-lived (60s) and scope-capped. See {@link #exchange} for the full rationale.
     *
     * <p>This is the single choke point where a request's identity (kid) and scope are resolved —
     * the natural place for a future ops audit log to attach.
     */
    private OpsToken.Claims authenticate(Request req) {
        if (tokenSecret == null) return null;

        // Check Authorization: Bearer <token> header
        var authHeader = req.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            var claims = OpsToken.verify(authHeader.substring(7), tokenSecret);
            if (claims != null) return claims;
        }

        // Check ops session cookie (set by /ops/auth/exchange)
        String cookieToken = req.cookie(OPS_COOKIE_NAME);
        if (cookieToken != null) {
            var claims = OpsToken.verify(cookieToken, tokenSecret);
            if (claims != null) return claims;
        }

        return null;
    }

    /**
     * True if the request carries a valid token whose scope grants {@code required}.
     * Every authenticated request (granted or scope-denied) is recorded to the ops
     * audit log, attributed to the token's key fingerprint.
     */
    private boolean authorize(Request req, OpsScope required) {
        return authorizedClaims(req, required) != null;
    }

    /**
     * Like {@link #authorize}, but returns the verified claims on success so the caller
     * can act at the token's <em>actual</em> scope — e.g. re-mint a derived token capped
     * at the caller's scope and attributed to the caller's key (H1: a READ caller must
     * never receive a CONTROL token). Returns {@code null} when unauthenticated or
     * scope-denied. Audited identically to {@link #authorize}.
     */
    private OpsToken.Claims authorizedClaims(Request req, OpsScope required) {
        var claims = authenticate(req);
        if (claims == null) return null;
        boolean granted = claims.scope().grants(required);
        OpsAudit.record(req.method(), req.path(), claims.kid(), claims.scope(), required, granted);
        return granted ? claims : null;
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

    /**
     * Per-instance, best-effort seen-nonce set for v2 ops auth replay suppression.
     * Maps nonce → expiry (epoch millis); expired entries are swept opportunistically on
     * each call (the auth endpoint is low-rate, so a full sweep per call is cheap). The
     * set is size-bounded and fails closed: when full (after sweeping), new nonces are
     * rejected rather than evicting live ones. Entries are only recorded after signature
     * verification, so only holders of an authorized key can fill it.
     *
     * <p>Deliberately NOT fleet-global (B5: ops must work without shared state), so a
     * captured request remains replayable against a different instance within the
     * timestamp acceptance window — see docs/SECURITY.md.
     */
    static final class NonceCache {
        private final java.util.concurrent.ConcurrentHashMap<String, Long> seen =
            new java.util.concurrent.ConcurrentHashMap<>();
        private final long ttlMillis;
        private final int maxSize;

        NonceCache(long ttlMillis, int maxSize) {
            this.ttlMillis = ttlMillis;
            this.maxSize = maxSize;
        }

        /**
         * Record the nonce if it is fresh. Returns {@code false} when the nonce was
         * already seen (replay) or the cache is at capacity (fail closed).
         */
        boolean checkAndRecord(String nonce, long nowMillis) {
            seen.entrySet().removeIf(e -> e.getValue() < nowMillis);
            if (seen.size() >= maxSize) {
                return false;
            }
            return seen.putIfAbsent(nonce, nowMillis + ttlMillis) == null;
        }

        int size() {
            return seen.size();
        }
    }
}

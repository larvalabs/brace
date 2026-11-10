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
    // Fingerprint (token kid) → scope ceiling of each key authorized at construction. A token is
    // honored only if its kid is in here, and never above that key's current ceiling, so removing
    // a key from ops-authorized-keys — or lowering its scope — takes effect on its outstanding
    // tokens at the next restart, even though the token secret is derived from the session secret.
    private final Map<String, OpsScope> ceilingByKid;
    private final String tokenSecret;
    private final ErrorStore errorStore;
    private final Cache cache;
    private final JfrProfiler profiler;
    private RegressionTracker regressionTracker;
    private Analytics analytics;
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

    /**
     * The Content-Security-Policy on every /ops response (M6). The dashboard and analytics pages
     * load their script and CSS from {@code /__brace/} and carry no inline script, handler or
     * style, so nothing but same-origin script, style and fetches is needed. Stricter than
     * {@link SecurityHeaders#strict()} ({@code default-src 'none'}), so the two never conflict.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; "
        + "img-src 'self'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    /**
     * Put the ops security headers on {@code result}, replacing any the app's after-middleware
     * set. Called by the response choke point for the framework's /ops routes, after the app's
     * after-middleware has run, so the ops policy holds whatever the app configures.
     */
    static void applySecurityHeaders(Result result) {
        // An app's CORS headers don't apply to ops: one that reflects any Origin with credentials
        // would let a same-site page read cookie-authenticated ops JSON.
        result.headers().keySet().removeIf(name -> name.regionMatches(true, 0, "Access-Control-", 0, 15));
        for (var header : List.of(
                Map.entry("Content-Security-Policy", CONTENT_SECURITY_POLICY),
                Map.entry("X-Content-Type-Options", "nosniff"),
                Map.entry("X-Frame-Options", "DENY"),
                // The exchange URL carries the login token; no ops page should send a Referer.
                Map.entry("Referrer-Policy", "no-referrer"),
                // Ops responses are per-credential: an app's "public" caching mustn't reach them.
                Map.entry("Cache-Control", "no-store"))) {
            // Result's header map is case-sensitive: drop an app's differently-cased copy too.
            result.headers().keySet().removeIf(name -> name.equalsIgnoreCase(header.getKey()));
            result.header(header.getKey(), header.getValue());
        }
    }

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
        var ceilings = new HashMap<String, OpsScope>();
        authorizedKeys.forEach((publicKey, ceiling) -> ceilings.put(OpsKeys.fingerprint(publicKey), ceiling));
        this.ceilingByKid = Map.copyOf(ceilings);
        this.seenNonces = new NonceCache(NONCE_TTL_MILLIS, NONCE_CAP, nonceQuotaPerKey(authorizedKeys.size()));
        this.tokenSecret = tokenSecret;
        this.errorStore = errorStore;
        this.cache = cache;
        this.profiler = profiler;
    }

    public record OpsAuthRequest(String v, String publicKey, String timestamp, String signature,
                                 String nonce, Integer ttlSeconds, String scope, String origin) {}

    // M14: the only origins a v3 auth signature may name, when configured (app.opsOrigins).
    private volatile List<String> pinnedOrigins = List.of();

    // WebSocket routes live outside the Router; /ops/routes lists them too.
    private volatile List<Route> webSocketRoutes = List.of();

    // M3: static mappings are in the access model too; /ops/routes lists them with their access.
    private volatile java.util.function.Supplier<List<BraceHandler.StaticFileMapping>> staticMappings = List::of;

    void setStaticMappings(java.util.function.Supplier<List<BraceHandler.StaticFileMapping>> mappings) {
        this.staticMappings = mappings;
    }

    void setWebSocketRoutes(List<Route> routes) {
        this.webSocketRoutes = List.copyOf(routes);
    }

    void setPinnedOrigins(List<String> origins) {
        var normalized = new java.util.ArrayList<String>();
        for (String o : origins) normalized.add(Brace.parseOrigin(o));
        this.pinnedOrigins = List.copyOf(normalized);
    }

    // Replay suppression for v3 auth nonces, in two layers (L16, final review).
    //
    // 1. seenNonces: per-instance, in memory. Always on: ops must work with no database and with
    //    the database down (B5). TTL is 2 minutes, comfortably above the 60 s total acceptance
    //    window, and the map is size-bounded (entries are only recorded after a valid signature,
    //    so only holders of an authorized key can occupy slots).
    // 2. durableNonces: the same nonce recorded in brace_counters when the app has a database.
    //    That record is shared by the fleet and survives a restart, so a request any instance
    //    accepted is refused everywhere, by this process or the next.
    //
    // When layer 2 is absent or its write fails, a restart empties the only memory there is. Then
    // the restart window applies: a request whose timestamp is earlier than RESTART_WINDOW_MILLIS
    // after this process started is refused. The previous process accepted only timestamps within
    // 30 s of its own clock, and it stopped before this one started, so everything it accepted is
    // earlier than that. The cost is that sign-ins are refused for the first 30 s after a start
    // (up to 60 s for a client whose clock runs 30 s behind). Residual in that mode, documented in
    // docs/SECURITY.md ("Ops Endpoints"): a request accepted by ANOTHER live instance can be
    // replayed here within its timestamp window.
    static final long AUTH_WINDOW_MILLIS = 30_000;
    static final long RESTART_WINDOW_MILLIS = AUTH_WINDOW_MILLIS;
    static final int NONCE_CAP = 100_000;
    static final long NONCE_TTL_MILLIS = 120_000;
    private volatile long startedAtMillis = System.currentTimeMillis();
    private volatile boolean restartWindow = true;
    private volatile Counters durableNonces;
    private volatile long lastNonceRowSweepMillis;
    private final NonceCache seenNonces;

    /**
     * Each key's share of the nonce cache: 5,000 nonces per 2 minutes, less when there are so many
     * authorized keys that those shares would not all fit under {@link #NONCE_CAP}. The shares
     * always sum to at most the cap, so no set of keys can use up another key's room (final
     * review: with a fixed 5,000 and a cap of 100,000, twenty keys could block a twenty-first).
     */
    static int nonceQuotaPerKey(int authorizedKeys) {
        return Math.max(1, Math.min(5_000, NONCE_CAP / Math.max(1, authorizedKeys)));
    }

    /** Record accepted nonces in {@code brace_counters} too; called by {@code Brace.start()} when there is a database. */
    void setDurableNonces(Counters counters) {
        this.durableNonces = counters;
    }

    /**
     * Turn the restart window off: dev mode and the test harness, where a server is restarted
     * constantly and a replayed localhost sign-in is not a threat.
     */
    void disableRestartWindow() {
        this.restartWindow = false;
    }

    // Visible for testing
    void setStartedAtForTest(long millis) {
        this.startedAtMillis = millis;
    }

    /**
     * POST /ops/auth — validate a signed auth request, issue a short-lived bearer token.
     *
     * <p>Protocol v3 (current): body carries {@code v:"3"}, the target {@code origin}, {@code publicKey},
     * {@code timestamp}, a per-attempt random {@code nonce} (base64url, 16+ bytes), and a
     * {@code signature} over {@link OpsKeys#v3AuthMessage}. Binding the key kills cross-key
     * confusion; the nonce (rejected on reuse, see {@link #seenNonces}) makes a captured tuple
     * single-purpose on this instance; the origin, checked against the host the request arrived
     * for, makes a signature collected by another server useless here (M14).
     *
     * <p>Protocol v2 (0.1.7 to 0.1.10, <strong>removed in 0.1.11</strong>): the same without the
     * origin. A v2 body gets a 401 naming the cause.
     *
     * <p>Protocol v1 (shipped in 0.1.6, deprecated in 0.1.7, <strong>removed in 0.1.8</strong>):
     * no {@code v}/{@code nonce}, signature over the timestamp alone, so a captured request was
     * replayable verbatim within the ±30s window. A v1 body now gets a 401 naming the cause.
     * Optional fields: {@code ttlSeconds}, {@code scope}.
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
            if (Math.abs(java.time.Duration.between(ts, now).toMillis()) > AUTH_WINDOW_MILLIS) {
                return Result.unauthorized("Stale timestamp (more than 30 seconds from this server's clock: check "
                    + "this machine's clock)");
            }

            // Verify signature per protocol version
            if ("3".equals(auth.v)) {
                if (auth.nonce == null || auth.nonce.length() < 16 || auth.nonce.length() > 256) {
                    return Result.unauthorized("Missing or invalid nonce (base64url of 16+ random bytes required)");
                }
                String origin = auth.origin == null || auth.origin.length() > 512
                        || auth.origin.chars().anyMatch(c -> c < 0x21 || c > 0x7e)
                    ? null : Brace.parseOrigin(auth.origin);
                if (origin == null) {
                    return Result.unauthorized("Missing or invalid origin");
                }
                String message = OpsKeys.v3AuthMessage(origin, auth.publicKey, auth.timestamp, auth.nonce);
                if (!OpsKeys.verify(message, auth.signature, auth.publicKey)) {
                    return Result.unauthorized("Invalid signature");
                }
                // M14: the signature names the server the CLI meant. One made for another origin
                // (a .brace pointing elsewhere, a relayed request) is refused.
                if (!originIsThisServer(req, origin)) {
                    return Result.unauthorized("This request was signed for " + origin + ", which is not this "
                        + "server (Host: " + req.host() + "). Check ops.<env>.url in .brace; behind a proxy "
                        + "that rewrites Host, list the public origin with app.opsOrigins(...)");
                }
                // Replay checks last, so only validly-signed requests for this server consume
                // nonce slots, and nothing below is told to a caller without an authorized key
                // (final review: the start-time check ran before the signature was verified).
                long nowMillis = System.currentTimeMillis();
                switch (seenNonces.record(OpsKeys.fingerprint(auth.publicKey), auth.nonce, nowMillis)) {
                    case REPLAY -> {
                        return Result.unauthorized("Nonce already used");
                    }
                    case FULL -> {
                        return Result.unauthorized("Too many sign-ins with this key in the last two minutes; "
                            + "wait a minute and retry");
                    }
                    case FRESH -> { }
                }
                Boolean firstUse = recordDurably(auth.nonce, nowMillis);
                if (Boolean.FALSE.equals(firstUse)) {
                    return Result.unauthorized("Nonce already used");
                }
                // No durable record of what the previous process accepted: refuse what it could
                // have accepted (see the comment on seenNonces).
                if (firstUse == null && restartWindow && ts.toEpochMilli() < startedAtMillis + RESTART_WINDOW_MILLIS) {
                    return Result.unauthorized("This server started less than a minute ago and cannot yet tell a "
                        + "new sign-in from a replay of one made before the restart: this request's timestamp ("
                        + auth.timestamp + ") is earlier than 30 seconds after the server started ("
                        + java.time.Instant.ofEpochMilli(startedAtMillis) + "). Retry in up to a minute. If it "
                        + "still fails then, this machine's clock is behind the server's: correct the clock.");
                }
            } else if ("2".equals(auth.v)) {
                // M14: v2 signed no audience, so a signature made for one server worked at any
                // other within the ±30s window. Removed in 0.1.11, the release that adds v3.
                Log.warn("rejected ops auth protocol v2 (removed in 0.1.11) — upgrade the brace CLI"
                    + " (key " + OpsKeys.fingerprint(auth.publicKey) + ")");
                return Result.unauthorized("Ops auth protocol v2 was removed in 0.1.11 because a signature "
                    + "wasn't bound to a server — upgrade the brace CLI to 0.1.11 or later");
            } else if (auth.v == null || "1".equals(auth.v)) {
                // v1 signed the timestamp alone: not bound to the key, no nonce, so a captured
                // request was replayable verbatim within the ±30s window to mint a fresh token at
                // the key's full scope ceiling. It shipped in 0.1.6, was deprecated in 0.1.7 with
                // notice that a future release would reject it, and that release is this one (M5).
                Log.warn("rejected ops auth protocol v1 (removed in 0.1.8) — upgrade the brace CLI"
                    + " (key " + OpsKeys.fingerprint(auth.publicKey) + ")");
                return Result.unauthorized("Ops auth protocol v1 was removed in 0.1.8 because it is "
                    + "replayable — upgrade the brace CLI to 0.1.7 or later");
            } else {
                return Result.unauthorized("Unsupported ops auth protocol version \"" + auth.v
                    + "\" — this server accepts v3");
            }

            // Issue token — check for client-requested TTL
            int requestedTtl = auth.ttlSeconds != null ? auth.ttlSeconds : 3600; // default: 1 hour
            if (requestedTtl <= 0) {
                // The cap below bounded the top but not the bottom, so ttlSeconds: -1 minted a
                // token that was already expired — fail-closed, but a confusing 401 rather than
                // an error naming the cause (L7).
                return Result.badRequest("ttlSeconds must be positive");
            }
            int ttl = Math.min(requestedTtl, 86400); // cap at 24 hours

            // Scope: cap the requested scope at the key's ceiling. A read-only key can
            // never mint a control token, so the grant is min(requested, ceiling). When
            // the client requests no scope, it gets the full ceiling.
            OpsScope ceiling = authorizedKeys.getOrDefault(auth.publicKey, OpsScope.CONTROL);
            // No scope requested -> the ceiling; a scope that is present but unrecognised -> READ.
            OpsScope requested = auth.scope == null || auth.scope.isBlank()
                ? ceiling : OpsScope.parse(auth.scope, OpsScope.READ);
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
     * Record {@code nonce} in {@code brace_counters}, the one small framework table with an atomic
     * "add and tell me the count" and an expiry (no migration needed).
     *
     * @return true on first use, false if any instance has recorded it before (a replay), null
     *         when there is no database or the write failed (the caller then applies the restart
     *         window; an ops sign-in must not depend on the database being up)
     */
    private Boolean recordDurably(String nonce, long nowMillis) {
        Counters counters = durableNonces;
        if (counters == null) {
            return null;
        }
        try {
            // The nonce is client text of up to 256 characters: store its digest.
            String key = "ops-nonce:" + java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            long uses = counters.incrementAndGet(key, 1, java.time.Instant.ofEpochMilli(nowMillis + NONCE_TTL_MILLIS));
            // Expired rows are swept by the rate limiter's cleanup when there is one; make sure
            // they go either way. Rare (one sweep at most every ten minutes) and best effort.
            if (nowMillis - lastNonceRowSweepMillis > 600_000) {
                lastNonceRowSweepMillis = nowMillis;
                try {
                    counters.sweepExpired();
                } catch (RuntimeException ignored) {
                    // the next sweep retries
                }
            }
            return uses == 1;
        } catch (Exception e) {
            Log.warn("ops auth: could not record the sign-in nonce in the database, using this instance's "
                + "memory only — " + Redactor.redactMessage(e.getMessage()));
            return null;
        }
    }

    /**
     * POST /ops/auth/login-token — issue a short-lived, single-use browser login token.
     * Requires valid Bearer token authentication.
     */
    public Result loginToken(Request req) {
        var claims = req.opsClaims();
        if (claims == null) return Result.unauthorized("Invalid ops key");
        // L3: only a bearer credential (the CLI) may mint one. The dashboard's session cookie is
        // sent by any same-origin request, so script on any app page could otherwise mint a
        // login token here, read it from the response, and carry it off the browser.
        if (claims.type() != OpsToken.Type.BEARER) {
            return Result.json(Map.of("error", "bearer_token_required"), 403).header("Cache-Control", "no-store");
        }

        // Stateless, short-lived HMAC token — no server-side store, so exchange works on any
        // instance behind a load balancer (B5). It conveys exactly the access the caller
        // already holds: minted at the caller's scope and key id (H1), which exchange()
        // preserves into the browser session cookie — a read key yields a read-only,
        // attributed browser session. READ-gated because the dashboard itself is READ;
        // no escalation is possible since the minted token never exceeds the caller's scope.
        // Never outlives the bearer it came from. The session the exchange mints from it is a
        // cookie-only credential that can't mint anything itself, so no chain renews itself.
        long now = System.currentTimeMillis() / 1000;
        long exp = Math.min(now + LOGIN_TOKEN_TTL_SECONDS, claims.exp());
        String loginToken = OpsToken.create(tokenSecret, exp, claims.scope(), claims.kid(), OpsToken.Type.LOGIN);
        Instant expiry = Instant.ofEpochSecond(exp);

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

        var claims = verifyForKnownKey(loginToken);
        if (claims == null || claims.type() != OpsToken.Type.LOGIN) {
            return Result.unauthorized("Invalid or expired login token");
        }

        // Mint an 8h ops session token (one workday), preserving the login token's scope (H1).
        // 8h rather than 24h: with scope-preservation the session is already bound to the
        // caller's ceiling; shorter TTL limits the damage window of a stolen cookie further.
        String sessionToken = OpsToken.create(tokenSecret, System.currentTimeMillis() / 1000 + SESSION_TTL_SECONDS,
            claims.scope(), claims.kid(), OpsToken.Type.SESSION);

        // ?next=analytics lands on the analytics page instead (brace ops dashboard --analytics).
        // An allowlist, not a path: an open redirect here would bounce a fresh ops session anywhere.
        boolean toAnalytics = analytics != null && "analytics".equals(req.queryParam("next"));
        var result = Redirect.to(toAnalytics ? "/ops/analytics" : "/ops/dashboard");
        // Scoped to /ops (L1/L2): the ops session token is an operator credential and has no
        // business being attached to every application request, where any handler can read it
        // via req.cookie(...) and any request logging can capture it. Every endpoint that
        // accepts it lives under /ops.
        result.cookie(OPS_COOKIE_NAME, sessionToken, SESSION_TTL_SECONDS, true, true, "Strict", "/ops");
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

        var include = includeParams(req);

        var data = new LinkedHashMap<String, Object>();

        // App info
        var app = new LinkedHashMap<String, Object>();
        app.put("framework", "brace");
        app.put("instanceId", instanceId);   // which box served this snapshot (P3, fleet visibility)
        app.put("uptime", formatDuration(Duration.between(stats.startedAt(), Instant.now())));
        app.put("startedAt", stats.startedAt().toString());
        app.put("braceVersion", BraceVersion.get());
        app.put("javaVersion", System.getProperty("java.version"));
        data.put("app", app);

        // HTTP stats
        var http = new LinkedHashMap<String, Object>();
        var statusCodes = stats.statusCodeCounts();
        http.put("statusCodes", statusCodes);
        http.put("totalRequests", statusCodes.values().stream().mapToLong(Long::longValue).sum());
        // Rate from the minute ring; absent until the first full minute has rotated in.
        var reqRate = stats.requestRate();
        if (reqRate != null) {
            var rpm = new LinkedHashMap<String, Object>();
            rpm.put("lastMinute", reqRate.lastMinute());
            rpm.put("avg", Math.round(reqRate.avgPerMinute() * 100.0) / 100.0);
            rpm.put("windowMinutes", reqRate.windowMinutes());
            http.put("requestsPerMinute", rpm);
        }
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
        // Busiest routes over the last few full minutes (windowed per-minute counts, not
        // lifetime). Requests that matched no route are folded into one "(unmatched)" entry,
        // static files into one "(static)" entry.
        var topRoutes = stats.topRoutes(OpsDashboard.TOP_ROUTES_WINDOW_MINUTES, 5);
        var topList = new ArrayList<Map<String, Object>>();
        for (var r : topRoutes) {
            var t = new LinkedHashMap<String, Object>();
            t.put("route", r.route());
            t.put("count", r.count());
            t.put("perMinute", Math.round(r.perMinute() * 100.0) / 100.0);
            t.put("sharePct", Math.round(r.share() * 1000.0) / 10.0);
            topList.add(t);
        }
        http.put("topRoutes", topList);
        http.put("topRoutesWindowMinutes", topRoutes.isEmpty()
            ? OpsDashboard.TOP_ROUTES_WINDOW_MINUTES : topRoutes.getFirst().windowMinutes());
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
                m.put("p95Ms", Math.round(snap.p95LatencyMs() * 100.0) / 100.0);
                minutes.add(m);
            }
            timeseries.put("minutes", minutes);
            data.put("timeseries", timeseries);
        }

        // Analytics: today's headline numbers, when app.analytics() is on. A database hiccup here
        // must not take the whole status snapshot down with it.
        if (analytics != null) {
            var a = new LinkedHashMap<String, Object>();
            try {
                var today = analytics.summary();
                a.put("todayVisitors", today.visitors());
                a.put("todayPageviews", today.pageviews());
                a.put("live", today.live());
            } catch (RuntimeException e) {
                a.put("error", "unavailable");
            }
            data.put("analytics", a);
        }

        return Json.of(data);
    }

    public Result dashboard(Request req) {
        var claims = req.opsClaims();
        if (claims == null) return Result.unauthorized("Invalid ops key");
        // No token is minted into the page (L3): it used to embed a 2h bearer token for htmx,
        // which any script on the app's origin could fetch this page to read. The dashboard's
        // requests are same-origin under /ops, so they carry the httpOnly session cookie instead.
        Analytics.Summary today = null;
        if (analytics != null) {
            try {
                today = analytics.summary();
            } catch (RuntimeException e) {
                // The ops dashboard must render even when the analytics query fails.
            }
        }
        return Result.html(OpsDashboard.html(claims.scope(), stats, jobScheduler, mailer,
            errorStore, cache, profiler, analytics != null, today));
    }

    public Result routes(Request req) {

        var routeList = new ArrayList<Map<String, Object>>();
        var all = new ArrayList<Route>(router.routes());
        all.addAll(webSocketRoutes);
        for (var route : all) {
            var r = new LinkedHashMap<String, Object>();
            r.put("method", route.method());
            r.put("pattern", route.pattern());
            if (route.name() != null) r.put("name", route.name());
            r.put("access", route.accessLabel());
            routeList.add(r);
        }
        for (var mapping : staticMappings.get()) {
            var r = new LinkedHashMap<String, Object>();
            r.put("method", "STATIC");
            r.put("pattern", mapping.pattern());
            r.put("access", mapping.accessLabel());
            if (mapping.mode() == StaticFiles.UNTRUSTED) r.put("mode", "untrusted");
            routeList.add(r);
        }
        return Json.of(routeList);
    }

    public Result logs(Request req) {

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
        long id;
        try {
            id = req.longPathParam("id");
        } catch (NumberFormatException e) {
            // Like errorDetail: a malformed id is a 404, not a 500 that records a fresh
            // framework error — re-reddening the count this endpoint exists to clear.
            return Result.notFound();
        }
        Map<String, Object> resolved;
        if (errorStore == null) {
            // No database: resolving removes the in-memory record, so errors.count (and
            // the brace status exit code) recovers instead of staying red until restart.
            var rec = stats.resolveError(id);
            resolved = rec == null ? null : inMemoryDetail(rec);
            if (resolved != null) resolved.put("resolvedAt", Instant.now());
        } else {
            resolved = errorStore.resolve(id);
        }
        if (wantsJson(req)) {
            return resolved == null ? Result.notFound() : Json.of(resolved);
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

    public void setAnalytics(Analytics analytics) {
        this.analytics = analytics;
    }

    /** GET /ops/analytics?range=today|7d|30d|12mo&metric=visitors|pageviews — the analytics page. */
    public Result analytics(Request req) {
        String range = req.queryParam("range", "30d");
        if (!Analytics.RANGES.contains(range)) return Result.badRequest("range must be one of " + Analytics.RANGES);
        String metric = "pageviews".equals(req.queryParam("metric")) ? "pageviews" : "visitors";
        boolean ignored = req.cookie(Analytics.IGNORE_COOKIE) != null;
        return Result.html(AnalyticsDashboard.html(analytics.cachedReport(range), metric, ignored));
    }

    /** GET /ops/analytics/data?range=today|7d|30d|12mo — the same report as JSON (the CLI reads this). */
    public Result analyticsData(Request req) {
        String range = req.queryParam("range", "7d");
        if (!Analytics.RANGES.contains(range)) return Result.badRequest("range must be one of " + Analytics.RANGES);
        return Json.of(analytics.cachedReport(range));
    }

    /**
     * POST /ops/analytics/ignore — set (or clear, with {@code on=0}) the cookie that keeps this
     * browser's visits out of the counts. The ops cookie is scoped to /ops, so public pages can't
     * recognise an operator from it; this separate marker is scoped to / for that reason.
     */
    public Result analyticsIgnore(Request req) {
        boolean on = !"0".equals(req.formParam("on"));
        String range = req.formParam("range");
        String back = "/ops/analytics" + (range != null && Analytics.RANGES.contains(range) ? "?range=" + range : "");
        var result = Redirect.to(back);
        boolean secure = !BraceHandler.isLoopbackHost(req.host());
        result.cookie(Analytics.IGNORE_COOKIE, on ? "1" : "0", on ? 365 * 24 * 3600 : 0, true, secure, "Lax", "/");
        return result;
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
        if (regressionTracker == null) return Result.notFound();
        String id = req.pathParam("id");
        if (id == null || !regressionTracker.acknowledge(id)) return Result.notFound();
        return Json.of(Map.of("acknowledged", true, "id", id));
    }

    public Result clearCache(Request req) {
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

    /** A verified ops credential, and whether it came from the browser session cookie. */
    private record Credential(OpsToken.Claims claims, boolean cookie) {}

    /**
     * Verify the request's token and return its claims, or {@code null} if unauthenticated.
     * Two credential channels are accepted:
     * <ol>
     *   <li>{@code Authorization: Bearer <token>} header — the standard machine-to-machine
     *       channel used by the CLI.</li>
     *   <li>The {@code __brace_ops_session} httpOnly cookie — set by the
     *       {@code /ops/auth/exchange} browser handoff, and the only credential the dashboard
     *       and analytics pages use (L3: no token is written into their HTML).</li>
     * </ol>
     * {@code ?token=} query-param credentials are intentionally NOT accepted here. Tokens in
     * URLs leak via proxy access logs, browser history, and the {@code Referer} header on
     * outbound links. The exchange endpoint ({@code /ops/auth/exchange}) is the only endpoint
     * that reads {@code ?token=} because it is the browser-redirect handoff — there is no other
     * channel to carry a credential into a plain GET redirect — and the login token it accepts
     * is short-lived (60s) and scope-capped. See {@link #exchange} for the full rationale.
     *
     * <p>This is the single choke point where a request's identity (kid) and scope are resolved.
     */
    private Credential authenticate(Request req) {
        if (tokenSecret == null) return null;

        // Check Authorization: Bearer <token> header
        // Each channel takes only its own token type (L3): a login or session token presented as a
        // bearer, or a bearer token planted in the cookie, is no credential at all.
        var authHeader = req.header("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            var claims = verifyForKnownKey(authHeader.substring(7));
            if (claims != null && claims.type() == OpsToken.Type.BEARER) return new Credential(claims, false);
        }

        // Check ops session cookie (set by /ops/auth/exchange)
        String cookieToken = req.cookie(OPS_COOKIE_NAME);
        if (cookieToken != null) {
            var claims = verifyForKnownKey(cookieToken);
            if (claims != null && claims.type() == OpsToken.Type.SESSION) return new Credential(claims, true);
        }

        return null;
    }

    /**
     * Verify a token's HMAC and expiry, and that it was minted for a key that is still
     * authorized. Every token this handler mints carries the minting key's fingerprint as
     * {@code kid} (auth, login-token and exchange all do), so a token
     * with no {@code kid} is not one we issued and is rejected rather than trusted. The returned
     * scope is capped at the key's current ceiling: a CONTROL token minted before its key was
     * lowered to {@code scope:read} acts as READ from then on.
     */
    private OpsToken.Claims verifyForKnownKey(String token) {
        var claims = OpsToken.verify(token, tokenSecret);
        if (claims == null || claims.kid() == null) return null;
        OpsScope ceiling = ceilingByKid.get(claims.kid());
        if (ceiling == null) return null;
        OpsScope scope = claims.scope().min(ceiling);
        return scope == claims.scope() ? claims : new OpsToken.Claims(claims.exp(), scope, claims.kid(), claims.type());
    }

    /**
     * The route-level access requirement for an ops endpoint (H1): every /ops route is registered
     * with one, so an endpoint can't be added without authentication, and the handlers carry no
     * auth code of their own. Verifies the credential, caps it at the key's current ceiling,
     * audits it (granted or scope-denied, attributed to the key's fingerprint), and attaches the
     * claims to the request for the handlers that need them ({@code loginToken},
     * {@code dashboard}). A rejection is {@code no-store}, like every ops response.
     *
     * <p>A mutating request authenticated by the session cookie must come from a page on this
     * origin (L3). The dashboard's CONTROL buttons now post with the cookie alone, and
     * {@code SameSite=Strict} keeps it off cross-site requests but not off a sibling subdomain's
     * (same-site) form. A bearer header needs no such check: a cross-origin page can't set it.
     * The ops routes are {@code .csrf(false)} because the CLI posts with a bearer token, so the
     * framework's own cross-site check doesn't cover them.
     */
    Access access(OpsScope required) {
        return Access.internal("ops:" + required.wire(), req -> {
            var credential = authenticate(req);
            if (credential == null) {
                return Result.unauthorized("Invalid ops key").header("Cache-Control", "no-store");
            }
            var claims = credential.claims();
            if (credential.cookie() && BraceHandler.isMutating(req.method()) && !sameOrigin(req)) {
                OpsAudit.record(req.method(), req.path(), claims.kid(), claims.scope(), required, false);
                return Result.json(Map.of("error", "cross_origin_request"), 403).header("Cache-Control", "no-store");
            }
            boolean granted = claims.scope().grants(required);
            OpsAudit.record(req.method(), req.path(), claims.kid(), claims.scope(), required, granted);
            if (!granted) {
                return Result.unauthorized("Invalid ops key").header("Cache-Control", "no-store");
            }
            req.setOpsClaims(claims);
            return null;
        });
    }

    /**
     * Whether a v3 auth signature's origin names this server. With origins configured
     * ({@code app.opsOrigins}, required in prod) it must be one of them, exactly: the request's
     * {@code Host} and {@code X-Forwarded-Host} play no part, since the client sending a relayed
     * signature can choose those. Without (dev, tests) its host must be the one the request was
     * addressed to; the port isn't compared, since behind a proxy the app listens on another.
     */
    private boolean originIsThisServer(Request req, String origin) {
        if (!pinnedOrigins.isEmpty()) return pinnedOrigins.contains(origin);
        String signedHost = ProxyHeaders.hostOf(origin);
        String host = req.host();
        if (signedHost != null && host != null && signedHost.equalsIgnoreCase(host)) return true;
        if (host != null && BraceHandler.isLoopbackHost(host)) {
            String pageHost = ProxyHeaders.httpsPageHost(origin, null);
            if (pageHost != null) ProxyHeaders.warnHostRewrite(pageHost);
        }
        return false;
    }

    /**
     * Whether a browser request came from a page on this origin: {@code Sec-Fetch-Site} when
     * present (every current browser sends it), otherwise the {@code Origin} host must be the
     * host the client addressed. With neither it is refused: a browser sends at least
     * {@code Origin} on every POST, and non-browser clients authenticate with a bearer token.
     */
    static boolean sameOrigin(Request req) {
        String site = req.header("Sec-Fetch-Site");
        if (site != null) return site.strip().equalsIgnoreCase("same-origin");
        String origin = req.header("Origin");
        if (origin == null || origin.isBlank() || !origin.contains("://")) return false;
        String requestAuthority = req.hostWithPort();
        if (requestAuthority == null) return false;
        // Host and port, not the scheme: behind a TLS-terminating proxy the request's own scheme
        // is http whatever the browser used. Another port on the same host is another origin
        // (and gets the ops cookie, which isn't port-scoped), so it doesn't pass.
        String originAuthority = Brace.normalizeOrigin(origin);
        originAuthority = originAuthority.substring(originAuthority.indexOf("://") + 3);
        if (originAuthority.equals(withoutDefaultPort(requestAuthority))) return true;
        // A proxy rewriting Host to loopback makes a same-origin page look foreign; say so once.
        String host = req.host();
        if (host != null && BraceHandler.isLoopbackHost(host)) {
            String pageHost = ProxyHeaders.httpsPageHost(origin, null);
            if (pageHost != null) ProxyHeaders.warnHostRewrite(pageHost);
        }
        return false;
    }

    private static String withoutDefaultPort(String authority) {
        if (authority.endsWith(":443")) return authority.substring(0, authority.length() - 4);
        if (authority.endsWith(":80")) return authority.substring(0, authority.length() - 3);
        return authority;
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
     * Per-instance, best-effort seen-nonce set for v3 ops auth replay suppression.
     * Maps nonce to its expiry (epoch millis) and the key that used it. Entries are only
     * recorded after signature verification, so only holders of an authorized key can fill it.
     *
     * <p>Bounded two ways, both failing closed (a new nonce is rejected rather than a live one
     * evicted): an overall cap, and a per-key quota inside it (L16), so one key, including a
     * read-scoped one, can exhaust only its own share and never another operator's login
     * ({@link OpsHandler#nonceQuotaPerKey} keeps the shares within the cap).
     *
     * <p>Every call does a bounded amount of work (final review: a refused call swept the whole
     * map inside the monitor, 673 µs at 100,000 entries). Entries are kept in the order they were
     * recorded, which is the order they expire in since they all live {@code ttlMillis}, so the
     * expired ones are at the head of a queue; a call removes at most
     * {@link #MAX_EVICTIONS_PER_CALL} of them. A call adds at most one entry and removes up to
     * that many, so a backlog of expired entries only shrinks.
     *
     * <p>Per instance, and lost on restart; {@code OpsHandler.auth} layers a database record and
     * a restart window on top (see {@code seenNonces} there).
     */
    static final class NonceCache {
        static final int MAX_EVICTIONS_PER_CALL = 64;

        enum Outcome { FRESH, REPLAY, FULL }

        private record Entry(String nonce, long expiresAt, String key) {}

        private final java.util.HashMap<String, Entry> seen = new java.util.HashMap<>();
        private final java.util.ArrayDeque<Entry> byExpiry = new java.util.ArrayDeque<>();
        private final java.util.HashMap<String, Integer> perKey = new java.util.HashMap<>();
        private final long ttlMillis;
        private final int maxSize;
        private final int maxPerKey;

        NonceCache(long ttlMillis, int maxSize) {
            this(ttlMillis, maxSize, maxSize);
        }

        NonceCache(long ttlMillis, int maxSize, int maxPerKey) {
            this.ttlMillis = ttlMillis;
            this.maxSize = maxSize;
            this.maxPerKey = maxPerKey;
        }

        boolean checkAndRecord(String nonce, long nowMillis) {
            return checkAndRecord("", nonce, nowMillis);
        }

        /** True when {@code nonce} was fresh and is now recorded; see {@link #record}. */
        boolean checkAndRecord(String key, String nonce, long nowMillis) {
            return record(key, nonce, nowMillis) == Outcome.FRESH;
        }

        /**
         * Record {@code nonce}, used by {@code key} (a key fingerprint), if it is fresh.
         * {@link Outcome#REPLAY} when it was already seen, {@link Outcome#FULL} when the key's
         * quota or the overall cap is full (fail closed).
         */
        synchronized Outcome record(String key, String nonce, long nowMillis) {
            evictExpired(nowMillis);
            Entry existing = seen.get(nonce);
            if (existing != null && existing.expiresAt >= nowMillis) return Outcome.REPLAY;
            if (seen.size() >= maxSize || perKey.getOrDefault(key, 0) >= maxPerKey) {
                // An expired entry for this very nonce, not yet reached by the queue, still holds
                // its slot; either way the caller retries with a new nonce a moment later.
                return Outcome.FULL;
            }
            var entry = new Entry(nonce, nowMillis + ttlMillis, key);
            Entry replaced = seen.put(nonce, entry);
            if (replaced != null) release(replaced.key); // expired, still queued: its queue slot is skipped later
            byExpiry.addLast(entry);
            perKey.merge(key, 1, Integer::sum);
            return Outcome.FRESH;
        }

        /** Remove expired entries from the head of the queue, at most {@link #MAX_EVICTIONS_PER_CALL}. */
        private void evictExpired(long nowMillis) {
            for (int i = 0; i < MAX_EVICTIONS_PER_CALL; i++) {
                Entry head = byExpiry.peekFirst();
                if (head == null || head.expiresAt >= nowMillis) return;
                byExpiry.pollFirst();
                // Identity: the nonce may have been recorded again since (see record).
                if (seen.get(head.nonce) == head) {
                    seen.remove(head.nonce);
                    release(head.key);
                }
            }
        }

        private void release(String key) {
            perKey.computeIfPresent(key, (k, n) -> n <= 1 ? null : n - 1);
        }

        synchronized int size() {
            return seen.size();
        }
    }
}

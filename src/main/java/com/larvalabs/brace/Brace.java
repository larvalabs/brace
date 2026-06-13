package com.larvalabs.brace;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.VirtualThreads;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class Brace {

    private int port = 8080;
    private boolean showBanner = true;
    private final Router router = new Router();

    /** Package-private accessor used by tests that need to inspect/mutate route metadata. */
    List<Route> routesForTesting() { return router.routes(); }
    private final List<Middleware.BoundBefore> beforeMiddleware = new ArrayList<>();
    private final List<Middleware.BoundAfter> afterMiddleware = new ArrayList<>();
    private final List<BraceHandler.StaticFileMapping> staticFileMappings = new ArrayList<>();
    private DatabaseFactory databaseFactory;
    private String sessionSecret;
    private String opsSecret;
    private String deployMarker;
    private SessionOptions sessionOptions;
    private TemplateEngine templateEngine;
    private Mailer mailer;
    private Server server;
    private ServerConnector connector;
    private final JobScheduler jobScheduler = new JobScheduler();
    private final JobPoller jobPoller = new JobPoller();
    private String opsKeysPath;
    private Stats stats = new Stats();
    private JfrProfiler profiler;
    private ErrorStore errorStore;
    private boolean opsProfilerEnabled = true;
    private String instanceId;
    private OpsHandler opsHandler;
    private Cache cache;
    private Storage storage;
    private final Map<String, Function<WsContext, Object>> wsRoutes = new LinkedHashMap<>();
    private WsRegistry wsRegistry;
    private long wsMaxQueuedBytes = 4L * 1024 * 1024; // M18: per-connection outgoing backlog cap (4 MB)
    private long maxUploadSize = BraceHandler.DEFAULT_MAX_UPLOAD_SIZE;
    private int jobRetentionDays = 7;
    private String httpStatsInterval = "60s";
    private String cacheStatsInterval = "60s";
    private String mailerStatsInterval = "60s";
    private TrustedProxies trustedProxies;
    private int regressionsWarmupSeconds = 30;
    private final List<Notifier> regressionNotifiers = new ArrayList<>();

    public static Brace app() {
        return new Brace();
    }

    public static Cache cache() {
        return new Cache();
    }

    public Brace cache(Cache cache) {
        this.cache = cache;
        return this;
    }

    /**
     * Configure the cache with a specific backend — e.g.
     * {@code app.cache(CacheBackend.postgres(dbFactory))} for a shared, cross-server-consistent
     * cache. Defaults to in-process if never called.
     */
    public Brace cache(CacheBackend backend) {
        this.cache = new Cache(backend);
        return this;
    }

    public Brace storage(Storage storage) {
        this.storage = storage;
        return this;
    }

    public Brace trustedProxies(String... cidrs) {
        this.trustedProxies = new TrustedProxies(cidrs);
        return this;
    }

    public Brace trustedProxies(List<String> cidrs) {
        this.trustedProxies = new TrustedProxies(cidrs);
        return this;
    }

    public Brace port(int port) {
        this.port = port;
        return this;
    }

    public int port() {
        return port;
    }

    public Brace banner(boolean show) {
        this.showBanner = show;
        return this;
    }

    public Brace database(DatabaseFactory factory) {
        this.databaseFactory = factory;
        return this;
    }

    DatabaseFactory databaseFactory() {
        return databaseFactory;
    }

    String sessionSecret() {
        return sessionSecret;
    }

    SessionOptions sessionOptions() {
        return sessionOptions;
    }

    Mailer mailer() {
        return mailer;
    }

    public Stats stats() {
        return stats;
    }


    public Brace sessions(String secret) {
        validateSecret(secret, "session");
        this.sessionSecret = secret;
        this.sessionOptions = SessionOptions.of(secret);
        return this;
    }

    public Brace sessions(SessionOptions options) {
        validateSecret(options.secret(), "session");
        this.sessionSecret = options.secret();
        this.sessionOptions = options;
        return this;
    }

    /**
     * Explicitly set the secret used to sign ops session tokens/cookies. Set this when ops is
     * enabled on a multi-instance deployment that has no session secret (e.g. a bearer-token API);
     * it must be identical on every instance so an ops login on one box validates on another. When
     * unset, the ops secret is derived from the session secret if present, else generated per-process
     * (single-instance only — see {@link #start()}).
     */
    public Brace opsSecret(String secret) {
        validateSecret(secret, "ops");
        this.opsSecret = secret;
        return this;
    }

    /**
     * Set the deploy marker — a value identical on every instance of one deploy and different across
     * deploys (e.g. a git sha or release tag). It anchors the regression-detection baseline (B6): a
     * rolling deploy classifies the same error identically on every box, and a new deploy
     * re-evaluates regressions from a clean baseline. Falls back to the {@code BRACE_DEPLOY} env var,
     * then {@code "default"} (multi-instance dedup still works; deploy-over-deploy reset does not).
     */
    public Brace deploy(String marker) {
        this.deployMarker = marker;
        return this;
    }

    private String resolveDeployMarker() {
        if (deployMarker != null && !deployMarker.isBlank()) return deployMarker.trim();
        String env = System.getenv("BRACE_DEPLOY");
        if (env != null && !env.isBlank()) return env.trim();
        return "default";
    }

    /**
     * Resolve the ops-token signing secret. Priority: explicit {@link #opsSecret(String)} →
     * derived from the session secret → per-process random (with a warning). The first two are
     * shared config, identical on every instance, so an ops login on one box validates on another
     * (B5). The fallback is per-process and breaks the ops login flow behind a load balancer.
     */
    private String resolveOpsSecret() {
        if (opsSecret != null) {
            return opsSecret;
        }
        if (sessionSecret != null) {
            return OpsToken.deriveSecret(sessionSecret);
        }
        Log.warn("Ops is enabled without a shared secret (no .opsSecret(...) or .sessions(...)); " +
            "the ops token secret is per-process, so the browser login flow will fail behind a load " +
            "balancer. Set a shared secret for multi-instance deployments.");
        return OpsToken.generateSecret();
    }

    private void validateSecret(String secret, String type) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException(type + " secret cannot be null or empty");
        }
        if (secret.length() < 32) {
            throw new IllegalArgumentException(
                type + " secret must be at least 32 characters (current: " + secret.length() + ")");
        }
        // Warn about obvious placeholder values (including old scaffolds)
        String lower = secret.toLowerCase();
        if (lower.contains("changeme") || lower.contains("change-me") || lower.contains("change_me") ||
            lower.contains("CHANGE-ME-to-a-random-string-at-least-32-chars") ||
            lower.contains("secret") || lower.contains("password") ||
            lower.contains("test") || lower.equals("placeholder") || lower.matches("^[a-z]+$")) {
            Log.warn("Weak " + type + " secret detected - use a cryptographically random value in production");
        }
    }

    public Brace templates(String path) {
        this.templateEngine = new TemplateEngine(path);
        View.setEngine(this.templateEngine);
        return this;
    }

    public Brace mailer(Mailer mailer) {
        this.mailer = mailer;
        return this;
    }

    public Brace ops(String keysPath) {
        this.opsKeysPath = keysPath;
        return this;
    }

    /**
     * Enables or disables the always-on JFR profiler that backs the JVM panels of
     * {@code /ops/dashboard} (CPU, GC pauses, method/allocation sampling). Default
     * {@code true}: continuous sampling costs roughly 0.5–2% CPU plus one thread, which
     * is usually worth it for production diagnostics. Disable on CPU-constrained
     * instances; the dashboard then falls back to basic runtime heap numbers and the
     * {@code jvm.*} ops metrics are not collected.
     */
    public Brace opsProfiler(boolean enabled) {
        this.opsProfilerEnabled = enabled;
        return this;
    }

    /**
     * Seconds after startup during which a brand-new error kind is <em>not</em> flagged as a
     * regression — suppresses cold-boot noise (e.g. a transient DB-connect failure). Default 30.
     */
    public Brace regressionsWarmup(int seconds) {
        this.regressionsWarmupSeconds = seconds;
        return this;
    }

    /**
     * Register notifiers fired once when a new error kind first appears since startup
     * (e.g. {@code new WebhookNotifier(slackUrl)} for Slack, {@code new MailerNotifier(mailer, addr)}).
     * A {@link LogNotifier} is always attached when ops + a database are enabled, so regressions
     * are recorded regardless. Additive across calls.
     */
    public Brace notifyRegressions(Notifier... notifiers) {
        this.regressionNotifiers.addAll(List.of(notifiers));
        return this;
    }

    public Brace opsStatsInterval(String group, String interval) {
        switch (group) {
            case "http" -> httpStatsInterval = interval;
            case "cache" -> cacheStatsInterval = interval;
            case "mailer" -> mailerStatsInterval = interval;
            default -> throw new IllegalArgumentException("Unknown stats group: " + group);
        }
        return this;
    }

    public Brace staticFiles(String urlPrefix, String directory) {
        staticFileMappings.add(new BraceHandler.StaticFileMapping(urlPrefix, directory));
        return this;
    }

    public Brace maxUploadSize(String size) {
        this.maxUploadSize = parseSize(size);
        return this;
    }

    public Brace maxUploadSize(long bytes) {
        this.maxUploadSize = bytes;
        return this;
    }

    public Brace ws(String path, Function<WsContext, Object> handlerFactory) {
        wsRoutes.put(path, handlerFactory);
        return this;
    }

    /**
     * Cap on the bytes a single WebSocket connection may have queued-but-not-yet-flushed before it is
     * force-closed as a slow consumer (M18). A client that stops reading would otherwise make its
     * broadcast frames pile up in Jetty's outgoing queue without bound — a per-connection memory leak.
     * Default 4 MB; the bound is per connection, so a slow client never blocks healthy room members.
     */
    public Brace wsMaxQueuedBytes(long bytes) {
        this.wsMaxQueuedBytes = bytes;
        return this;
    }

    // Job scheduling

    public Brace every(String interval, String name, Job job) {
        jobScheduler.every(interval, name, job);
        return this;
    }

    public Brace daily(String time, String name, Job job) {
        jobScheduler.daily(time, name, job);
        return this;
    }

    /**
     * How many days finished (completed or failed) durable jobs stay in {@code scheduled_jobs}
     * before the framework's daily prune deletes them. Default 7. Set {@code 0} (or negative)
     * to keep finished rows forever — the pre-0.1.7 behavior. Rows another job still depends
     * on are kept regardless of age.
     */
    public Brace jobRetention(int days) {
        this.jobRetentionDays = days;
        return this;
    }

    // Route registration

    public RouteConfig get(String pattern, Handler handler) {
        return new RouteConfig(this, router.add("GET", pattern, handler));
    }

    public RouteConfig post(String pattern, Handler handler) {
        return new RouteConfig(this, router.add("POST", pattern, handler));
    }

    public RouteConfig put(String pattern, Handler handler) {
        return new RouteConfig(this, router.add("PUT", pattern, handler));
    }

    public RouteConfig delete(String pattern, Handler handler) {
        return new RouteConfig(this, router.add("DELETE", pattern, handler));
    }

    // Route registration with Database

    public RouteConfig get(String pattern, DbHandler handler) {
        return new RouteConfig(this, router.add("GET", pattern, handler, Invoker.fromDbFunction(handler)));
    }

    public RouteConfig post(String pattern, DbHandler handler) {
        return new RouteConfig(this, router.add("POST", pattern, handler, Invoker.fromDbFunction(handler)));
    }

    public RouteConfig put(String pattern, DbHandler handler) {
        return new RouteConfig(this, router.add("PUT", pattern, handler, Invoker.fromDbFunction(handler)));
    }

    public RouteConfig delete(String pattern, DbHandler handler) {
        return new RouteConfig(this, router.add("DELETE", pattern, handler, Invoker.fromDbFunction(handler)));
    }

    // Route registration with Session

    public RouteConfig get(String pattern, SessionHandler handler) {
        return new RouteConfig(this, router.add("GET", pattern, handler, Invoker.fromSessionFunction(handler)));
    }

    public RouteConfig post(String pattern, SessionHandler handler) {
        return new RouteConfig(this, router.add("POST", pattern, handler, Invoker.fromSessionFunction(handler)));
    }

    public RouteConfig put(String pattern, SessionHandler handler) {
        return new RouteConfig(this, router.add("PUT", pattern, handler, Invoker.fromSessionFunction(handler)));
    }

    public RouteConfig delete(String pattern, SessionHandler handler) {
        return new RouteConfig(this, router.add("DELETE", pattern, handler, Invoker.fromSessionFunction(handler)));
    }

    // Route registration with read-only Database (no transaction)

    public RouteConfig get(String pattern, ReadDbHandler handler) {
        return new RouteConfig(this, router.add("GET", pattern, handler, Invoker.fromReadDbFunction(handler)));
    }

    public RouteConfig post(String pattern, ReadDbHandler handler) {
        return new RouteConfig(this, router.add("POST", pattern, handler, Invoker.fromReadDbFunction(handler)));
    }

    public RouteConfig put(String pattern, ReadDbHandler handler) {
        return new RouteConfig(this, router.add("PUT", pattern, handler, Invoker.fromReadDbFunction(handler)));
    }

    public RouteConfig delete(String pattern, ReadDbHandler handler) {
        return new RouteConfig(this, router.add("DELETE", pattern, handler, Invoker.fromReadDbFunction(handler)));
    }

    // Route registration with read-only Database + Session (no transaction)

    public RouteConfig get(String pattern, ReadFullHandler handler) {
        return new RouteConfig(this, router.add("GET", pattern, handler, Invoker.fromReadFullFunction(handler)));
    }

    public RouteConfig post(String pattern, ReadFullHandler handler) {
        return new RouteConfig(this, router.add("POST", pattern, handler, Invoker.fromReadFullFunction(handler)));
    }

    public RouteConfig put(String pattern, ReadFullHandler handler) {
        return new RouteConfig(this, router.add("PUT", pattern, handler, Invoker.fromReadFullFunction(handler)));
    }

    public RouteConfig delete(String pattern, ReadFullHandler handler) {
        return new RouteConfig(this, router.add("DELETE", pattern, handler, Invoker.fromReadFullFunction(handler)));
    }

    // Route registration with Database + Session

    public RouteConfig get(String pattern, FullHandler handler) {
        return new RouteConfig(this, router.add("GET", pattern, handler, Invoker.fromFullFunction(handler)));
    }

    public RouteConfig post(String pattern, FullHandler handler) {
        return new RouteConfig(this, router.add("POST", pattern, handler, Invoker.fromFullFunction(handler)));
    }

    public RouteConfig put(String pattern, FullHandler handler) {
        return new RouteConfig(this, router.add("PUT", pattern, handler, Invoker.fromFullFunction(handler)));
    }

    public RouteConfig delete(String pattern, FullHandler handler) {
        return new RouteConfig(this, router.add("DELETE", pattern, handler, Invoker.fromFullFunction(handler)));
    }

    // Typed route methods with explicit names (eliminates cast syntax for lambdas)

    public RouteConfig getDb(String pattern, DbHandler handler) {
        return get(pattern, handler);
    }

    public RouteConfig postDb(String pattern, DbHandler handler) {
        return post(pattern, handler);
    }

    public RouteConfig putDb(String pattern, DbHandler handler) {
        return put(pattern, handler);
    }

    public RouteConfig deleteDb(String pattern, DbHandler handler) {
        return delete(pattern, handler);
    }

    public RouteConfig getSession(String pattern, SessionHandler handler) {
        return get(pattern, handler);
    }

    public RouteConfig postSession(String pattern, SessionHandler handler) {
        return post(pattern, handler);
    }

    public RouteConfig putSession(String pattern, SessionHandler handler) {
        return put(pattern, handler);
    }

    public RouteConfig deleteSession(String pattern, SessionHandler handler) {
        return delete(pattern, handler);
    }

    public RouteConfig getFull(String pattern, FullHandler handler) {
        return get(pattern, handler);
    }

    public RouteConfig postFull(String pattern, FullHandler handler) {
        return post(pattern, handler);
    }

    public RouteConfig putFull(String pattern, FullHandler handler) {
        return put(pattern, handler);
    }

    public RouteConfig deleteFull(String pattern, FullHandler handler) {
        return delete(pattern, handler);
    }

    // Route grouping

    public Brace group(String prefix, Consumer<RouteGroup> config) {
        var group = new RouteGroup(prefix, router);
        config.accept(group);
        return this;
    }

    // Middleware

    public Brace before(Middleware.Before handler) {
        beforeMiddleware.add(new Middleware.BoundBefore(null, handler));
        return this;
    }

    public Brace before(String pathPattern, Middleware.Before handler) {
        beforeMiddleware.add(new Middleware.BoundBefore(
                Middleware.PathPattern.compile(pathPattern), handler));
        return this;
    }

    public Brace after(Middleware.After handler) {
        afterMiddleware.add(new Middleware.BoundAfter(null, handler));
        return this;
    }

    public Brace after(String pathPattern, Middleware.After handler) {
        afterMiddleware.add(new Middleware.BoundAfter(
                Middleware.PathPattern.compile(pathPattern), handler));
        return this;
    }

    // Route inspection

    public List<Route> routes() {
        return router.routes();
    }

    // Server lifecycle

    public void start() throws Exception {
        // Create ErrorStore if database is available
        if (databaseFactory != null) {
            int maxErrors = 1000;
            errorStore = new ErrorStore(databaseFactory, maxErrors);
        }

        // Create JFR profiler when ops is enabled, unless explicitly disabled (M19):
        // continuous sampling is ~0.5–2% CPU, on by default as a deliberate trade.
        if (opsKeysPath != null && opsProfilerEnabled) {
            profiler = new JfrProfiler();
        }

        // Register ops endpoints if authorized keys are configured
        String tokenSecret = null;
        if (opsKeysPath != null) {
            var authorizedKeys = OpsKeys.loadAuthorizedKeys(opsKeysPath);
            tokenSecret = resolveOpsSecret();
            opsHandler = new OpsHandler(stats, jobScheduler, mailer, router, authorizedKeys, tokenSecret, errorStore, cache, profiler);

            // Server-side regression detection: track new error kinds since startup and notify on
            // first appearance. Requires the error store (a database); the LogNotifier is always on
            // so regressions hit the structured log even with no webhook/email configured.
            if (errorStore != null) {
                var notifiers = new ArrayList<Notifier>();
                notifiers.add(new LogNotifier());
                notifiers.addAll(regressionNotifiers);
                var regressionTracker = new RegressionTracker(stats.startedAt(), regressionsWarmupSeconds,
                    notifiers, resolveDeployMarker(), databaseFactory);
                regressionTracker.seed(errorStore);
                errorStore.setRegressionListener(regressionTracker);
                opsHandler.setRegressionTracker(regressionTracker);
            }
            // Ops POSTs use signed-payload or bearer auth, not cookies — CSRF is the wrong layer
            // and would block the CLI on any app that also calls .sessions(...).
            router.add("POST", "/ops/auth", (Handler) opsHandler::auth).setCsrfRequired(false);
            router.add("POST", "/ops/auth/login-token", (Handler) opsHandler::loginToken).setCsrfRequired(false);
            router.add("GET", "/ops/auth/exchange", (Handler) opsHandler::exchange);
            router.add("GET", "/ops/status", (Handler) opsHandler::status);
            router.add("GET", "/ops/routes", (Handler) opsHandler::routes);
            router.add("GET", "/ops/logs", (Handler) opsHandler::logs);
            router.add("GET", "/ops/dashboard", (Handler) opsHandler::dashboard);
            router.add("GET", "/ops/errors", (Handler) opsHandler::errors);
            router.add("POST", "/ops/errors/{id}/resolve", (Handler) opsHandler::resolveError).setCsrfRequired(false);
            router.add("POST", "/ops/cache/clear", (Handler) opsHandler::clearCache).setCsrfRequired(false);
            router.add("GET", "/ops/cache", (Handler) opsHandler::cacheStats);
            router.add("GET", "/ops/regressions", (Handler) opsHandler::regressions);
            router.add("POST", "/ops/regressions/{id}/acknowledge", (Handler) opsHandler::acknowledgeRegression).setCsrfRequired(false);
        }

        var threadPool = new QueuedThreadPool();
        // Run request handlers on real virtual threads. A blocking call in a handler
        // (e.g. Content.Source.asString for the request body, or a JDBC query) then
        // parks the virtual thread and frees its carrier to keep producing — notably
        // to read the next body chunk. Passing a synchronous executor like
        // Runnable::run instead makes Jetty believe virtual threads are enabled while
        // running handlers inline on the producer thread, so a blocking multi-chunk
        // body read deadlocks the producer until the idle timeout fires.
        var virtualThreads = VirtualThreads.getDefaultVirtualThreadsExecutor();
        if (virtualThreads != null) {
            threadPool.setVirtualThreadsExecutor(virtualThreads);
        }

        server = new Server(threadPool);
        connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        var staticMappingsCopy = List.copyOf(staticFileMappings);
        Assets.init(staticMappingsCopy);
        var handler = new BraceHandler(router, beforeMiddleware, afterMiddleware, databaseFactory, sessionSecret, sessionOptions, stats, errorStore, staticMappingsCopy, maxUploadSize, storage, trustedProxies);

        if (!wsRoutes.isEmpty()) {
            // Room fan-out: shared across the fleet on Postgres (LISTEN/NOTIFY), local otherwise.
            MessageBus messageBus = (databaseFactory != null && databaseFactory.isPostgres())
                ? new PostgresMessageBus(databaseFactory)
                : new InProcessMessageBus();
            wsRegistry = new WsRegistry(messageBus, wsMaxQueuedBytes);
            final WsRegistry wsRegistryRef = wsRegistry;
            // Wrap with WebSocketUpgradeHandler for WebSocket support
            var wsUpgradeHandler = WebSocketUpgradeHandler.from(server, container -> {
                for (var entry : wsRoutes.entrySet()) {
                    String wsPath = entry.getKey();
                    Function<WsContext, Object> factory = entry.getValue();
                    container.addMapping(wsPath, (upgradeRequest, upgradeResponse, callback) -> {
                        // Extract session from upgrade request cookie
                        Session braceSession = null;
                        if (sessionSecret != null) {
                            var cookies = org.eclipse.jetty.server.Request.getCookies(upgradeRequest);
                            for (var cookie : cookies) {
                                if ("brace_session".equals(cookie.getName())) {
                                    braceSession = Session.fromCookie(cookie.getValue(), sessionSecret);
                                    break;
                                }
                            }
                        }
                        return new WsHandler(factory, braceSession, wsRegistryRef);
                    });
                }
            });
            wsUpgradeHandler.setHandler(handler);
            server.setHandler(wsUpgradeHandler);
        } else {
            server.setHandler(handler);
        }

        server.start();

        // Stable per-process identity for the fleet (uses the actually-bound port, known only
        // after start()). Tags metrics and anchors regression detection across N instances.
        instanceId = InstanceId.generate(actualPort());
        if (opsHandler != null) {
            opsHandler.setInstanceId(instanceId);
        }

        // Capture uncaught exceptions from any thread (e.g., background libraries)
        // so they appear in /ops/status errors.recent and in structured logs.
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            var sw = new java.io.StringWriter();
            throwable.printStackTrace(new java.io.PrintWriter(sw));
            String threadName = thread.getName();
            stats.recordError(
                throwable.getClass().getName(),
                throwable.getMessage(),
                "thread:" + threadName,
                sw.toString(),
                null,
                null);
            Log.event("uncaught_exception", java.util.Map.of(
                "thread", threadName,
                "type", throwable.getClass().getName(),
                "message", String.valueOf(throwable.getMessage())));
        });

        // Durable-job retention (perf review H3): finished rows otherwise accumulate forever and
        // every poll's claim query walks the dead prefix. Daily, once cluster-wide (B1
        // coordination via brace_scheduled_runs). Registered before jobScheduler.start —
        // daily() has no late-registration path.
        if (databaseFactory != null && jobRetentionDays > 0) {
            int retentionDays = jobRetentionDays;
            jobScheduler.daily("03:23", "brace-jobs-prune", (db, ctx) -> {
                var cutoff = java.time.Instant.now().minus(java.time.Duration.ofDays(retentionDays));
                int deleted = JobPoller.purgeFinishedJobs(db, cutoff);
                ctx.message("Deleted " + deleted + " finished jobs older than " + retentionDays + " days");
            });
        }

        jobScheduler.start(databaseFactory);
        if (databaseFactory != null) {
            jobPoller.start(databaseFactory);
        }

        // On Postgres, rate limiters count cluster-wide via a shared DB counter (B4) so a limit is
        // enforced across the fleet, not N times too loosely. Off Postgres they stay per-process.
        if (databaseFactory != null && databaseFactory.isPostgres()) {
            RateLimiter.useSharedBackend(new Counters(databaseFactory));
        }

        // Snapshot stats for dashboard sparklines (even without a database)
        if (opsKeysPath != null && databaseFactory == null) {
            var snapshotTimer = new java.util.Timer("brace-stats-snapshot", true);
            snapshotTimer.scheduleAtFixedRate(new java.util.TimerTask() {
                @Override public void run() { stats.snapshot(); }
            }, 60_000, 60_000);
            // M19: without a database there is no ops-flush-jvm-profiling job to reset the
            // JFR sample maps, so methodSamples/allocationByClass would grow for the life of
            // the JVM. Reset on the same 5-minute cadence the DB-backed flush job uses.
            if (profiler != null) {
                snapshotTimer.scheduleAtFixedRate(new java.util.TimerTask() {
                    @Override public void run() { profiler.resetProfiling(); }
                }, 300_000, 300_000);
            }
        }

        // Flush stats to ops_timeseries
        if (databaseFactory != null && opsKeysPath != null) {
            // Per-instance flush (everyLocal): each instance writes its OWN instance-tagged rows so
            // the fleet's metrics are all captured and distinguishable (P3), rather than the recurring
            // scheduler's cluster-wide dedupe leaving only one box's data.
            jobScheduler.everyLocal(httpStatsInterval, "ops-flush-http", (db, ctx) -> {
                var snapshot = stats.snapshot();
                if (snapshot.requests() > 0) {
                    var metrics = new java.util.LinkedHashMap<String, Object>();
                    metrics.put("http.requests", snapshot.requests());
                    metrics.put("http.errors", snapshot.errors());
                    metrics.put("http.avg_latency_us", snapshot.totalLatencyUs() / snapshot.requests());
                    metrics.put("http.max_latency_us", snapshot.maxLatencyUs());
                    metrics.put("http.queries", snapshot.queries());
                    if (snapshot.queries() > 0) {
                        metrics.put("http.avg_query_us", snapshot.queryUs() / snapshot.queries());
                    }
                    insertMetrics(db, java.sql.Timestamp.from(snapshot.ts()), instanceId, metrics);
                }
            });

            if (cache != null) {
                jobScheduler.everyLocal(cacheStatsInterval, "ops-flush-cache", (db, ctx) -> {
                    long h = cache.drainHits(), m = cache.drainMisses(), e = cache.drainEvictions();
                    if (h > 0 || m > 0 || e > 0) {
                        var metrics = new java.util.LinkedHashMap<String, Object>();
                        metrics.put("cache.hits", h);
                        metrics.put("cache.misses", m);
                        metrics.put("cache.evictions", e);
                        insertMetrics(db, java.sql.Timestamp.from(java.time.Instant.now()), instanceId, metrics);
                    }
                });
            }

            if (mailer != null) {
                jobScheduler.everyLocal(mailerStatsInterval, "ops-flush-mailer", (db, ctx) -> {
                    long f = mailer.drainFailCount();
                    if (f > 0) {
                        var ts = java.sql.Timestamp.from(java.time.Instant.now());
                        db.sql("INSERT INTO ops_timeseries (ts, metric, val, instance_id) VALUES (?, ?, ?, ?)",
                            ts, "mailer.failures", f, instanceId);
                    }
                });
            }

            // JVM metrics flush (per-instance: heap/CPU/threads are inherently per-JVM)
            if (profiler != null) {
                jobScheduler.everyLocal(httpStatsInterval, "ops-flush-jvm", (db, ctx) -> {
                    var snap = profiler.snapshot();
                    var heap = (java.util.Map<String, Object>) snap.get("heap");
                    var cpu = (java.util.Map<String, Object>) snap.get("cpu");
                    var threads = (java.util.Map<String, Object>) snap.get("threads");
                    var metrics = new java.util.LinkedHashMap<String, Object>();
                    metrics.put("jvm.heap_used_mb", heap.get("usedMB"));
                    metrics.put("jvm.heap_max_mb", heap.get("maxMB"));
                    metrics.put("jvm.cpu_user", Math.round((double) cpu.get("jvmUser") * 10000)); // basis points (0.01% precision)
                    metrics.put("jvm.cpu_system", Math.round((double) cpu.get("jvmSystem") * 10000)); // basis points
                    metrics.put("jvm.threads_active", threads.get("active"));
                    metrics.put("jvm.threads_peak", threads.get("peak"));
                    metrics.put("jvm.gc_count", profiler.gcCount());
                    metrics.put("jvm.gc_total_pause_ms", profiler.totalGcPauseMs());
                    metrics.put("jvm.gc_max_pause_ms", profiler.maxRecentGcPauseMs());
                    insertMetrics(db, java.sql.Timestamp.from(java.time.Instant.now()), instanceId, metrics);
                });

                jobScheduler.every("5m", "ops-flush-jvm-profiling", (db, ctx) -> {
                    var ts = java.sql.Timestamp.from(java.time.Instant.now());
                    var rows = new java.util.ArrayList<Object[]>();
                    for (var entry : profiler.topMethods(20)) {
                        rows.add(new Object[]{ts, "method", entry.getKey(), entry.getValue()});
                    }
                    for (var entry : profiler.topAllocations(20)) {
                        rows.add(new Object[]{ts, "allocation", entry.getKey(), entry.getValue()});
                    }
                    if (!rows.isEmpty()) {
                        // One multi-row INSERT instead of up to 40 single-row round-trips.
                        var sql = new StringBuilder("INSERT INTO ops_profiling_snapshots (ts, type, name, value) VALUES ");
                        var params = new java.util.ArrayList<Object>(rows.size() * 4);
                        for (int i = 0; i < rows.size(); i++) {
                            sql.append(i == 0 ? "(?, ?, ?, ?)" : ", (?, ?, ?, ?)");
                            java.util.Collections.addAll(params, rows.get(i));
                        }
                        db.sql(sql.toString(), params.toArray());
                    }
                    profiler.resetProfiling();
                });
            }

            // Retention: ops_timeseries and ops_profiling_snapshots are append-only with no reader
            // today, so without pruning they grow unbounded. Delete rows past a 14-day window once
            // a day; B1 coordination (brace_scheduled_runs) runs it once cluster-wide.
            jobScheduler.daily("03:17", "ops-metrics-prune", (db, ctx) -> {
                var cutoff = java.sql.Timestamp.from(java.time.Instant.now().minus(java.time.Duration.ofDays(14)));
                db.sql("DELETE FROM ops_timeseries WHERE ts < ?", cutoff);
                db.sql("DELETE FROM ops_profiling_snapshots WHERE ts < ?", cutoff);
            });
        }

        if (showBanner) {
            printBanner();
        }
    }

    /**
     * Write a flush's metrics as one multi-row INSERT into ops_timeseries instead of N single-row
     * round-trips. The {@code ?} placeholders are renumbered to ?1, ?2… by {@link Database#sql}.
     * Package-private so {@code OpsMetricsFlushTest} can exercise the dynamic-SQL build directly.
     */
    static void insertMetrics(Database db, java.sql.Timestamp ts, String instanceId,
                              java.util.Map<String, Object> metrics) {
        if (metrics.isEmpty()) {
            return;
        }
        var sql = new StringBuilder("INSERT INTO ops_timeseries (ts, metric, val, instance_id) VALUES ");
        var params = new java.util.ArrayList<Object>(metrics.size() * 4);
        boolean first = true;
        for (var entry : metrics.entrySet()) {
            sql.append(first ? "(?, ?, ?, ?)" : ", (?, ?, ?, ?)");
            first = false;
            params.add(ts);
            params.add(entry.getKey());
            params.add(entry.getValue());
            params.add(instanceId);
        }
        db.sql(sql.toString(), params.toArray());
    }

    private void printBanner() {
        System.out.println("  {");
        System.out.println("      _");
        System.out.println("     | |__ _ _ __ _ __ ___");
        System.out.println("     | '_ \\ '_/ _` / _/ -_)");
        System.out.println("     |_.__/_| \\__,_\\__\\___|");
        System.out.println();
        System.out.printf("     port   %d%n", actualPort());
        System.out.printf("     mode   %s%n", modeOrDash());
        System.out.println("     ready  \u2713");
        System.out.println();
        int routeCount = router.routes().size() + wsRoutes.size();
        System.out.printf("     routes (%d) {%n", routeCount);
        for (var route : router.routes()) {
            System.out.printf("        %-6s %s%n", route.method(), route.pattern());
        }
        for (var wsPath : wsRoutes.keySet()) {
            System.out.printf("        %-6s %s%n", "WS", wsPath);
        }
        System.out.println("     }");
        System.out.println("  }");
    }

    private String modeOrDash() {
        String mode = System.getProperty("brace.mode");
        return (mode != null && !mode.isEmpty()) ? mode : "\u2014";
    }

    /**
     * Generate a CLAUDE.md file with a capability index and pointers to the full framework reference.
     */
    public void generateClaudeMd(String projectName, java.nio.file.Path path) {
        ClaudeMdGenerator.write(projectName, path);
    }

    public void stop() throws Exception {
        jobPoller.stop();
        jobScheduler.stop();
        if (cache != null) {
            cache.close();
        }
        if (errorStore != null) {
            // Stops the flusher and writes out the buffered window, so a stop() right after a
            // 500 (tests, clean shutdown) doesn't lose the last <2s of error data.
            errorStore.close();
        }
        if (profiler != null) {
            profiler.close();
        }
        if (server != null) {
            server.stop();
        }
        if (wsRegistry != null) {
            wsRegistry.close();
        }
        // Drain any structured log lines still queued in the async writer (H1) so a stop()
        // immediately followed by assertions (tests) or process exit loses nothing.
        Log.flush();
    }

    /** The error store, for tests that need to flush the H9 buffer deterministically. */
    ErrorStore errorStore() {
        return errorStore;
    }

    public int actualPort() {
        if (connector != null) {
            return connector.getLocalPort();
        }
        return port;
    }

    /**
     * Stable per-process instance id (e.g. {@code web-3:8080-a1b2c3d4}), assigned at
     * {@link #start()}. Null before the server starts. Identifies this running instance in a
     * multi-server fleet — used to tag metrics and anchor regression detection. See
     * {@link InstanceId}.
     */
    public String instanceId() {
        return instanceId;
    }

    private static long parseSize(String size) {
        var s = size.strip().toLowerCase();
        if (s.endsWith("k")) return Long.parseLong(s.substring(0, s.length() - 1)) * 1024;
        if (s.endsWith("m")) return Long.parseLong(s.substring(0, s.length() - 1)) * 1024 * 1024;
        if (s.endsWith("g")) return Long.parseLong(s.substring(0, s.length() - 1)) * 1024 * 1024 * 1024;
        return Long.parseLong(s);
    }

    // --- Test support ---

    public static TestAppBuilder test() {
        return new TestAppBuilder();
    }

    public static class TestAppBuilder {
        private String dbUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1";
        private String templatesPath;
        private String secret;
        private final List<Class<?>> entityClasses = new ArrayList<>();

        public TestAppBuilder database(String url) {
            this.dbUrl = url;
            return this;
        }

        public TestAppBuilder templates(String path) {
            this.templatesPath = path;
            return this;
        }

        public TestAppBuilder sessions(String secret) {
            this.secret = secret;
            return this;
        }

        public TestAppBuilder entities(Class<?>... classes) {
            this.entityClasses.addAll(Arrays.asList(classes));
            return this;
        }

        public TestApp start(Consumer<Brace> configure) throws Exception {
            DatabaseFactory dbFactory = null;
            if (!entityClasses.isEmpty()) {
                dbFactory = new DatabaseFactory(dbUrl, null, null, entityClasses);
            }

            var mailer = new Mailer(null);
            var app = Brace.app()
                .port(0)
                .banner(false)
                .mailer(mailer);

            if (secret != null) {
                app.sessions(secret);
            }

            if (dbFactory != null) {
                app.database(dbFactory);
            }
            if (templatesPath != null) {
                app.templates(templatesPath);
            }

            configure.accept(app);
            app.start();

            return new TestApp(app, dbFactory);
        }
    }
}

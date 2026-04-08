package io.brace;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
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
    private final Router router = new Router();
    private final List<Middleware.BoundBefore> beforeMiddleware = new ArrayList<>();
    private final List<Middleware.BoundAfter> afterMiddleware = new ArrayList<>();
    private final List<BraceHandler.StaticFileMapping> staticFileMappings = new ArrayList<>();
    private DatabaseFactory databaseFactory;
    private String sessionSecret;
    private TemplateEngine templateEngine;
    private Mailer mailer;
    private Server server;
    private ServerConnector connector;
    private final JobScheduler jobScheduler = new JobScheduler();
    private final JobPoller jobPoller = new JobPoller();
    private String opsSecret;
    private Stats stats;
    private JfrProfiler profiler;
    private Cache cache;
    private final Map<String, Function<WsContext, Object>> wsRoutes = new LinkedHashMap<>();
    private long maxUploadSize = BraceHandler.DEFAULT_MAX_UPLOAD_SIZE;
    private String httpStatsInterval = "60s";
    private String cacheStatsInterval = "60s";
    private String mailerStatsInterval = "60s";

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

    public Brace port(int port) {
        this.port = port;
        return this;
    }

    public int port() {
        return port;
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

    Mailer mailer() {
        return mailer;
    }


    public Brace sessions(String secret) {
        this.sessionSecret = secret;
        return this;
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

    public Brace ops(String secret) {
        this.opsSecret = secret;
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

    // Job scheduling

    public Brace every(String interval, String name, Job job) {
        jobScheduler.every(interval, name, job);
        return this;
    }

    public Brace daily(String time, String name, Job job) {
        jobScheduler.daily(time, name, job);
        return this;
    }

    // Route registration

    public Brace get(String pattern, Handler handler) {
        router.add("GET", pattern, handler);
        return this;
    }

    public Brace post(String pattern, Handler handler) {
        router.add("POST", pattern, handler);
        return this;
    }

    public Brace put(String pattern, Handler handler) {
        router.add("PUT", pattern, handler);
        return this;
    }

    public Brace delete(String pattern, Handler handler) {
        router.add("DELETE", pattern, handler);
        return this;
    }

    // Route registration with Database

    public Brace get(String pattern, DbHandler handler) {
        router.add("GET", pattern, handler, Invoker.fromDbFunction(handler));
        return this;
    }

    public Brace post(String pattern, DbHandler handler) {
        router.add("POST", pattern, handler, Invoker.fromDbFunction(handler));
        return this;
    }

    public Brace put(String pattern, DbHandler handler) {
        router.add("PUT", pattern, handler, Invoker.fromDbFunction(handler));
        return this;
    }

    public Brace delete(String pattern, DbHandler handler) {
        router.add("DELETE", pattern, handler, Invoker.fromDbFunction(handler));
        return this;
    }

    // Route registration with Session

    public Brace get(String pattern, SessionHandler handler) {
        router.add("GET", pattern, handler, Invoker.fromSessionFunction(handler));
        return this;
    }

    public Brace post(String pattern, SessionHandler handler) {
        router.add("POST", pattern, handler, Invoker.fromSessionFunction(handler));
        return this;
    }

    public Brace put(String pattern, SessionHandler handler) {
        router.add("PUT", pattern, handler, Invoker.fromSessionFunction(handler));
        return this;
    }

    public Brace delete(String pattern, SessionHandler handler) {
        router.add("DELETE", pattern, handler, Invoker.fromSessionFunction(handler));
        return this;
    }

    // Route registration with read-only Database (no transaction)

    public Brace get(String pattern, ReadDbHandler handler) {
        router.add("GET", pattern, handler, Invoker.fromReadDbFunction(handler));
        return this;
    }

    public Brace post(String pattern, ReadDbHandler handler) {
        router.add("POST", pattern, handler, Invoker.fromReadDbFunction(handler));
        return this;
    }

    public Brace put(String pattern, ReadDbHandler handler) {
        router.add("PUT", pattern, handler, Invoker.fromReadDbFunction(handler));
        return this;
    }

    public Brace delete(String pattern, ReadDbHandler handler) {
        router.add("DELETE", pattern, handler, Invoker.fromReadDbFunction(handler));
        return this;
    }

    // Route registration with read-only Database + Session (no transaction)

    public Brace get(String pattern, ReadFullHandler handler) {
        router.add("GET", pattern, handler, Invoker.fromReadFullFunction(handler));
        return this;
    }

    public Brace post(String pattern, ReadFullHandler handler) {
        router.add("POST", pattern, handler, Invoker.fromReadFullFunction(handler));
        return this;
    }

    public Brace put(String pattern, ReadFullHandler handler) {
        router.add("PUT", pattern, handler, Invoker.fromReadFullFunction(handler));
        return this;
    }

    public Brace delete(String pattern, ReadFullHandler handler) {
        router.add("DELETE", pattern, handler, Invoker.fromReadFullFunction(handler));
        return this;
    }

    // Route registration with Database + Session

    public Brace get(String pattern, FullHandler handler) {
        router.add("GET", pattern, handler, Invoker.fromFullFunction(handler));
        return this;
    }

    public Brace post(String pattern, FullHandler handler) {
        router.add("POST", pattern, handler, Invoker.fromFullFunction(handler));
        return this;
    }

    public Brace put(String pattern, FullHandler handler) {
        router.add("PUT", pattern, handler, Invoker.fromFullFunction(handler));
        return this;
    }

    public Brace delete(String pattern, FullHandler handler) {
        router.add("DELETE", pattern, handler, Invoker.fromFullFunction(handler));
        return this;
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
        stats = new Stats();

        // Create ErrorStore if database is available
        ErrorStore errorStore = null;
        if (databaseFactory != null) {
            int maxErrors = 1000;
            errorStore = new ErrorStore(databaseFactory, maxErrors);
        }

        // Create JFR profiler when ops is enabled
        if (opsSecret != null) {
            profiler = new JfrProfiler();
        }

        // Register ops endpoints if secret is configured
        if (opsSecret != null) {
            var opsHandler = new OpsHandler(stats, jobScheduler, mailer, router, opsSecret, errorStore, cache, profiler);
            router.add("GET", "/ops/status", (Handler) opsHandler::status);
            router.add("GET", "/ops/routes", (Handler) opsHandler::routes);
            router.add("GET", "/ops/dashboard", (Handler) opsHandler::dashboard);
            router.add("GET", "/ops/errors", (Handler) opsHandler::errors);
            router.add("POST", "/ops/errors/{id}/resolve", (Handler) opsHandler::resolveError);
            router.add("POST", "/ops/cache/clear", (Handler) opsHandler::clearCache);
        }

        var threadPool = new QueuedThreadPool();
        threadPool.setVirtualThreadsExecutor(Runnable::run);

        server = new Server(threadPool);
        connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        var handler = new BraceHandler(router, beforeMiddleware, afterMiddleware, databaseFactory, sessionSecret, stats, errorStore, List.copyOf(staticFileMappings), maxUploadSize);

        if (!wsRoutes.isEmpty()) {
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
                        return new WsHandler(factory, braceSession);
                    });
                }
            });
            wsUpgradeHandler.setHandler(handler);
            server.setHandler(wsUpgradeHandler);
        } else {
            server.setHandler(handler);
        }

        server.start();
        jobScheduler.start(databaseFactory);
        if (databaseFactory != null) {
            jobPoller.start(databaseFactory);
        }

        // Snapshot stats for dashboard sparklines (even without a database)
        if (opsSecret != null && databaseFactory == null) {
            var snapshotTimer = new java.util.Timer("brace-stats-snapshot", true);
            snapshotTimer.scheduleAtFixedRate(new java.util.TimerTask() {
                @Override public void run() { stats.snapshot(); }
            }, 60_000, 60_000);
        }

        // Flush stats to ops_timeseries
        if (databaseFactory != null && opsSecret != null) {
            jobScheduler.every(httpStatsInterval, "ops-flush-http", db -> {
                var snapshot = stats.snapshot();
                if (snapshot.requests() > 0) {
                    var ts = java.sql.Timestamp.from(snapshot.ts());
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)", ts, "http.requests", snapshot.requests());
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)", ts, "http.errors", snapshot.errors());
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                        ts, "http.avg_latency_us", snapshot.totalLatencyUs() / snapshot.requests());
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)", ts, "http.max_latency_us", snapshot.maxLatencyUs());
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)", ts, "http.queries", snapshot.queries());
                    if (snapshot.queries() > 0) {
                        db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                            ts, "http.avg_query_us", snapshot.queryUs() / snapshot.queries());
                    }
                }
            });

            if (cache != null) {
                jobScheduler.every(cacheStatsInterval, "ops-flush-cache", db -> {
                    long h = cache.drainHits(), m = cache.drainMisses(), e = cache.drainEvictions();
                    if (h > 0 || m > 0 || e > 0) {
                        var ts = java.sql.Timestamp.from(java.time.Instant.now());
                        db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)", ts, "cache.hits", h);
                        db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)", ts, "cache.misses", m);
                        db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)", ts, "cache.evictions", e);
                    }
                });
            }

            if (mailer != null) {
                jobScheduler.every(mailerStatsInterval, "ops-flush-mailer", db -> {
                    long f = mailer.drainFailCount();
                    if (f > 0) {
                        var ts = java.sql.Timestamp.from(java.time.Instant.now());
                        db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)", ts, "mailer.failures", f);
                    }
                });
            }

            // JVM metrics flush
            if (profiler != null) {
                jobScheduler.every(httpStatsInterval, "ops-flush-jvm", db -> {
                    var ts = java.sql.Timestamp.from(java.time.Instant.now());
                    var snap = profiler.snapshot();
                    var heap = (java.util.Map<String, Object>) snap.get("heap");
                    var cpu = (java.util.Map<String, Object>) snap.get("cpu");
                    var threads = (java.util.Map<String, Object>) snap.get("threads");
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                        ts, "jvm.heap_used_mb", heap.get("usedMB"));
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                        ts, "jvm.heap_max_mb", heap.get("maxMB"));
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                        ts, "jvm.cpu_user", Math.round((double) cpu.get("jvmUser") * 10000)); // basis points (0.01% precision)
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                        ts, "jvm.cpu_system", Math.round((double) cpu.get("jvmSystem") * 10000)); // basis points
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                        ts, "jvm.threads_active", threads.get("active"));
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                        ts, "jvm.threads_peak", threads.get("peak"));
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                        ts, "jvm.gc_count", profiler.gcCount());
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                        ts, "jvm.gc_total_pause_ms", profiler.totalGcPauseMs());
                    db.sql("INSERT INTO ops_timeseries (ts, metric, val) VALUES (?, ?, ?)",
                        ts, "jvm.gc_max_pause_ms", profiler.maxRecentGcPauseMs());
                });

                jobScheduler.every("5m", "ops-flush-jvm-profiling", db -> {
                    var ts = java.sql.Timestamp.from(java.time.Instant.now());
                    for (var entry : profiler.topMethods(20)) {
                        db.sql("INSERT INTO ops_profiling_snapshots (ts, type, name, value) VALUES (?, ?, ?, ?)",
                            ts, "method", entry.getKey(), entry.getValue());
                    }
                    for (var entry : profiler.topAllocations(20)) {
                        db.sql("INSERT INTO ops_profiling_snapshots (ts, type, name, value) VALUES (?, ?, ?, ?)",
                            ts, "allocation", entry.getKey(), entry.getValue());
                    }
                    profiler.resetProfiling();
                });
            }
        }

        // Print route table
        System.out.println("Brace started on port " + actualPort());
        for (var route : router.routes()) {
            System.out.printf("  %-6s %s%n", route.method(), route.pattern());
        }
        for (var wsPath : wsRoutes.keySet()) {
            System.out.printf("  %-6s %s%n", "WS", wsPath);
        }
    }

    /**
     * Generate a CLAUDE.md file by introspecting the app's routes, entities, and configuration.
     * Call after start() or during build to produce an AI context file.
     */
    public void generateClaudeMd(java.nio.file.Path path) {
        ClaudeMdGenerator.write(this, path);
    }

    public void stop() throws Exception {
        jobPoller.stop();
        jobScheduler.stop();
        if (profiler != null) {
            profiler.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    public int actualPort() {
        if (connector != null) {
            return connector.getLocalPort();
        }
        return port;
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

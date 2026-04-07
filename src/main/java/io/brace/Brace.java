package io.brace;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

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

    public static Brace app() {
        return new Brace();
    }

    public static Cache cache() {
        return new Cache();
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

    public Brace staticFiles(String urlPrefix, String directory) {
        staticFileMappings.add(new BraceHandler.StaticFileMapping(urlPrefix, directory));
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

        // Register ops endpoints if secret is configured
        if (opsSecret != null) {
            var opsHandler = new OpsHandler(stats, jobScheduler, mailer, router, opsSecret, errorStore);
            router.add("GET", "/ops/status", (Handler) opsHandler::status);
            router.add("GET", "/ops/routes", (Handler) opsHandler::routes);
            router.add("GET", "/ops/dashboard", (Handler) opsHandler::dashboard);
            router.add("GET", "/ops/errors", (Handler) opsHandler::errors);
            router.add("POST", "/ops/errors/{id}/resolve", (Handler) opsHandler::resolveError);
        }

        var threadPool = new QueuedThreadPool();
        threadPool.setVirtualThreadsExecutor(Runnable::run);

        server = new Server(threadPool);
        connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        var handler = new BraceHandler(router, beforeMiddleware, afterMiddleware, databaseFactory, sessionSecret, stats, errorStore, List.copyOf(staticFileMappings));
        server.setHandler(handler);

        server.start();
        jobScheduler.start(databaseFactory);
        if (databaseFactory != null) {
            jobPoller.start(databaseFactory);
        }

        // Flush stats to database every 60 seconds
        if (databaseFactory != null && opsSecret != null) {
            jobScheduler.every("60s", "ops-stats-flush", db -> {
                var snapshot = stats.rotateMinute();
                if (snapshot.requests() > 0) {
                    db.sql("INSERT INTO ops_stats (ts, granularity, requests, errors, avg_latency_us, max_latency_us, queries, avg_query_us) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        java.sql.Timestamp.from(snapshot.ts()),
                        "minute",
                        (int) snapshot.requests(),
                        (int) snapshot.errors(),
                        snapshot.requests() > 0 ? (int)(snapshot.totalLatencyUs() / snapshot.requests()) : 0,
                        (int) snapshot.maxLatencyUs(),
                        (int) snapshot.queries(),
                        snapshot.queries() > 0 ? (int)(snapshot.queryUs() / snapshot.queries()) : 0
                    );
                }
            });
        }

        // Print route table
        System.out.println("Brace started on port " + actualPort());
        for (var route : router.routes()) {
            System.out.printf("  %-6s %s%n", route.method(), route.pattern());
        }
    }

    public void stop() throws Exception {
        jobPoller.stop();
        jobScheduler.stop();
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

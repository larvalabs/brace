package io.brace;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import java.util.ArrayList;
import java.util.List;

public class Brace {

    private int port = 8080;
    private final Router router = new Router();
    private final List<Middleware.BoundBefore> beforeMiddleware = new ArrayList<>();
    private final List<Middleware.BoundAfter> afterMiddleware = new ArrayList<>();
    private DatabaseFactory databaseFactory;
    private String sessionSecret;
    private TemplateEngine templateEngine;
    private Mailer mailer;
    private Server server;
    private ServerConnector connector;
    private final JobScheduler jobScheduler = new JobScheduler();
    private final JobPoller jobPoller = new JobPoller();

    public static Brace app() {
        return new Brace();
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
        var threadPool = new QueuedThreadPool();
        threadPool.setVirtualThreadsExecutor(Runnable::run);

        server = new Server(threadPool);
        connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        var handler = new BraceHandler(router, beforeMiddleware, afterMiddleware, databaseFactory, sessionSecret);
        server.setHandler(handler);

        server.start();
        jobScheduler.start(databaseFactory);
        if (databaseFactory != null) {
            jobPoller.start(databaseFactory);
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
}

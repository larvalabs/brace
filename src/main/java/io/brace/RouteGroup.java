package io.brace;

import java.util.function.Consumer;

public class RouteGroup {

    private final String prefix;
    private final Router router;

    RouteGroup(String prefix, Router router) {
        this.prefix = prefix;
        this.router = router;
    }

    private String path(String pattern) {
        return prefix + pattern;
    }

    // Nested group

    public RouteGroup group(String subPrefix, Consumer<RouteGroup> config) {
        var group = new RouteGroup(prefix + subPrefix, router);
        config.accept(group);
        return this;
    }

    // Route registration

    public GroupRouteConfig get(String pattern, Handler handler) {
        return new GroupRouteConfig(this, router.add("GET", path(pattern), handler));
    }

    public GroupRouteConfig post(String pattern, Handler handler) {
        return new GroupRouteConfig(this, router.add("POST", path(pattern), handler));
    }

    public GroupRouteConfig put(String pattern, Handler handler) {
        return new GroupRouteConfig(this, router.add("PUT", path(pattern), handler));
    }

    public GroupRouteConfig delete(String pattern, Handler handler) {
        return new GroupRouteConfig(this, router.add("DELETE", path(pattern), handler));
    }

    // Route registration with Database

    public GroupRouteConfig get(String pattern, DbHandler handler) {
        return new GroupRouteConfig(this, router.add("GET", path(pattern), handler, Invoker.fromDbFunction(handler)));
    }

    public GroupRouteConfig post(String pattern, DbHandler handler) {
        return new GroupRouteConfig(this, router.add("POST", path(pattern), handler, Invoker.fromDbFunction(handler)));
    }

    public GroupRouteConfig put(String pattern, DbHandler handler) {
        return new GroupRouteConfig(this, router.add("PUT", path(pattern), handler, Invoker.fromDbFunction(handler)));
    }

    public GroupRouteConfig delete(String pattern, DbHandler handler) {
        return new GroupRouteConfig(this, router.add("DELETE", path(pattern), handler, Invoker.fromDbFunction(handler)));
    }

    // Route registration with Session

    public GroupRouteConfig get(String pattern, SessionHandler handler) {
        return new GroupRouteConfig(this, router.add("GET", path(pattern), handler, Invoker.fromSessionFunction(handler)));
    }

    public GroupRouteConfig post(String pattern, SessionHandler handler) {
        return new GroupRouteConfig(this, router.add("POST", path(pattern), handler, Invoker.fromSessionFunction(handler)));
    }

    public GroupRouteConfig put(String pattern, SessionHandler handler) {
        return new GroupRouteConfig(this, router.add("PUT", path(pattern), handler, Invoker.fromSessionFunction(handler)));
    }

    public GroupRouteConfig delete(String pattern, SessionHandler handler) {
        return new GroupRouteConfig(this, router.add("DELETE", path(pattern), handler, Invoker.fromSessionFunction(handler)));
    }

    // Route registration with read-only Database (no transaction)

    public GroupRouteConfig get(String pattern, ReadDbHandler handler) {
        return new GroupRouteConfig(this, router.add("GET", path(pattern), handler, Invoker.fromReadDbFunction(handler)));
    }

    public GroupRouteConfig post(String pattern, ReadDbHandler handler) {
        return new GroupRouteConfig(this, router.add("POST", path(pattern), handler, Invoker.fromReadDbFunction(handler)));
    }

    public GroupRouteConfig put(String pattern, ReadDbHandler handler) {
        return new GroupRouteConfig(this, router.add("PUT", path(pattern), handler, Invoker.fromReadDbFunction(handler)));
    }

    public GroupRouteConfig delete(String pattern, ReadDbHandler handler) {
        return new GroupRouteConfig(this, router.add("DELETE", path(pattern), handler, Invoker.fromReadDbFunction(handler)));
    }

    // Route registration with read-only Database + Session (no transaction)

    public GroupRouteConfig get(String pattern, ReadFullHandler handler) {
        return new GroupRouteConfig(this, router.add("GET", path(pattern), handler, Invoker.fromReadFullFunction(handler)));
    }

    public GroupRouteConfig post(String pattern, ReadFullHandler handler) {
        return new GroupRouteConfig(this, router.add("POST", path(pattern), handler, Invoker.fromReadFullFunction(handler)));
    }

    public GroupRouteConfig put(String pattern, ReadFullHandler handler) {
        return new GroupRouteConfig(this, router.add("PUT", path(pattern), handler, Invoker.fromReadFullFunction(handler)));
    }

    public GroupRouteConfig delete(String pattern, ReadFullHandler handler) {
        return new GroupRouteConfig(this, router.add("DELETE", path(pattern), handler, Invoker.fromReadFullFunction(handler)));
    }

    // Route registration with Database + Session

    public GroupRouteConfig get(String pattern, FullHandler handler) {
        return new GroupRouteConfig(this, router.add("GET", path(pattern), handler, Invoker.fromFullFunction(handler)));
    }

    public GroupRouteConfig post(String pattern, FullHandler handler) {
        return new GroupRouteConfig(this, router.add("POST", path(pattern), handler, Invoker.fromFullFunction(handler)));
    }

    public GroupRouteConfig put(String pattern, FullHandler handler) {
        return new GroupRouteConfig(this, router.add("PUT", path(pattern), handler, Invoker.fromFullFunction(handler)));
    }

    public GroupRouteConfig delete(String pattern, FullHandler handler) {
        return new GroupRouteConfig(this, router.add("DELETE", path(pattern), handler, Invoker.fromFullFunction(handler)));
    }

    // Typed route methods with explicit names (eliminates cast syntax for lambdas)

    public GroupRouteConfig getDb(String pattern, DbHandler handler) {
        return get(pattern, handler);
    }

    public GroupRouteConfig postDb(String pattern, DbHandler handler) {
        return post(pattern, handler);
    }

    public GroupRouteConfig putDb(String pattern, DbHandler handler) {
        return put(pattern, handler);
    }

    public GroupRouteConfig deleteDb(String pattern, DbHandler handler) {
        return delete(pattern, handler);
    }

    public GroupRouteConfig getSession(String pattern, SessionHandler handler) {
        return get(pattern, handler);
    }

    public GroupRouteConfig postSession(String pattern, SessionHandler handler) {
        return post(pattern, handler);
    }

    public GroupRouteConfig putSession(String pattern, SessionHandler handler) {
        return put(pattern, handler);
    }

    public GroupRouteConfig deleteSession(String pattern, SessionHandler handler) {
        return delete(pattern, handler);
    }

    public GroupRouteConfig getFull(String pattern, FullHandler handler) {
        return get(pattern, handler);
    }

    public GroupRouteConfig postFull(String pattern, FullHandler handler) {
        return post(pattern, handler);
    }

    public GroupRouteConfig putFull(String pattern, FullHandler handler) {
        return put(pattern, handler);
    }

    public GroupRouteConfig deleteFull(String pattern, FullHandler handler) {
        return delete(pattern, handler);
    }
}

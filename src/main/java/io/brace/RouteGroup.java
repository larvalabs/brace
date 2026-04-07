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

    public RouteGroup get(String pattern, Handler handler) {
        router.add("GET", path(pattern), handler);
        return this;
    }

    public RouteGroup post(String pattern, Handler handler) {
        router.add("POST", path(pattern), handler);
        return this;
    }

    public RouteGroup put(String pattern, Handler handler) {
        router.add("PUT", path(pattern), handler);
        return this;
    }

    public RouteGroup delete(String pattern, Handler handler) {
        router.add("DELETE", path(pattern), handler);
        return this;
    }

    // Route registration with Database

    public RouteGroup get(String pattern, DbHandler handler) {
        router.add("GET", path(pattern), handler, Invoker.fromDbFunction(handler));
        return this;
    }

    public RouteGroup post(String pattern, DbHandler handler) {
        router.add("POST", path(pattern), handler, Invoker.fromDbFunction(handler));
        return this;
    }

    public RouteGroup put(String pattern, DbHandler handler) {
        router.add("PUT", path(pattern), handler, Invoker.fromDbFunction(handler));
        return this;
    }

    public RouteGroup delete(String pattern, DbHandler handler) {
        router.add("DELETE", path(pattern), handler, Invoker.fromDbFunction(handler));
        return this;
    }

    // Route registration with Session

    public RouteGroup get(String pattern, SessionHandler handler) {
        router.add("GET", path(pattern), handler, Invoker.fromSessionFunction(handler));
        return this;
    }

    public RouteGroup post(String pattern, SessionHandler handler) {
        router.add("POST", path(pattern), handler, Invoker.fromSessionFunction(handler));
        return this;
    }

    public RouteGroup put(String pattern, SessionHandler handler) {
        router.add("PUT", path(pattern), handler, Invoker.fromSessionFunction(handler));
        return this;
    }

    public RouteGroup delete(String pattern, SessionHandler handler) {
        router.add("DELETE", path(pattern), handler, Invoker.fromSessionFunction(handler));
        return this;
    }

    // Route registration with read-only Database (no transaction)

    public RouteGroup get(String pattern, ReadDbHandler handler) {
        router.add("GET", path(pattern), handler, Invoker.fromReadDbFunction(handler));
        return this;
    }

    public RouteGroup post(String pattern, ReadDbHandler handler) {
        router.add("POST", path(pattern), handler, Invoker.fromReadDbFunction(handler));
        return this;
    }

    public RouteGroup put(String pattern, ReadDbHandler handler) {
        router.add("PUT", path(pattern), handler, Invoker.fromReadDbFunction(handler));
        return this;
    }

    public RouteGroup delete(String pattern, ReadDbHandler handler) {
        router.add("DELETE", path(pattern), handler, Invoker.fromReadDbFunction(handler));
        return this;
    }

    // Route registration with read-only Database + Session (no transaction)

    public RouteGroup get(String pattern, ReadFullHandler handler) {
        router.add("GET", path(pattern), handler, Invoker.fromReadFullFunction(handler));
        return this;
    }

    public RouteGroup post(String pattern, ReadFullHandler handler) {
        router.add("POST", path(pattern), handler, Invoker.fromReadFullFunction(handler));
        return this;
    }

    public RouteGroup put(String pattern, ReadFullHandler handler) {
        router.add("PUT", path(pattern), handler, Invoker.fromReadFullFunction(handler));
        return this;
    }

    public RouteGroup delete(String pattern, ReadFullHandler handler) {
        router.add("DELETE", path(pattern), handler, Invoker.fromReadFullFunction(handler));
        return this;
    }

    // Route registration with Database + Session

    public RouteGroup get(String pattern, FullHandler handler) {
        router.add("GET", path(pattern), handler, Invoker.fromFullFunction(handler));
        return this;
    }

    public RouteGroup post(String pattern, FullHandler handler) {
        router.add("POST", path(pattern), handler, Invoker.fromFullFunction(handler));
        return this;
    }

    public RouteGroup put(String pattern, FullHandler handler) {
        router.add("PUT", path(pattern), handler, Invoker.fromFullFunction(handler));
        return this;
    }

    public RouteGroup delete(String pattern, FullHandler handler) {
        router.add("DELETE", path(pattern), handler, Invoker.fromFullFunction(handler));
        return this;
    }
}

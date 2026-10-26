package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class RouteGroup {

    private final String prefix;
    private final Router router;
    // H1: access requirements of this group and every enclosing group, outermost first. Applied
    // to each route the group registers, on top of the app's default access.
    private final List<Access> access;

    RouteGroup(String prefix, Router router) {
        this(prefix, router, List.of());
    }

    RouteGroup(String prefix, Router router, List<Access> access) {
        this.prefix = prefix;
        this.router = router;
        this.access = List.copyOf(access);
    }

    /** Enclosing groups' requirements plus {@code requirement}, outermost first. */
    static List<Access> extend(List<Access> outer, Access requirement) {
        if (requirement == null) throw new IllegalArgumentException("group access requirement must not be null");
        if (requirement.isNone()) {
            throw new IllegalArgumentException("Access.none() is only valid as app.defaultAccess(...); "
                + "register public routes outside the group and mark them .publicRoute()");
        }
        var chain = new ArrayList<>(outer);
        chain.add(requirement);
        return chain;
    }

    private GroupRouteConfig config(Route route) {
        route.setGroupAccess(access);
        return new GroupRouteConfig(this, route);
    }

    Router router() { return router; }

    private String path(String pattern) {
        return prefix + pattern;
    }

    // Nested group

    public RouteGroup group(String subPrefix, Consumer<RouteGroup> config) {
        var group = new RouteGroup(prefix + subPrefix, router, access);
        config.accept(group);
        return this;
    }

    /**
     * Nested group with its own access requirement, added to this group's (and the app
     * default's): every route inside must pass all of them.
     */
    public RouteGroup group(String subPrefix, Access requirement, Consumer<RouteGroup> config) {
        var group = new RouteGroup(prefix + subPrefix, router, extend(access, requirement));
        config.accept(group);
        return this;
    }

    // Route registration

    public GroupRouteConfig get(String pattern, Handler handler) {
        return config(router.add("GET", path(pattern), handler));
    }

    public GroupRouteConfig post(String pattern, Handler handler) {
        return config(router.add("POST", path(pattern), handler));
    }

    public GroupRouteConfig put(String pattern, Handler handler) {
        return config(router.add("PUT", path(pattern), handler));
    }

    public GroupRouteConfig delete(String pattern, Handler handler) {
        return config(router.add("DELETE", path(pattern), handler));
    }

    // Route registration with Database

    public GroupRouteConfig get(String pattern, DbHandler handler) {
        return config(router.add("GET", path(pattern), handler, Invoker.fromDbFunction(handler)));
    }

    public GroupRouteConfig post(String pattern, DbHandler handler) {
        return config(router.add("POST", path(pattern), handler, Invoker.fromDbFunction(handler)));
    }

    public GroupRouteConfig put(String pattern, DbHandler handler) {
        return config(router.add("PUT", path(pattern), handler, Invoker.fromDbFunction(handler)));
    }

    public GroupRouteConfig delete(String pattern, DbHandler handler) {
        return config(router.add("DELETE", path(pattern), handler, Invoker.fromDbFunction(handler)));
    }

    // Route registration with Session

    public GroupRouteConfig get(String pattern, SessionHandler handler) {
        return config(router.add("GET", path(pattern), handler, Invoker.fromSessionFunction(handler)));
    }

    public GroupRouteConfig post(String pattern, SessionHandler handler) {
        return config(router.add("POST", path(pattern), handler, Invoker.fromSessionFunction(handler)));
    }

    public GroupRouteConfig put(String pattern, SessionHandler handler) {
        return config(router.add("PUT", path(pattern), handler, Invoker.fromSessionFunction(handler)));
    }

    public GroupRouteConfig delete(String pattern, SessionHandler handler) {
        return config(router.add("DELETE", path(pattern), handler, Invoker.fromSessionFunction(handler)));
    }

    // Route registration with read-only Database (no transaction)

    public GroupRouteConfig get(String pattern, ReadDbHandler handler) {
        return config(router.add("GET", path(pattern), handler, Invoker.fromReadDbFunction(handler)));
    }

    public GroupRouteConfig post(String pattern, ReadDbHandler handler) {
        return config(router.add("POST", path(pattern), handler, Invoker.fromReadDbFunction(handler)));
    }

    public GroupRouteConfig put(String pattern, ReadDbHandler handler) {
        return config(router.add("PUT", path(pattern), handler, Invoker.fromReadDbFunction(handler)));
    }

    public GroupRouteConfig delete(String pattern, ReadDbHandler handler) {
        return config(router.add("DELETE", path(pattern), handler, Invoker.fromReadDbFunction(handler)));
    }

    // Route registration with read-only Database + Session (no transaction)

    public GroupRouteConfig get(String pattern, ReadFullHandler handler) {
        return config(router.add("GET", path(pattern), handler, Invoker.fromReadFullFunction(handler)));
    }

    public GroupRouteConfig post(String pattern, ReadFullHandler handler) {
        return config(router.add("POST", path(pattern), handler, Invoker.fromReadFullFunction(handler)));
    }

    public GroupRouteConfig put(String pattern, ReadFullHandler handler) {
        return config(router.add("PUT", path(pattern), handler, Invoker.fromReadFullFunction(handler)));
    }

    public GroupRouteConfig delete(String pattern, ReadFullHandler handler) {
        return config(router.add("DELETE", path(pattern), handler, Invoker.fromReadFullFunction(handler)));
    }

    // Route registration with Database + Session

    public GroupRouteConfig get(String pattern, FullHandler handler) {
        return config(router.add("GET", path(pattern), handler, Invoker.fromFullFunction(handler)));
    }

    public GroupRouteConfig post(String pattern, FullHandler handler) {
        return config(router.add("POST", path(pattern), handler, Invoker.fromFullFunction(handler)));
    }

    public GroupRouteConfig put(String pattern, FullHandler handler) {
        return config(router.add("PUT", path(pattern), handler, Invoker.fromFullFunction(handler)));
    }

    public GroupRouteConfig delete(String pattern, FullHandler handler) {
        return config(router.add("DELETE", path(pattern), handler, Invoker.fromFullFunction(handler)));
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

    // GET only, mirroring Brace: mutating verbs with transaction-skipping Read handlers
    // are footguns (see the note in Brace).

    public GroupRouteConfig getRead(String pattern, ReadDbHandler handler) {
        return get(pattern, handler);
    }

    public GroupRouteConfig getReadFull(String pattern, ReadFullHandler handler) {
        return get(pattern, handler);
    }
}

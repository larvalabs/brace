package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Router {

    private final List<Route> routes = new ArrayList<>();
    // method + ' ' + path → route: O(1) lookup, no regex, for the common static case.
    private final Map<String, Route> staticRoutes = new HashMap<>();
    // Dynamic routes partitioned by method so a match only scans candidates that could win.
    private final Map<String, List<Route>> dynamicRoutes = new HashMap<>();
    // Named routes for reverse routing (Url.to(name, ...)). Sorted so error messages and
    // ops listings are stable.
    private final Map<String, Route> namedRoutes = new TreeMap<>();

    public Route add(String method, String pattern, Handler handler) {
        // L1: build the plain-Handler invoker once at registration — every other handler type
        // already does (Brace.get(DbHandler) etc.), but this overload (used by Brace.get(Handler),
        // RouteGroup, and the /ops/* routes) previously left it null, forcing BraceHandler to
        // allocate a fresh Invoker.fromFunction on every request.
        return register(new Route(method, pattern, handler, Invoker.fromFunction(handler)));
    }

    public Route add(String method, String pattern, Object handler, Invoker invoker) {
        return register(new Route(method, pattern, handler, invoker));
    }

    public Route add(String method, String pattern, Object handler, Invoker invoker, boolean csrfRequired) {
        return register(new Route(method, pattern, handler, invoker, csrfRequired));
    }

    private Route register(Route route) {
        routes.add(route);
        if (route.isStatic()) {
            // putIfAbsent: first registration wins, matching the old scan order.
            staticRoutes.putIfAbsent(route.method() + ' ' + route.staticPath(), route);
        } else {
            dynamicRoutes.computeIfAbsent(route.method(), m -> new ArrayList<>()).add(route);
        }
        return route;
    }

    /**
     * Assign a name to a registered route so {@code Url.to(name, params...)} can build its
     * URL. Names must be non-blank and must not start with {@code /} (that is how
     * {@code Url.to} tells a name from a literal pattern). Each name maps to exactly one
     * route, and a route carries at most one name; violating either fails here, at
     * registration, rather than at first render.
     */
    void name(Route route, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Route name must not be blank (route "
                + route.method() + " " + route.pattern() + ")");
        }
        if (name.startsWith("/")) {
            throw new IllegalArgumentException("Route name \"" + name + "\" must not start with '/' "
                + "— names are looked up by Url.to(name, ...), which treats a leading '/' as a literal pattern");
        }
        if (route.name() != null) {
            throw new IllegalStateException("Route " + route.method() + " " + route.pattern()
                + " is already named \"" + route.name() + "\"; cannot also name it \"" + name + "\"");
        }
        var existing = namedRoutes.putIfAbsent(name, route);
        if (existing != null) {
            throw new IllegalStateException("Duplicate route name \"" + name + "\": already used by "
                + existing.method() + " " + existing.pattern() + ", cannot reuse for "
                + route.method() + " " + route.pattern());
        }
        route.setName(name);
    }

    /** The route registered under {@code name}, or {@code null}. */
    public Route byName(String name) {
        return namedRoutes.get(name);
    }

    /** All registered route names, sorted. */
    public List<String> names() {
        return List.copyOf(namedRoutes.keySet());
    }

    public RouteMatch match(String method, String path) {
        var route = staticRoutes.get(method + ' ' + path);
        if (route != null) return new RouteMatch(route, Map.of());
        for (var candidate : dynamicRoutes.getOrDefault(method, List.of())) {
            var params = candidate.match(path);
            if (params != null) return new RouteMatch(candidate, params);
        }
        return null;
    }

    public List<Route> routes() {
        return List.copyOf(routes);
    }
}

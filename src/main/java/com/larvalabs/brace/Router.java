package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Router {

    private final List<Route> routes = new ArrayList<>();
    // method + ' ' + path → route: O(1) lookup, no regex, for the common static case.
    private final Map<String, Route> staticRoutes = new HashMap<>();
    // Dynamic routes partitioned by method so a match only scans candidates that could win.
    private final Map<String, List<Route>> dynamicRoutes = new HashMap<>();

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

    public RouteMatch match(String method, String path) {
        var found = matchExact(method, path);
        if (found != null) return found;
        // L1: a trailing slash is not a different resource. "/users/" compiled to nothing that
        // could match "/users", so a user who typed the trailing slash — or a link that carried
        // one — got a bare 404 with no hint. Retry once against the canonical form rather than
        // registering two routes or redirecting (a redirect would turn a POST into a GET).
        // "/" itself is canonical and is handled by the exact pass above.
        if (path.length() > 1 && path.endsWith("/")) {
            return matchExact(method, stripTrailingSlashes(path));
        }
        return null;
    }

    private RouteMatch matchExact(String method, String path) {
        var route = staticRoutes.get(method + ' ' + path);
        if (route != null) return new RouteMatch(route, Map.of());
        for (var candidate : dynamicRoutes.getOrDefault(method, List.of())) {
            var params = candidate.match(path);
            if (params != null) return new RouteMatch(candidate, params);
        }
        return null;
    }

    /** Drop trailing slashes, keeping at least "/" — {@code "/a//"} and {@code "/a/"} → {@code "/a"}. */
    private static String stripTrailingSlashes(String path) {
        int end = path.length();
        while (end > 1 && path.charAt(end - 1) == '/') end--;
        return path.substring(0, end);
    }

    public List<Route> routes() {
        return List.copyOf(routes);
    }
}

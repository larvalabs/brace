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
        return register(new Route(method, pattern, handler));
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

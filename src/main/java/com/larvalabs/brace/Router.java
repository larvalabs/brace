package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.List;

public class Router {

    private final List<Route> routes = new ArrayList<>();

    public Route add(String method, String pattern, Handler handler) {
        var route = new Route(method, pattern, handler);
        routes.add(route);
        return route;
    }

    public Route add(String method, String pattern, Object handler, Invoker invoker) {
        var route = new Route(method, pattern, handler, invoker);
        routes.add(route);
        return route;
    }

    public Route add(String method, String pattern, Object handler, Invoker invoker, boolean csrfRequired) {
        var route = new Route(method, pattern, handler, invoker, csrfRequired);
        routes.add(route);
        return route;
    }

    public RouteMatch match(String method, String path) {
        for (var route : routes) {
            if (!route.method().equals(method)) continue;
            if (!route.isStatic()) continue;
            var params = route.match(path);
            if (params != null) return new RouteMatch(route, params);
        }
        for (var route : routes) {
            if (!route.method().equals(method)) continue;
            if (route.isStatic()) continue;
            var params = route.match(path);
            if (params != null) return new RouteMatch(route, params);
        }
        return null;
    }

    public List<Route> routes() {
        return List.copyOf(routes);
    }
}

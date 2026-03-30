package io.brace;

import java.util.ArrayList;
import java.util.List;

public class Router {

    private final List<Route> routes = new ArrayList<>();

    public void add(String method, String pattern, Handler handler) {
        routes.add(new Route(method, pattern, handler));
    }

    public void add(String method, String pattern, Object handler, Invoker invoker) {
        routes.add(new Route(method, pattern, handler, invoker));
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

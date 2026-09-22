package com.larvalabs.brace;

/**
 * Fluent configuration for a route registered within a RouteGroup.
 * Allows naming the route for reverse routing and customizing behavior like CSRF protection.
 */
public class GroupRouteConfig {

    private final RouteGroup group;
    private final Route route;

    GroupRouteConfig(RouteGroup group, Route route) {
        this.group = group;
        this.route = route;
    }

    /**
     * Name this route so {@code Url.to(name, params...)} can build its URL without repeating
     * the pattern. Names must be unique across the app and must not start with {@code /}.
     * Returns this config so {@code .csrf(false)} can follow.
     *
     * @throws IllegalStateException if the name is already used by another route
     */
    public GroupRouteConfig name(String name) {
        group.router().name(route, name);
        return this;
    }

    /**
     * Configure CSRF protection for this route.
     * Set to false for API endpoints that use non-cookie authentication (bearer tokens, etc).
     * Do NOT disable for cookie-authenticated JSON endpoints - those are still CSRF-vulnerable.
     *
     * @param required whether CSRF protection is required (default: true)
     * @return the RouteGroup for further configuration
     */
    public RouteGroup csrf(boolean required) {
        route.setCsrfRequired(required);
        return group;
    }
}

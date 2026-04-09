package io.brace;

/**
 * Fluent configuration for a route registered within a RouteGroup.
 * Allows customizing route behavior like CSRF protection.
 */
public class GroupRouteConfig {

    private final RouteGroup group;
    private final Route route;

    GroupRouteConfig(RouteGroup group, Route route) {
        this.group = group;
        this.route = route;
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

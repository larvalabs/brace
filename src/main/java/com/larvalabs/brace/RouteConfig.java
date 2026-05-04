package com.larvalabs.brace;

/**
 * Fluent configuration for a just-registered route.
 * Allows customizing route behavior like CSRF protection.
 */
public class RouteConfig {

    private final Brace app;
    private final Route route;

    RouteConfig(Brace app, Route route) {
        this.app = app;
        this.route = route;
    }

    /**
     * Configure CSRF protection for this route.
     * Set to false for API endpoints that use non-cookie authentication (bearer tokens, etc).
     * Do NOT disable for cookie-authenticated JSON endpoints - those are still CSRF-vulnerable.
     *
     * @param required whether CSRF protection is required (default: true)
     * @return the Brace app for further configuration
     */
    public Brace csrf(boolean required) {
        route.setCsrfRequired(required);
        return app;
    }
}

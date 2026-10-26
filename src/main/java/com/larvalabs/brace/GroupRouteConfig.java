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
     * Add an access requirement to this route, on top of the app default and any group's. All
     * must pass. See {@link Access}.
     */
    public GroupRouteConfig access(Access requirement) {
        route.addAccess(requirement);
        return this;
    }

    /**
     * Opt this route out of the app's default access ({@link Brace#defaultAccess(Access)}).
     * Every route must either have an access requirement or say this; {@link Brace#start()}
     * refuses to start otherwise. It removes only the default: combining it with
     * {@code .access(...)}, or using it inside a group that has a requirement, fails at start.
     */
    public GroupRouteConfig publicRoute() {
        route.markPublic();
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

    /**
     * Opt a route that <em>does</em> use the cookie session out of CSRF, giving the reason
     * (M2). {@code csrf(false)} alone on such a route fails {@link Brace#start()}: the session
     * cookie rides along on cross-site requests, so without a token the route is forgeable. Only
     * for a route that is safe anyway (an idempotent sign-out, a deliberate cross-site
     * callback); the reason is logged at startup.
     */
    public RouteGroup csrf(boolean required, String reason) {
        if (required) throw new IllegalArgumentException("csrf(true, reason): a reason only applies to an opt-out");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("csrf(false, reason) needs a reason");
        route.setCsrfOptOut(reason);
        return group;
    }

    /**
     * Include or exclude this route from page-view analytics ({@code app.analytics()}).
     * Views are counted by default; turn it off for pages whose URL carries a secret the
     * automatic redaction misses (a short {@code /invite/{code}}; a parameter named like a secret,
     * such as {@code {token}}, is already stored as its placeholder) or that you don't want counted.
     */
    public GroupRouteConfig analytics(boolean counted) {
        route.setAnalytics(counted ? Analytics.Track.PATH : Analytics.Track.OFF);
        return this;
    }

    /**
     * Count this route's views under its pattern ({@code /u/{username}}) instead of each
     * concrete path, for routes with many distinct URLs that are only interesting together.
     */
    public GroupRouteConfig analyticsByRoute() {
        route.setAnalytics(Analytics.Track.ROUTE);
        return this;
    }
}

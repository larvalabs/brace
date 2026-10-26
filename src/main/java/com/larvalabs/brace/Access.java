package com.larvalabs.brace;

import java.util.Map;

/**
 * An access requirement on a route (H1, 2026-10 security review): a guard that returns
 * {@code null} to let the request through or a {@link Result} to reject it.
 *
 * <p>Requirements attach to routes, never to path strings: app-wide with
 * {@link Brace#defaultAccess(Access)}, per group with {@link Brace#group(String, Access,
 * java.util.function.Consumer)}, per route with {@code .access(...)}. They stack — default,
 * then groups outer to inner, then the route's own — and all must pass. {@code .publicRoute()}
 * removes only the default. {@link Brace#start()} refuses to start while any route has no
 * requirement and isn't marked public, so a route can't become public by forgetting a guard.
 *
 * <p>Requirements run after plain {@code before(...)} middleware (so a rate limiter still sheds
 * load first) and before the request body is read and CSRF is checked. A session-reading
 * requirement gets the same {@link Session} instance the handler later receives.
 */
public final class Access {

    /** A requirement that reads the session. Return {@code null} to allow. */
    @FunctionalInterface
    public interface Check {
        Result check(Request req, Session session);
    }

    /** A requirement that needs only the request (a bearer token, an IP allowlist). */
    @FunctionalInterface
    public interface RequestCheck {
        Result check(Request req);
    }

    private final String label;
    private final boolean usesSession;
    private final Check check; // null only for none()
    private final boolean replacesDefault;

    private Access(String label, boolean usesSession, Check check) {
        this(label, usesSession, check, false);
    }

    private Access(String label, boolean usesSession, Check check, boolean replacesDefault) {
        this.label = label;
        this.usesSession = usesSession;
        this.check = check;
        this.replacesDefault = replacesDefault;
    }

    /**
     * Allow the request when the session has {@code key}; otherwise send the client to
     * {@code loginUrl}. A page request gets a 302 to it; an htmx request gets 401 with
     * {@code HX-Redirect: loginUrl}, so htmx navigates instead of swapping the login page into a
     * fragment; a JSON request gets a plain 401.
     */
    public static Access session(String key, String loginUrl) {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Access.session needs a session key");
        if (loginUrl == null || loginUrl.isBlank()) throw new IllegalArgumentException("Access.session needs a login URL");
        return new Access("session:" + key, true, (req, session) ->
            session.has(key) ? null : notLoggedIn(req, loginUrl));
    }

    /** A custom requirement with the session. Return {@code null} to allow, a Result to reject. */
    public static Access check(Check check) {
        if (check == null) throw new IllegalArgumentException("Access.check needs a check");
        return new Access("check", true, check);
    }

    /**
     * A custom requirement that needs only the request. A separate name rather than an overload
     * of {@link #check}, so a method reference never needs a cast.
     */
    public static Access checkRequest(RequestCheck check) {
        if (check == null) throw new IllegalArgumentException("Access.checkRequest needs a check");
        return new Access("checkRequest", false, (req, session) -> check.check(req));
    }

    /**
     * {@code requirement} in place of the app's default, for the routes it covers: a bearer-token
     * API or a signature-checked webhook in an app whose default is a session login. On a route
     * ({@code .access(Access.insteadOfDefault(...))}) or a group
     * ({@code app.group("/api", Access.insteadOfDefault(...), g -> ...)}); requirements from
     * other groups and the route still stack on it. Unlike {@code .publicRoute()}, the route is
     * still guarded.
     */
    public static Access insteadOfDefault(Access requirement) {
        if (requirement == null) throw new IllegalArgumentException("Access.insteadOfDefault needs a requirement");
        if (requirement.isNone()) {
            throw new IllegalArgumentException("Access.insteadOfDefault(Access.none()) is a public route: use .publicRoute()");
        }
        return new Access(requirement.label, requirement.usesSession, requirement.check, true);
    }

    /**
     * The explicit statement that an app has no login: every route is public. Only valid as
     * {@link Brace#defaultAccess(Access)}; logged at startup.
     */
    public static Access none() {
        return NONE;
    }

    private static final Access NONE = new Access("none", false, null);

    /** Framework-internal requirement with a fixed label (the {@code /ops} routes). */
    static Access internal(String label, RequestCheck check) {
        return new Access(label, false, (req, session) -> check.check(req));
    }

    boolean isNone() {
        return this == NONE;
    }

    boolean replacesDefault() {
        return replacesDefault;
    }

    boolean usesSession() {
        return usesSession;
    }

    /** Short description for the startup banner and {@code /ops/routes}. */
    String label() {
        return label;
    }

    /** Runs the requirement. {@code session} is null for requirements that don't read it. */
    Result check(Request req, Session session) {
        return check.check(req, session);
    }

    private static Result notLoggedIn(Request req, String loginUrl) {
        if (req.isHtmx()) {
            return Result.unauthorized().header("HX-Redirect", loginUrl);
        }
        if (wantsJson(req)) {
            return Result.json(Map.of("error", "unauthorized"), 401);
        }
        return Redirect.to(loginUrl);
    }

    private static boolean wantsJson(Request req) {
        String accept = req.header("Accept");
        return req.isJson() || (accept != null && accept.toLowerCase().contains("application/json"));
    }

    @Override
    public String toString() {
        return label;
    }
}

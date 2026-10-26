package com.larvalabs.brace;

/**
 * Server-side check on a decoded session cookie (M10), set with
 * {@link Brace#sessionValidator(SessionValidator)}. The cookie is sealed and self-contained, so
 * nothing on the server can revoke it on its own: a stolen or logged-out-elsewhere cookie stays
 * valid until it expires. A validator is the hook for "log out everywhere", a password change, or
 * a demoted role: compare something in the session (a {@code sessionVersion}) with the current
 * value for that user. Returning false turns the request into an anonymous one and clears the
 * client's cookie.
 *
 * <p>Runs once per request that reads the session, and only for a non-empty session. Keep it
 * cheap: cache the per-user value rather than querying on every request.
 */
@FunctionalInterface
public interface SessionValidator {
    boolean isValid(Session session);
}

package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ops tokens are bound to the key that minted them: removing a key from
 * ops-authorized-keys revokes its outstanding tokens (L4). The token secret is derived from the
 * session secret, so it survives a restart — the authorized-key set is the only thing that can
 * invalidate a still-unexpired token.
 */
class OpsKeyRevocationTest {

    private static final String SECRET = OpsToken.deriveSecret("a-session-secret-at-least-32-chars-long");

    private final OpsKeys.Keypair current = OpsKeys.generateKeypair();
    private final OpsKeys.Keypair removed = OpsKeys.generateKeypair();

    private static OpsHandler handler(OpsKeys.Keypair... keys) {
        var authorized = new java.util.LinkedHashMap<String, OpsScope>();
        for (var k : keys) authorized.put(k.publicKey(), OpsScope.CONTROL);
        return new OpsHandler(new Stats(), null, null, new Router(), authorized, SECRET);
    }

    private static String token(OpsKeys.Keypair key) {
        return OpsToken.create(SECRET, 60, OpsScope.READ, OpsKeys.fingerprint(key.publicKey()));
    }

    private static String sessionToken(OpsKeys.Keypair key) {
        return OpsToken.create(SECRET, System.currentTimeMillis() / 1000 + 60, OpsScope.READ,
            OpsKeys.fingerprint(key.publicKey()), OpsToken.Type.SESSION);
    }

    /** What the router does for an ops route: the route's access requirement, then the handler (H1). */
    private static Result guarded(OpsHandler h, OpsScope scope, Request req,
                                  java.util.function.Function<Request, Result> handler) {
        Result rejected = h.access(scope).check(req, null);
        return rejected != null ? rejected : handler.apply(req);
    }

    private static Result status(OpsHandler h, Request req) {
        return guarded(h, OpsScope.READ, req, h::status);
    }

    private static Request bearer(String token) {
        return new Request("GET", "/ops/status", Map.of(), Map.of(),
            Map.of("Authorization", "Bearer " + token), null);
    }

    private static Request cookie(String token) {
        return new Request("GET", "/ops/status", Map.of(), Map.of(),
            Map.of("Cookie", "__brace_ops_session=" + token), null);
    }

    @Test
    void tokenForACurrentKeyStillWorks() {
        var h = handler(current, removed);
        assertEquals(200, status(h, bearer(token(current))).status());
        assertEquals(200, status(h, cookie(sessionToken(current))).status());
    }

    @Test
    void tokenForAKeyNotInTheAuthorizedSetIsRejected() {
        var h = handler(current);
        assertEquals(401, status(h, bearer(token(removed))).status());
        assertEquals(401, status(h, cookie(sessionToken(removed))).status());
    }

    @Test
    void removingAKeyAndRestartingRevokesItsOutstandingToken() {
        String outstanding = token(removed);
        // Before: both keys authorized.
        assertEquals(200, status(handler(current, removed), bearer(outstanding)).status());
        // "Restart" with the key removed from ops-authorized-keys: same secret, new handler.
        var restarted = handler(current);
        assertEquals(401, status(restarted, bearer(outstanding)).status());
        assertEquals(200, status(restarted, bearer(token(current))).status());
    }

    @Test
    void loweringAKeysScopeCapsItsOutstandingTokens() {
        // A CONTROL token minted while the key was CONTROL; the key is then lowered to
        // scope:read and the app restarted. The token's own claim must not outrank the key.
        String control = OpsToken.create(SECRET, 60, OpsScope.CONTROL, OpsKeys.fingerprint(current.publicKey()));
        var post = new Request("POST", "/ops/cache/clear", Map.of(), Map.of(),
            Map.of("Authorization", "Bearer " + control), null);
        assertNotEquals(401, guarded(handler(current), OpsScope.CONTROL, post, handler(current)::clearCache).status(), "CONTROL key: allowed");

        var readOnly = new java.util.LinkedHashMap<String, OpsScope>();
        readOnly.put(current.publicKey(), OpsScope.READ);
        var lowered = new OpsHandler(new Stats(), null, null, new Router(), readOnly, SECRET);
        assertEquals(401, guarded(lowered, OpsScope.CONTROL, post, lowered::clearCache).status(), "key lowered to READ: CONTROL refused");
        assertEquals(200, status(lowered, bearer(control)).status(), "READ still works");
    }

    @Test
    void tokenWithNoKidIsRejected() {
        // Every token this handler mints carries a kid; a kid-less one is not ours.
        var h = handler(current);
        String noKid = OpsToken.create(SECRET, 60, OpsScope.READ, null);
        assertEquals(401, status(h, bearer(noKid)).status());
    }

    @Test
    void loginTokenOfARevokedKeyCannotBeExchanged() {
        var h = handler(current);
        var req = new Request("GET", "/ops/auth/exchange", Map.of(), Map.of("token", token(removed)), Map.of(), null);
        assertEquals(401, h.exchange(req).status());
    }
}

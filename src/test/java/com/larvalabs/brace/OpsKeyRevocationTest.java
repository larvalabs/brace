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
        assertEquals(200, h.status(bearer(token(current))).status());
        assertEquals(200, h.status(cookie(token(current))).status());
    }

    @Test
    void tokenForAKeyNotInTheAuthorizedSetIsRejected() {
        var h = handler(current);
        assertEquals(401, h.status(bearer(token(removed))).status());
        assertEquals(401, h.status(cookie(token(removed))).status());
    }

    @Test
    void removingAKeyAndRestartingRevokesItsOutstandingToken() {
        String outstanding = token(removed);
        // Before: both keys authorized.
        assertEquals(200, handler(current, removed).status(bearer(outstanding)).status());
        // "Restart" with the key removed from ops-authorized-keys: same secret, new handler.
        var restarted = handler(current);
        assertEquals(401, restarted.status(bearer(outstanding)).status());
        assertEquals(200, restarted.status(bearer(token(current))).status());
    }

    @Test
    void tokenWithNoKidIsRejected() {
        // Every token this handler mints carries a kid; a kid-less one is not ours.
        var h = handler(current);
        String noKid = OpsToken.create(SECRET, 60, OpsScope.READ, null);
        assertEquals(401, h.status(bearer(noKid)).status());
    }

    @Test
    void loginTokenOfARevokedKeyCannotBeExchanged() {
        var h = handler(current);
        var req = new Request("GET", "/ops/auth/exchange", Map.of(), Map.of("token", token(removed)), Map.of(), null);
        assertEquals(401, h.exchange(req).status());
    }
}

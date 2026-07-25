package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8 (2026-07 security review): {@code brace ops dashboard} took the login token from the
 * server's JSON response, concatenated it into a URL, and on Windows handed that URL to
 * {@code cmd /c start}. {@code cmd} re-parses its command line, so an {@code &} in the
 * server-supplied token began a second command on the operator's workstation.
 *
 * <p>The launch path no longer goes through {@code cmd}, and the token is validated before it
 * can become part of any command line — belt and braces, since the validator is the part that
 * holds regardless of which opener a future platform needs.
 */
class CliOpsTokenValidationTest {

    @Test
    void shellMetacharactersAreRejected() {
        assertFalse(CliOps.isBase64Url("x&calc.exe"), "& is what terminates the cmd /c start command");
        assertFalse(CliOps.isBase64Url("x|whoami"));
        assertFalse(CliOps.isBase64Url("x;rm -rf /"));
        assertFalse(CliOps.isBase64Url("x\"y"));
        assertFalse(CliOps.isBase64Url("x'y"));
        assertFalse(CliOps.isBase64Url("x`id`"));
        assertFalse(CliOps.isBase64Url("x$(id)"));
        assertFalse(CliOps.isBase64Url("x y"));
        assertFalse(CliOps.isBase64Url("x\r\ny"));
        assertFalse(CliOps.isBase64Url("x%20y"));
    }

    @Test
    void realTokensAreAccepted() {
        // The exact shape OpsToken mints: base64url payload, '.', base64url HMAC.
        String token = OpsToken.create("a-signing-secret-at-least-32-chars-x", 60, OpsScope.READ, "kid123");
        assertTrue(CliOps.isBase64Url(token), "a genuine ops token must pass: " + token);
        assertTrue(CliOps.isBase64Url("abcXYZ019-_.="));
    }

    @Test
    void emptyOrOversizedTokensAreRejected() {
        assertFalse(CliOps.isBase64Url(null));
        assertFalse(CliOps.isBase64Url(""));
        assertFalse(CliOps.isBase64Url("a".repeat(4097)));
    }
}

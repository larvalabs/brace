package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M10 (2026-10 security review): a sealed cookie session couldn't be revoked server-side, and the
 * secret couldn't be rotated without logging everyone out.
 */
class SessionRevocationTest {

    static final String OLD = "previous-session-secret-at-least-32-chars-aa";
    static final String CURRENT = "current-session-secret-at-least-32-chars-bbb";

    // --- previous secrets ---

    @Test
    void cookieSealedWithAPreviousSecretOpensAndIsResealed() {
        String old = Session.of("userId", "5").toCookie(OLD);
        var session = Session.fromCookie(old, CURRENT, SessionOptions.of(CURRENT).previousSecrets(OLD).previousSecrets());
        assertEquals("5", session.get("userId"));
        assertTrue(session.isModified(), "re-sealed with the current secret on this response");
        // Not opened without the previous secret listed.
        assertNull(Session.fromCookie(old, CURRENT, SessionOptions.of(CURRENT).previousSecrets()).get("userId"));
    }

    @Test
    void rotationEndToEnd() throws Exception {
        var app = Brace.test().start(a -> {
            a.sessions(SessionOptions.of(CURRENT).previousSecrets(OLD));
            a.getSession("/me", (req, s) -> Result.text(String.valueOf(s.get("userId"))));
        });
        try {
            var res = app.request("GET", "/me")
                .header("Cookie", "brace_session=" + Session.of("userId", "5").toCookie(OLD)).send();
            assertEquals("5", res.body());
            var reissued = res.headers("Set-Cookie").stream().filter(c -> c.startsWith("brace_session=")).findFirst();
            assertTrue(reissued.isPresent(), "the old-secret cookie is re-sealed");
            String value = reissued.get().substring("brace_session=".length(), reissued.get().indexOf(';'));
            assertEquals("5", Session.fromCookie(value, CURRENT).get("userId"), "now sealed with the current secret");
        } finally {
            app.stop();
        }
    }

    @Test
    void shortPreviousSecretIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SessionOptions.of(CURRENT).previousSecrets("short"));
    }

    @Test
    void placeholderPreviousSecretRefusedAtStartup() {
        String placeholder = "CHANGE-ME-to-a-random-string-at-least-32-chars";
        var app = Brace.app();
        assertThrows(IllegalArgumentException.class,
            () -> app.sessions(SessionOptions.of(CURRENT).previousSecrets(placeholder)));
    }

    @Test
    void previousSecretCookieWithoutAbsIsRejected() {
        String forged = sealRaw(OLD, "{\"userId\":\"1\",\"_exp\":\"" + (System.currentTimeMillis() / 1000 + 3600) + "\"}");
        var options = SessionOptions.of(CURRENT).previousSecrets(OLD);
        assertNull(Session.fromCookie(forged, CURRENT, options.previousSecrets()).get("userId"));
    }

    @Test
    void previousSecretCookieWithDistantAbsIsRejected() {
        long now = System.currentTimeMillis() / 1000;
        String forged = sealRaw(OLD, "{\"userId\":\"1\",\"_exp\":\"" + (now + 3600) + "\",\"_abs\":\""
            + (now + 3L * 365 * 86400) + "\"}");
        var options = SessionOptions.of(CURRENT).previousSecrets(OLD);
        assertNull(Session.fromCookie(forged, CURRENT, options.previousSecrets()).get("userId"));
    }

    @Test
    void currentSecretCookieWithoutAbsKeepsWorking() {
        String c = sealRaw(CURRENT, "{\"userId\":\"1\",\"_exp\":\"" + (System.currentTimeMillis() / 1000 + 3600) + "\"}");
        assertEquals("1", Session.fromCookie(c, CURRENT, SessionOptions.of(CURRENT).previousSecrets(OLD).previousSecrets()).get("userId"));
    }

    // --- M7 review fixes ---

    @Test
    void anEmptyPreviousSecretCookieDoesNotPassItsDeadlineOn() {
        // Forged with the old secret: no data, a valid _exp and an _abs a century out. Opening it,
        // writing to it and re-sealing must not carry that _abs into the new cookie.
        long now = System.currentTimeMillis() / 1000;
        String forged = sealRaw(OLD, "{\"_exp\":\"" + (now + 3600) + "\",\"_abs\":\""
            + (now + 100L * 365 * 86400) + "\"}");
        var options = SessionOptions.of(CURRENT).previousSecrets(OLD);
        var session = Session.fromCookie(forged, CURRENT, options.previousSecrets());
        session.set("userId", "1");
        String resealed = session.toCookie(CURRENT);
        long abs = Long.parseLong(openRaw(CURRENT, resealed).replaceAll(".*\"_abs\":\"(\\d+)\".*", "$1"));
        assertTrue(abs <= now + Session.DEFAULT_ABSOLUTE_MAX_AGE_SECONDS + 60,
            "the re-sealed _abs must be a fresh, bounded deadline, was " + (abs - now) + "s out");
    }

    @Test
    void previousSecretsWithoutTheConfiguredBoundAreNotHonoured() {
        // The bound travels with SessionOptions.previousSecrets(); a copied (plain) list loses it.
        // That must fail closed: no previous-secret cookie opens, even one with a modest _abs.
        long now = System.currentTimeMillis() / 1000;
        String cookie = sealRaw(OLD, "{\"userId\":\"1\",\"_exp\":\"" + (now + 3600) + "\",\"_abs\":\""
            + (now + 3600) + "\"}");
        var options = SessionOptions.of(CURRENT).previousSecrets(OLD);
        assertEquals("1", Session.fromCookie(cookie, CURRENT, options.previousSecrets()).get("userId"));
        assertNull(Session.fromCookie(cookie, CURRENT, List.copyOf(options.previousSecrets())).get("userId"));
        assertNull(Session.fromCookie(cookie, CURRENT, List.of(OLD)).get("userId"));
    }

    @Test
    void mutatingSessionOptionsAfterSessionsHasNoEffect() throws Exception {
        String placeholder = "CHANGE-ME-to-a-random-string-at-least-32-chars";
        long now = System.currentTimeMillis() / 1000;
        String forged = sealRaw(placeholder, "{\"userId\":\"1\",\"_exp\":\"" + (now + 3600) + "\",\"_abs\":\""
            + (now + 3600) + "\"}");
        var opts = SessionOptions.of(CURRENT);
        String previousMode = System.getProperty("brace.mode");
        System.setProperty("brace.mode", "prod");
        TestApp app;
        try {
            app = Brace.test().start(a -> {
                a.sessions(opts);
                // Accepted by SessionOptions itself (it's 46 characters); the app validated a snapshot.
                opts.previousSecrets(placeholder);
                a.getSession("/me", (req, s) -> Result.text(String.valueOf(s.get("userId"))));
            });
        } finally {
            if (previousMode == null) System.clearProperty("brace.mode");
            else System.setProperty("brace.mode", previousMode);
        }
        try {
            var res = app.request("GET", "/me").header("Cookie", "brace_session=" + forged).send();
            assertEquals("null", res.body(), "a cookie forged with the placeholder must not open");
        } finally {
            app.stop();
        }
    }

    @Test
    void startRevalidatesTheSessionSecretsAsABackstop() {
        // Configured while the mode was dev (placeholder only warns), started under prod.
        String placeholder = "CHANGE-ME-to-a-random-string-at-least-32-chars";
        String previousMode = System.getProperty("brace.mode");
        try {
            var e = assertThrows(Exception.class, () -> Brace.test().start(a -> {
                System.setProperty("brace.mode", "dev");
                a.sessions(SessionOptions.of(CURRENT).previousSecrets(placeholder));
                System.setProperty("brace.mode", "prod");
                a.getSession("/me", (req, s) -> Result.text("x"));
            }));
            Throwable t = e;
            while (t != null && !(t instanceof IllegalArgumentException)) t = t.getCause();
            assertNotNull(t, "refused at start: " + e);
            assertTrue(t.getMessage().contains("previous session secret"), t.getMessage());
        } finally {
            if (previousMode == null) System.clearProperty("brace.mode");
            else System.setProperty("brace.mode", previousMode);
        }
    }

    static String openRaw(String secret, String cookie) {
        try {
            byte[] combined = java.util.Base64.getUrlDecoder().decode(cookie);
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, Session.getCachedKey(secret),
                new javax.crypto.spec.GCMParameterSpec(128, combined, 0, 12));
            return new String(cipher.doFinal(combined, 12, combined.length - 12), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String sealRaw(String secret, String json) {
        try {
            var key = Session.getCachedKey(secret);
            byte[] nonce = new byte[12];
            new java.security.SecureRandom().nextBytes(nonce);
            var cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, new javax.crypto.spec.GCMParameterSpec(128, nonce));
            byte[] ct = cipher.doFinal(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + ct.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ct, 0, combined, nonce.length, ct.length);
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- validator ---

    @Test
    void validatorRevokesSessionsAndClearsTheCookie() throws Exception {
        var currentVersion = new ConcurrentHashMap<String, String>(Map.of("5", "1"));
        var calls = new AtomicInteger();
        var app = Brace.test().sessions(CURRENT).start(a -> {
            a.sessionValidator(s -> {
                calls.incrementAndGet();
                return String.valueOf(s.get("ver")).equals(currentVersion.get(s.get("userId")));
            });
            a.getSession("/me", (req, s) -> Result.text(String.valueOf(s.get("userId"))));
            a.get("/public", req -> Result.text("hi"));
        });
        try {
            var session = Session.of("userId", "5", "ver", "1");
            assertEquals("5", app.get("/me", session).body());

            // "Log out everywhere": bump the user's version.
            currentVersion.put("5", "2");
            var revoked = app.get("/me", session);
            assertEquals("null", revoked.body(), "the old cookie no longer authenticates");
            assertTrue(revoked.headers("Set-Cookie").stream().anyMatch(c -> c.startsWith("brace_session=")),
                "the client's cookie is replaced");

            // Anonymous requests and routes that never read the session don't call it.
            calls.set(0);
            app.evictSessionCookie();
            app.get("/me");
            app.get("/public", session);
            assertEquals(0, calls.get());
        } finally {
            app.stop();
        }
    }

    @Test
    void validatorAppliesToWebSocketUpgrades() throws Exception {
        var app = Brace.app().port(0).banner(false).sessions(CURRENT)
            .sessionValidator(s -> !"revoked".equals(s.get("userId")))
            .defaultAccess(Access.session("userId", "/login"));
        app.ws("/live", ctx -> new Object());
        app.start();
        try {
            var cookie = "brace_session=" + Session.of("userId", "revoked").toCookie(CURRENT);
            var ex = assertThrows(java.util.concurrent.ExecutionException.class, () ->
                java.net.http.HttpClient.newHttpClient().newWebSocketBuilder().header("Cookie", cookie)
                    .buildAsync(java.net.URI.create("ws://localhost:" + app.actualPort() + "/live"),
                        new java.net.http.WebSocket.Listener() {})
                    .get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(401, ((java.net.http.WebSocketHandshakeException) ex.getCause()).getResponse().statusCode());
        } finally {
            app.stop();
        }
    }
}

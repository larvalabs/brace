package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B5: with a shared secret, the ops browser login works behind a load balancer — a login token
 * issued on one instance exchanges on another, and the resulting session cookie (minted on A)
 * validates on B. Two {@code Brace} instances with the same {@code .sessions(secret)} stand in for
 * two boxes behind an LB. No database needed (the ops secret derives from the session secret).
 */
class OpsSharedSecretTest {

    @TempDir static Path tmp;
    static final String SHARED = "shared-ops-session-secret-at-least-32-chars";

    static OpsKeys.Keypair keypair;
    static Brace appA;
    static Brace appB;
    static int portA;
    static int portB;

    static final HttpClient client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();

    @BeforeAll
    static void start() throws Exception {
        keypair = OpsKeys.generateKeypair();
        Path keys = tmp.resolve("authorized-keys");
        Files.writeString(keys, keypair.publicKey() + " test-key\n");

        appA = Brace.app().port(0).ops(keys.toString()).sessions(SHARED);
        appB = Brace.app().port(0).ops(keys.toString()).sessions(SHARED);
        appA.start();
        appB.start();
        portA = appA.actualPort();
        portB = appB.actualPort();
    }

    @AfterAll
    static void stop() throws Exception {
        if (appA != null) appA.stop();
        if (appB != null) appB.stop();
    }

    @Test
    void loginIssuedOnAExchangedAndUsedOnB() throws Exception {
        // Authenticate against A and get a login token from A.
        String bearer = authenticate(portA);
        String loginToken = json(post(portA, "/ops/auth/login-token", bearer), "loginToken");

        // Exchange the login token on B (different instance) — must succeed and set the cookie.
        var exchange = get(portB, "/ops/auth/exchange?token=" + loginToken, null);
        assertEquals(302, exchange.statusCode(), "exchange on a different instance should succeed");
        String cookie = opsCookie(exchange);
        assertNotNull(cookie, "exchange should set the ops session cookie");

        // The cookie minted on B (and equivalently A — same derived secret) validates on A.
        var dash = get(portA, "/ops/dashboard", cookie);
        assertEquals(200, dash.statusCode(), "session cookie should validate on any instance");
    }

    @Test
    void cookieDoesNotValidateUnderADifferentSecret() {
        // The whole scheme rests on the secret being derived deterministically from shared config.
        String a = OpsToken.deriveSecret(SHARED);
        String b = OpsToken.deriveSecret(SHARED);
        String other = OpsToken.deriveSecret("a-totally-different-secret-32-chars-long");
        assertEquals(a, b, "same base secret must derive the same ops secret (cross-instance interop)");
        assertNotEquals(a, other, "different base secret must derive a different ops secret");

        String token = OpsToken.create(a, 60, OpsScope.READ, null);
        assertNotNull(OpsToken.verify(token, b), "token validates under the same derived secret");
        assertNull(OpsToken.verify(token, other), "token must not validate under a different secret");
    }

    // --- helpers ---

    private static String authenticate(int port) throws Exception {
        String ts = java.time.Instant.now().toString();
        byte[] nonceBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(nonceBytes);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String sig = OpsKeys.sign(OpsKeys.v2AuthMessage(keypair.publicKey(), ts, nonce), keypair.privateKey());
        String body = "{\"v\":\"2\",\"publicKey\":\"" + keypair.publicKey() + "\",\"timestamp\":\"" + ts
            + "\",\"nonce\":\"" + nonce + "\",\"signature\":\"" + sig + "\"}";
        var resp = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/ops/auth"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "auth: " + resp.body());
        return json(resp, "token");
    }

    private static HttpResponse<String> post(int port, String path, String bearer) throws Exception {
        var b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
            .POST(HttpRequest.BodyPublishers.noBody());
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(int port, String path, String cookie) throws Exception {
        var b = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET();
        if (cookie != null) b.header("Cookie", cookie);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String json(HttpResponse<String> resp, String field) {
        String body = resp.body();
        int s = body.indexOf("\"" + field + "\":\"") + field.length() + 4;
        int e = body.indexOf("\"", s);
        return body.substring(s, e);
    }

    private static String opsCookie(HttpResponse<String> resp) {
        String setCookie = resp.headers().firstValue("Set-Cookie").orElse("");
        if (!setCookie.contains("__brace_ops_session=")) return null;
        return setCookie.substring(0, setCookie.indexOf(';') < 0 ? setCookie.length() : setCookie.indexOf(';'));
    }
}

package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L3 (2026-10 security review, Phase 3 review pass): script on the app's origin could
 * {@code POST /ops/auth/login-token} with the operator's cookie, read a CONTROL token from the
 * response and use it as a bearer anywhere, renewing it forever. Each token now carries its
 * channel, and each channel accepts only its own: bearer from the header, the 60-second login
 * token only at the exchange, the session token only from the cookie.
 */
class OpsTokenTypeTest {

    static final String OPS_SECRET = "ops-token-type-test-secret-at-least-32-chars";

    static Brace app;
    static int port;
    static String kid;
    static HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    @TempDir static Path tmp;

    @BeforeAll
    static void startApp() throws Exception {
        var key = OpsKeys.generateKeypair();
        kid = OpsKeys.fingerprint(key.publicKey());
        Path keys = tmp.resolve("ops-authorized-keys");
        Files.writeString(keys, key.publicKey() + " test\n");
        app = Brace.app().port(0).opsSecret(OPS_SECRET).ops(keys.toString()).cache(Brace.cache());
        app.after(SecurityHeaders.strict());
        // An app CORS policy that reflects any origin with credentials must not reach /ops.
        app.after((req, result) -> result.header("Access-Control-Allow-Origin", "https://evil.example")
            .header("Access-Control-Allow-Credentials", "true"));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.stop();
    }

    private static HttpResponse<String> send(String method, String path, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .method(method, HttpRequest.BodyPublishers.noBody());
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String bearer(int ttl) {
        return OpsToken.create(OPS_SECRET, ttl, OpsScope.CONTROL, kid);
    }

    private static String loginToken(String bearer) throws Exception {
        var resp = send("POST", "/ops/auth/login-token", "Authorization", "Bearer " + bearer);
        assertEquals(200, resp.statusCode(), resp.body());
        return Json.mapper().readTree(resp.body()).get("loginToken").asText();
    }

    private static String sessionFrom(HttpResponse<?> exchange) {
        String cookie = exchange.headers().allValues("Set-Cookie").stream()
            .filter(c -> c.startsWith("__brace_ops_session=")).findFirst().orElseThrow();
        return cookie.substring("__brace_ops_session=".length(), cookie.indexOf(';'));
    }

    @Test
    void theCookieCantMintALoginToken() throws Exception {
        String session = sessionFrom(send("GET", "/ops/auth/exchange?token=" + loginToken(bearer(600))));
        var resp = send("POST", "/ops/auth/login-token", "Cookie", "__brace_ops_session=" + session,
            "Sec-Fetch-Site", "same-origin");
        assertEquals(403, resp.statusCode(), resp.body());
        assertFalse(resp.body().contains("loginToken"));
    }

    @Test
    void eachTokenWorksOnlyOnItsOwnChannel() throws Exception {
        String bearer = bearer(600);
        String login = loginToken(bearer);
        var exchange = send("GET", "/ops/auth/exchange?token=" + login);
        assertEquals(302, exchange.statusCode());
        String session = sessionFrom(exchange);

        assertEquals(200, send("GET", "/ops/status", "Authorization", "Bearer " + bearer).statusCode());
        assertEquals(200, send("GET", "/ops/status", "Cookie", "__brace_ops_session=" + session).statusCode());
        // Off-channel: none of these is a credential.
        assertEquals(401, send("GET", "/ops/status", "Authorization", "Bearer " + login).statusCode());
        assertEquals(401, send("GET", "/ops/status", "Authorization", "Bearer " + session).statusCode());
        assertEquals(401, send("GET", "/ops/status", "Cookie", "__brace_ops_session=" + bearer).statusCode());
        assertEquals(401, send("GET", "/ops/auth/exchange?token=" + bearer).statusCode());
        assertEquals(401, send("GET", "/ops/auth/exchange?token=" + session).statusCode());
    }

    @Test
    void aLoginTokenNeverOutlivesItsBearer() throws Exception {
        long before = System.currentTimeMillis() / 1000;
        var claims = OpsToken.verify(loginToken(bearer(5)), OPS_SECRET);
        assertNotNull(claims);
        assertEquals(OpsToken.Type.LOGIN, claims.type());
        assertTrue(claims.exp() <= before + 6, "capped at the bearer's expiry, not the 60s default");
    }

    @Test
    void opsResponsesSendNoReferrerAndNoAppCors() throws Exception {
        var exchange = send("GET", "/ops/auth/exchange?token=" + loginToken(bearer(600)));
        assertEquals("no-referrer", exchange.headers().firstValue("Referrer-Policy").orElse(null));
        var status = send("GET", "/ops/status", "Authorization", "Bearer " + bearer(600));
        assertTrue(status.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        assertTrue(status.headers().firstValue("Access-Control-Allow-Credentials").isEmpty());
        // The app's own routes keep the app's headers.
        assertTrue(send("GET", "/nope").headers().firstValue("Access-Control-Allow-Origin").isPresent());
    }

    @Test
    void theOriginFallbackComparesHostAndPort() throws Exception {
        String session = sessionFrom(send("GET", "/ops/auth/exchange?token=" + loginToken(bearer(600))));
        String cookie = "__brace_ops_session=" + session;
        assertEquals(403, send("POST", "/ops/cache/clear", "Cookie", cookie, "Origin", "http://localhost:1").statusCode());
        assertEquals(403, send("POST", "/ops/cache/clear", "Cookie", cookie, "Origin", "null").statusCode());
        assertEquals(200, send("POST", "/ops/cache/clear", "Cookie", cookie, "Origin", "http://localhost:" + port).statusCode());
    }
}

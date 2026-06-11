package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end checks that a read-only ops key cannot reach control endpoints. */
class OpsScopeIntegrationTest {

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @TempDir
    static Path tmpDir;

    static OpsKeys.Keypair controlKey;
    static OpsKeys.Keypair readKey;

    @BeforeAll
    static void startApp() throws Exception {
        controlKey = OpsKeys.generateKeypair();
        readKey = OpsKeys.generateKeypair();

        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile,
            controlKey.publicKey() + "  ops-laptop\n" +
            readKey.publicKey() + "  scope:read  oncall-agent\n");

        var cache = Brace.cache();
        cache.set("k", "v");
        var db = new DatabaseFactory(
            "jdbc:h2:mem:scopedb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null, List.of());

        app = Brace.app().port(0).ops(keysFile.toString()).cache(cache).database(db);
        app.get("/boom", req -> { throw new RuntimeException("scope test error"); });
        app.start();
        port = app.actualPort();

        // Trigger a persisted error so the dashboard renders the resolve button (for CONTROL).
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/boom")).GET().build(),
            HttpResponse.BodyHandlers.discarding());
        Thread.sleep(200);
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
    }

    /** Mint a token for the given key, optionally requesting a scope. Returns the parsed JSON response. */
    private static JsonResponse authenticate(OpsKeys.Keypair kp, String requestedScope) throws Exception {
        String ts = java.time.Instant.now().toString();
        String sig = OpsKeys.sign(ts, kp.privateKey());
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("publicKey", kp.publicKey());
        body.put("timestamp", ts);
        body.put("signature", sig);
        if (requestedScope != null) body.put("scope", requestedScope);
        var resp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.mapper().writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        var node = Json.mapper().readTree(resp.body());
        String token = node.has("token") ? node.get("token").asText() : null;
        String scope = node.has("scope") ? node.get("scope").asText() : null;
        return new JsonResponse(resp.statusCode(), token, scope);
    }

    record JsonResponse(int status, String token, String scope) {}

    private int getStatus(String path, String token) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private int postStatus(String path, String token) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void readKeyMintsReadScopedToken() throws Exception {
        var auth = authenticate(readKey, null);
        assertEquals(200, auth.status());
        assertEquals("read", auth.scope(), "a read-ceiling key gets a read token by default");
    }

    @Test
    void readKeyCannotEscalateToControl() throws Exception {
        // Even asking for control, a read-ceiling key is capped at read.
        var auth = authenticate(readKey, "control");
        assertEquals("read", auth.scope(), "requested control must be down-capped to the key's read ceiling");
    }

    @Test
    void readTokenCanReadButNotControl() throws Exception {
        var read = authenticate(readKey, null).token();
        assertEquals(200, getStatus("/ops/status", read), "read token may read status");
        assertEquals(200, getStatus("/ops/cache", read), "read token may read cache stats");
        assertEquals(401, postStatus("/ops/cache/clear", read), "read token must NOT clear the cache");
    }

    @Test
    void controlTokenCanControl() throws Exception {
        var control = authenticate(controlKey, null).token();
        assertEquals("control", authenticate(controlKey, null).scope());
        assertEquals(200, getStatus("/ops/status", control));
        assertEquals(200, postStatus("/ops/cache/clear", control), "control token may clear the cache");
    }

    private String getBody(String path, String token) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(),
            HttpResponse.BodyHandlers.ofString()).body();
    }

    /** Pull the htmx polling token embedded in the dashboard HTML (hx-headers Bearer value). */
    private static String extractEmbeddedToken(String dashboardHtml) {
        var m = java.util.regex.Pattern.compile("Bearer ([A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+)")
            .matcher(dashboardHtml);
        assertTrue(m.find(), "dashboard should embed a Bearer token for htmx polling");
        return m.group(1);
    }

    // --- H1: the dashboard must mint its embedded token at the caller's scope ---

    @Test
    void dashboardTokenForReadCallerCannotControl() throws Exception {
        var read = authenticate(readKey, null).token();
        String html = getBody("/ops/dashboard", read);
        String embedded = extractEmbeddedToken(html);

        // The embedded token exists for the 5s polling refresh — it must still read...
        assertEquals(200, getStatus("/ops/dashboard", embedded),
            "embedded dashboard token must work for the htmx refresh");
        // ...but scraped from a READ caller's page, it must NOT reach control endpoints (H1).
        assertEquals(401, postStatus("/ops/cache/clear", embedded),
            "token embedded in a READ caller's dashboard must be rejected by CONTROL endpoints");
        assertEquals(401, postStatus("/ops/errors/1/resolve", embedded),
            "token embedded in a READ caller's dashboard must not resolve errors");
    }

    @Test
    void dashboardHidesMutatingControlsAtReadScope() throws Exception {
        var read = authenticate(readKey, null).token();
        String html = getBody("/ops/dashboard", read);
        assertFalse(html.contains("hx-post"),
            "READ dashboard must render no mutating controls at all");
        assertFalse(html.contains(">resolve</button>"),
            "READ dashboard must not render error resolve buttons");
        assertTrue(html.contains("read-only"),
            "READ dashboard should indicate the cache section is read-only");
    }

    @Test
    void dashboardShowsMutatingControlsAtControlScope() throws Exception {
        var control = authenticate(controlKey, null).token();
        String html = getBody("/ops/dashboard", control);
        assertTrue(html.contains("hx-post=\"/ops/cache/clear\""),
            "CONTROL dashboard must render the cache clear button");
        assertTrue(html.contains(">resolve</button>"),
            "CONTROL dashboard must render error resolve buttons");
    }

    // --- H1: loginToken → exchange must preserve the caller's scope end to end ---

    @Test
    void loginExchangePreservesReadScopeEndToEnd() throws Exception {
        var read = authenticate(readKey, null).token();

        // A READ caller may obtain a browser login token (the dashboard is a READ surface)...
        var loginResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth/login-token"))
                .header("Authorization", "Bearer " + read)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResp.statusCode(), "READ key should be able to start a browser login");
        var loginToken = Json.mapper().readTree(loginResp.body()).get("loginToken").asText();

        // ...exchange it for the session cookie...
        var exchange = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth/exchange?token=" + loginToken))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(302, exchange.statusCode());
        String setCookie = exchange.headers().firstValue("Set-Cookie").orElse("");
        assertTrue(setCookie.contains("__brace_ops_session="));
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        // ...the resulting browser session can view the dashboard...
        var dash = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/dashboard"))
                .header("Cookie", cookie).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, dash.statusCode(), "READ browser session may view the dashboard");

        // ...but it must NOT reach control endpoints: scope was preserved across the whole chain.
        var clear = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/cache/clear"))
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(401, clear.statusCode(),
            "session cookie obtained from a READ key must be rejected by CONTROL endpoints");
    }

    @Test
    void loginExchangePreservesControlScope() throws Exception {
        var control = authenticate(controlKey, null).token();
        var loginResp = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth/login-token"))
                .header("Authorization", "Bearer " + control)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, loginResp.statusCode());
        var loginToken = Json.mapper().readTree(loginResp.body()).get("loginToken").asText();

        var exchange = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/auth/exchange?token=" + loginToken))
                .GET().build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(302, exchange.statusCode());
        String setCookie = exchange.headers().firstValue("Set-Cookie").orElse("");
        String cookie = setCookie.substring(0, setCookie.indexOf(';'));

        // A CONTROL key's browser session retains control (no privilege loss either).
        var clear = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/ops/cache/clear"))
                .header("Cookie", cookie)
                .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(200, clear.statusCode(),
            "session cookie obtained from a CONTROL key must keep control access");
    }

    @Test
    void authenticatedAccessIsAudited() throws Exception {
        LogTap.clear();
        var read = authenticate(readKey, null).token();
        getStatus("/ops/status", read);               // granted
        postStatus("/ops/cache/clear", read);         // authenticated but scope-denied
        String kid = OpsKeys.fingerprint(readKey.publicKey());

        var access = LogTap.snapshot().stream()
            .filter(e -> "ops.access".equals(e.fields().get("event")))
            .toList();

        assertTrue(access.stream().anyMatch(e ->
                "/ops/status".equals(e.fields().get("path"))
                && Boolean.TRUE.equals(e.fields().get("granted"))
                && kid.equals(e.fields().get("kid"))),
            "granted read access must be audited and attributed to the key fingerprint");
        assertTrue(access.stream().anyMatch(e ->
                "/ops/cache/clear".equals(e.fields().get("path"))
                && Boolean.FALSE.equals(e.fields().get("granted"))),
            "an authenticated but scope-denied control attempt must be audited as granted=false");
    }
}

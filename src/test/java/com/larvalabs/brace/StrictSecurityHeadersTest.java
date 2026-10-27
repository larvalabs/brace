package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M6 (2026-10 security review): an app using {@code after(SecurityHeaders.strict())} gets the
 * strict headers on every response, and the framework's {@code /ops} pages keep their own,
 * stricter policy regardless of what the app's after-middleware sets.
 */
class StrictSecurityHeadersTest {

    static final String OPS_SECRET = "strict-headers-ops-secret-at-least-32-chars";

    static Brace app;
    static int port;
    static OpsKeys.Keypair key;
    static HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    @TempDir static Path tmp;

    @BeforeAll
    static void startApp() throws Exception {
        key = OpsKeys.generateKeypair();
        Path keys = tmp.resolve("ops-authorized-keys");
        Files.writeString(keys, key.publicKey() + " test\n");
        Path assets = Files.createDirectories(tmp.resolve("public"));
        Files.writeString(assets.resolve("app.css"), "body{}");

        var db = new DatabaseFactory("jdbc:h2:mem:stricthdr" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
            null, null, List.of());
        app = Brace.app().port(0)
            .sessions("strict-headers-session-secret-at-least-32")
            .opsSecret(OPS_SECRET)
            .ops(keys.toString())
            .database(db)
            .cache(Brace.cache())
            .staticFiles("/assets", assets.toString());
        app.after(SecurityHeaders.strict());
        // A lax app policy in another case, aimed at /ops: it must not reach the ops pages.
        app.after("/ops/*", (req, result) -> result.header("content-security-policy", "script-src * 'unsafe-inline'"));
        app.get("/hello", req -> Result.html("<p>hi</p>"));
        app.get("/boom", req -> { throw new RuntimeException("strict headers test error"); });
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.stop();
    }

    private static HttpResponse<String> get(String path, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        for (int i = 0; i < headers.length; i += 2) builder.header(headers[i], headers[i + 1]);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookie() {
        return "__brace_ops_session=" + OpsToken.create(OPS_SECRET, 600, OpsScope.CONTROL,
            OpsKeys.fingerprint(key.publicKey()));
    }

    /** Every value for {@code name}, however the response cased it. */
    private static List<String> all(HttpResponse<?> resp, String name) {
        return resp.headers().allValues(name);
    }

    private static void assertStrict(HttpResponse<?> resp, String what) {
        assertEquals(List.of(SecurityHeaders.STRICT_CSP), all(resp, "Content-Security-Policy"), what);
        assertEquals("nosniff", resp.headers().firstValue("X-Content-Type-Options").orElse(null), what);
        assertEquals("DENY", resp.headers().firstValue("X-Frame-Options").orElse(null), what);
        assertEquals("strict-origin-when-cross-origin", resp.headers().firstValue("Referrer-Policy").orElse(null), what);
        assertEquals("interest-cohort=()", resp.headers().firstValue("Permissions-Policy").orElse(null), what);
    }

    private static void assertOpsPolicy(HttpResponse<?> resp, String what) {
        assertEquals(List.of(OpsHandler.CONTENT_SECURITY_POLICY), all(resp, "Content-Security-Policy"),
            what + ": exactly the ops policy, the app's (any case) replaced");
        assertEquals("nosniff", resp.headers().firstValue("X-Content-Type-Options").orElse(null), what);
        assertEquals("DENY", resp.headers().firstValue("X-Frame-Options").orElse(null), what);
    }

    @Test
    void strictHeadersOnHandlerStaticNotFoundAndServerError() throws Exception {
        var handler = get("/hello");
        assertEquals(200, handler.statusCode());
        assertStrict(handler, "handler");

        var file = get("/assets/app.css");
        assertEquals(200, file.statusCode());
        assertStrict(file, "static file");

        var missing = get("/no-such-page");
        assertEquals(404, missing.statusCode());
        assertStrict(missing, "404");

        var error = get("/boom");
        assertEquals(500, error.statusCode());
        assertStrict(error, "500");
    }

    @Test
    void opsResponsesCarryTheFrameworkPolicy() throws Exception {
        var dashboard = get("/ops/dashboard", "Cookie", cookie());
        assertEquals(200, dashboard.statusCode());
        assertOpsPolicy(dashboard, "dashboard");

        assertOpsPolicy(get("/ops/status", "Cookie", cookie()), "status JSON");
        var unauthenticated = get("/ops/status");
        assertEquals(401, unauthenticated.statusCode());
        assertOpsPolicy(unauthenticated, "401");
        var exchange = get("/ops/auth/exchange?token=bogus");
        assertEquals(401, exchange.statusCode());
        assertOpsPolicy(exchange, "exchange");
    }

    @Test
    void opsPolicyIsAtLeastAsStrictAsTheAppStrictPolicy() {
        // The two are compatible either way round: nothing the ops pages need is outside strict().
        String ops = OpsHandler.CONTENT_SECURITY_POLICY;
        assertTrue(ops.startsWith("default-src 'none';"), ops);
        for (String d : List.of("script-src 'self'", "style-src 'self'", "frame-ancestors 'none'",
                "base-uri 'none'", "form-action 'self'")) {
            assertTrue(ops.contains(d), d);
        }
        assertFalse(ops.contains("unsafe"), ops);
    }

    @Test
    void dashboardIsInlineFreeAndCookieOnly() throws Exception {
        String html = get("/ops/dashboard", "Cookie", cookie()).body();
        assertFalse(java.util.regex.Pattern.compile("<script(?![^>]*\\bsrc=)").matcher(html).find(), "no inline <script>");
        assertFalse(java.util.regex.Pattern.compile("\\son[a-z]+\\s*=", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(html).find(), "no on*= handlers");
        assertFalse(html.contains("style="), "no style attributes");
        assertFalse(html.contains("<style"), "no <style> block");
        assertFalse(html.contains("Bearer"), "no token");
        assertFalse(html.contains("hx-headers"), "no token");
        assertTrue(html.contains("hx-post=\"/ops/cache/clear\""), "CONTROL cookie renders the buttons");
    }

    @Test
    void opsAssetsAreServedWithTypeAndRevalidation() throws Exception {
        for (var asset : List.of(
                new String[] {"/__brace/ops.js", "text/javascript; charset=utf-8", "data-ops-tab"},
                new String[] {"/__brace/ops.css", "text/css; charset=utf-8", ".spark-row"},
                new String[] {"/__brace/ops-analytics.css", "text/css; charset=utf-8", ".rowbar"})) {
            var resp = get(asset[0]);
            assertEquals(200, resp.statusCode(), asset[0]);
            assertEquals(asset[1], resp.headers().firstValue("Content-Type").orElse(null), asset[0]);
            assertEquals("nosniff", resp.headers().firstValue("X-Content-Type-Options").orElse(null), asset[0]);
            assertEquals("public, max-age=0, must-revalidate", resp.headers().firstValue("Cache-Control").orElse(null));
            assertTrue(resp.body().contains(asset[2]), asset[0]);
            String etag = resp.headers().firstValue("ETag").orElseThrow();
            var revalidated = get(asset[0], "If-None-Match", etag);
            assertEquals(304, revalidated.statusCode(), asset[0]);
        }
        String js = get("/__brace/ops.js").body();
        assertFalse(js.contains("Bearer") || js.contains("Authorization"), "ops.js carries no credential logic");
    }
}

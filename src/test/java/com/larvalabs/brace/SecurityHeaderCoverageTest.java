package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1 (2026-07 security review): after-middleware ran only on the normal handler path, so an
 * app that added {@code SecurityHeaders.defaults()} got no X-Frame-Options / Referrer-Policy
 * on static files, 404s, 500s, CSRF 403s or 413s — exactly the responses that most need them.
 * Every response leaving the handler is now decorated.
 */
class SecurityHeaderCoverageTest {

    static Brace app;
    static int port;
    @TempDir static Path assets;

    @BeforeAll
    static void startApp() throws Exception {
        Files.writeString(assets.resolve("app.css"), "body{}");

        app = Brace.app().port(0)
            .maxUploadSize(1024)
            .staticFiles("/assets", assets.toString())
            .sessions("header-coverage-secret-at-least-32-chars");
        app.after(SecurityHeaders.defaults());
        // Scoped after-middleware must stay scoped — the fix must not make everything global.
        app.after("/admin/*", (req, result) -> result.header("X-Admin-Only", "yes"));
        // An after-middleware that throws on every response under /fragile — the error paths must
        // still deliver their own status rather than degrade to a bare 500.
        app.after("/fragile/*", (req, result) -> { throw new IllegalStateException("decorator bug"); });

        app.get("/ok", req -> Result.text("ok"));
        app.get("/admin/panel", req -> Result.text("admin"));
        app.get("/boom", req -> { throw new RuntimeException("kaboom"); });
        app.get("/gone", req -> { throw new NotFoundException(); });
        app.post("/upload", req -> Result.text("got"));
        app.post("/fragile/upload", req -> Result.text("got"));
        app.get("/fragile/gone", req -> { throw new NotFoundException(); });
        app.get("/fragile/ok", req -> Result.text("ok"));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.stop();
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return client().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private static void assertHardened(HttpResponse<String> resp, String what) {
        assertEquals("DENY", resp.headers().firstValue("X-Frame-Options").orElse(null),
            "X-Frame-Options missing on " + what);
        assertEquals("nosniff", resp.headers().firstValue("X-Content-Type-Options").orElse(null),
            "nosniff missing on " + what);
        assertEquals("strict-origin-when-cross-origin",
            resp.headers().firstValue("Referrer-Policy").orElse(null),
            "Referrer-Policy missing on " + what);
    }

    @Test
    void handlerResponseIsHardened() throws Exception {
        assertHardened(get("/ok"), "a normal handler response");
    }

    @Test
    void staticFileIsHardened() throws Exception {
        var resp = get("/assets/app.css");
        assertEquals(200, resp.statusCode());
        assertHardened(resp, "a static file");
    }

    @Test
    void noRouteFoundIsHardened() throws Exception {
        var resp = get("/no-such-path");
        assertEquals(404, resp.statusCode());
        assertHardened(resp, "an unmatched-route 404");
    }

    @Test
    void thrownNotFoundIsHardened() throws Exception {
        var resp = get("/gone");
        assertEquals(404, resp.statusCode());
        assertHardened(resp, "a handler-thrown 404");
    }

    @Test
    void serverErrorIsHardened() throws Exception {
        var resp = get("/boom");
        assertEquals(500, resp.statusCode());
        assertHardened(resp, "a 500");
    }

    @Test
    void csrfRejectionIsHardened() throws Exception {
        var resp = client().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/upload"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("a=1")).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(403, resp.statusCode());
        assertHardened(resp, "a CSRF 403");
    }

    @Test
    void payloadTooLargeIsHardened() throws Exception {
        var resp = client().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/upload"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(4096))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(413, resp.statusCode());
        assertHardened(resp, "a 413");
    }

    @Test
    void scopedAfterMiddlewareStaysScoped() throws Exception {
        assertEquals("yes", get("/admin/panel").headers().firstValue("X-Admin-Only").orElse(null));
        assertTrue(get("/ok").headers().firstValue("X-Admin-Only").isEmpty(),
            "path-scoped after-middleware must not leak onto unrelated paths");
        assertTrue(get("/assets/app.css").headers().firstValue("X-Admin-Only").isEmpty(),
            "path-scoped after-middleware must not leak onto static files");
    }

    @Test
    void errorResponsesSurviveAThrowingAfterMiddleware() throws Exception {
        // Every error path decorates quietly: a broken after-middleware is logged, and the client
        // still gets the real status with the headers the healthy middleware added. The 413 path
        // used the strict chain, so the exception escaped handle() and the client got Jetty's 500.
        var tooLarge = client().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/fragile/upload"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("x".repeat(4096))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertEquals(413, tooLarge.statusCode());
        assertHardened(tooLarge, "a 413 behind a throwing after-middleware");

        var gone = get("/fragile/gone");
        assertEquals(404, gone.statusCode());
        assertHardened(gone, "a thrown 404 behind a throwing after-middleware");

        // On a normal response the chain stays strict: a decorator bug is an application fault,
        // surfaced as a 500 (itself hardened) rather than a 200 missing whatever it was meant to add.
        var ok = get("/fragile/ok");
        assertEquals(500, ok.statusCode());
        assertHardened(ok, "the 500 from a throwing after-middleware");
    }
}

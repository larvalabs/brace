package com.larvalabs.brace;

import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M2 (2026-07 security review): the request body used to be buffered before the
 * before-middleware loops ran, so a rate limiter or auth guard could not shed a request
 * until up to {@code maxUploadSize} had already been read into the heap. The body is now
 * read lazily, after the guards have had their say.
 */
class BodyReadOrderingTest {

    static Brace app;
    static int port;
    static final AtomicInteger bodyReads = new AtomicInteger();

    @BeforeAll
    static void startApp() throws Exception {
        app = Brace.app().port(0).maxUploadSize(1024);

        // A guard that rejects without ever touching the body.
        app.before("/guarded/*", req -> Result.forbidden("nope"));
        app.post("/guarded/upload", req -> {
            bodyReads.incrementAndGet();
            return Result.text("len=" + req.body().length());
        });

        // A guard that DOES read the body (webhook-signature style) — must still see it.
        app.before("/signed/*", req ->
            req.body().contains("valid-signature") ? null : Result.unauthorized("bad sig"));
        app.post("/signed/hook", req -> Result.text("ok:" + req.body().length()));

        app.post("/plain", req -> Result.text("len=" + req.body().length()));
        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (app != null) app.stop();
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build().send(
            HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void guardRejectsOversizedBodyWithoutReadingIt() throws Exception {
        // 4 KB against a 1 KB cap. Before M2 this returned 413 — the body was read (and
        // rejected) before the guard ran. Now the guard wins and the bytes are never buffered.
        var resp = post("/guarded/upload", "x".repeat(4096));
        assertEquals(403, resp.statusCode(),
            "the before-middleware guard must decide before the body is read, got: " + resp.body());
        assertEquals(0, bodyReads.get(), "handler must not have run");
    }

    @Test
    void middlewareThatReadsTheBodyStillSeesIt() throws Exception {
        assertEquals(401, post("/signed/hook", "nope").statusCode());
        var ok = post("/signed/hook", "valid-signature here");
        assertEquals(200, ok.statusCode());
        assertEquals("ok:20", ok.body());
    }

    @Test
    void oversizedBodyStillReturns413WhenNoGuardRejectsIt() throws Exception {
        assertEquals(413, post("/plain", "x".repeat(4096)).statusCode(),
            "the cap must still be enforced for requests that reach a handler");
    }

    @Test
    void bodyUnderTheCapReachesTheHandler() throws Exception {
        var resp = post("/plain", "hello");
        assertEquals(200, resp.statusCode());
        assertEquals("len=5", resp.body());
    }
}

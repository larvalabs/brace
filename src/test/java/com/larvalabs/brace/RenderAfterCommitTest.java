package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M12: template rendering is deferred past the request transaction's commit and connection release.
 * The consequence chosen deliberately (see the perf review todos): a render failure surfaces as a 500
 * with the transaction already committed — rendering is response delivery, not part of the unit of work.
 * Contrast {@code DatabaseIntegrationTest.transactionRollbackOnError}, where the handler itself throws
 * (before producing a Result) and the write IS rolled back.
 */
class RenderAfterCommitTest {

    static Brace app;
    static DatabaseFactory dbFactory;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        dbFactory = new DatabaseFactory(
            "jdbc:h2:mem:renderaftercommit;DB_CLOSE_DELAY=-1", null, null,
            List.of(Post.class));

        app = Brace.app().port(0).database(dbFactory).templates("src/test/resources/views");

        // Inserts a row, then returns a View whose template throws at render time. The render runs
        // after commit, so the row must survive even though the response is a 500.
        app.post("/commit-then-fail-render", (DbHandler) (req, db) -> {
            var p = new Post();
            p.title = "committed-before-broken-render";
            p.body = "x";
            p.createdAt = Instant.now();
            db.insert(p);
            return View.of("brokenRender", "post", p);
        });

        // Inserts a row and returns a View that renders cleanly (the normal DB + render path).
        app.post("/commit-then-ok-render", (DbHandler) (req, db) -> {
            var p = new Post();
            p.title = "committed-with-good-render";
            p.body = "x";
            p.createdAt = Instant.now();
            db.insert(p);
            return View.of("hello");
        });

        // A broken render whose Result also carries handler-set headers and an app cookie. The render
        // must run before any of them reach Jetty, so the 500 carries none of them.
        app.post("/fail-render-with-headers", (DbHandler) (req, db) -> {
            var p = new Post();
            p.title = "broken-render-with-headers";
            p.body = "x";
            p.createdAt = Instant.now();
            return View.of("brokenRender", "post", p)
                .header("X-Handler", "leaked")
                .cookie("app_cookie", "v", 60, true, false, "Lax");
        });

        app.get("/count/{title}", (DbHandler) (req, db) ->
            Json.of(db.count(Post.class, "title = ?", req.pathParam("title"))));

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
        dbFactory.close();
        // Static engine is process-wide; reset so stub-mode tests (ResultTest) are unaffected.
        View.setEngine(null);
    }

    /** Requests this class has sent. Each is recorded in stats once, after its response is written. */
    static final AtomicInteger sent = new AtomicInteger();

    private static long recorded() {
        return app.stats().statusCodeCounts().values().stream().mapToLong(Long::longValue).sum();
    }

    /** Stats lag the response (see {@link TestWait}); wait for every request sent so far to land. */
    private static void awaitAllRecorded() {
        TestWait.until(() -> recorded() >= sent.get(),
            () -> "only " + recorded() + " of " + sent.get() + " requests recorded");
    }

    @BeforeEach
    void settlePreviousTests() {
        // A straggler from the previous test would otherwise land inside the next test's
        // status-count baseline.
        awaitAllRecorded();
    }

    private HttpResponse<String> post(String path) throws Exception {
        sent.incrementAndGet();
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private int count(String title) throws Exception {
        sent.incrementAndGet();
        var r = client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + "/count/" + title)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        return Integer.parseInt(r.body().trim());
    }

    @Test
    void renderFailureAfterCommitKeepsTheWrite() throws Exception {
        assertEquals(0, count("committed-before-broken-render"));

        var response = post("/commit-then-fail-render");
        assertEquals(500, response.statusCode(), "broken render must surface as a 500");

        assertEquals(1, count("committed-before-broken-render"),
            "the transaction committed before the deferred render ran, so the write must persist");
    }

    @Test
    void cleanRenderOnADbRouteStillWorks() throws Exception {
        var response = post("/commit-then-ok-render");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Hello from JTE!"));
        assertEquals(1, count("committed-with-good-render"));
    }

    @Test
    void renderFailureIsACleanErrorResponseCountedOnce() throws Exception {
        // M12: the render runs explicitly before the status and headers are written. When it ran
        // lazily inside the wire write (the state merge b8609b6 left), the failure came after the
        // handler's headers and cookie were already on the Jetty response, so the 500 carried
        // them, and the request was recorded as a 200 and then never as the 500 it became.
        var stats = app.stats();
        long ok = stats.statusCodeCounts().getOrDefault(200, 0L);
        long failed = stats.statusCodeCounts().getOrDefault(500, 0L);

        var response = post("/fail-render-with-headers");

        assertEquals(500, response.statusCode());
        assertTrue(response.headers().firstValue("X-Handler").isEmpty(),
            "handler headers must not leak onto the 500: " + response.headers().map());
        assertTrue(response.headers().allValues("Set-Cookie").stream().noneMatch(c -> c.startsWith("app_cookie=")),
            "handler cookies must not leak onto the 500: " + response.headers().allValues("Set-Cookie"));
        assertEquals("Internal Server Error", response.body());
        awaitAllRecorded();
        assertEquals(1, stats.routeStats().get("POST /fail-render-with-headers").count(), "recorded once");
        assertEquals(failed + 1, stats.statusCodeCounts().getOrDefault(500, 0L), "recorded as the 500 sent");
        assertEquals(ok, stats.statusCodeCounts().getOrDefault(200, 0L), "never recorded as a 200");
    }
}

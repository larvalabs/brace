package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.List;

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

    private HttpResponse<String> post(String path) throws Exception {
        return client.send(HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + port + path))
            .POST(HttpRequest.BodyPublishers.noBody()).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private int count(String title) throws Exception {
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
}

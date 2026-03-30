package io.brace;

import io.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseIntegrationTest {

    static Brace app;
    static DatabaseFactory dbFactory;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

    @BeforeAll
    static void startApp() throws Exception {
        dbFactory = new DatabaseFactory(
            "jdbc:h2:mem:integrationdb;DB_CLOSE_DELAY=-1", null, null,
            List.of(Post.class));

        app = Brace.app().port(0).database(dbFactory);

        // Seed some data
        var db = new Database(dbFactory.openSession());
        db.beginTransaction();
        var post = new Post();
        post.title = "Test Post";
        post.body = "Test Body";
        post.createdAt = Instant.now();
        db.insert(post);
        db.commitTransaction();
        db.close();

        // Route that uses Database
        app.get("/posts", (DbHandler) (req, db2) -> {
            var posts = db2.findAll(Post.class);
            return Json.of(posts);
        });

        app.get("/posts/{id}", (DbHandler) (req, db2) -> {
            var p = db2.find(Post.class, req.longParam("id"));
            if (p == null) return Result.notFound();
            return Json.of(p);
        });

        // Route that does NOT use Database
        app.get("/health", req -> Result.text("ok"));

        // Route that creates data
        app.post("/posts", (DbHandler) (req, db2) -> {
            var p = new Post();
            p.title = "Created via API";
            p.body = "API Body";
            p.createdAt = Instant.now();
            db2.insert(p);
            return Json.of(p, 201);
        });

        // Route that throws (for rollback test)
        app.post("/posts/fail", (DbHandler) (req, db2) -> {
            var p = new Post();
            p.title = "Should Be Rolled Back";
            p.body = "fail";
            p.createdAt = Instant.now();
            db2.insert(p);
            throw new RuntimeException("Deliberate failure");
        });

        app.start();
        port = app.actualPort();
    }

    @AfterAll
    static void stopApp() throws Exception {
        app.stop();
        dbFactory.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthCheckWithoutDatabase() throws Exception {
        var response = get("/health");
        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());
    }

    @Test
    void listPosts() throws Exception {
        var response = get("/posts");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Test Post"));
    }

    @Test
    void getPostById() throws Exception {
        var response = get("/posts/1");
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("Test Post"));
    }

    @Test
    void getPostNotFound() throws Exception {
        var response = get("/posts/99999");
        assertEquals(404, response.statusCode());
    }

    @Test
    void createPost() throws Exception {
        var response = post("/posts", "");
        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("Created via API"));
    }

    @Test
    void transactionRollbackOnError() throws Exception {
        // Count posts before
        var beforeResponse = get("/posts");
        int beforeCount = countOccurrences(beforeResponse.body(), "\"id\"");

        // This should fail and roll back
        var response = post("/posts/fail", "");
        assertEquals(500, response.statusCode());

        // Count posts after — should be the same (rolled back)
        var afterResponse = get("/posts");
        int afterCount = countOccurrences(afterResponse.body(), "\"id\"");
        assertEquals(beforeCount, afterCount, "Transaction should have been rolled back");
    }

    private int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}

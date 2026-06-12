package com.larvalabs.brace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestAppTest {

    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .entities(Post.class)
            .templates("src/test/resources/views")
            .start(app -> {
                app.get("/hello", req -> Result.text("Hello!"));
                app.get("/posts", (DbHandler) (req, db) ->
                    Json.of(db.findAll(Post.class)));
                app.post("/posts", (DbHandler) (req, db) -> {
                    var post = new Post();
                    post.title = req.formParam("title");
                    post.body = req.formParam("body");
                    post.createdAt = Instant.now();
                    db.insert(post);
                    return Json.of(post, 201);
                });
                app.get("/view", req -> View.of("hello"));
            });
    }

    @AfterAll
    static void teardown() throws Exception {
        testApp.stop();
    }

    @BeforeEach
    void reset() {
        testApp.resetDatabase();
    }

    @Test
    void simpleGet() {
        var response = testApp.get("/hello");
        assertEquals(200, response.status());
        assertEquals("Hello!", response.body());
    }

    @Test
    void postFormAndQuery() {
        var response = testApp.post("/posts", Map.of("title", "Test", "body", "Content"));
        assertEquals(201, response.status());

        // Structural assertion via json() — no fragile body().contains(...) substring match.
        var list = testApp.get("/posts");
        assertEquals("Test", list.json().get(0).get("title").asText());
    }

    @Test
    void resetDatabaseClearsData() {
        testApp.withDb(db -> {
            var post = new Post();
            post.title = "Seed";
            post.body = "Data";
            post.createdAt = Instant.now();
            db.insert(post);
        });

        testApp.resetDatabase();

        var response = testApp.get("/posts");
        assertEquals("[]", response.body());
    }

    @Test
    void dbHelper() {
        testApp.withDb(db -> {
            var post = new Post();
            post.title = "Via Helper";
            post.body = "Body";
            post.createdAt = Instant.now();
            db.insert(post);
        });

        // Typed deserialization via bodyAs(TypeReference) for generic types.
        var posts = testApp.get("/posts").bodyAs(new TypeReference<List<Post>>() {});
        assertEquals(1, posts.size());
        assertEquals("Via Helper", posts.get(0).title);
    }

    @Test
    void mailerAccessible() {
        assertNotNull(testApp.mailer());
        assertEquals(0, testApp.mailer().sentCount());
    }

    @Test
    void templateRendering() {
        var response = testApp.get("/view");
        assertEquals(200, response.status());
        assertTrue(response.body().contains("Hello from JTE!"));
    }
}

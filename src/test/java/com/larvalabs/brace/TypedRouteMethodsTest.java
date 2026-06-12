package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The typed route methods exist because the raw overloads of get/post/put/delete are
 * ambiguous for bare lambdas (a 2-arg lambda matches both DbHandler and ReadDbHandler,
 * SessionHandler too; a 3-arg lambda matches FullHandler and ReadFullHandler). Every
 * registration below is a bare lambda — this class compiling at all is the core assertion.
 */
class TypedRouteMethodsTest {

    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .entities(Post.class)
            .start(app -> {
                app.getDb("/db", (req, db) -> Json.of(db.findAll(Post.class)));
                app.postDb("/db", (req, db) -> {
                    var post = new Post();
                    post.title = req.formParam("title");
                    post.body = "b";
                    post.createdAt = Instant.now();
                    db.insert(post);
                    return Json.of(post, 201);
                });
                app.getRead("/read", (req, db) -> Json.of(db.findAll(Post.class)));
                app.getReadFull("/read-full", (req, db, session) ->
                    Result.text(db.count(Post.class) + ":" + session.get("who")));
                app.getSession("/session", (req, session) -> {
                    session.set("who", "alice");
                    return Result.text("set");
                });
                app.getFull("/full", (req, db, session) ->
                    Result.text("full:" + db.count(Post.class)));

                app.group("/grouped", g -> {
                    g.getRead("/read", (req, db) -> Json.of(db.findAll(Post.class)));
                    g.getReadFull("/read-full", (req, db, session) ->
                        Result.text("grouped:" + db.count(Post.class)));
                    g.getDb("/db", (req, db) -> Json.of(db.findAll(Post.class)));
                });
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
    void readRouteQueriesWithoutTransaction() {
        testApp.post("/db", Map.of("title", "ReadMe"));
        var response = testApp.get("/read");
        assertEquals(200, response.status());
        assertTrue(response.body().contains("ReadMe"));
    }

    @Test
    void readFullRouteGetsDatabaseAndSession() {
        var response = testApp.get("/read-full");
        assertEquals(200, response.status());
        assertTrue(response.body().startsWith("0:"));
    }

    @Test
    void dbSessionAndFullVariantsWork() {
        assertEquals(200, testApp.get("/session").status());
        assertEquals("full:0", testApp.get("/full").body());
        assertEquals(201, testApp.post("/db", Map.of("title", "T")).status());
    }

    @Test
    void groupedTypedVariantsWork() {
        testApp.post("/db", Map.of("title", "GroupRead"));
        assertTrue(testApp.get("/grouped/read").body().contains("GroupRead"));
        assertEquals("grouped:1", testApp.get("/grouped/read-full").body());
        assertEquals(200, testApp.get("/grouped/db").status());
    }
}

package com.larvalabs.brace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M6 — new TestApp surface: the {@code request(...)} builder (custom headers for
 * bearer-token APIs), session variants for every verb, {@code postWithCsrf} /
 * {@code putWithCsrf} / {@code deleteWithCsrf}, and JSON assertions
 * ({@code json()}, {@code bodyAs(TypeReference)}).
 */
class TestAppHelpersTest {

    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test()
            .entities(Post.class)
            .sessions("m6-test-secret-at-least-32-characters-x")
            .start(app -> {
                // Bearer-token style API route: the handler reads the Authorization header.
                app.get("/api/token-echo", req ->
                    Result.text(String.valueOf(req.header("Authorization"))));

                // Session-reading routes with CSRF off, to test cookie injection in isolation.
                app.get("/whoami", (SessionHandler) (req, session) ->
                    Result.text(String.valueOf(session.get("user"))));
                app.post("/api/echo", (SessionHandler) (req, session) ->
                    Result.text(session.get("user") + ":" + req.body())).csrf(false);
                app.put("/api/items", (SessionHandler) (req, session) ->
                    Result.text(session.get("user") + ":" + req.formParam("title"))).csrf(false);
                app.delete("/api/items", (SessionHandler) (req, session) ->
                    Result.text("deleted-by:" + session.get("user"))).csrf(false);

                // CSRF-protected mutating routes — the default when sessions are enabled.
                app.post("/protected", (SessionHandler) (req, session) ->
                    Result.text("posted:" + session.get("user")));
                app.put("/protected", (SessionHandler) (req, session) ->
                    Result.text("put:" + req.formParam("title")));
                app.delete("/protected", (SessionHandler) (req, session) ->
                    Result.text("deleted:" + session.get("user")));

                // JSON list endpoint for json() / bodyAs(TypeReference).
                app.get("/posts", (DbHandler) (req, db) -> Json.of(db.findAll(Post.class)));
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

    // --- request(...) builder ---

    @Test
    void builderHeaderReachesHandler() {
        var res = testApp.request("GET", "/api/token-echo")
            .header("Authorization", "Bearer tok-123")
            .send();
        assertEquals(200, res.status());
        assertEquals("Bearer tok-123", res.body());
    }

    @Test
    void builderSendsSessionCookie() {
        var res = testApp.request("GET", "/whoami")
            .session(Session.of("user", "alice"))
            .send();
        assertEquals(200, res.status());
        assertEquals("alice", res.body());
    }

    @Test
    void builderSendsBodyWithContentType() {
        var res = testApp.request("POST", "/api/echo")
            .session(Session.of("user", "alice"))
            .body("{\"n\":1}", "application/json")
            .send();
        assertEquals(200, res.status());
        assertEquals("alice:{\"n\":1}", res.body());
    }

    // --- session variants ---

    @Test
    void getWithSession() {
        var res = testApp.get("/whoami", Session.of("user", "alice"));
        assertEquals(200, res.status());
        assertEquals("alice", res.body());
    }

    @Test
    void postJsonWithSession() {
        var res = testApp.postJson("/api/echo", Map.of("n", 1), Session.of("user", "alice"));
        assertEquals(200, res.status());
        assertEquals("alice:{\"n\":1}", res.body());
    }

    @Test
    void putWithSession() {
        var res = testApp.put("/api/items", Map.of("title", "Renamed"), Session.of("user", "alice"));
        assertEquals(200, res.status());
        assertEquals("alice:Renamed", res.body());
    }

    @Test
    void deleteWithSession() {
        var res = testApp.delete("/api/items", Session.of("user", "alice"));
        assertEquals(200, res.status());
        assertEquals("deleted-by:alice", res.body());
    }

    // --- CSRF helpers ---

    @Test
    void postWithCsrfSucceedsOnProtectedRoute() {
        var res = testApp.postWithCsrf("/protected", Map.of("data", "x"), Session.of("user", "alice"));
        assertEquals(200, res.status());
        assertEquals("posted:alice", res.body());
    }

    @Test
    void plainPostWithoutTokenStill403s() {
        // The raw post(...) deliberately does NOT auto-inject a CSRF token, so a missing
        // token keeps failing loudly — CSRF regressions stay testable.
        var res = testApp.post("/protected", Map.of("data", "x"), Session.of("user", "alice"));
        assertEquals(403, res.status());
    }

    @Test
    void putWithCsrfSucceedsOnProtectedRoute() {
        var res = testApp.putWithCsrf("/protected", Map.of("title", "New"), Session.of("user", "alice"));
        assertEquals(200, res.status());
        assertEquals("put:New", res.body());
    }

    @Test
    void deleteWithCsrfSucceedsOnProtectedRoute() {
        var res = testApp.deleteWithCsrf("/protected", Session.of("user", "alice"));
        assertEquals(200, res.status());
        assertEquals("deleted:alice", res.body());
    }

    @Test
    void deleteWithoutTokenStill403s() {
        var res = testApp.delete("/protected", Session.of("user", "alice"));
        assertEquals(403, res.status());
    }

    // --- JSON assertions ---

    @Test
    void jsonTreeNavigation() {
        insertPost("First post");
        var res = testApp.get("/posts");
        assertEquals(200, res.status());
        assertEquals("First post", res.json().get(0).get("title").asText());
        assertEquals(1, res.json().size());
    }

    @Test
    void bodyAsTypeReferenceRoundTrips() {
        insertPost("Typed");
        var posts = testApp.get("/posts").bodyAs(new TypeReference<List<Post>>() {});
        assertEquals(1, posts.size());
        assertEquals("Typed", posts.get(0).title);
        assertEquals("Body of Typed", posts.get(0).body);
        assertNotNull(posts.get(0).createdAt);
    }

    private void insertPost(String title) {
        testApp.withDb(db -> {
            var post = new Post();
            post.title = title;
            post.body = "Body of " + title;
            post.createdAt = Instant.now();
            db.insert(post);
        });
    }
}

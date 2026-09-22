package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Reverse routing: {@code .name(...)} at registration, {@code Url.to(name, ...)} everywhere else. */
class NamedRoutesTest {

    static final String POSTS_SHOW = "posts.show";
    static final String ADMIN_USERS = "admin.users";

    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        testApp = Brace.test().templates("src/test/resources/views").start(app -> {
            app.get("/posts/{id}", req -> Result.text("post " + req.pathParam("id"))).name(POSTS_SHOW);
            app.post("/api/things", req -> Result.text("created")).name("things.create").csrf(false);
            app.group("/admin", admin -> {
                admin.get("/users", req -> Result.text("users")).name(ADMIN_USERS);
                admin.group("/v2", v2 -> v2.get("/users/{id}", req -> Result.text("u")).name("admin.v2.user"));
            });
            app.get("/links/{id}", req -> View.of("namedLink", "id", req.intPathParam("id")));
            app.get("/users/{id}/posts", req -> {
                var query = req.form(PostsQuery.class).value();
                return Result.text("user " + req.pathParam("id") + " q=" + query.q() + " page=" + query.page());
            }).name("users.posts");
            app.get("/query-links/{id}", req -> View.of("namedQueryLink", "id", req.intPathParam("id")));
            app.get("/redirect/{id}", req -> Result.redirect(Url.to(POSTS_SHOW, req.pathParam("id"))));
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        testApp.stop();
        // Static engine is process-wide; reset so stub-mode tests (ResultTest) are unaffected.
        View.setEngine(null);
    }

    @Test
    void urlToResolvesNamedRoute() {
        assertEquals("/posts/42", Url.to(POSTS_SHOW, 42));
    }

    @Test
    void groupedRoutesResolveToFullPrefixedPath() {
        assertEquals("/admin/users", Url.to(ADMIN_USERS));
        assertEquals("/admin/v2/users/7", Url.to("admin.v2.user", 7));
    }

    @Test
    void nameChainsWithCsrf() {
        var route = testApp.app().routes().stream()
            .filter(r -> "things.create".equals(r.name())).findFirst().orElseThrow();
        assertEquals("/api/things", route.pattern());
        assertFalse(route.csrfRequired());
    }

    @Test
    void generatedUrlActuallyRoutes() {
        var response = testApp.get(Url.to(POSTS_SHOW, 42));
        assertEquals(200, response.status());
        assertEquals("post 42", response.body());
    }

    @Test
    void templatesCanReverseRouteWithoutAnAppReference() {
        var response = testApp.get("/links/5");
        assertEquals(200, response.status());
        assertTrue(response.body().contains("href=\"/posts/5\""), response.body());
        assertTrue(response.body().contains("href=\"/admin/users\""), response.body());
    }

    @Test
    void handlersCanRedirectByName() {
        var response = testApp.get("/redirect/9");
        assertEquals(302, response.status());
        assertEquals("/posts/9", response.header("Location"));
    }

    @Test
    void queryRecordLinkRoundTripsThroughTheHandler() {
        var url = Url.to("users.posts", 7, new PostsQuery("red hat & co", 3));
        assertEquals("/users/7/posts?q=red+hat+%26+co&page=3", url);
        var response = testApp.get(url);
        assertEquals(200, response.status());
        assertEquals("user 7 q=red hat & co page=3", response.body());
    }

    @Test
    void templatesCanBuildQueryRecordLinks() {
        var response = testApp.get("/query-links/5");
        assertEquals(200, response.status());
        // JTE escapes '&' in attribute values; browsers decode it back.
        assertTrue(response.body().contains("href=\"/users/5/posts?q=red+hat&amp;page=2\""), response.body());
    }

    @Test
    void unknownNameFailsLoudly() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Url.to("posts.shwo", 1));
        assertTrue(ex.getMessage().contains(POSTS_SHOW), ex.getMessage());
    }

    @Test
    void duplicateNameFailsAtRegistration() {
        var app = Brace.app();
        app.get("/a", req -> Result.text("a")).name("dup");
        assertThrows(IllegalStateException.class,
            () -> app.get("/b", req -> Result.text("b")).name("dup"));
        // Restore the default router for the other tests in this class.
        Url.router(testApp.app().router());
    }
}

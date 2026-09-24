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
            app.get("/redirect/{id}", req -> Result.redirect(Url.to(POSTS_SHOW, req.pathParam("id"))));
            app.get("/tags/{name}", req -> Result.text("[" + req.pathParam("name") + "] q=" + req.queryParam("q")
                + " tags=" + req.queryParams("tag"))).name("tags.show");
            app.get("/query-links/{id}", req -> View.of("namedQueryLink", "id", req.intPathParam("id")));
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
    void encodedPathValuesRoundTripThroughTheHandler() {
        for (var name : new String[]{"red hat", "c++", "café", "a?b#c", "a;b", "x&y=z", "it's (ok)!", "ann@example.com"}) {
            var response = testApp.get(Url.to("tags.show", name));
            assertEquals(200, response.status(), name);
            assertTrue(response.body().startsWith("[" + name + "]"), name + " -> " + response.body());
        }
    }

    @Test
    void queryRoundTripsThroughTheHandler() {
        var url = Url.to("tags.show", "java", Url.query("q", "red hat & co", "tag", java.util.List.of("a", "b c")));
        assertEquals("/tags/java?q=red+hat+%26+co&tag=a&tag=b+c", url);
        var response = testApp.get(url);
        assertEquals(200, response.status());
        assertEquals("[java] q=red hat & co tags=[a, b c]", response.body());
    }

    @Test
    void templatesCanBuildQueryLinks() {
        var response = testApp.get("/query-links/5");
        assertEquals(200, response.status());
        // JTE escapes '&' in attribute values; browsers decode it back.
        assertTrue(response.body().contains("href=\"/posts/5?q=red+hat&amp;page=2\""), response.body());
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

package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    private final Router router = new Router();

    Result dummyHandler(Request req) {
        return Result.text("ok");
    }

    @Test
    void matchesSimpleRoute() {
        router.add("GET", "/hello", this::dummyHandler);
        var match = router.match("GET", "/hello");
        assertNotNull(match);
    }

    @Test
    void returnsNullForNoMatch() {
        router.add("GET", "/hello", this::dummyHandler);
        var match = router.match("GET", "/goodbye");
        assertNull(match);
    }

    @Test
    void matchesMethodExactly() {
        router.add("GET", "/hello", this::dummyHandler);
        assertNotNull(router.match("GET", "/hello"));
        assertNull(router.match("POST", "/hello"));
    }

    @Test
    void extractsPathParams() {
        router.add("GET", "/posts/{id}", this::dummyHandler);
        var match = router.match("GET", "/posts/42");
        assertNotNull(match);
        assertEquals("42", match.pathParams().get("id"));
    }

    @Test
    void extractsMultiplePathParams() {
        router.add("GET", "/users/{userId}/posts/{postId}", this::dummyHandler);
        var match = router.match("GET", "/users/5/posts/42");
        assertNotNull(match);
        assertEquals("5", match.pathParams().get("userId"));
        assertEquals("42", match.pathParams().get("postId"));
    }

    @Test
    void staticRouteMatchedBeforeParam() {
        router.add("GET", "/posts/new", this::dummyHandler);
        router.add("GET", "/posts/{id}", this::dummyHandler);
        var match = router.match("GET", "/posts/new");
        assertNotNull(match);
        assertTrue(match.pathParams().isEmpty());
    }

    @Test
    void staticRouteWinsRegardlessOfRegistrationOrder() {
        router.add("GET", "/posts/{id}", this::dummyHandler);
        router.add("GET", "/posts/new", this::dummyHandler);
        var match = router.match("GET", "/posts/new");
        assertNotNull(match);
        assertTrue(match.pathParams().isEmpty());
    }

    @Test
    void duplicateStaticRouteFirstRegistrationWins() {
        var first = router.add("GET", "/dup", this::dummyHandler);
        router.add("GET", "/dup", this::dummyHandler);
        assertSame(first, router.match("GET", "/dup").route());
    }

    @Test
    void matchesRootRoute() {
        router.add("GET", "/", this::dummyHandler);
        assertNotNull(router.match("GET", "/"));
    }

    @Test
    void trailingSlashPatternNormalized() {
        // "/about/" compiles to the same matcher as "/about": the bare path matches,
        // a trailing-slash request does not.
        router.add("GET", "/about/", this::dummyHandler);
        assertNotNull(router.match("GET", "/about"));
        assertNull(router.match("GET", "/about/"));
    }

    @Test
    void routeTableListing() {
        router.add("GET", "/", this::dummyHandler);
        router.add("GET", "/posts/{id}", this::dummyHandler);
        router.add("POST", "/posts", this::dummyHandler);
        var routes = router.routes();
        assertEquals(3, routes.size());
    }

    // Route naming

    @Test
    void nameRegistersLookupByName() {
        var route = router.add("GET", "/posts/{id}", this::dummyHandler);
        router.name(route, "posts.show");
        assertSame(route, router.byName("posts.show"));
        assertEquals("posts.show", route.name());
        assertEquals(java.util.List.of("posts.show"), router.names());
    }

    @Test
    void duplicateNameThrowsAtRegistration() {
        router.name(router.add("GET", "/posts/{id}", this::dummyHandler), "posts.show");
        var other = router.add("GET", "/articles/{id}", this::dummyHandler);
        var ex = assertThrows(IllegalStateException.class, () -> router.name(other, "posts.show"));
        assertTrue(ex.getMessage().contains("/posts/{id}"), ex.getMessage());
        assertTrue(ex.getMessage().contains("/articles/{id}"), ex.getMessage());
        assertNull(other.name(), "failed naming must not leave a partial name on the route");
    }

    @Test
    void routeCannotHaveTwoNames() {
        var route = router.add("GET", "/posts/{id}", this::dummyHandler);
        router.name(route, "posts.show");
        assertThrows(IllegalStateException.class, () -> router.name(route, "posts.view"));
        assertNull(router.byName("posts.view"));
    }

    @Test
    void nameMustNotStartWithSlashOrBeBlank() {
        var route = router.add("GET", "/posts", this::dummyHandler);
        assertThrows(IllegalArgumentException.class, () -> router.name(route, "/posts"));
        assertThrows(IllegalArgumentException.class, () -> router.name(route, " "));
        assertThrows(IllegalArgumentException.class, () -> router.name(route, null));
        assertNull(route.name());
    }

    @Test
    void unnamedRoutesAreNotListed() {
        router.add("GET", "/posts", this::dummyHandler);
        assertTrue(router.names().isEmpty());
        assertNull(router.byName("posts"));
    }
}

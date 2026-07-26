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
        // "/about/" compiles to the same matcher as "/about", so the bare path matches.
        router.add("GET", "/about/", this::dummyHandler);
        assertNotNull(router.match("GET", "/about"));
        // ...and since correctness review L1, so does the trailing-slash request. This assertion
        // used to be assertNull, which pinned the bug rather than a requirement: registering
        // "/about/" and then 404ing a request for "/about/" is indefensible either way round.
        assertNotNull(router.match("GET", "/about/"));
    }

    @Test
    void trailingSlashMatchesTheCanonicalRoute() {
        // L1: a trailing slash is not a different resource. Matching (rather than redirecting)
        // keeps non-GET verbs intact — a 301 would turn a POST into a GET and drop its body.
        router.add("GET", "/users", this::dummyHandler);
        router.add("GET", "/posts/{id}", this::dummyHandler);

        assertNotNull(router.match("GET", "/users/"));
        assertNotNull(router.match("GET", "/posts/42/"));
        assertEquals("42", router.match("GET", "/posts/42/").pathParams().get("id"));
    }

    @Test
    void trailingSlashDoesNotInventRoutes() {
        router.add("GET", "/users", this::dummyHandler);
        assertNull(router.match("GET", "/unknown/"));
        assertNull(router.match("POST", "/users/"), "the method must still have to match");
    }

    @Test
    void routeTableListing() {
        router.add("GET", "/", this::dummyHandler);
        router.add("GET", "/posts/{id}", this::dummyHandler);
        router.add("POST", "/posts", this::dummyHandler);
        var routes = router.routes();
        assertEquals(3, routes.size());
    }
}

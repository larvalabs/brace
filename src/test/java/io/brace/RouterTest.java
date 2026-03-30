package io.brace;

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
    void routeTableListing() {
        router.add("GET", "/", this::dummyHandler);
        router.add("GET", "/posts/{id}", this::dummyHandler);
        router.add("POST", "/posts", this::dummyHandler);
        var routes = router.routes();
        assertEquals(3, routes.size());
    }
}

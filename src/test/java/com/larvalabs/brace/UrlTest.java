package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UrlTest {

    @Test
    void staticPath() {
        assertEquals("/users", Url.to("/users"));
    }

    @Test
    void rootPath() {
        assertEquals("/", Url.to("/"));
    }

    @Test
    void singleParam() {
        assertEquals("/users/42", Url.to("/users/{id}", 42));
    }

    @Test
    void multipleParams() {
        assertEquals("/users/42/posts/7", Url.to("/users/{id}/posts/{postId}", 42, 7));
    }

    @Test
    void stringParam() {
        assertEquals("/teams/rockets", Url.to("/teams/{slug}", "rockets"));
    }

    @Test
    void tooFewParamsThrows() {
        assertThrows(IllegalArgumentException.class, () -> Url.to("/users/{id}"));
    }

    @Test
    void mixedStaticAndDynamic() {
        assertEquals("/api/v1/users/42/profile", Url.to("/api/v1/users/{id}/profile", 42));
    }

    // Named routes (reverse routing)

    private Router routerWith(String name, String method, String pattern) {
        var router = new Router();
        router.name(router.add(method, pattern, req -> Result.text("ok")), name);
        return router;
    }

    @Test
    void namedRouteFillsParamsFromRegisteredPattern() {
        Url.router(routerWith("users.show", "GET", "/users/{id}"));
        assertEquals("/users/42", Url.to("users.show", 42));
    }

    @Test
    void namedStaticRoute() {
        Url.router(routerWith("users.index", "GET", "/users"));
        assertEquals("/users", Url.to("users.index"));
    }

    @Test
    void namedRouteTooFewParamsThrows() {
        Url.router(routerWith("users.show", "GET", "/users/{id}"));
        assertThrows(IllegalArgumentException.class, () -> Url.to("users.show"));
    }

    @Test
    void unknownNameThrowsAndListsRegisteredNames() {
        Url.router(routerWith("users.show", "GET", "/users/{id}"));
        var ex = assertThrows(IllegalArgumentException.class, () -> Url.to("users.shwo", 1));
        assertTrue(ex.getMessage().contains("users.shwo"), ex.getMessage());
        assertTrue(ex.getMessage().contains("[users.show]"), ex.getMessage());
    }

    @Test
    void nameLookupWithoutAppThrowsClearly() {
        Url.router(null);
        var ex = assertThrows(IllegalStateException.class, () -> Url.to("users.show", 1));
        assertTrue(ex.getMessage().contains("no Brace app"), ex.getMessage());
    }

    @Test
    void leadingSlashAlwaysMeansPatternEvenWhenRouterIsSet() {
        Url.router(routerWith("users.show", "GET", "/users/{id}"));
        assertEquals("/other/9", Url.to("/other/{id}", 9));
    }
}

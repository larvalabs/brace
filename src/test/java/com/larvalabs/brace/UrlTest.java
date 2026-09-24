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

    @Test
    void tooManyParamsThrows() {
        // Used to return "/users" and silently drop the argument.
        var ex = assertThrows(IllegalArgumentException.class, () -> Url.to("/users", "0xabc"));
        assertTrue(ex.getMessage().contains("Url.query"), ex.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Url.to("/users/{id}", 42, "posts"));
    }

    // Path value encoding

    @Test
    void pathValuesArePercentEncoded() {
        assertEquals("/tags/red%20hat", Url.to("/tags/{name}", "red hat"));
        assertEquals("/tags/caf%C3%A9", Url.to("/tags/{name}", "café"));
        assertEquals("/tags/a%3Fb%23c%3Bd", Url.to("/tags/{name}", "a?b#c;d"));
        assertEquals("/tags/%22%3C%3E", Url.to("/tags/{name}", "\"<>"));
    }

    @Test
    void safePathCharactersStayLiteral() {
        assertEquals("/tags/c++", Url.to("/tags/{name}", "c++"));
        assertEquals("/u/ann@example.com", Url.to("/u/{email}", "ann@example.com"));
        assertEquals("/x/a-b_c.d~e:f,g=h&i", Url.to("/x/{v}", "a-b_c.d~e:f,g=h&i"));
    }

    @Test
    void enumsUseName() {
        assertEquals("/status/ACTIVE", Url.to("/status/{s}", Status.ACTIVE));
    }

    @Test
    void unroutablePathValuesThrow() {
        for (var bad : new String[]{"a/b", "50%", "", ".", ".."}) {
            var ex = assertThrows(IllegalArgumentException.class, () -> Url.to("/tags/{name}", bad), bad);
            assertTrue(ex.getMessage().contains("{name}"), ex.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Url.to("/tags/{name}", (Object) null));
    }

    // Query strings

    enum Status { ACTIVE }

    @Test
    void queryIsAppendedAndFormEncoded() {
        assertEquals("/posts?q=red+hat+%26+co&page=2", Url.to("/posts", Url.query("q", "red hat & co", "page", 2)));
    }

    @Test
    void queryFollowsPathArguments() {
        assertEquals("/users/42/posts?page=3", Url.to("/users/{id}/posts", 42, Url.query("page", 3)));
    }

    @Test
    void nullAndEmptyQueryValuesAreLeftOut() {
        assertEquals("/posts?tag=java", Url.to("/posts", Url.query("q", null, "tag", "java", "sort", "")));
        assertEquals("/posts", Url.to("/posts", Url.query("q", null)));
    }

    @Test
    void collectionValuesRepeatTheName() {
        assertEquals("/posts?tag=a&tag=b", Url.to("/posts", Url.query("tag", java.util.List.of("a", "b"))));
    }

    @Test
    void queryValuesFormatEnumsAndDecimals() {
        assertEquals("/p?s=ACTIVE&min=1000", Url.to("/p",
            Url.query("s", Status.ACTIVE, "min", new java.math.BigDecimal("1E+3"))));
    }

    @Test
    void queryOnPatternWithQueryJoinsWithAmpersand() {
        assertEquals("/p?a=1&b=2", Url.to("/p?a=1", Url.query("b", 2)));
    }

    @Test
    void queryMustBeLastArgument() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> Url.to("/users/{id}", Url.query("page", 2), 42));
        assertTrue(ex.getMessage().contains("last argument"), ex.getMessage());
    }

    @Test
    void queryRejectsOddArgumentsAndBadNames() {
        assertThrows(IllegalArgumentException.class, () -> Url.query("q"));
        assertThrows(IllegalArgumentException.class, () -> Url.query(1, "x"));
        assertThrows(IllegalArgumentException.class, () -> Url.query("", "x"));
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
    void namedRouteWithQuery() {
        Url.router(routerWith("catalog.list", "GET", "/catalog/list"));
        assertEquals("/catalog/list?project=punks", Url.to("catalog.list", Url.query("project", "punks")));
        assertThrows(IllegalArgumentException.class, () -> Url.to("catalog.list", "punks"));
    }

    @Test
    void leadingSlashAlwaysMeansPatternEvenWhenRouterIsSet() {
        Url.router(routerWith("users.show", "GET", "/users/{id}"));
        assertEquals("/other/9", Url.to("/other/{id}", 9));
    }
}

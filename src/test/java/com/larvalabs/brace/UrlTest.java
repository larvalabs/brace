package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

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

    // Surplus path arguments

    @Test
    void tooManyParamsThrows() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Url.to("/users/{id}", 42, 99));
        assertTrue(ex.getMessage().contains("1 placeholder(s) but 2 path argument(s)"), ex.getMessage());
        assertTrue(ex.getMessage().contains("record"), ex.getMessage());
    }

    @Test
    void surplusArgOnStaticPatternThrows() {
        assertThrows(IllegalArgumentException.class, () -> Url.to("/catalog/list", "0xabc"));
    }

    @Test
    void namedRouteSurplusArgThrows() {
        Url.router(routerWith("catalog.list", "GET", "/catalog/list"));
        assertThrows(IllegalArgumentException.class, () -> Url.to("catalog.list", "0xabc"));
    }

    @Test
    void surplusArgBeforeQueryRecordThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> Url.to("/users/{id}", 42, 99, new PageQuery(2)));
    }

    // Query records

    record ListQuery(String project, String collector, String q, Integer page) {}
    record PageQuery(Integer page) {}
    record Flags(int count, boolean archived) {}
    record Ordered(String z, String a, String m) {}
    record Text(String v) {}
    record UserId(long value) {}
    record WithList(String name, List<String> tags) {}
    enum Sort { NEWEST, OLDEST }
    record AllTypes(String s, Integer i, Long l, Double d, Float f, Boolean b, BigDecimal bd,
                    Sort e, LocalDate date, Instant at, int pi, long pl, double pd, float pf, boolean pb) {}

    @Test
    void queryRecordWithLiteralPattern() {
        assertEquals("/catalog/list?project=cryptopunks&q=red+hat",
            Url.to("/catalog/list", new ListQuery("cryptopunks", null, "red hat", null)));
    }

    @Test
    void queryRecordWithNamedRoute() {
        Url.router(routerWith("catalog.list", "GET", "/catalog/list"));
        assertEquals("/catalog/list?collector=0xabc&page=3",
            Url.to("catalog.list", new ListQuery(null, "0xabc", null, 3)));
    }

    @Test
    void queryRecordAfterPathArgs() {
        Url.router(routerWith("users.posts", "GET", "/users/{id}/posts"));
        assertEquals("/users/42/posts?page=2", Url.to("users.posts", 42, new PageQuery(2)));
        assertEquals("/a/1/b/2?page=5", Url.to("/a/{x}/b/{y}", 1, 2, new PageQuery(5)));
    }

    @Test
    void allNullQueryRecordAddsNoQuestionMark() {
        assertEquals("/catalog/list", Url.to("/catalog/list", new ListQuery(null, null, null, null)));
        assertEquals("/", Url.to("/", new PageQuery(null)));
    }

    @Test
    void emptyStringsAreSkipped() {
        assertEquals("/s?q=x", Url.to("/s", new ListQuery("", null, "x", null)));
    }

    @Test
    void primitiveZeroAndFalseAreWritten() {
        assertEquals("/x?count=0&archived=false", Url.to("/x", new Flags(0, false)));
    }

    @Test
    void declarationOrderIsKept() {
        assertEquals("/x?z=1&a=2&m=3", Url.to("/x", new Ordered("1", "2", "3")));
    }

    @Test
    void valuesAreFormEncoded() {
        assertEquals("/x?v=a+b", Url.to("/x", new Text("a b")));
        assertEquals("/x?v=a%26b%3Dc", Url.to("/x", new Text("a&b=c")));
        assertEquals("/x?v=1%2B1", Url.to("/x", new Text("1+1")));
        assertEquals("/x?v=%23top", Url.to("/x", new Text("#top")));
        assertEquals("/x?v=a%2Fb", Url.to("/x", new Text("a/b")));
        assertEquals("/x?v=100%25", Url.to("/x", new Text("100%")));
        assertEquals("/x?v=caf%C3%A9+%E2%9C%93", Url.to("/x", new Text("café ✓")));
    }

    @Test
    void everySupportedTypeIsWritten() {
        var r = new AllTypes("s", 1, 2L, 1.5, 2.5f, true, new BigDecimal("12.50"), Sort.OLDEST,
            LocalDate.of(2026, 9, 22), Instant.parse("2026-09-22T10:15:30Z"), 3, 4L, 0.25, 0.5f, true);
        assertEquals("/x?s=s&i=1&l=2&d=1.5&f=2.5&b=true&bd=12.50&e=OLDEST&date=2026-09-22"
                + "&at=2026-09-22T10%3A15%3A30Z&pi=3&pl=4&pd=0.25&pf=0.5&pb=true",
            Url.to("/x", r));
    }

    @Test
    void bigDecimalIsWrittenPlain() {
        record Amount(BigDecimal amount) {}
        assertEquals("/x?amount=1000", Url.to("/x", new Amount(new BigDecimal("1E+3"))));
    }

    @Test
    void unsupportedComponentTypeThrowsNamingIt() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> Url.to("/x", new WithList("n", List.of("a"))));
        assertTrue(ex.getMessage().contains("WithList"), ex.getMessage());
        assertTrue(ex.getMessage().contains("tags"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Supported types"), ex.getMessage());
    }

    @Test
    void unsupportedComponentTypeThrowsEvenWhenNull() {
        assertThrows(IllegalArgumentException.class, () -> Url.to("/x", new WithList("n", null)));
    }

    @Test
    void recordInNonFinalPositionThrows() {
        var ex = assertThrows(IllegalArgumentException.class,
            () -> Url.to("/users/{id}/posts", new UserId(42), new PageQuery(1)));
        assertTrue(ex.getMessage().contains("UserId"), ex.getMessage());
        assertTrue(ex.getMessage().contains("last argument"), ex.getMessage());
    }

    @Test
    void typedIdRecordAsLastArgumentFailsWithHint() {
        var ex = assertThrows(IllegalArgumentException.class, () -> Url.to("/users/{id}", new UserId(42)));
        assertTrue(ex.getMessage().contains("UserId record was used as the query string"), ex.getMessage());
    }

    @Test
    void queryRecordOnPatternWithQuestionMarkThrows() {
        assertThrows(IllegalArgumentException.class, () -> Url.to("/search?x=1", new PageQuery(1)));
    }

    @Test
    void queryRecordRoundTripsThroughFormBinder() {
        var original = new AllTypes("red hat & co/é ✓ 100%+#", -7, 9_000_000_000L, 0.1, 1.0e10f, false,
            new BigDecimal("-12.50"), Sort.NEWEST, LocalDate.of(2000, 2, 29),
            Instant.parse("2026-09-22T10:15:30.123Z"), -1, Long.MAX_VALUE, 1e-9, 3.25f, true);
        assertEquals(original, roundTrip(original));

        var withNulls = new AllTypes(null, null, null, null, null, null, null, null, null, null,
            0, 0L, 0.0, 0.0f, false);
        assertEquals(withNulls, roundTrip(withNulls));
    }

    private static AllTypes roundTrip(AllTypes r) {
        var url = Url.to("/x", r);
        int q = url.indexOf('?');
        var params = Request.parseSingleValues(q < 0 ? "" : url.substring(q + 1), true);
        var form = FormBinder.bind(AllTypes.class, params);
        assertFalse(form.hasErrors(), () -> url + " -> " + form.allErrors());
        return form.value();
    }
}

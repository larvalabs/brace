package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * L11: {@code req.queryParams(name)} / {@code req.formParams(name)} expose ALL values of
 * a repeated parameter ({@code <select multiple>}, checkbox groups), while the existing
 * single-value accessors keep their last-value-wins behavior.
 */
class MultiValueParamsTest {

    static TestApp app;

    @BeforeAll
    static void setup() throws Exception {
        app = Brace.test().start(a -> {
            // "all|single" — multi-value list joined with commas, then the single-value accessor.
            a.get("/q", req ->
                Result.text(String.join(",", req.queryParams("tag")) + "|" + req.queryParam("tag")));
            a.post("/f", req ->
                Result.text(String.join(",", req.formParams("tag")) + "|" + req.formParam("tag")));
        });
    }

    @AfterAll
    static void teardown() throws Exception {
        app.stop();
    }

    @Test
    void queryParamsReturnsAllValuesInOrder() {
        assertEquals("a,b|b", app.get("/q?tag=a&tag=b").body());
    }

    @Test
    void queryParamsUrlDecodesValues() {
        assertEquals("hello world,b&c|b&c", app.get("/q?tag=hello%20world&tag=b%26c").body());
    }

    @Test
    void queryParamsAbsentIsEmptyList() {
        assertEquals("|null", app.get("/q?other=x").body());
    }

    @Test
    void singleValueQueryParamStillLastWins() {
        // Pins the existing map semantics: ?tag=a&tag=b -> queryParam("tag") == "b".
        assertEquals("b", app.get("/q?tag=a&tag=b").body().split("\\|")[1]);
    }

    @Test
    void formParamsReturnsAllValuesInOrder() {
        var res = app.request("POST", "/f")
            .body("tag=a&tag=b", "application/x-www-form-urlencoded")
            .send();
        assertEquals("a,b|b", res.body());
    }

    @Test
    void formParamsUrlDecodesValues() {
        var res = app.request("POST", "/f")
            .body("tag=hello+world&tag=b%26c", "application/x-www-form-urlencoded")
            .send();
        assertEquals("hello world,b&c|b&c", res.body());
    }

    @Test
    void formParamsAbsentIsEmptyList() {
        var res = app.request("POST", "/f")
            .body("other=x", "application/x-www-form-urlencoded")
            .send();
        assertEquals("|null", res.body());
    }

    // --- Unit-level: hand-constructed Request (no raw query string available) ---

    @Test
    void handConstructedRequestFallsBackToSingleValueMap() {
        var req = new Request("GET", "/q", Map.of(), Map.of("tag", "only"), Map.of(), null);
        assertEquals(List.of("only"), req.queryParams("tag"));
        assertEquals(List.of(), req.queryParams("missing"));
    }

    @Test
    void handConstructedRequestFormParamsParseBody() {
        var req = new Request("POST", "/f", Map.of(), Map.of(), Map.of(), "tag=a&tag=b&x=1");
        assertEquals(List.of("a", "b"), req.formParams("tag"));
        assertEquals(List.of(), req.formParams("missing"));
    }
}

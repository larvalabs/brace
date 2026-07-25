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

    // --- Multipart (correctness review M1) ---

    /**
     * A repeated field must survive multipart parsing. The parser used to accumulate non-file
     * parts into a {@code Map<String,String>} before re-encoding them, so a checkbox group
     * submitted as multipart kept only its last value while the byte-identical urlencoded
     * submission kept all of them.
     */
    @Test
    void multipartFormParamsReturnsAllValuesInOrder() {
        assertEquals("a,b,c|c", multipartPost("tag", "a", "tag", "b", "tag", "c"));
    }

    @Test
    void multipartSingleValueIsUnchanged() {
        assertEquals("only|only", multipartPost("tag", "only"));
    }

    @Test
    void multipartValuesNeedingEncodingRoundTrip() {
        assertEquals("a&b,c=d,e f|e f", multipartPost("tag", "a&b", "tag", "c=d", "tag", "e f"));
    }

    @Test
    void multipartInterleavedFieldsKeepPerNameOrder() {
        assertEquals("a,b|b", multipartPost("tag", "a", "other", "x", "tag", "b"));
    }

    /** POST the given name/value pairs as {@code multipart/form-data}, returning "/f"'s body. */
    private static String multipartPost(String... namesAndValues) {
        String boundary = "----braceMultiValueTest";
        var body = new StringBuilder();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            body.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"").append(namesAndValues[i])
                .append("\"\r\n\r\n")
                .append(namesAndValues[i + 1]).append("\r\n");
        }
        body.append("--").append(boundary).append("--\r\n");
        return app.request("POST", "/f")
            .body(body.toString(), "multipart/form-data; boundary=" + boundary)
            .send().body();
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

package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RequestTest {

    @Test
    void pathParams() {
        var req = new Request("GET", "/posts/42", Map.of("id", "42"), Map.of(), Map.of(), null);
        assertEquals("42", req.pathParam("id"));
        assertEquals(42, req.intPathParam("id"));
    }

    @Test
    void queryParams() {
        var req = new Request("GET", "/search", Map.of(), Map.of("q", "hello", "page", "2"), Map.of(), null);
        assertEquals("hello", req.queryParam("q"));
        assertEquals(2, req.queryInt("page"));
    }

    @Test
    void pathParamIndependent() {
        var req = new Request("GET", "/posts/42", Map.of("id", "42"), Map.of("id", "99"), Map.of(), null);
        assertEquals("42", req.pathParam("id"));
        assertEquals("99", req.queryParam("id"));
    }

    @Test
    void headers() {
        var req = new Request("GET", "/", Map.of(), Map.of(), Map.of("Accept", "text/html"), null);
        assertEquals("text/html", req.header("Accept"));
    }

    @Test
    void headersAreCaseInsensitive() {
        var req = new Request("GET", "/", Map.of(), Map.of(),
            Map.of("Content-Type", "application/json"), null);
        assertEquals("application/json", req.header("content-type"));
        assertEquals("application/json", req.header("CONTENT-TYPE"));
        assertTrue(req.hasHeader("content-type"));
        assertTrue(req.isJson());
    }

    @Test
    void method() {
        var req = new Request("POST", "/posts", Map.of(), Map.of(), Map.of(), null);
        assertEquals("POST", req.method());
    }

    @Test
    void path() {
        var req = new Request("GET", "/posts/42", Map.of(), Map.of(), Map.of(), null);
        assertEquals("/posts/42", req.path());
    }

    @Test
    void body() {
        var req = new Request("POST", "/posts", Map.of(), Map.of(), Map.of(), "{\"title\":\"hello\"}");
        assertEquals("{\"title\":\"hello\"}", req.body());
    }

    @Test
    void bodyAs() {
        record TestDto(String title) {}
        var req = new Request("POST", "/posts", Map.of(), Map.of(), Map.of(), "{\"title\":\"hello\"}");
        var dto = req.bodyAs(TestDto.class);
        assertEquals("hello", dto.title());
    }

    @Test
    void intPathParamThrowsForMissing() {
        var req = new Request("GET", "/", Map.of(), Map.of(), Map.of(), null);
        assertThrows(NumberFormatException.class, () -> req.intPathParam("missing"));
    }

    @Test
    void defaultedQueryIntReturnsDefaultOnUnparseable() {
        var req = new Request("GET", "/search", Map.of(), Map.of("page", "abc"), Map.of(), null);
        assertEquals(1, req.queryInt("page", 1));
    }

    @Test
    void defaultedQueryIntStillParsesValidValue() {
        var req = new Request("GET", "/search", Map.of(), Map.of("page", "7"), Map.of(), null);
        assertEquals(7, req.queryInt("page", 1));
    }

    @Test
    void defaultedQueryIntReturnsDefaultOnMissing() {
        var req = new Request("GET", "/search", Map.of(), Map.of(), Map.of(), null);
        assertEquals(1, req.queryInt("page", 1));
    }

    @Test
    void defaultedQueryLongReturnsDefaultOnUnparseable() {
        var req = new Request("GET", "/search", Map.of(), Map.of("since", "not-a-number"), Map.of(), null);
        assertEquals(99L, req.queryLong("since", 99L));
    }

    @Test
    void defaultedQueryLongStillParsesValidValue() {
        var req = new Request("GET", "/search", Map.of(), Map.of("since", "123456789012"), Map.of(), null);
        assertEquals(123456789012L, req.queryLong("since", 99L));
    }

    @Test
    void nonDefaultedQueryIntStillThrowsOnUnparseable() {
        var req = new Request("GET", "/search", Map.of(), Map.of("page", "abc"), Map.of(), null);
        assertThrows(NumberFormatException.class, () -> req.queryInt("page"));
    }

    @Test
    void nonDefaultedQueryLongStillThrowsOnUnparseable() {
        var req = new Request("GET", "/search", Map.of(), Map.of("since", "abc"), Map.of(), null);
        assertThrows(NumberFormatException.class, () -> req.queryLong("since"));
    }

    @Test
    void intPathParamStillThrowsOnUnparseable() {
        var req = new Request("GET", "/posts/abc", Map.of("id", "abc"), Map.of(), Map.of(), null);
        assertThrows(NumberFormatException.class, () -> req.intPathParam("id"));
    }
}

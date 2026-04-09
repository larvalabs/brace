package io.brace;

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
}

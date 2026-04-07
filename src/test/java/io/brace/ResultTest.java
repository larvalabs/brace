package io.brace;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ResultTest {

    @Test
    void textResult() {
        var result = Result.text("hello");
        assertEquals(200, result.status());
        assertEquals("text/plain", result.contentType());
        assertEquals("hello", result.body());
    }

    @Test
    void notFoundResult() {
        var result = Result.notFound();
        assertEquals(404, result.status());
    }

    @Test
    void errorResult() {
        var result = Result.error(500, "something broke");
        assertEquals(500, result.status());
        assertEquals("something broke", result.body());
    }

    @Test
    void noContentResult() {
        var result = Result.noContent();
        assertEquals(204, result.status());
        assertEquals("", result.body());
    }

    @Test
    void jsonResult() {
        var result = Json.of(Map.of("name", "Alice", "age", 30));
        assertEquals(200, result.status());
        assertEquals("application/json", result.contentType());
        assertTrue(result.body().contains("\"name\""));
        assertTrue(result.body().contains("\"Alice\""));
    }

    @Test
    void jsonResultWithStatus() {
        var result = Json.of(Map.of("id", 1), 201);
        assertEquals(201, result.status());
        assertEquals("application/json", result.contentType());
    }

    @Test
    void redirectResult() {
        var result = Redirect.to("/login");
        assertEquals(302, result.status());
        assertEquals("/login", result.header("Location"));
    }

    @Test
    void permanentRedirectResult() {
        var result = Redirect.permanent("/new-url");
        assertEquals(301, result.status());
        assertEquals("/new-url", result.header("Location"));
    }

    @Test
    void viewStubResult() {
        var result = View.of("posts/show", "post", "hello");
        assertEquals(200, result.status());
        assertEquals("text/html", result.contentType());
        assertTrue(result.body().contains("posts/show"));
    }

    @Test
    void downloadSetsContentType() {
        var result = Result.download(new byte[]{1, 2, 3}, "application/pdf", "report.pdf");
        assertEquals("application/pdf", result.contentType());
    }

    @Test
    void downloadSetsContentDispositionHeader() {
        var result = Result.download(new byte[]{1, 2, 3}, "application/pdf", "report.pdf");
        assertEquals("attachment; filename=\"report.pdf\"", result.header("Content-Disposition"));
    }

    @Test
    void downloadHasRawBytes() {
        byte[] data = new byte[]{1, 2, 3};
        var result = Result.download(data, "application/pdf", "report.pdf");
        assertArrayEquals(data, result.rawBytes());
    }

    @Test
    void notFoundIfNullReturnsValueWhenNonNull() {
        String value = Result.notFoundIfNull("hello");
        assertEquals("hello", value);
    }

    @Test
    void notFoundIfNullThrowsWhenNull() {
        assertThrows(NotFoundException.class, () -> Result.notFoundIfNull(null));
    }

    @Test
    void resultHeaders() {
        var result = Result.text("hello");
        result.header("X-Custom", "value");
        assertEquals("value", result.header("X-Custom"));
    }
}

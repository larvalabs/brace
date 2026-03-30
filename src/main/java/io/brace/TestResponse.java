package io.brace;

import java.net.http.HttpResponse;

/**
 * Simple wrapper around java.net.http.HttpResponse with convenience methods
 * for assertions in tests.
 */
public class TestResponse {

    private final HttpResponse<String> raw;

    TestResponse(HttpResponse<String> raw) {
        this.raw = raw;
    }

    public int status() {
        return raw.statusCode();
    }

    public String body() {
        return raw.body();
    }

    public String header(String name) {
        return raw.headers().firstValue(name).orElse(null);
    }

    public String redirectedTo() {
        return header("Location");
    }

    public <T> T bodyAs(Class<T> type) {
        try {
            return Json.mapper().readValue(body(), type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize response body as " + type.getSimpleName(), e);
        }
    }
}

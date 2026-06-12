package com.larvalabs.brace;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.http.HttpResponse;
import java.util.List;

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

    /** All values for a (case-insensitive) header name, e.g. every {@code Set-Cookie}. */
    public List<String> headers(String name) {
        return raw.headers().allValues(name);
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

    /**
     * Deserialize the body via a Jackson {@link TypeReference} — for generic types that
     * {@link #bodyAs(Class)} can't express, e.g.
     * {@code res.bodyAs(new TypeReference<List<Post>>() {})}.
     */
    public <T> T bodyAs(TypeReference<T> type) {
        try {
            return Json.mapper().readValue(body(), type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize response body as " + type.getType(), e);
        }
    }

    /**
     * Parse the body as a Jackson {@link JsonNode} tree for structural assertions without a
     * DTO: {@code res.json().get(0).get("title").asText()}.
     */
    public JsonNode json() {
        try {
            return Json.mapper().readTree(body());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse response body as JSON: " + body(), e);
        }
    }
}

package io.brace;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final String method;
    private final String url;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private String body;
    private Duration timeout = Duration.ofSeconds(30);

    private Http(String method, String url) {
        this.method = method;
        this.url = url;
    }

    public static Http get(String url) { return new Http("GET", url); }
    public static Http post(String url) { return new Http("POST", url); }
    public static Http put(String url) { return new Http("PUT", url); }
    public static Http delete(String url) { return new Http("DELETE", url); }

    public Http header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public Http bearer(String token) {
        return header("Authorization", "Bearer " + token);
    }

    public Http timeout(Duration timeout) {
        this.timeout = timeout;
        return this;
    }

    public Http bodyJson(Object value) {
        try {
            this.body = Json.mapper().writeValueAsString(value);
            headers.putIfAbsent("Content-Type", "application/json");
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    public Http bodyForm(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(java.net.URLEncoder.encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        this.body = sb.toString();
        headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
        return this;
    }

    public Http bodyString(String body) {
        this.body = body;
        return this;
    }

    // --- Execute ---

    public Response fetch() {
        try {
            var builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout);
            for (var entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
            var bodyPublisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body)
                : HttpRequest.BodyPublishers.noBody();
            builder.method(method, bodyPublisher);
            var httpResponse = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(httpResponse);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted: " + method + " " + url, e);
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + method + " " + url, e);
        }
    }

    public <T> T fetchJson(Class<T> type) {
        var response = fetch();
        return response.as(type);
    }

    public String fetchString() {
        return fetch().body();
    }

    // --- Response ---

    public static class Response {

        private final HttpResponse<String> raw;

        Response(HttpResponse<String> raw) {
            this.raw = raw;
        }

        public int status() { return raw.statusCode(); }
        public String body() { return raw.body(); }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        public boolean ok() { return status() >= 200 && status() < 300; }

        public <T> T as(Class<T> type) {
            try {
                return Json.mapper().readValue(body(), type);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse response as " + type.getSimpleName()
                    + " (status " + status() + "): " + body(), e);
            }
        }
    }
}

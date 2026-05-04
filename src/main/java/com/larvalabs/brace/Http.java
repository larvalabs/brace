package com.larvalabs.brace;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Http {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final String method;
    private final String url;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private HttpRequest.BodyPublisher bodyPublisher;
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
            var json = Json.mapper().writeValueAsString(value);
            this.bodyPublisher = HttpRequest.BodyPublishers.ofString(json);
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
            sb.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        this.bodyPublisher = HttpRequest.BodyPublishers.ofString(sb.toString());
        headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
        return this;
    }

    public Http bodyString(String body) {
        this.bodyPublisher = HttpRequest.BodyPublishers.ofString(body);
        return this;
    }

    public Http bodyBytes(byte[] bytes, String contentType) {
        this.bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
        headers.putIfAbsent("Content-Type", contentType);
        return this;
    }

    public Multipart multipart() {
        return new Multipart(this);
    }

    // --- Execute ---

    private HttpRequest buildRequest() {
        var builder = HttpRequest.newBuilder().uri(URI.create(url)).timeout(timeout);
        for (var entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        var pub = bodyPublisher != null ? bodyPublisher : HttpRequest.BodyPublishers.noBody();
        builder.method(method, pub);
        return builder.build();
    }

    public Response fetch() {
        try {
            var httpResponse = CLIENT.send(buildRequest(), HttpResponse.BodyHandlers.ofString());
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

    public byte[] fetchBytes() {
        try {
            var httpResponse = CLIENT.send(buildRequest(), HttpResponse.BodyHandlers.ofByteArray());
            if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
                throw new RuntimeException("HTTP request failed: " + method + " " + url + " (status " + httpResponse.statusCode() + ")");
            }
            return httpResponse.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted: " + method + " " + url, e);
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + method + " " + url, e);
        }
    }

    // --- Multipart builder ---

    public static class Multipart {

        private final Http http;
        private final String boundary = "----BraceBoundary" + Long.toHexString(System.nanoTime());
        private final List<Part> parts = new ArrayList<>();

        Multipart(Http http) { this.http = http; }

        public Multipart field(String name, String value) {
            parts.add(new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        public Multipart field(String name, byte[] bytes, String filename) {
            return field(name, bytes, filename, guessContentType(filename));
        }

        public Multipart field(String name, byte[] bytes, String filename, String contentType) {
            parts.add(new Part(name, filename, contentType, bytes));
            return this;
        }

        public Multipart header(String name, String value) { http.header(name, value); return this; }
        public Multipart bearer(String token) { http.bearer(token); return this; }
        public Multipart timeout(Duration timeout) { http.timeout(timeout); return this; }

        public Response fetch() { finalizeBody(); return http.fetch(); }
        public String fetchString() { finalizeBody(); return http.fetchString(); }
        public byte[] fetchBytes() { finalizeBody(); return http.fetchBytes(); }
        public <T> T fetchJson(Class<T> type) { finalizeBody(); return http.fetchJson(type); }

        private void finalizeBody() {
            var out = new ByteArrayOutputStream();
            try {
                for (var part : parts) {
                    writeAscii(out, "--" + boundary + "\r\n");
                    if (part.filename != null) {
                        writeAscii(out, "Content-Disposition: form-data; name=\"" + part.name
                            + "\"; filename=\"" + part.filename + "\"\r\n");
                        writeAscii(out, "Content-Type: "
                            + (part.contentType != null ? part.contentType : "application/octet-stream")
                            + "\r\n");
                    } else {
                        writeAscii(out, "Content-Disposition: form-data; name=\"" + part.name + "\"\r\n");
                    }
                    writeAscii(out, "\r\n");
                    out.write(part.bytes);
                    writeAscii(out, "\r\n");
                }
                writeAscii(out, "--" + boundary + "--\r\n");
            } catch (IOException e) {
                throw new RuntimeException("Failed to build multipart body", e);
            }
            http.bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(out.toByteArray());
            http.headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        }

        private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.US_ASCII));
        }

        private static String guessContentType(String filename) {
            if (filename == null) return "application/octet-stream";
            var lower = filename.toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".pdf")) return "application/pdf";
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".txt")) return "text/plain";
            if (lower.endsWith(".html")) return "text/html";
            if (lower.endsWith(".css")) return "text/css";
            if (lower.endsWith(".js")) return "application/javascript";
            if (lower.endsWith(".zip")) return "application/zip";
            return "application/octet-stream";
        }

        private record Part(String name, String filename, String contentType, byte[] bytes) {}
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

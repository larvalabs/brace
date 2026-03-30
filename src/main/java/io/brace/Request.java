package io.brace;

import java.util.Map;

public class Request {

    private final String method;
    private final String path;
    private final Map<String, String> pathParams;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;
    private final String body;

    public Request(String method, String path, Map<String, String> pathParams,
                   Map<String, String> queryParams, Map<String, String> headers,
                   String body) {
        this.method = method;
        this.path = path;
        this.pathParams = pathParams;
        this.queryParams = queryParams;
        this.headers = headers;
        this.body = body;
    }

    public String method() { return method; }
    public String path() { return path; }

    public String param(String name) {
        var value = pathParams.get(name);
        if (value != null) return value;
        return queryParams.get(name);
    }

    public int intParam(String name) {
        return Integer.parseInt(param(name));
    }

    public long longParam(String name) {
        return Long.parseLong(param(name));
    }

    public String header(String name) {
        return headers.get(name);
    }

    public boolean hasHeader(String name) {
        return headers.containsKey(name);
    }

    public String body() { return body; }

    public <T> T bodyAs(Class<T> type) {
        try {
            return Json.mapper().readValue(body, type);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse request body as " + type.getSimpleName(), e);
        }
    }

    public String cookie(String name) {
        var cookieHeader = header("Cookie");
        if (cookieHeader == null) return null;
        for (var part : cookieHeader.split(";")) {
            var trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }

    public String ip() {
        var forwarded = header("X-Forwarded-For");
        if (forwarded != null) return forwarded.split(",")[0].trim();
        return header("Remote-Addr");
    }
}

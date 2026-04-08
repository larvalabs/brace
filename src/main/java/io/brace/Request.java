package io.brace;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Request {

    private final String method;
    private final String path;
    private final Map<String, String> pathParams;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;
    private final String body;
    private final Map<String, List<UploadedFile>> uploadedFiles;
    private Storage storage;

    public Request(String method, String path, Map<String, String> pathParams,
                   Map<String, String> queryParams, Map<String, String> headers,
                   String body) {
        this(method, path, pathParams, queryParams, headers, body, Map.of());
    }

    public Request(String method, String path, Map<String, String> pathParams,
                   Map<String, String> queryParams, Map<String, String> headers,
                   String body, Map<String, List<UploadedFile>> uploadedFiles) {
        this.method = method;
        this.path = path;
        this.pathParams = pathParams;
        this.queryParams = queryParams;
        this.headers = headers;
        this.body = body;
        this.uploadedFiles = uploadedFiles;
    }

    public String method() { return method; }
    public String path() { return path; }
    public Map<String, String> queryParams() { return queryParams; }

    public String param(String name) {
        var value = pathParams.get(name);
        if (value != null) return value;
        value = queryParams.get(name);
        if (value != null) return value;
        return parseFormBody(body).get(name);
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

    public boolean isHtmx() {
        return "true".equals(header("HX-Request"));
    }

    public String body() { return body; }

    public UploadedFile file(String name) {
        var files = uploadedFiles.get(name);
        if (files == null || files.isEmpty()) return null;
        return files.getFirst();
    }

    public List<UploadedFile> files(String name) {
        return uploadedFiles.getOrDefault(name, List.of());
    }

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

    public <T> Form<T> form(Class<T> type) {
        var params = parseFormBody(body());
        for (var entry : queryParams.entrySet()) {
            params.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return FormBinder.bind(type, params);
    }

    private static Map<String, String> parseFormBody(String body) {
        var params = new LinkedHashMap<String, String>();
        if (body == null || body.isEmpty()) return params;
        for (var pair : body.split("&")) {
            var eq = pair.indexOf('=');
            if (eq < 0) {
                params.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                var key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                var value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                params.put(key, value);
            }
        }
        return params;
    }

    void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Storage storage() {
        if (storage == null) {
            throw new IllegalStateException("Storage not configured. Call app.storage() to configure.");
        }
        return storage;
    }
}

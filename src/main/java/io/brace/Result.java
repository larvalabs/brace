package io.brace;

import java.util.LinkedHashMap;
import java.util.Map;

public class Result {

    private int status;
    private String contentType;
    private String body;
    private final Map<String, String> headers = new LinkedHashMap<>();

    protected Result(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    public static Result text(String body) {
        return new Result(200, "text/plain", body);
    }

    public static Result notFound() {
        return new Result(404, "text/plain", "Not Found");
    }

    public static Result error(int status, String message) {
        return new Result(status, "text/plain", message);
    }

    public static Result noContent() {
        return new Result(204, "text/plain", "");
    }

    public static Result unauthorized(String message) {
        return new Result(401, "text/plain", message);
    }

    public static Result html(String body) {
        return new Result(200, "text/html", body);
    }

    public int status() { return status; }
    public String contentType() { return contentType; }
    public String body() { return body; }
    public Map<String, String> headers() { return headers; }

    public Result header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public String header(String name) {
        return headers.get(name);
    }
}

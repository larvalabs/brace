package io.brace;

import java.util.LinkedHashMap;
import java.util.Map;

public class Result {

    private int status;
    private String contentType;
    private String body;
    private byte[] rawBytes;
    private final Map<String, String> headers = new LinkedHashMap<>();

    protected Result(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    private Result(int status, String contentType, byte[] rawBytes) {
        this.status = status;
        this.contentType = contentType;
        this.rawBytes = rawBytes;
    }

    public static Result bytes(byte[] bytes, String contentType) {
        return new Result(200, contentType, bytes);
    }

    public static Result download(byte[] bytes, String contentType, String filename) {
        return new Result(200, contentType, bytes)
            .header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
    }

    public static Result text(String body) {
        return new Result(200, "text/plain", body);
    }

    public static Result notFound() {
        return new Result(404, "text/plain", "Not Found");
    }

    public static <T> T notFoundIfNull(T value) {
        if (value == null) throw new NotFoundException();
        return value;
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
    public byte[] rawBytes() { return rawBytes; }
    public Map<String, String> headers() { return headers; }

    public Result header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public String header(String name) {
        return headers.get(name);
    }
}

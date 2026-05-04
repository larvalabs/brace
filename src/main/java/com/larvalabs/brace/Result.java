package com.larvalabs.brace;

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

    public static Result json(Object value) {
        return Json.of(value);
    }

    public static Result json(Object value, int status) {
        return Json.of(value, status);
    }

    public static Result view(String template, Object... keyValues) {
        return View.of(template, keyValues);
    }

    public static Result redirect(String location) {
        return Redirect.to(location);
    }

    public static Result redirectPermanent(String location) {
        return Redirect.permanent(location);
    }

    public static Result unauthorized() {
        return new Result(401, "text/plain", "Unauthorized");
    }

    public static Result forbidden() {
        return new Result(403, "text/plain", "Forbidden");
    }

    public static Result forbidden(String message) {
        return new Result(403, "text/plain", message);
    }

    public static Result badRequest(String message) {
        return new Result(400, "text/plain", message);
    }

    public static Result created(String location) {
        return new Result(201, "text/plain", "Created")
            .header("Location", location);
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

    /**
     * Set a cookie on the response.
     * @param name Cookie name
     * @param value Cookie value
     * @param maxAge Max age in seconds
     * @param httpOnly HttpOnly flag
     * @param secure Secure flag
     * @param sameSite SameSite attribute (Strict, Lax, or None)
     * @return this Result for chaining
     */
    public Result cookie(String name, String value, int maxAge, boolean httpOnly, boolean secure, String sameSite) {
        var cookie = new StringBuilder();
        cookie.append(name).append("=").append(value);
        cookie.append("; Max-Age=").append(maxAge);
        cookie.append("; Path=/");
        if (httpOnly) cookie.append("; HttpOnly");
        if (secure) cookie.append("; Secure");
        if (sameSite != null) cookie.append("; SameSite=").append(sameSite);
        header("Set-Cookie", cookie.toString());
        return this;
    }
}

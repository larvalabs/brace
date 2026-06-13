package com.larvalabs.brace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Result {

    private int status;
    private String contentType;
    private String body;
    private byte[] rawBytes;
    private final Map<String, String> headers = new LinkedHashMap<>();
    // Set-Cookie is the one header that legitimately repeats: a response may carry several
    // cookies (e.g. an app cookie plus the framework session cookie). It is kept out of the
    // single-value `headers` map — which would collapse repeats — and appended here instead.
    private final List<String> setCookies = new ArrayList<>();

    protected Result(int status, String contentType, String body) {
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    // Package-private (not private): Json extends Result and serializes straight to UTF-8 bytes (M6).
    Result(int status, String contentType, byte[] rawBytes) {
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

    /**
     * Rebuild a fully-materialized response from a cached render (see {@code Cache.RenderedResponse}).
     * Replays an arbitrary status, content type, body bytes, and headers — the page cache's replay
     * path, where {@link #bytes} (hardcoded 200) is insufficient.
     */
    static Result raw(int status, String contentType, byte[] body, Map<String, String> headers) {
        var result = new Result(status, contentType, body);
        // Route through header() so a replayed Set-Cookie lands in the cookie list, not the map.
        if (headers != null) headers.forEach(result::header);
        return result;
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

    /** A 304 Not Modified with an empty body, for conditional-GET revalidation hits. */
    public static Result notModified() {
        return new Result(304, "text/plain", "");
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

    /**
     * The response body as a String. When the body is held as raw UTF-8 bytes (a {@link View} render or
     * a {@link Json} serialization — M6, which writes bytes directly to avoid a String round-trip on the
     * hot path), it is decoded on demand and memoized. The wire path ({@code writeResult}) and the page
     * cache read {@link #rawBytes()} first, so this decode runs only for a caller that actually wants the
     * String form (e.g. a body-rewriting after-middleware).
     */
    public String body() {
        materialize();
        if (body == null && rawBytes != null) {
            body = new String(rawBytes, StandardCharsets.UTF_8);
        }
        return body;
    }

    public byte[] rawBytes() { materialize(); return rawBytes; }

    /**
     * Renders any deferred body (M12). A plain {@code Result} is already materialized, so this is a
     * no-op; {@link View} overrides it to run the template engine. The framework calls this once after
     * the request transaction commits and its DB connection is released, so template rendering — which
     * touches no transactional state under StatelessSession — no longer holds a pooled connection.
     * Also invoked lazily by {@link #body()} / {@link #rawBytes()} so any earlier reader (a body-rewriting
     * after-middleware, the page cache snapshot) still sees a fully rendered response. Idempotent.
     */
    void materialize() {}

    /** Sets a deferred {@link #materialize()} body as a String (stub renders, no template engine). */
    void setRenderedBody(String rendered) { this.body = rendered; }

    /** Sets a deferred {@link #materialize()} body as raw UTF-8 bytes (M6: the rendered-to-bytes path). */
    void setRenderedBytes(byte[] rendered) { this.rawBytes = rendered; }
    public Map<String, String> headers() { return headers; }

    /** All {@code Set-Cookie} values for this response, in the order they were added. */
    public List<String> setCookies() { return setCookies; }

    public Result header(String name, String value) {
        if (name.equalsIgnoreCase("Set-Cookie")) {
            setCookies.add(value);
        } else {
            headers.put(name, value);
        }
        return this;
    }

    public String header(String name) {
        if (name.equalsIgnoreCase("Set-Cookie")) {
            return setCookies.isEmpty() ? null : setCookies.get(0);
        }
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

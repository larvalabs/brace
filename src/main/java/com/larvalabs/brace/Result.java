package com.larvalabs.brace;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
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
            .header("Content-Disposition", contentDisposition(filename));
    }

    /**
     * Streams a file from disk, with a {@code Content-Length} and {@code Range} support. The
     * content type is guessed from the extension.
     *
     * <p>Costs a bounded buffer regardless of the file's size — the whole point, versus reading it
     * into a {@code byte[]} first.
     */
    public static Result file(Path path) {
        return file(path, contentTypeForFile(path));
    }

    /** Streams a file from disk with an explicit content type. */
    public static Result file(Path path, String contentType) {
        long length;
        try {
            length = Files.size(path);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Cannot serve " + path, e);
        }
        return new StreamResult(200, contentType,
            new StreamResult.FileBody(path, 0, length), length)
            .header("Accept-Ranges", "bytes");
    }

    /**
     * Streams a file as a download, under {@code filename}. The streaming counterpart of
     * {@link #download(byte[], String, String)}; the filename is escaped identically.
     */
    public static Result download(Path path, String filename) {
        return file(path, contentTypeForFile(path))
            .header("Content-Disposition", contentDisposition(filename));
    }

    /**
     * Streams an {@link InputStream} of unknown length (chunked transfer encoding). The stream is
     * closed by the framework when the response completes or fails.
     */
    public static Result stream(InputStream in, String contentType) {
        return new StreamResult(200, contentType, new StreamResult.StreamBody(in, -1), -1);
    }

    /** Streams an {@link InputStream} whose length is known, so a {@code Content-Length} is set. */
    public static Result stream(InputStream in, String contentType, long contentLength) {
        return new StreamResult(200, contentType,
            new StreamResult.StreamBody(in, contentLength), contentLength);
    }

    /**
     * Streams generated content — a CSV export, a ZIP, an NDJSON feed. The writer is handed an
     * {@link OutputStream} and its output goes to the client as it is produced, chunked.
     *
     * <pre>{@code
     * return Result.stream(out -> {
     *     var w = new PrintWriter(out);
     *     w.println("id,name");
     *     for (var row : rows) w.println(row.id() + "," + row.name());
     *     w.flush();
     * }, "text/csv");
     * }</pre>
     *
     * <p>The writer runs <em>after</em> the request transaction has committed and its connection
     * has been returned to the pool, so it must not touch the request's {@code Database} — see
     * {@link StreamResult} for what to do instead.
     */
    public static Result stream(Consumer<OutputStream> writer, String contentType) {
        return new StreamResult(200, contentType, new StreamResult.WriterBody(writer), -1);
    }

    /** Whether this response's body is streamed rather than materialized. */
    public boolean isStreaming() {
        return false;
    }

    /** Extension-based content type for the streaming file helpers. */
    private static String contentTypeForFile(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (ext) {
            case "html", "htm" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "txt", "md" -> "text/plain; charset=utf-8";
            case "csv" -> "text/csv; charset=utf-8";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "ico" -> "image/x-icon";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            case "gz" -> "application/gzip";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "woff" -> "font/woff";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
    }

    /**
     * Build a safe {@code Content-Disposition} value for a download (2026-07 review, M6).
     *
     * <p>The filename used to be interpolated into {@code attachment; filename="…"} raw, so a
     * name containing a double quote closed the quoted-string early and everything after it was
     * parsed by the client as further parameters — verified on the wire with a filename of
     * {@code x"; name="y}. Serving a user-uploaded file under its original name is this method's
     * primary use case, which is exactly where the name is attacker-supplied.
     *
     * <p>Emits the RFC 6266 pair: an ASCII-safe {@code filename="…"} that every client
     * understands, plus {@code filename*=UTF-8''…} carrying the exact name for clients that
     * support RFC 5987. Any directory component is dropped (a download name is a leaf), and
     * characters that cannot appear literally in a quoted-string — quotes, backslashes,
     * control characters, non-ASCII — are replaced with {@code _} in the ASCII form.
     */
    static String contentDisposition(String filename) {
        if (filename == null || filename.isBlank()) return "attachment";
        // A download name is a leaf: drop anything before the last separator of either flavour.
        String leaf = filename;
        int slash = Math.max(leaf.lastIndexOf('/'), leaf.lastIndexOf('\\'));
        if (slash >= 0) leaf = leaf.substring(slash + 1);
        if (leaf.isBlank() || leaf.equals(".") || leaf.equals("..")) return "attachment";

        var ascii = new StringBuilder(leaf.length());
        for (int i = 0; i < leaf.length(); i++) {
            char c = leaf.charAt(i);
            ascii.append(c < 0x20 || c > 0x7E || c == '"' || c == '\\' ? '_' : c);
        }

        var encoded = new StringBuilder();
        for (byte b : leaf.getBytes(StandardCharsets.UTF_8)) {
            int v = b & 0xFF;
            // RFC 5987 attr-char: alphanumerics plus a fixed punctuation set; everything else
            // is percent-encoded.
            if ((v >= 'A' && v <= 'Z') || (v >= 'a' && v <= 'z') || (v >= '0' && v <= '9')
                    || "!#$&+-.^_`|~".indexOf(v) >= 0) {
                encoded.append((char) v);
            } else {
                encoded.append('%').append(String.format("%02X", v));
            }
        }
        return "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
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
        return cookie(name, value, maxAge, httpOnly, secure, sameSite, "/");
    }

    /**
     * Set a cookie on the response, scoped to {@code path}.
     * @param path Cookie Path attribute — narrow it so the cookie is not attached to every
     *             request to the app (e.g. {@code "/ops"} for an ops-only credential)
     * @see #cookie(String, String, int, boolean, boolean, String)
     */
    public Result cookie(String name, String value, int maxAge, boolean httpOnly, boolean secure,
                         String sameSite, String path) {
        requireCookieToken(name, "name");
        requireCookieValue(value);
        var cookie = new StringBuilder();
        cookie.append(name).append("=").append(value);
        cookie.append("; Max-Age=").append(maxAge);
        cookie.append("; Path=").append(path == null ? "/" : path);
        if (httpOnly) cookie.append("; HttpOnly");
        if (secure) cookie.append("; Secure");
        if (sameSite != null) cookie.append("; SameSite=").append(sameSite);
        header("Set-Cookie", cookie.toString());
        return this;
    }

    /**
     * Reject a cookie value that could inject attributes (2026-07 review, L1).
     *
     * <p>The value was appended raw ahead of the framework's own attributes, so a {@code ;} in it
     * injected cookie-av entries — verified on the wire, where a value of
     * {@code 1; Path=/; Domain=evil} produced
     * {@code Set-Cookie: c=1; Path=/; Domain=evil; Max-Age=60; …}. A handler setting a cookie
     * from user input (a theme, a locale, a returnTo) could have its cookie re-scoped by that
     * input. CR/LF is folded to a space by Jetty's generator, so this was attribute injection
     * rather than header injection — still worth failing on rather than emitting.
     */
    private static void requireCookieValue(String value) {
        if (value == null) return;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F || c == ';' || c == ',' || c == '"' || c == '\\' || c == ' ') {
                throw new IllegalArgumentException(
                    "Cookie value must not contain control characters, spaces, quotes, backslashes, "
                        + "';' or ',' (they inject cookie attributes) — URL-encode it first. Got: " + value);
            }
        }
    }

    /** Validate a cookie name against the RFC 6265 token grammar (2026-07 review, L1). */
    private static void requireCookieToken(String name, String what) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Cookie " + what + " must not be empty");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 0x20 || c >= 0x7F || "()<>@,;:\\\"/[]?={}".indexOf(c) >= 0) {
                throw new IllegalArgumentException(
                    "Cookie " + what + " must be an RFC 6265 token; got: " + name);
            }
        }
    }
}

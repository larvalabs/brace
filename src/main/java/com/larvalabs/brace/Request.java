package com.larvalabs.brace;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class Request {

    private final String method;
    private final String path;
    private final Map<String, String> pathParams;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;
    private final String body;
    private final Map<String, List<UploadedFile>> uploadedFiles;
    private final String remoteAddr;
    private final TrustedProxies trustedProxies;
    private Storage storage;

    public Request(String method, String path, Map<String, String> pathParams,
                   Map<String, String> queryParams, Map<String, String> headers,
                   String body) {
        this(method, path, pathParams, queryParams, headers, body, Map.of());
    }

    public Request(String method, String path, Map<String, String> pathParams,
                   Map<String, String> queryParams, Map<String, String> headers,
                   String body, Map<String, List<UploadedFile>> uploadedFiles) {
        this(method, path, pathParams, queryParams, headers, body, uploadedFiles, null, null);
    }

    public Request(String method, String path, Map<String, String> pathParams,
                   Map<String, String> queryParams, Map<String, String> headers,
                   String body, Map<String, List<UploadedFile>> uploadedFiles,
                   String remoteAddr, TrustedProxies trustedProxies) {
        this.method = method;
        this.path = path;
        this.pathParams = pathParams;
        this.queryParams = queryParams;
        this.headers = caseInsensitive(headers);
        this.body = body;
        this.uploadedFiles = uploadedFiles;
        this.remoteAddr = remoteAddr;
        this.trustedProxies = trustedProxies;
    }

    public String method() { return method; }
    public String path() { return path; }
    public Map<String, String> queryParams() { return queryParams; }
    public Map<String, String> pathParams() { return pathParams; }

    // Path parameter accessors

    public String pathParam(String name) {
        return pathParams.get(name);
    }

    public int intPathParam(String name) {
        return Integer.parseInt(pathParams.get(name));
    }

    public long longPathParam(String name) {
        return Long.parseLong(pathParams.get(name));
    }

    // Query parameter accessors

    public String queryParam(String name) {
        return queryParams.get(name);
    }

    public String queryParam(String name, String defaultValue) {
        return queryParams.getOrDefault(name, defaultValue);
    }

    public int queryInt(String name) {
        return Integer.parseInt(queryParams.get(name));
    }

    public int queryInt(String name, int defaultValue) {
        var value = queryParams.get(name);
        return value != null ? Integer.parseInt(value) : defaultValue;
    }

    public long queryLong(String name) {
        return Long.parseLong(queryParams.get(name));
    }

    public long queryLong(String name, long defaultValue) {
        var value = queryParams.get(name);
        return value != null ? Long.parseLong(value) : defaultValue;
    }

    public boolean hasQueryParam(String name) {
        return queryParams.containsKey(name);
    }

    // Form parameter accessors

    public String formParam(String name) {
        return parseFormBody(body).get(name);
    }

    public int formInt(String name) {
        return Integer.parseInt(parseFormBody(body).get(name));
    }

    public boolean hasFormParam(String name) {
        return parseFormBody(body).containsKey(name);
    }

    // JSON request helpers

    public boolean isJson() {
        var contentType = header("Content-Type");
        return contentType != null && contentType.toLowerCase().contains("application/json");
    }

    public boolean isFormPost() {
        var contentType = header("Content-Type");
        return contentType != null && contentType.toLowerCase().contains("application/x-www-form-urlencoded");
    }

    public boolean isMultipart() {
        var contentType = header("Content-Type");
        return contentType != null && contentType.toLowerCase().contains("multipart/form-data");
    }

    public <T> T json(Class<T> type) {
        return bodyAs(type);
    }

    public <T> T requireJson(Class<T> type) {
        if (!isJson()) {
            throw new IllegalArgumentException("Request Content-Type must be application/json");
        }
        return bodyAs(type);
    }

    public String header(String name) {
        return headers.get(name);
    }

    public boolean hasHeader(String name) {
        return headers.containsKey(name);
    }

    /** Copy headers into a case-insensitive map so lookups don't depend on the sender's casing. */
    private static Map<String, String> caseInsensitive(Map<String, String> source) {
        var map = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        if (source != null) map.putAll(source);
        return map;
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
        // Only trust forwarding headers if proxies are configured and the immediate peer is trusted
        if (trustedProxies != null && remoteAddr != null && trustedProxies.isTrusted(remoteAddr)) {
            // Check X-Forwarded-For first (most common).
            // NOTE: Request.headers is a single-value map (TreeMap, case-insensitive), so multiple
            // X-Forwarded-For header instances from the wire are not visible here — only the value
            // as concatenated by Jetty (which joins multi-occurrence headers with ", ") is available.
            var forwarded = header("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty()) {
                var result = rightmostUntrusted(forwarded.split(","));
                if (result != null) return result;
            }

            // Check Forwarded header (RFC 7239)
            var forwardedRfc = header("Forwarded");
            if (forwardedRfc != null && !forwardedRfc.isEmpty()) {
                var forPart = extractForwardedFor(forwardedRfc);
                if (forPart != null) return forPart;
            }
        }

        // Default: use socket remote address
        return remoteAddr != null ? remoteAddr : "unknown";
    }

    /**
     * Rightmost-untrusted walk: given an ordered array of IP entries (left = client-appended,
     * right = most-recently-appended by a trusted proxy), walk from the right, skip any entry
     * whose address is trusted, and return the first untrusted one. If every entry is trusted
     * (fully internal infrastructure chain), return the leftmost non-blank entry to preserve
     * the original client address.
     *
     * <p>Blank/whitespace-only entries are skipped in both directions. If the array is empty or
     * contains only blank entries, {@code null} is returned so callers fall back to the socket
     * remote address — this prevents returning an empty string and prevents an
     * {@link ArrayIndexOutOfBoundsException} on a header value of exactly {@code ","}.
     *
     * <p>Port suffixes are stripped before trust evaluation:
     * <ul>
     *   <li>{@code 1.2.3.4:5678} → {@code 1.2.3.4} (IPv4 with port)</li>
     *   <li>{@code [2001:db8::1]:443} → {@code 2001:db8::1} (bracketed IPv6 with port)</li>
     *   <li>{@code ::1} → {@code ::1} (bare IPv6, colons are part of the address — no stripping)</li>
     * </ul>
     */
    private String rightmostUntrusted(String[] entries) {
        // Right-to-left: skip blank segments; return the first untrusted non-blank entry.
        for (int i = entries.length - 1; i >= 0; i--) {
            var trimmed = entries[i].trim();
            if (trimmed.isEmpty()) continue;
            var addr = stripPort(trimmed);
            if (!trustedProxies.isTrusted(addr)) {
                return addr;
            }
        }
        // All non-blank entries are trusted (fully internal chain) — return the leftmost non-blank.
        for (int i = 0; i < entries.length; i++) {
            var trimmed = entries[i].trim();
            if (!trimmed.isEmpty()) {
                return stripPort(trimmed);
            }
        }
        // No non-blank entries at all — signal to the caller to fall back to remoteAddr.
        return null;
    }

    /**
     * Strip a port suffix from an IP address string, handling IPv4, bracketed IPv6 ({@code
     * [::1]:443}), and bare IPv6 ({@code ::1}) correctly.
     */
    static String stripPort(String addr) {
        if (addr.startsWith("[")) {
            // Bracketed IPv6: "[addr]:port" — drop the brackets and port
            int closingBracket = addr.indexOf(']');
            if (closingBracket > 0) {
                return addr.substring(1, closingBracket);
            }
            return addr; // malformed — return as-is
        }
        // Count colons: more than one means bare IPv6 (no port to strip)
        long colonCount = addr.chars().filter(c -> c == ':').count();
        if (colonCount > 1) {
            return addr; // bare IPv6 address — colons are part of the address
        }
        // IPv4 with optional port: "a.b.c.d:port"
        if (colonCount == 1) {
            int colon = addr.lastIndexOf(':');
            var portPart = addr.substring(colon + 1);
            // Only strip if the suffix looks like a decimal port number
            if (portPart.matches("\\d+")) {
                return addr.substring(0, colon);
            }
        }
        return addr;
    }

    private String extractForwardedFor(String forwarded) {
        // RFC 7239 parser: each list-element is separated by ","; within each element,
        // parameters are separated by ";". Extract "for=" values and apply rightmost-untrusted walk.
        var elements = forwarded.split(",");
        var forValues = new java.util.ArrayList<String>(elements.length);
        for (var element : elements) {
            // Split the element's parameters on ";"
            for (var param : element.split(";")) {
                var trimmed = param.trim();
                if (trimmed.toLowerCase().startsWith("for=")) {
                    var value = trimmed.substring(4);
                    // Remove surrounding quotes if present (RFC 7239 allows quoted-string)
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                        value = value.substring(1, value.length() - 1);
                    }
                    // Strip port suffix (handles IPv4:port and [IPv6]:port forms)
                    value = stripPort(value);
                    forValues.add(value);
                    break; // only one "for=" per list-element
                }
            }
        }
        if (forValues.isEmpty()) return null;
        return rightmostUntrusted(forValues.toArray(new String[0]));
    }

    public <T> Form<T> form(Class<T> type) {
        var params = parseFormBody(body());
        for (var entry : queryParams.entrySet()) {
            params.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return FormBinder.bind(type, params);
    }

    /**
     * Bind a JSON request body to a record and run the same validation pipeline as
     * {@link #form(Class)} — annotations ({@code @Required}, {@code @Min}, …) plus the
     * record's custom {@code validate(Errors)} method. Never throws on bad input: an
     * unparseable or non-object body returns a {@code Form} carrying a {@code "_body"}
     * error, so the {@code hasErrors()} idiom covers malformed JSON too (unlike
     * {@link #bodyAs(Class)}, which throws and surfaces as a 500).
     *
     * <p>Scalar JSON values (strings, numbers, booleans) bind to the record's components;
     * JSON {@code null}s are treated as absent; nested objects/arrays bind to String
     * components as raw JSON text. The reject idiom:
     * {@code if (form.hasErrors()) return Result.json(Map.of("errors", form.allErrors()), 422);}
     */
    public <T> Form<T> jsonForm(Class<T> type) {
        com.fasterxml.jackson.databind.JsonNode node = null;
        try {
            String b = body();
            if (b != null && !b.isBlank()) {
                node = Json.mapper().readTree(b);
            }
        } catch (Exception e) {
            // fall through to the _body error below
        }
        if (node == null || !node.isObject()) {
            // Bind against empty params so value() is a defaults-populated record (never
            // null, matching form()); the "_body" error marks the malformed payload.
            var form = FormBinder.bind(type, Map.of());
            form.errors().add("_body", "must be a JSON object");
            return form;
        }
        var params = new LinkedHashMap<String, String>();
        node.fields().forEachRemaining(entry -> {
            var value = entry.getValue();
            if (value == null || value.isNull()) return;
            params.put(entry.getKey(), value.isValueNode() ? value.asText() : value.toString());
        });
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

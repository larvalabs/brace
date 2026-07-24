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
    // M2: body and uploaded files are resolved lazily through bodySource so before-middleware
    // (rate limiting, auth) runs BEFORE the bytes are buffered. Once resolved they are cached
    // here — the underlying request stream can only be read once. bodySource is null for the
    // eager constructors (unit tests, hand-built Requests), where body is supplied directly.
    private String body;
    private Map<String, List<UploadedFile>> uploadedFiles;
    private java.util.function.Supplier<BodyContent> bodySource;
    private final String remoteAddr;
    private final TrustedProxies trustedProxies;
    private Storage storage;
    private String rawQuery;
    /** Lazily parsed form body (see {@link #parsedFormBody()}); body is immutable so this is safe to cache. */
    private Map<String, String> formBodyParams;

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

    /** A resolved request body: the form-encoded text plus any multipart file parts. */
    record BodyContent(String body, Map<String, List<UploadedFile>> files) {}

    /**
     * The framework constructor (M2): the body is not read yet. {@code bodySource} is invoked
     * at most once, on the first access to the body, form params, or uploaded files — which
     * {@link BraceHandler} forces after before-middleware has run, so a request that a guard
     * rejects never buffers its bytes.
     */
    Request(String method, String path, Map<String, String> pathParams,
            Map<String, String> queryParams, Map<String, String> headers,
            java.util.function.Supplier<BodyContent> bodySource,
            String remoteAddr, TrustedProxies trustedProxies) {
        this.method = method;
        this.path = path;
        this.pathParams = pathParams;
        this.queryParams = queryParams;
        this.headers = caseInsensitive(headers);
        this.bodySource = bodySource;
        this.remoteAddr = remoteAddr;
        this.trustedProxies = trustedProxies;
    }

    /**
     * Resolve the deferred body exactly once. Idempotent, and a no-op for eagerly-constructed
     * Requests. Propagates {@link PayloadTooLargeException} to the caller — {@link BraceHandler}
     * forces resolution at a point where it can turn that into a 413.
     */
    void resolveBody() {
        if (bodySource == null) return;
        var source = bodySource;
        bodySource = null; // clear first: a throwing source must not be retried against a drained stream
        var content = source.get();
        this.body = content.body();
        this.uploadedFiles = content.files();
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

    /**
     * Returns the query param parsed as an int, or {@code defaultValue} when the param is
     * missing OR present but unparseable ({@code ?page=abc}). The defaulted variants never
     * throw — a caller that supplies a default has already said what a bad value means.
     * Use {@link #queryInt(String)} when an unparseable value should surface as an error.
     */
    public int queryInt(String name, int defaultValue) {
        var value = queryParams.get(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long queryLong(String name) {
        return Long.parseLong(queryParams.get(name));
    }

    /**
     * Returns the query param parsed as a long, or {@code defaultValue} when the param is
     * missing OR present but unparseable. See {@link #queryInt(String, int)}.
     */
    public long queryLong(String name, long defaultValue) {
        var value = queryParams.get(name);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean hasQueryParam(String name) {
        return queryParams.containsKey(name);
    }

    /**
     * All values of a query parameter, URL-decoded, in order of appearance — for
     * {@code <select multiple>} / checkbox-group submissions like {@code ?tag=a&tag=b}.
     * Returns an empty list when the parameter is absent. The single-value
     * {@link #queryParam(String)} (last value wins) is unchanged.
     */
    public List<String> queryParams(String name) {
        if (rawQuery == null) {
            // No raw query string available (e.g. a hand-constructed Request in a unit
            // test): fall back to the single-value map.
            var single = queryParams.get(name);
            return single != null ? List.of(single) : List.of();
        }
        // Mirror BraceHandler.parseQuery's split rules: a pair without '=' (or starting
        // with '=') is a bare key with an empty value.
        return valuesOf(rawQuery, name, true);
    }

    // Form parameter accessors

    public String formParam(String name) {
        return parsedFormBody().get(name);
    }

    public int formInt(String name) {
        return Integer.parseInt(parsedFormBody().get(name));
    }

    public boolean hasFormParam(String name) {
        return parsedFormBody().containsKey(name);
    }

    /**
     * All values of a form parameter, URL-decoded, in order of appearance in the
     * {@code application/x-www-form-urlencoded} body — for {@code <select multiple>} /
     * checkbox-group submissions like {@code tag=a&tag=b}. Returns an empty list when
     * the parameter is absent. The single-value {@link #formParam(String)} (last value
     * wins) is unchanged.
     */
    public List<String> formParams(String name) {
        // Mirror parseFormBody's split rules: a pair without '=' is a bare key with an
        // empty value; a leading '=' means an empty-string key.
        resolveBody();
        return valuesOf(body, name, false);
    }

    /**
     * THE pair scanner — every {@code &}-separated, URL-encoded pair string (query string,
     * form body) goes through here, multi-value and single-value alike, so the split rules
     * can't drift between copies. Pairs are fed to {@code sink} in order of appearance.
     * {@code bareKeyOnLeadingEq} selects the edge-case rule for a pair starting with '=':
     * {@code true} is query parsing (the whole pair is the key), {@code false} is form
     * parsing (empty key, rest is value).
     */
    private static void scanPairs(String raw, boolean bareKeyOnLeadingEq,
                                  java.util.function.BiConsumer<String, String> sink) {
        for (var pair : raw.split("&")) {
            var eq = pair.indexOf('=');
            String key;
            String value;
            if (eq < 0 || (eq == 0 && bareKeyOnLeadingEq)) {
                key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                value = "";
            } else {
                key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
            sink.accept(key, value);
        }
    }

    /** Multi-value view of {@link #scanPairs}: keys map to their values in order of appearance. */
    static Map<String, List<String>> parsePairs(String raw, boolean bareKeyOnLeadingEq) {
        if (raw == null || raw.isEmpty()) return Map.of();
        var params = new LinkedHashMap<String, List<String>>();
        scanPairs(raw, bareKeyOnLeadingEq,
            (k, v) -> params.computeIfAbsent(k, x -> new java.util.ArrayList<>()).add(v));
        return params;
    }

    /**
     * Single-pass single-value view of {@link #scanPairs}: first-appearance key order
     * (LinkedHashMap keeps the original position on re-put), last value wins. Returns the
     * shared empty map for a null/empty input — no per-request allocation when there is
     * nothing to parse.
     */
    static Map<String, String> parseSingleValues(String raw, boolean bareKeyOnLeadingEq) {
        if (raw == null || raw.isEmpty()) return Map.of();
        var params = new LinkedHashMap<String, String>();
        scanPairs(raw, bareKeyOnLeadingEq, params::put);
        return params;
    }

    private static List<String> valuesOf(String raw, String name, boolean bareKeyOnLeadingEq) {
        // copyOf: don't leak parsePairs' mutable value lists to callers.
        return List.copyOf(parsePairs(raw, bareKeyOnLeadingEq).getOrDefault(name, List.of()));
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

    public String body() { resolveBody(); return body; }

    public UploadedFile file(String name) {
        resolveBody();
        var files = uploadedFiles.get(name);
        if (files == null || files.isEmpty()) return null;
        return files.getFirst();
    }

    public List<UploadedFile> files(String name) {
        resolveBody();
        return uploadedFiles.getOrDefault(name, List.of());
    }

    public <T> T bodyAs(Class<T> type) {
        try {
            return Json.mapper().readValue(body(), type);
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
            // Only strip if the suffix looks like a decimal port number. Hand-rolled digit check
            // avoids compiling a fresh regex Pattern on every ip() resolution (L4).
            if (isAllDigits(portPart)) {
                return addr.substring(0, colon);
            }
        }
        return addr;
    }

    /** True for a non-empty string of ASCII digits only — a regex-free {@code matches("\\d+")}. */
    private static boolean isAllDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
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
        // Copy: the cached form map must not absorb query params, and may be immutable-empty.
        var params = new LinkedHashMap<>(parsedFormBody());
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

    /**
     * The body parsed as a form, cached — the body is immutable, so the first accessor
     * ({@code formParam}, {@code form}, CSRF's {@code _csrf} extraction) pays the parse
     * and the rest reuse it. May be the shared immutable empty map; callers copy if they
     * need to mutate.
     */
    Map<String, String> parsedFormBody() {
        if (formBodyParams == null) {
            resolveBody();
            formBodyParams = parseSingleValues(body, false);
        }
        return formBodyParams;
    }

    void setStorage(Storage storage) {
        this.storage = storage;
    }

    /** Raw (still URL-encoded) query string, set by {@link BraceHandler}; backs {@link #queryParams(String)}. */
    void setRawQuery(String rawQuery) {
        this.rawQuery = rawQuery;
    }

    public Storage storage() {
        if (storage == null) {
            throw new IllegalStateException("Storage not configured. Call app.storage() to configure.");
        }
        return storage;
    }
}

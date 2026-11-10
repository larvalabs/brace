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
    /** Releases the temp files behind {@link #uploadedFiles}; see {@link #releaseUploads()}. */
    private java.io.Closeable uploadCleanup;
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

    /**
     * A resolved request body: the form-encoded text, any multipart file parts, and the handle that
     * releases whatever backs them.
     *
     * <p>{@code cleanup} is Jetty's {@code Parts} for a multipart body, and closing it deletes every
     * temp file the parser spilled. It must therefore run at the <em>end of the request</em>, not at
     * the end of parsing — the uploads are handed to the handler and are only valid until it does.
     * {@link BraceHandler} owns that call; nothing else should close it.
     */
    record BodyContent(String body, Map<String, List<UploadedFile>> files, java.io.Closeable cleanup) {
        BodyContent(String body, Map<String, List<UploadedFile>> files) {
            this(body, files, null);
        }
    }

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
        // Empty state first, so a caller that catches the supplier's exception (a guard wrapping
        // its own body read in try/catch) still sees a usable Request rather than null fields.
        this.body = "";
        this.uploadedFiles = Map.of();
        var content = source.get();
        this.body = content.body();
        this.uploadedFiles = content.files();
        this.uploadCleanup = content.cleanup();
    }

    /**
     * Releases whatever backs this request's uploads — the temp files Jetty spilled large parts to.
     *
     * <p>Called by {@link BraceHandler} in a finally that covers every exit from the request,
     * including the ones that never reached a handler (413, CSRF 403, thrown 404/500). Idempotent,
     * and never throws: a failure to delete a temp file is a leak to be logged, not a reason to
     * fail a response that has already been written. Parts moved out by
     * {@link UploadedFile#saveTo(Path)} are already detached and are left alone.
     */
    void releaseUploads() {
        var cleanup = takeUploadCleanup();
        if (cleanup == null) return;
        try {
            cleanup.close();
        } catch (Throwable t) {
            Log.warn("failed to release upload temp files: " + Redactor.redactMessage(t.toString()));
        }
    }

    /**
     * Transfers ownership of the upload cleanup to the caller, which becomes responsible for
     * closing it. Used by the streaming-response path: the response is still being written when
     * {@code handle()} returns, so the end-of-request release would delete the temp files out from
     * under a response that is streaming one of them back. After this call
     * {@link #releaseUploads()} is a no-op.
     */
    java.io.Closeable takeUploadCleanup() {
        var cleanup = this.uploadCleanup;
        this.uploadCleanup = null;
        return cleanup;
    }

    public String method() { return method; }
    /**
     * The request path as the client sent it (still percent-encoded). Under a page-cached
     * handler in dev mode or a test this is a recorded read: the cache key holds the route's
     * decoded path parameters, not this string (L4).
     */
    public String path() { readRawPath(); return path; }
    public Map<String, String> queryParams() { readQuery("*"); return queryParams; }
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
        readQuery(name);
        return queryParams.get(name);
    }

    public String queryParam(String name, String defaultValue) {
        readQuery(name);
        return queryParams.getOrDefault(name, defaultValue);
    }

    public int queryInt(String name) {
        readQuery(name);
        return Integer.parseInt(queryParams.get(name));
    }

    /**
     * Returns the query param parsed as an int, or {@code defaultValue} when the param is
     * missing OR present but unparseable ({@code ?page=abc}). The defaulted variants never
     * throw — a caller that supplies a default has already said what a bad value means.
     * Use {@link #queryInt(String)} when an unparseable value should surface as an error.
     */
    public int queryInt(String name, int defaultValue) {
        readQuery(name);
        var value = queryParams.get(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long queryLong(String name) {
        readQuery(name);
        return Long.parseLong(queryParams.get(name));
    }

    /**
     * Returns the query param parsed as a long, or {@code defaultValue} when the param is
     * missing OR present but unparseable. See {@link #queryInt(String, int)}.
     */
    public long queryLong(String name, long defaultValue) {
        readQuery(name);
        var value = queryParams.get(name);
        if (value == null) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean hasQueryParam(String name) {
        readQuery(name);
        return queryParams.containsKey(name);
    }

    /**
     * All values of a query parameter, URL-decoded, in order of appearance — for
     * {@code <select multiple>} / checkbox-group submissions like {@code ?tag=a&tag=b}.
     * Returns an empty list when the parameter is absent. The single-value
     * {@link #queryParam(String)} (last value wins) is unchanged.
     */
    public List<String> queryParams(String name) {
        readQuery(name);
        return queryValues(name);
    }

    /** {@link #queryParams(String)} without recording the read, for the page cache's own key. */
    List<String> queryValues(String name) {
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

    /**
     * This request's path and query string, e.g. {@code /posts?tag=java} — for a {@code next=}
     * return address or a canonical link. The query is re-encoded, so the result is safe to put in
     * a link.
     */
    public String url() {
        readQuery("*");
        readRawPath();
        var qs = new StringBuilder();
        for (var pair : queryPairs()) Url.appendPair(qs, pair[0], pair[1]);
        return qs.isEmpty() ? path : path + "?" + qs;
    }

    /**
     * This request's path and query string with one query parameter set to {@code value},
     * everything else kept — for sort, filter and page links that preserve the current query:
     * {@code req.urlWith("sort", "name")} on {@code /posts?tag=java&sort=date} gives
     * {@code /posts?tag=java&sort=name}. A {@code null} or empty value removes the parameter.
     * Values are re-encoded, so the result is safe to put in a link.
     */
    public String urlWith(String name, Object value) {
        readQuery("*");
        readRawPath();
        var text = Url.format(value);
        var qs = new StringBuilder();
        boolean placed = false;
        for (var pair : queryPairs()) {
            if (pair[0].equals(name)) {
                // Replace in place (first occurrence) so parameter order stays stable across links.
                if (!placed && text != null && !text.isEmpty()) Url.appendPair(qs, name, text);
                placed = true;
            } else {
                Url.appendPair(qs, pair[0], pair[1]);
            }
        }
        if (!placed && text != null && !text.isEmpty()) Url.appendPair(qs, name, text);
        return qs.isEmpty() ? path : path + "?" + qs;
    }

    /** Decoded query {@code {name, value}} pairs in order of appearance, repeats included. */
    List<String[]> queryPairs() {
        var pairs = new java.util.ArrayList<String[]>();
        if (rawQuery != null) {
            if (!rawQuery.isEmpty()) scanPairs(rawQuery, true, (k, v) -> pairs.add(new String[]{k, v}));
        } else {
            // Hand-constructed Request (unit tests): only the single-value map is available.
            queryParams.forEach((k, v) -> pairs.add(new String[]{k, v}));
        }
        return pairs;
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
        readBody();
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
            try {
                if (eq < 0 || (eq == 0 && bareKeyOnLeadingEq)) {
                    key = URLDecoder.decode(pair, StandardCharsets.UTF_8);
                    value = "";
                } else {
                    key = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
                    value = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException badEscape) {
                throw new MalformedRequestException(badEscape); // a 400, not a 500
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
        readHeader(name);
        return headers.get(name);
    }

    public boolean hasHeader(String name) {
        readHeader(name);
        return headers.containsKey(name);
    }

    /** Copy headers into a case-insensitive map so lookups don't depend on the sender's casing. */
    private static Map<String, String> caseInsensitive(Map<String, String> source) {
        // M3: adopt a map that is already case-insensitive (BraceHandler builds one per
        // request) instead of copying it into a second TreeMap — the copy was ~20
        // comparator-driven inserts of pure waste on every request. Maps with any other
        // comparator (or HashMaps from tests/public callers) still get the defensive copy.
        if (source instanceof TreeMap<String, String> tm
            && tm.comparator() == String.CASE_INSENSITIVE_ORDER) {
            return source;
        }
        var map = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        if (source != null) map.putAll(source);
        return map;
    }

    public boolean isHtmx() {
        return "true".equals(header("HX-Request"));
    }

    public String body() { readBody(); resolveBody(); return body; }

    public UploadedFile file(String name) {
        readBody();
        resolveBody();
        var files = uploadedFiles.get(name);
        if (files == null || files.isEmpty()) return null;
        return files.getFirst();
    }

    public List<UploadedFile> files(String name) {
        readBody();
        resolveBody();
        return uploadedFiles.getOrDefault(name, List.of());
    }

    public <T> T bodyAs(Class<T> type) {
        // Binding JSON straight into an entity lets the client set any public field (id, role,
        // passwordHash), and a following db.update(entity) would write them. Always refused:
        // here for the top-level class (before the body is read), and by EntityBindingGuard for
        // an entity nested anywhere in the type (an array element, a record component).
        if (type.isAnnotationPresent(jakarta.persistence.Entity.class)) {
            throw new IllegalArgumentException(EntityBindingGuard.message(type));
        }
        try {
            return Json.mapper().readerFor(type)
                .withAttribute(EntityBindingGuard.REFUSE, Boolean.TRUE)
                .readValue(body());
        } catch (Exception e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof EntityBindingGuard.Refused refused) {
                    throw new IllegalArgumentException(refused.getMessage());
                }
            }
            throw new RuntimeException("Failed to parse request body as " + type.getSimpleName(), e);
        }
    }

    public String cookie(String name) {
        if (reads != null) reads.cookies.add(name);
        var cookieHeader = headers.get("Cookie");
        if (cookieHeader == null) return null;
        for (var part : cookieHeader.split(";")) {
            var trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }

    /**
     * The host the client addressed, without port: {@code X-Forwarded-Host} when a trusted proxy
     * sent it, else {@code Host}. Null if neither is present.
     */
    String host() {
        String h = ProxyHeaders.effectiveHost(headers::get, remoteAddr, trustedProxies);
        return h == null ? null : stripPort(h.strip());
    }

    /** The host the client addressed, with its port if it gave one. */
    String hostWithPort() {
        String h = ProxyHeaders.effectiveHost(headers::get, remoteAddr, trustedProxies);
        return h == null ? null : h.strip().toLowerCase(java.util.Locale.ROOT);
    }

    /** True when the immediate peer is a configured trusted proxy, so its headers can be believed. */
    boolean fromTrustedProxy() {
        return trustedProxies != null && remoteAddr != null && trustedProxies.isTrusted(remoteAddr);
    }

    /**
     * Every wire instance of the configured forwarding header ({@link TrustedProxies#clientIpHeader}),
     * joined with {@code ","} in wire order, captured when the framework builds the request (L3).
     * {@link #headers} keeps one value per name (the last line), which let a client's line and the
     * proxy's line race; the address chain needs all of them. Null when not captured (a hand-built
     * Request), in which case {@link #ip()} falls back to the single-value header.
     */
    private String forwardingChain;
    private boolean forwardingChainCaptured;

    /** Set once the "trusted proxy sends only the other forwarding header" warning is logged (L3 review F3). */
    private static final java.util.concurrent.atomic.AtomicBoolean warnedForwardingHeader =
        new java.util.concurrent.atomic.AtomicBoolean();

    static boolean warnedForwardingHeader() { return warnedForwardingHeader.get(); }

    static void resetForwardingHeaderWarning() { warnedForwardingHeader.set(false); }

    void setForwardingChain(String joinedValues) {
        this.forwardingChain = joinedValues;
        this.forwardingChainCaptured = true;
    }

    /** The sentinel {@link #ip()} returns when no client address can be determined. */
    static final String UNKNOWN_IP = "unknown";

    /** {@link #ip()}'s answer, resolved once per request. */
    private String resolvedIp;

    /**
     * The client's address: a canonical IP literal, or {@code "unknown"} (never anything else).
     *
     * <p><b>Form.</b> IPv4 is dotted decimal ({@code 203.0.113.9}). IPv6 is RFC 5952: lower-case,
     * no leading zeros, the longest run of zero groups written {@code ::} ({@code 2001:db8::5},
     * {@code ::1}), never bracketed and never with a port. An IPv4-mapped IPv6 address
     * ({@code ::ffff:6.6.6.6}) is returned as the IPv4 address. A zone id is kept as written
     * ({@code fe80::1%eth0}). So comparing {@code req.ip()} with a string written in that form is
     * reliable, however the proxy or the socket layer spelled the address.
     *
     * <p><b>Which address.</b> The socket peer, unless the peer is a trusted proxy, in which case
     * it is the rightmost untrusted entry of the one forwarding header {@link TrustedProxies}
     * names. When a trusted proxy's header names no usable client (the selected entry is not an
     * address: {@code unknown}, {@code _hidden}, a malformed element, an empty header, or the
     * proxy sent only the forwarding header Brace was not told to read), the answer is
     * {@code "unknown"}, never the proxy's own address: returning the proxy's address would let a
     * client that can malform the header pass for the proxy in an address allowlist. Treat
     * {@code "unknown"} as matching nothing (it is not an address, so
     * {@link TrustedProxies#isTrusted} is false for it); {@code RateLimiter.perIp} counts every
     * such request in one shared bucket. A trusted peer that sends no forwarding header at all
     * (a health check from the proxy, a local process) is itself the client and is returned.
     */
    public String ip() {
        if (reads != null) reads.ip = true;
        if (resolvedIp == null) resolvedIp = resolveIp();
        return resolvedIp;
    }

    private String resolveIp() {
        String peer = TrustedProxies.canonical(remoteAddr);
        if (peer == null) return UNKNOWN_IP;
        // Only trust forwarding headers if proxies are configured and the immediate peer is trusted
        if (trustedProxies == null || !trustedProxies.isTrusted(remoteAddr)) return peer;
        // L3: exactly one forwarding header is read, the one TrustedProxies names
        // (X-Forwarded-For unless useForwardedHeader()); the other is ignored, so a client
        // cannot supply its own address in the header the proxy doesn't overwrite.
        var chain = forwardingChainCaptured ? forwardingChain : headers.get(trustedProxies.clientIpHeader());
        if (chain == null) {
            // Nothing in the header Brace reads. If the peer sent the other one it is proxying
            // for someone Brace cannot identify; with neither, the peer speaks for itself.
            return warnIfOnlyTheOtherForwardingHeader() ? UNKNOWN_IP : peer;
        }
        var selected = trustedProxies.usesForwardedHeader()
            ? extractForwardedFor(chain)
            : rightmostUntrusted(chain.split(","));
        // L3 final review: the peer is a proxy and its header names no address. The proxy's own
        // address is not the client's, so it is never the fallback.
        String client = selected == null ? null : TrustedProxies.canonical(selected);
        return client != null ? client : UNKNOWN_IP;
    }

    /**
     * L3 review F3: a trusted proxy sent the forwarding header Brace was not told to read, and not
     * the one it was. Typically a proxy that writes only RFC 7239 {@code Forwarded} while the app
     * reads {@code X-Forwarded-For} (the default), so no client's address can be determined.
     * Logged once per process. Returns true when the other header is present.
     */
    private boolean warnIfOnlyTheOtherForwardingHeader() {
        boolean forwardedChosen = trustedProxies.usesForwardedHeader();
        String other = forwardedChosen ? "X-Forwarded-For" : "Forwarded";
        String otherValue = headers.get(other);
        if (otherValue == null || otherValue.isBlank()) return false;
        if (warnedForwardingHeader.compareAndSet(false, true)) {
            Log.warn("Trusted proxy " + remoteAddr + " sent " + other + " but not "
                + trustedProxies.clientIpHeader() + ", the header req.ip() reads, so req.ip() is \"unknown\" "
                + "for every client. " + (forwardedChosen
                    ? "If the proxy writes X-Forwarded-For, drop useForwardedHeader() from app.trustedProxies(...)."
                    : "If the proxy writes RFC 7239 Forwarded, use app.trustedProxies(new TrustedProxies(...)"
                        + ".useForwardedHeader()).")
                + " See docs/SECURITY.md, Trusted Proxies. Logged once.");
        }
        return true;
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

    /**
     * The {@code for=} value of each RFC 7239 element, in order, then the rightmost-untrusted walk.
     * L3 review F8: commas and semicolons inside a quoted-string are not separators, and a
     * backslash escapes the next character in one, so {@code for="a,b"} is one value (which is not
     * an address, so {@link #ip()} is {@code "unknown"}) rather than {@code "a} and {@code b"}.
     * An element without {@code for=} contributes nothing.
     *
     * <p>Elements are split from the RIGHT ({@link #splitForwardedElements}; L3 final review):
     * the element a trusted proxy appended is the end of the string and is well-formed, so read
     * from that end it is delimited by its own text alone. Read from the left, a client's
     * unbalanced quote ({@code for="x}) opened a quoted-string that ran through the proxy's comma
     * and swallowed its element.
     */
    private String extractForwardedFor(String forwarded) {
        var forValues = new java.util.ArrayList<String>();
        for (var element : splitForwardedElements(forwarded)) {
            for (var param : splitForwarded(element, ';')) {
                var trimmed = param.strip();
                int eq = trimmed.indexOf('=');
                if (eq < 0 || !trimmed.substring(0, eq).strip().equalsIgnoreCase("for")) continue;
                forValues.add(unquote(trimmed.substring(eq + 1).strip()));
                break; // only one "for=" per list-element
            }
        }
        if (forValues.isEmpty()) return null;
        return rightmostUntrusted(forValues.toArray(new String[0]));
    }

    /**
     * The comma-separated elements of a {@code Forwarded} value, in wire order, found by scanning
     * right to left. A {@code "} met from the right closes a quoted-string whose opening quote is
     * the nearest unescaped {@code "} to its left; commas between the two are content. A
     * {@code "} with no partner to its left (a client's unbalanced quote) is an ordinary
     * character, so it spoils only the element it is in. Whatever a suffix of the string is
     * (the elements trusted proxies appended), it splits the same way regardless of the text
     * before it.
     */
    static List<String> splitForwardedElements(String s) {
        var parts = new java.util.ArrayList<String>();
        int end = s.length();
        boolean noQuoteToTheLeft = false;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ',') {
                parts.add(s.substring(i + 1, end));
                end = i;
            } else if (c == '"' && !noQuoteToTheLeft) {
                int open = i - 1;
                while (open >= 0 && !(s.charAt(open) == '"' && !escaped(s, open))) open--;
                if (open >= 0) {
                    i = open; // skip the quoted-string
                } else {
                    // A lone quote is just a character, and so is every quote left of it (none
                    // of them has an unescaped quote to its left either): no rescans.
                    noQuoteToTheLeft = true;
                }
            }
        }
        parts.add(s.substring(0, end));
        java.util.Collections.reverse(parts);
        return parts;
    }

    /** True when the character at {@code index} is preceded by an odd number of backslashes. */
    private static boolean escaped(String s, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && s.charAt(i) == '\\'; i--) backslashes++;
        return (backslashes & 1) == 1;
    }

    /** Split on {@code separator} outside quoted-strings (backslash escapes inside them). */
    static List<String> splitForwarded(String s, char separator) {
        var parts = new java.util.ArrayList<String>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted && c == '\\') {
                i++; // the escaped character, whatever it is
            } else if (c == '"') {
                quoted = !quoted;
            } else if (c == separator && !quoted) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    /** A quoted-string's content with escapes resolved, or {@code s} unchanged if it is a token. */
    private static String unquote(String s) {
        if (s.length() < 2 || s.charAt(0) != '"' || s.charAt(s.length() - 1) != '"') return s;
        var out = new StringBuilder(s.length());
        for (int i = 1; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() - 1) c = s.charAt(++i);
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Bind the form to a record and validate it.
     *
     * <p>On {@code GET} and {@code HEAD} (a search or filter form) the fields come from the query
     * string, with any body field taking precedence. On every other method ({@code POST},
     * {@code PUT}, {@code PATCH}, {@code DELETE}) they come from the body only, the same source
     * as {@link #formParam(String)} (M4): a value in the URL must not fill a field the body left
     * out, or a guard reading {@code formParam} and the handler would see different values.
     */
    public <T> Form<T> form(Class<T> type) {
        if (!bindsQueryString(method)) {
            return FormBinder.bind(type, parsedFormBody());
        }
        // Copy: the cached form map must not absorb query params, and may be immutable-empty.
        readQuery("*");
        var params = new LinkedHashMap<>(parsedFormBody());
        for (var entry : queryParams.entrySet()) {
            params.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return FormBinder.bind(type, params);
    }

    /** Only the safe methods bind a form from the query string; fail closed for anything else. */
    private static boolean bindsQueryString(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
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
        readBody();
        if (formBodyParams == null) {
            resolveBody();
            formBodyParams = parseSingleValues(body, false);
        }
        return formBodyParams;
    }

    /**
     * Dev mode or {@code Brace.test()} (set by {@link BraceHandler}, as for the query-taint check):
     * a page-cached handler's reads are recorded so an input outside the cache key is reported
     * (L4 review F6). False in production, where nothing is recorded.
     */
    private boolean strictInputChecks;

    void setStrictInputChecks(boolean strict) { this.strictInputChecks = strict; }

    boolean strictInputChecks() { return strictInputChecks; }

    /**
     * The request inputs a handler read while {@link #recordReads} was on: query parameter names
     * ({@code "*"} for the whole query), header names, cookie names, and whether it read the body
     * or the client address. Only ever set in dev mode and tests.
     */
    static final class InputReads {
        final java.util.Set<String> query = new java.util.LinkedHashSet<>();
        final java.util.Set<String> headers = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        final java.util.Set<String> cookies = new java.util.LinkedHashSet<>();
        boolean body;
        boolean ip;
        /** The raw path ({@code path()}, {@code url()}, {@code urlWith()}), not the decoded path params. */
        boolean rawPath;
    }

    /** Non-null only while a page-cached handler runs in dev mode or a test; see {@link #strictInputChecks}. */
    private InputReads reads;

    /** Start recording reads into {@code into}, or stop with null. */
    void recordReads(InputReads into) { this.reads = into; }

    private void readQuery(String name) { if (reads != null) reads.query.add(name); }
    private void readHeader(String name) { if (reads != null) reads.headers.add(name); }
    private void readBody() { if (reads != null) reads.body = true; }
    private void readRawPath() { if (reads != null) reads.rawPath = true; }

    void setStorage(Storage storage) {
        this.storage = storage;
    }

    /** Raw (still URL-encoded) query string, set by {@link BraceHandler}; backs {@link #queryParams(String)}. */
    void setRawQuery(String rawQuery) {
        this.rawQuery = rawQuery;
    }

    /** The route this request matched, set by {@link BraceHandler}; null for a hand-built Request. */
    private Route route;

    Route route() { return route; }

    void setRoute(Route route) { this.route = route; }

    /** Verified ops credential, attached by the ops routes' access requirement (H1). */
    private OpsToken.Claims opsClaims;

    OpsToken.Claims opsClaims() { return opsClaims; }

    void setOpsClaims(OpsToken.Claims claims) { this.opsClaims = claims; }

    public Storage storage() {
        if (storage == null) {
            throw new IllegalStateException("Storage not configured. Call app.storage() to configure.");
        }
        return storage;
    }
}

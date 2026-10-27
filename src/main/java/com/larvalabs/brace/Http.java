package com.larvalabs.brace;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * Fluent outbound HTTP client over {@code java.net.http}.
 *
 * <p><strong>Public destinations only, by default.</strong> Before each request the host is
 * resolved and the request is refused with {@link BlockedDestinationException} if any address it
 * resolves to is loopback, private (RFC 1918, CGNAT, IPv6 ULA), link-local (which includes the
 * cloud metadata service at 169.254.169.254), unspecified, multicast or otherwise reserved, so a
 * URL that reaches {@code Http} from user input can't be pointed at the app's own network
 * (server-side request forgery). Call {@link #trusted()} on a request to an internal service you
 * chose yourself. See {@link #isPublicAddress} for the exact ranges.
 *
 * <p>The check runs on the resolved {@link InetAddress}, never on the URL text, so numeric forms
 * such as {@code http://2130706433/} or {@code http://[::ffff:127.0.0.1]/} are caught as what
 * they are. The connection itself is made by the JDK, which resolves the name again through the
 * JVM's address cache (the same cache entry the check just read), and the name is checked once
 * more when the response headers arrive, before any of the response reaches the caller. What
 * remains is a narrow DNS-rebinding window, described in {@code docs/SECURITY.md}.
 */
public class Http {

    /** The default {@link #maxResponseBytes} for buffered fetches: 10 MB. */
    public static final long DEFAULT_MAX_RESPONSE_BYTES = 10L * 1024 * 1024;

    /**
     * Shared client. {@code followRedirects} is deliberately left at the JDK default
     * ({@code NEVER}) — see {@link #fetch()} (L7). That also keeps the destination check
     * complete: every connection {@code Http} makes is to a URL it has checked.
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final String method;
    private final String url;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private HttpRequest.BodyPublisher bodyPublisher;
    private Duration timeout = Duration.ofSeconds(30);
    private boolean timeoutSet;
    private Duration idleTimeout = Duration.ofSeconds(30);
    private boolean trusted;
    private long maxResponseBytes = DEFAULT_MAX_RESPONSE_BYTES;
    /** The wall clock the stream timeouts also go by. Tests move it to stand in for the machine sleeping. */
    LongSupplier wallClock = System::currentTimeMillis;

    private Http(String method, String url) {
        this.method = method;
        this.url = url;
    }

    public static Http get(String url) { return new Http("GET", url); }
    public static Http post(String url) { return new Http("POST", url); }
    public static Http put(String url) { return new Http("PUT", url); }
    public static Http delete(String url) { return new Http("DELETE", url); }

    public Http header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public Http bearer(String token) {
        return header("Authorization", "Bearer " + token);
    }

    /**
     * For {@code fetch*} calls, how long to wait for the response headers (default 30s). For
     * {@link #stream()} and {@link #fetchEvents}, the deadline for the whole call, headers and
     * body together (default none): the connection is closed and {@link StreamTimeoutException}
     * thrown when it passes. Time the machine spends asleep counts.
     */
    public Http timeout(Duration timeout) {
        this.timeout = timeout;
        this.timeoutSet = true;
        return this;
    }

    /**
     * For {@link #stream()} and {@link #fetchEvents}: close the connection and throw
     * {@link StreamTimeoutException} when the response headers, or the next bytes of the body,
     * take this long to arrive (default 30s; {@code null} or {@link Duration#ZERO} turns it off).
     * Time the caller spends between reads doesn't count; time the machine spends asleep does.
     * Ignored by {@code fetch*} calls.
     */
    public Http idleTimeout(Duration idleTimeout) {
        if (idleTimeout != null && idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must not be negative: " + idleTimeout);
        }
        this.idleTimeout = idleTimeout == null || idleTimeout.isZero() ? null : idleTimeout;
        return this;
    }

    /**
     * Allow this request to reach any address, including loopback, private networks and the
     * cloud metadata service. Use it only for a destination you chose yourself (an internal
     * service, a sidecar, a URL from your own config), never for a URL that came from a user:
     * that is exactly the request the default check exists to stop.
     */
    public Http trusted() {
        this.trusted = true;
        return this;
    }

    /**
     * The most body bytes {@link #fetch()}, {@link #fetchString()}, {@link #fetchBytes()} and
     * {@link #fetchJson} will buffer (default {@link #DEFAULT_MAX_RESPONSE_BYTES}, 10 MB), and the
     * most of an error body {@link #fetchEvents} will read. A larger response fails with
     * {@link ResponseTooLargeException} as soon as its {@code Content-Length}, or the bytes read
     * so far, pass the limit; the rest is never read. {@link #stream()} is not capped: it hands
     * you the body to read as you choose.
     */
    public Http maxResponseBytes(long maxResponseBytes) {
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive: " + maxResponseBytes);
        }
        this.maxResponseBytes = maxResponseBytes;
        return this;
    }

    public Http bodyJson(Object value) {
        try {
            var json = Json.mapper().writeValueAsString(value);
            this.bodyPublisher = HttpRequest.BodyPublishers.ofString(json);
            headers.putIfAbsent("Content-Type", "application/json");
            return this;
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize request body", e);
        }
    }

    public Http bodyForm(Map<String, String> params) {
        var sb = new StringBuilder();
        for (var entry : params.entrySet()) {
            if (!sb.isEmpty()) sb.append("&");
            sb.append(java.net.URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
            sb.append("=");
            sb.append(java.net.URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        this.bodyPublisher = HttpRequest.BodyPublishers.ofString(sb.toString());
        headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");
        return this;
    }

    public Http bodyString(String body) {
        this.bodyPublisher = HttpRequest.BodyPublishers.ofString(body);
        return this;
    }

    public Http bodyBytes(byte[] bytes, String contentType) {
        this.bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(bytes);
        headers.putIfAbsent("Content-Type", contentType);
        return this;
    }

    public Multipart multipart() {
        return new Multipart(this);
    }

    // --- Execute ---

    private HttpRequest buildRequest() {
        return buildRequest(timeout);
    }

    private HttpRequest buildRequest(Duration headerTimeout) {
        var builder = HttpRequest.newBuilder().uri(URI.create(url));
        if (headerTimeout != null) builder.timeout(headerTimeout);
        for (var entry : headers.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        var pub = bodyPublisher != null ? bodyPublisher : HttpRequest.BodyPublishers.noBody();
        builder.method(method, pub);
        return builder.build();
    }

    /**
     * Send the request and return the response, <strong>whatever its status</strong>. A 4xx or 5xx
     * is a {@link Response} with that {@link Response#status()}, not an exception — check
     * {@link Response#ok()} yourself. Only a transport failure (connect refused, timeout, DNS)
     * throws, plus {@link BlockedDestinationException} for a non-public destination and
     * {@link ResponseTooLargeException} for a body over {@link #maxResponseBytes}.
     *
     * <p>Redirects are <strong>not</strong> followed: a 301/302 comes back as itself, so read
     * {@code header("Location")} and re-issue if you want to follow it. This is the JDK's default
     * and is kept because silently following a redirect can replay a request body or leak an
     * {@code Authorization} header to another host. Re-issue it with {@code Http} (not another
     * client), so the new destination is checked too, and leave off {@link #trusted()} unless
     * you trust wherever the redirect points.
     *
     * <p>{@link #fetchBytes()} is the exception to the first rule: it throws on a non-2xx, because
     * a caller asking for bytes has nowhere to put an error body. (L7 — the asymmetry is
     * deliberate but was previously undocumented.)
     */
    public Response fetch() {
        return exchange(() -> {
            var raw = send(buildRequest());
            var body = readBody(raw.body(), raw.headers());
            return new Response(raw.statusCode(), raw.headers(), new String(body, charset(raw.headers())));
        });
    }

    /**
     * Send the request and parse the body as JSON, <strong>whatever the status</strong>. On a 500
     * returning an HTML error page this fails with a parse error, not an HTTP error — the
     * exception message carries the status and body so the cause is visible. Call {@link #fetch()}
     * and check {@link Response#ok()} first if you need to distinguish the two. (L7)
     */
    public <T> T fetchJson(Class<T> type) {
        var response = fetch();
        return response.as(type);
    }

    /** Send the request and return the body as a String, whatever the status. See {@link #fetch()}. */
    public String fetchString() {
        return fetch().body();
    }

    /**
     * Send the request and return the raw body bytes, <strong>throwing on a non-2xx status</strong>
     * — unlike {@link #fetch()} and friends, which hand the status back. A caller asking for bytes
     * has nowhere to put an error body, so failing loudly is the useful behavior. (L7)
     */
    public byte[] fetchBytes() {
        var raw = exchange(() -> send(buildRequest()));
        int status = raw.statusCode();
        if (status < 200 || status >= 300) {
            closeQuietly(raw.body());
            throw new RuntimeException("HTTP request failed: " + method + " " + url + " (status " + status + ")");
        }
        return exchange(() -> readBody(raw.body(), raw.headers()));
    }

    /** One blocking step of a call: send, or read the body. */
    private interface Step<T> {
        T run() throws IOException, InterruptedException;
    }

    /**
     * Run a step, turning its failures into this class's exceptions: {@link #failed} for a
     * transport failure, with the exceptions that already say what went wrong passed through.
     */
    private <T> T exchange(Step<T> step) {
        try {
            return step.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted: " + method + " " + url, e);
        } catch (BlockedDestinationException | ResponseTooLargeException | StreamTimeoutException e) {
            throw e;
        } catch (Exception e) {
            throw failed(e);
        }
    }

    /**
     * Send the request, leaving the body unread, with the destination checked before the request
     * goes out and again once the response headers are in, unless {@link #trusted()}.
     *
     * <p>H5: the second check is for DNS rebinding. The JDK resolves the name again to connect,
     * and that lookup is normally served by the JVM address cache entry the first check read, so
     * it gets the addresses that were checked. A name whose cache entry expired between the two
     * lookups could get a different, private answer, but the JVM caches that answer in turn, so
     * the second check sees it and the response is discarded before the caller reads any of it.
     */
    private HttpResponse<InputStream> send(HttpRequest request) throws IOException, InterruptedException {
        var host = request.uri().getHost();
        if (!trusted) checkDestination(host);
        var raw = CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (!trusted) {
            try {
                checkDestination(host);
            } catch (IOException | RuntimeException e) {
                closeQuietly(raw.body());
                throw e;
            }
        }
        return raw;
    }

    /**
     * Read a buffered body, failing as soon as it is known to exceed {@link #maxResponseBytes}:
     * from {@code Content-Length} before reading anything, otherwise once the bytes read pass
     * the limit. Closing the stream early cancels the exchange, so the rest is never downloaded.
     */
    private byte[] readBody(InputStream body, HttpHeaders headers) throws IOException {
        try (body) {
            long declared = headers.firstValueAsLong("Content-Length").orElse(-1);
            if (declared > maxResponseBytes) throw tooLarge();
            var out = new ByteArrayOutputStream((int) Math.min(Math.max(declared, 0), 64 * 1024));
            var buffer = new byte[8192];
            long total = 0;
            for (int n; (n = body.read(buffer)) != -1; ) {
                total += n;
                if (total > maxResponseBytes) throw tooLarge();
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        }
    }

    private ResponseTooLargeException tooLarge() {
        return new ResponseTooLargeException(method, url, maxResponseBytes);
    }

    /** The body charset named by {@code Content-Type}, or UTF-8, as {@code BodyHandlers.ofString} does. */
    static Charset charset(HttpHeaders headers) {
        var contentType = headers.firstValue("Content-Type").orElse("");
        for (var param : contentType.split(";")) {
            var p = param.trim();
            if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                var name = p.substring(8).trim();
                if (name.length() >= 2 && name.startsWith("\"") && name.endsWith("\"")) {
                    name = name.substring(1, name.length() - 1);
                }
                try {
                    return Charset.forName(name);
                } catch (IllegalArgumentException e) {
                    return StandardCharsets.UTF_8;
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // Closing only cancels the exchange; there's nothing to report.
        }
    }

    /**
     * The transport failure, with its cause named in the message ({@code ConnectException},
     * {@code UnknownHostException}, ...) so callers matching on the text can tell a refused
     * connection from a bad host without unwrapping. An unknown host is reported by the
     * destination check's own lookup as {@code UnknownHostException}; on a {@link #trusted()}
     * request the JDK reports it ({@code ConnectException} caused by
     * {@code UnresolvedAddressException}).
     */
    private RuntimeException failed(Exception e) {
        String detail = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        return new RuntimeException("HTTP request failed: " + method + " " + url + " (" + detail + ")", e);
    }

    /**
     * Send the request and return as soon as the response headers arrive, with the body left
     * to read as it streams in. Close the result (try-with-resources) to release the
     * connection. {@link #idleTimeout} (default 30s) bounds each wait for data, including the
     * wait for the response headers, and an explicit
     * {@link #timeout} the whole call; either closes the connection and throws
     * {@link StreamTimeoutException}. A non-2xx status is returned, not thrown: check
     * {@link StreamResponse#ok()}.
     *
     * <p>The destination is checked as for {@link #fetch()}, but the body is
     * <strong>not</strong> capped by {@link #maxResponseBytes}: you read it, so bound what you
     * read yourself.
     */
    public StreamResponse stream() {
        var watchdog = new Watchdog(this);
        try {
            var raw = send(buildRequest(watchdog.headerTimeout()));
            return new StreamResponse(raw, watchdog.attach(raw.body()));
        } catch (BlockedDestinationException e) {
            watchdog.close();
            throw e;
        } catch (HttpTimeoutException e) {
            watchdog.close();
            if (e instanceof HttpConnectTimeoutException) {
                throw failed(e);
            }
            throw watchdog.headerTimeoutException();
        } catch (InterruptedException e) {
            watchdog.close();
            Thread.currentThread().interrupt();
            throw new RuntimeException("HTTP request interrupted: " + method + " " + url, e);
        } catch (StreamTimeoutException e) {
            throw e;
        } catch (Exception e) {
            watchdog.close();
            throw failed(e);
        }
    }

    /**
     * Read a {@code text/event-stream} response, passing each Server-Sent Event to
     * {@code onEvent} as it arrives, and return once the server ends the stream. The returned
     * {@link Response} carries the status and headers; on a non-2xx status no events are
     * parsed and its {@code body()} is the error body. Sends {@code Accept: text/event-stream}
     * unless an Accept header is set. Timeouts are as for {@link #stream()}. The destination is
     * checked as for {@link #fetch()}; an error body is capped at {@link #maxResponseBytes}, the
     * events are not.
     */
    public Response fetchEvents(Consumer<Event> onEvent) {
        if (headers.keySet().stream().noneMatch("Accept"::equalsIgnoreCase)) {
            headers.put("Accept", "text/event-stream");
        }
        try (var response = stream()) {
            if (!response.ok()) {
                var responseHeaders = response.raw.headers();
                var body = exchange(() -> readBody(response.body(), responseHeaders));
                return new Response(response.status(), responseHeaders, new String(body, charset(responseHeaders)));
            }
            response.events(onEvent);
            return new Response(response.status(), response.raw.headers(), "");
        }
    }

    // --- Destination policy (H5) ---

    /** How {@link #checkDestination} resolves a host. */
    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /**
     * The JVM resolver, whose cache the JDK's own connect reads too. Tests swap it to make a name
     * change its answer mid-request; nothing else should.
     */
    static volatile Resolver resolver = InetAddress::getAllByName;

    /**
     * Resolve {@code host} and refuse it if <em>any</em> of its addresses is not public. Any, not
     * the first: the connection may be made to a different one of them than the one checked.
     * The check runs on the resolved addresses, so a numeric or bracketed host
     * ({@code 2130706433}, {@code [::ffff:127.0.0.1]}) is judged by the address it parses to.
     */
    static void checkDestination(String host) throws UnknownHostException {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("URL has no host");
        }
        // Java reads 0177.0.0.1 as decimal 177.0.0.1 (public), but an HTTP proxy or anything
        // using inet_aton reads it as octal 127.0.0.1. Numeric hosts must be a plain dotted quad,
        // so every reader agrees on the address that was checked.
        if (isNonCanonicalNumericHost(host)) {
            throw new BlockedDestinationException(host, null);
        }
        warnIfDnsCacheDisabled();
        for (var address : resolver.resolve(host)) {
            if (!isPublicAddress(address)) {
                throw new BlockedDestinationException(host, address);
            }
        }
    }

    private static final java.util.regex.Pattern NUMERIC_LABEL = java.util.regex.Pattern.compile("\\d+|0[xX][0-9a-fA-F]*");
    private static final java.util.regex.Pattern DECIMAL_OCTET = java.util.regex.Pattern.compile("0|[1-9]\\d{0,2}");

    /**
     * A host made only of numeric labels (decimal, or 0x hex) that isn't a canonical dotted quad:
     * {@code 2130706433}, {@code 0177.0.0.1}, {@code 0x7f.1}, {@code 127.1}. No real host name is
     * all-numeric (a top-level domain never is), so refusing these costs nothing.
     */
    static boolean isNonCanonicalNumericHost(String host) {
        if (host.indexOf(':') >= 0) return false; // bracketed IPv6
        String name = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String[] labels = name.split("\\.", -1);
        for (String label : labels) {
            if (!NUMERIC_LABEL.matcher(label).matches()) return false;
        }
        if (labels.length != 4) return true;
        for (String label : labels) {
            if (!DECIMAL_OCTET.matcher(label).matches() || Integer.parseInt(label) > 255) return true;
        }
        return false;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean dnsCacheWarned = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The re-check after the response headers relies on the JVM caching a lookup for longer than
     * a request takes (30 seconds by default). With the cache off or very short, each lookup is
     * fresh, so a rebinding name can pass both checks while the connection goes to a private
     * address, and the response can be read. Warn once when that's the configuration.
     */
    private static void warnIfDnsCacheDisabled() {
        if (dnsCacheWarned.get()) return;
        Integer ttl = parseTtl(java.security.Security.getProperty("networkaddress.cache.ttl"));
        Integer legacy = parseTtl(System.getProperty("sun.net.inetaddr.ttl"));
        int effective = legacy != null ? legacy : ttl != null ? ttl : 30;
        if (effective >= 0 && effective < 10 && dnsCacheWarned.compareAndSet(false, true)) {
            Log.warn("The JVM DNS cache TTL is " + effective + "s (networkaddress.cache.ttl / sun.net.inetaddr.ttl). "
                + "Http's destination check relies on the cache holding a lookup for longer than a request takes; "
                + "with it this short, a DNS-rebinding host can reach internal addresses. Leave the TTL at its "
                + "30s default or longer, and block internal ranges at the network egress too.");
        }
    }

    private static Integer parseTtl(String value) {
        if (value == null) return null;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Whether {@code address} is on the public internet, the only destinations {@code Http}
     * reaches without {@link #trusted()}. Useful to reject a URL when a user enters it, ahead of
     * the request. Not public:
     *
     * <ul>
     *   <li>IPv4: {@code 0.0.0.0/8} (0.0.0.0 reaches the local host), loopback
     *       {@code 127.0.0.0/8}, private {@code 10.0.0.0/8}, {@code 172.16.0.0/12},
     *       {@code 192.168.0.0/16}, CGNAT {@code 100.64.0.0/10}, link-local
     *       {@code 169.254.0.0/16} (cloud metadata), {@code 192.0.0.0/24},
     *       documentation {@code 192.0.2.0/24}, {@code 198.51.100.0/24}, {@code 203.0.113.0/24},
     *       benchmarking {@code 198.18.0.0/15}, the 6to4 relay {@code 192.88.99.0/24},
     *       multicast {@code 224.0.0.0/4}, and reserved {@code 240.0.0.0/4}, which includes
     *       broadcast 255.255.255.255. Also Azure's WireServer {@code 168.63.129.16}, a platform
     *       endpoint in a public range.</li>
     *   <li>IPv6: everything outside global unicast {@code 2000::/3}, which covers unspecified
     *       {@code ::}, loopback {@code ::1}, IPv4-compatible {@code ::/96}, ULA {@code fc00::/7},
     *       link-local {@code fe80::/10}, site-local {@code fec0::/10}, multicast
     *       {@code ff00::/8} and the local-use NAT64 prefix {@code 64:ff9b:1::/48}; inside it,
     *       Teredo {@code 2001::/32}, benchmarking {@code 2001:2::/48} and documentation
     *       {@code 2001:db8::/32} and {@code 3fff::/20}.</li>
     *   <li>IPv6 forms that carry an IPv4 address are judged by that address: IPv4-mapped
     *       {@code ::ffff:0:0/96}, NAT64 {@code 64:ff9b::/96} and 6to4 {@code 2002::/16}.</li>
     * </ul>
     */
    public static boolean isPublicAddress(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 4 ? isPublicV4(b, 0) : isPublicV6(b);
    }

    /** IPv4 ranges that aren't globally reachable (IANA special-purpose registry), as {network, mask}. */
    private static final int[][] BLOCKED_V4 = {
        v4(0, 0, 0, 0, 8),
        v4(10, 0, 0, 0, 8),
        v4(100, 64, 0, 0, 10),
        v4(127, 0, 0, 0, 8),
        v4(169, 254, 0, 0, 16),
        v4(172, 16, 0, 0, 12),
        v4(192, 0, 0, 0, 24),
        v4(192, 0, 2, 0, 24),
        v4(192, 88, 99, 0, 24),
        v4(192, 168, 0, 0, 16),
        v4(198, 18, 0, 0, 15),
        v4(198, 51, 100, 0, 24),
        v4(203, 0, 113, 0, 24),
        v4(168, 63, 129, 16, 32),   // Azure WireServer: public-range, but a platform endpoint
        v4(224, 0, 0, 0, 4),
        v4(240, 0, 0, 0, 4),
    };

    private static int[] v4(int a, int b, int c, int d, int prefix) {
        int mask = -1 << (32 - prefix);
        return new int[] {((a << 24) | (b << 16) | (c << 8) | d) & mask, mask};
    }

    private static boolean isPublicV4(byte[] b, int offset) {
        int address = ((b[offset] & 0xff) << 24) | ((b[offset + 1] & 0xff) << 16)
            | ((b[offset + 2] & 0xff) << 8) | (b[offset + 3] & 0xff);
        for (int[] range : BLOCKED_V4) {
            if ((address & range[1]) == range[0]) return false;
        }
        return true;
    }

    private static boolean isPublicV6(byte[] b) {
        // IPv4-mapped ::ffff:a.b.c.d. The JDK usually hands these back as Inet4Address already;
        // this covers the ones it doesn't.
        if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            return isPublicV4(b, 12);
        }
        int w0 = word(b, 0), w1 = word(b, 2), w2 = word(b, 4);
        // NAT64 64:ff9b::a.b.c.d: the gateway connects to the embedded IPv4 address.
        if (w0 == 0x0064 && w1 == 0xff9b && allZero(b, 4, 12)) {
            return isPublicV4(b, 12);
        }
        // Only global unicast (2000::/3) is routed on the internet.
        if ((b[0] & 0xe0) != 0x20) return false;
        if (w0 == 0x2002) return isPublicV4(b, 2);              // 6to4: 2002:a.b.c.d::/48
        if (w0 == 0x2001 && w1 == 0x0000) return false;         // Teredo
        if (w0 == 0x2001 && w1 == 0x0002 && w2 == 0) return false;  // benchmarking
        if (w0 == 0x2001 && w1 == 0x0db8) return false;         // documentation
        if (w0 == 0x3fff && (w1 & 0xf000) == 0) return false;   // documentation (3fff::/20)
        return true;
    }

    private static int word(byte[] b, int offset) {
        return ((b[offset] & 0xff) << 8) | (b[offset + 1] & 0xff);
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) return false;
        }
        return true;
    }

    /**
     * A request was refused because its host resolves to an address that isn't on the public
     * internet (see {@link Http#isPublicAddress}). Thrown before the request is sent; or, if the
     * name started resolving to such an address while the request was in flight, before any of
     * the response is read. For an internal service you chose yourself, call
     * {@link Http#trusted()} on the request.
     */
    public static class BlockedDestinationException extends RuntimeException {

        private final String host;
        private final InetAddress address;

        BlockedDestinationException(String host, InetAddress address) {
            // The host only: the resolved address stays on address() for logs, so an app that
            // shows the message can't be used to map internal names to private IPs, and nothing
            // was connected to, so there's no port or reachability to report either.
            super(address == null
                ? "Http refused " + host + ": a numeric host must be a plain dotted quad (a.b.c.d)."
                : "Http refused " + host + ": it does not resolve to a public internet address. If this is"
                    + " an internal service you chose, call .trusted() on the request; never do that for a"
                    + " URL from a user.");
            this.host = host;
            this.address = address;
        }

        /** The host as it appeared in the URL. */
        public String host() { return host; }

        /** The resolved address that isn't public; null for a refused numeric host spelling. */
        public InetAddress address() { return address; }
    }

    /**
     * A buffered fetch's response body was larger than {@link Http#maxResponseBytes}. The rest of
     * the body was not read and the connection was released.
     */
    public static class ResponseTooLargeException extends RuntimeException {

        private final long limit;

        ResponseTooLargeException(String method, String url, long limit) {
            super("HTTP response too large: " + method + " " + url + " is over maxResponseBytes ("
                + limit + " bytes). Raise the limit with .maxResponseBytes(n), or read the body"
                + " as it arrives with .stream().");
            this.limit = limit;
        }

        /** The limit that was exceeded, in bytes. */
        public long limit() { return limit; }
    }

    // --- Multipart builder ---

    public static class Multipart {

        private final Http http;
        private final String boundary = "----BraceBoundary" + Long.toHexString(System.nanoTime());
        private final List<Part> parts = new ArrayList<>();

        Multipart(Http http) { this.http = http; }

        public Multipart field(String name, String value) {
            parts.add(new Part(name, null, null, value.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        public Multipart field(String name, byte[] bytes, String filename) {
            return field(name, bytes, filename, guessContentType(filename));
        }

        public Multipart field(String name, byte[] bytes, String filename, String contentType) {
            parts.add(new Part(name, filename, contentType, bytes));
            return this;
        }

        public Multipart header(String name, String value) { http.header(name, value); return this; }
        public Multipart bearer(String token) { http.bearer(token); return this; }
        public Multipart timeout(Duration timeout) { http.timeout(timeout); return this; }
        public Multipart idleTimeout(Duration idleTimeout) { http.idleTimeout(idleTimeout); return this; }
        /** See {@link Http#trusted()}. */
        public Multipart trusted() { http.trusted(); return this; }
        /** See {@link Http#maxResponseBytes(long)}. */
        public Multipart maxResponseBytes(long maxResponseBytes) { http.maxResponseBytes(maxResponseBytes); return this; }

        /** Send the request; the status comes back rather than throwing. See {@link Http#fetch()}. */
        public Response fetch() { finalizeBody(); return http.fetch(); }
        public String fetchString() { finalizeBody(); return http.fetchString(); }
        /** Send the request and return the body bytes, throwing on a non-2xx. See {@link Http#fetchBytes()}. */
        public byte[] fetchBytes() { finalizeBody(); return http.fetchBytes(); }
        public <T> T fetchJson(Class<T> type) { finalizeBody(); return http.fetchJson(type); }
        public StreamResponse stream() { finalizeBody(); return http.stream(); }
        public Response fetchEvents(Consumer<Event> onEvent) { finalizeBody(); return http.fetchEvents(onEvent); }

        private void finalizeBody() {
            var out = new ByteArrayOutputStream();
            try {
                for (var part : parts) {
                    writeAscii(out, "--" + boundary + "\r\n");
                    if (part.filename != null) {
                        writeAscii(out, "Content-Disposition: form-data; name=\"" + escapePartValue(part.name)
                            + "\"; filename=\"" + escapePartValue(part.filename) + "\"\r\n");
                        writeAscii(out, "Content-Type: "
                            + escapePartValue(part.contentType != null ? part.contentType : "application/octet-stream")
                            + "\r\n");
                    } else {
                        writeAscii(out, "Content-Disposition: form-data; name=\"" + escapePartValue(part.name) + "\"\r\n");
                    }
                    writeAscii(out, "\r\n");
                    out.write(part.bytes);
                    writeAscii(out, "\r\n");
                }
                writeAscii(out, "--" + boundary + "--\r\n");
            } catch (IOException e) {
                throw new RuntimeException("Failed to build multipart body", e);
            }
            http.bodyPublisher = HttpRequest.BodyPublishers.ofByteArray(out.toByteArray());
            http.headers.put("Content-Type", "multipart/form-data; boundary=" + boundary);
        }

        private static void writeAscii(ByteArrayOutputStream out, String s) throws IOException {
            out.write(s.getBytes(StandardCharsets.US_ASCII));
        }

        /**
         * Make a value safe to place inside a quoted-string in a multipart part header
         * (2026-07 review, M7).
         *
         * <p>Part names and filenames were concatenated into
         * {@code Content-Disposition: form-data; name="…"; filename="…"} raw and written
         * verbatim, so CR/LF in either value terminated the part headers and let the caller
         * forge <em>additional parts</em> in the outbound request — parameter smuggling into
         * whatever third-party API the app is calling. A user-supplied upload filename is the
         * obvious way that value becomes attacker-controlled.
         *
         * <p>Control characters are a hard failure rather than a silent strip: for an outbound
         * API call, a request that quietly differs from what the caller asked for is worse than
         * one that doesn't happen. Quotes and backslashes are escaped per RFC 7578 §4.2.
         */
        static String escapePartValue(String value) {
            if (value == null) return "";
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c < 0x20 || c == 0x7F) {
                    throw new IllegalArgumentException(
                        "Multipart part name/filename must not contain control characters "
                            + "(found 0x" + Integer.toHexString(c) + "): " + value);
                }
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private static String guessContentType(String filename) {
            if (filename == null) return "application/octet-stream";
            var lower = filename.toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".pdf")) return "application/pdf";
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".txt")) return "text/plain";
            if (lower.endsWith(".html")) return "text/html";
            if (lower.endsWith(".css")) return "text/css";
            if (lower.endsWith(".js")) return "application/javascript";
            if (lower.endsWith(".zip")) return "application/zip";
            return "application/octet-stream";
        }

        private record Part(String name, String filename, String contentType, byte[] bytes) {}
    }

    // --- Response ---

    public static class Response {

        private final int status;
        private final HttpHeaders headers;
        private final String body;

        Response(int status, HttpHeaders headers, String body) {
            this.status = status;
            this.headers = headers;
            this.body = body;
        }

        public int status() { return status; }
        public String body() { return body; }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public boolean ok() { return status() >= 200 && status() < 300; }

        public <T> T as(Class<T> type) {
            try {
                return Json.mapper().readValue(body(), type);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse response as " + type.getSimpleName()
                    + " (status " + status() + "): " + body(), e);
            }
        }
    }

    // --- Streaming ---

    /**
     * A response whose body is read as it arrives, from {@link #stream()}. Holds the connection
     * open until closed; use try-with-resources.
     */
    public static class StreamResponse implements AutoCloseable {

        private final HttpResponse<InputStream> raw;
        private final InputStream body;
        private volatile boolean closed;

        StreamResponse(HttpResponse<InputStream> raw, InputStream body) {
            this.raw = raw;
            this.body = body;
        }

        public int status() { return raw.statusCode(); }
        public boolean ok() { return status() >= 200 && status() < 300; }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }

        /** The body. Reads block until bytes arrive, bounded by the call's timeouts. */
        public InputStream body() { return body; }

        /** The body as UTF-8 lines, each available as soon as it arrives. Call once. */
        public Stream<String> lines() { return reader().lines(); }

        /** The rest of the body as a UTF-8 string, such as the error body of a non-2xx response. */
        public String readString() {
            try {
                return new String(body.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Parse the body as Server-Sent Events, passing each to {@code onEvent} as it arrives,
         * and return when the stream ends or this response is closed (from the consumer or
         * another thread), which is how to stop early. Check {@link #ok()} first: an error body
         * isn't an event stream. Call once.
         */
        public void events(Consumer<Event> onEvent) {
            var parser = new EventParser(onEvent);
            try {
                var reader = reader();
                for (String line; !closed && (line = reader.readLine()) != null; ) parser.line(line);
            } catch (IOException e) {
                if (closed) return;
                throw new UncheckedIOException(e);
            }
        }

        BufferedReader reader() {
            return new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        }

        @Override
        public void close() {
            closed = true;
            try {
                body.close();
            } catch (IOException ignored) {
                // Closing only cancels the exchange; there's nothing to report.
            }
        }
    }

    /**
     * One Server-Sent Event. {@code type} is the {@code event:} field, {@code "message"} when
     * absent. {@code id} and {@code retry} are the last values the stream set (they carry over
     * between events, as in a browser's EventSource), or null if it never set them.
     */
    public record Event(String type, String data, String id, Duration retry) {

        public <T> T as(Class<T> type) {
            try {
                return Json.mapper().readValue(data, type);
            } catch (Exception e) {
                throw new RuntimeException("Failed to parse event data as " + type.getSimpleName()
                    + " (event " + this.type + "): " + data, e);
            }
        }
    }

    /**
     * A streamed call ran past its {@link #timeout} deadline or its {@link #idleTimeout}. The
     * connection has been closed. An {@link UncheckedIOException}, so it also surfaces through
     * {@link StreamResponse#body()} reads and {@link StreamResponse#lines()}.
     */
    public static class StreamTimeoutException extends UncheckedIOException {

        private final boolean idle;
        private final Duration limit;

        /** Public so test doubles of an HTTP transport can throw it. */
        public StreamTimeoutException(String method, String url, boolean idle, Duration limit) {
            this(message(method, url, idle, limit), idle, limit);
        }

        private StreamTimeoutException(String message, boolean idle, Duration limit) {
            super(message, new HttpTimeoutException(message));
            this.idle = idle;
            this.limit = limit;
        }

        /** True for the idle timeout, false for the total deadline. */
        public boolean idle() { return idle; }

        /** The timeout that was exceeded. */
        public Duration limit() { return limit; }

        private static String message(String method, String url, boolean idle, Duration limit) {
            var millis = limit.toMillis();
            var span = millis % 1000 == 0 ? (millis / 1000) + "s" : millis + "ms";
            return "HTTP stream timed out: " + method + " " + url
                + (idle ? " (no data for " + span + ")" : " (exceeded " + span + " deadline)");
        }
    }

    /**
     * The text/event-stream format, per the WHATWG HTML spec ("Parsing an event stream"), fed
     * one line at a time with the line ending already stripped. As the spec requires, an event
     * still pending when the stream ends (no closing blank line) is dropped: the stream was
     * most likely cut off mid-event, and half a payload is worse than none.
     */
    static final class EventParser {

        private final Consumer<Event> onEvent;
        private final StringBuilder data = new StringBuilder();
        private String type = "";
        private String id;
        private Duration retry;
        private boolean first = true;

        EventParser(Consumer<Event> onEvent) { this.onEvent = onEvent; }

        void line(String line) {
            if (first) {
                first = false;
                if (line.startsWith("﻿")) line = line.substring(1);
            }
            if (line.isEmpty()) {
                dispatch();
                return;
            }
            if (line.charAt(0) == ':') return;
            int colon = line.indexOf(':');
            var field = colon < 0 ? line : line.substring(0, colon);
            var value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            switch (field) {
                case "event" -> type = value;
                case "data" -> data.append(value).append('\n');
                case "id" -> { if (value.indexOf('\0') < 0) id = value; }
                case "retry" -> {
                    if (!value.isEmpty() && value.chars().allMatch(c -> c >= '0' && c <= '9')) {
                        try {
                            retry = Duration.ofMillis(Long.parseLong(value));
                        } catch (NumberFormatException ignored) {
                            // Too large for a long; ignored like any other invalid retry.
                        }
                    }
                }
                default -> { }
            }
        }

        private void dispatch() {
            if (!data.isEmpty()) {
                data.setLength(data.length() - 1);
                onEvent.accept(new Event(type.isEmpty() ? "message" : type, data.toString(), id, retry));
            }
            data.setLength(0);
            type = "";
        }
    }

    /**
     * Enforces a streamed call's timeouts. {@code HttpRequest.timeout} only covers the wait for
     * response headers, so a body that stalls would otherwise block forever: the watchdog closes
     * the body stream from a shared timer thread, which unblocks a pending read, and the
     * wrapped stream turns that into a {@link StreamTimeoutException}.
     *
     * <p>One task at a time checks the limits and re-arms itself, at least once a second. It
     * measures time on both {@code System.nanoTime} and the wall clock and goes by whichever has
     * moved further: on macOS {@code nanoTime}, and so the timer's own scheduling, stops while
     * the machine sleeps, so a laptop that slept through a call would otherwise hold it open
     * far past its deadline, on a connection that is usually dead by then. A limit that passed
     * during sleep fires within a second of waking. The idle timeout only counts time a read
     * has been blocked, so a caller that is slow to read never trips it.
     */
    private static final class Watchdog {

        private static final ScheduledThreadPoolExecutor TIMER = timer();
        private static final long NOT_READING = Long.MIN_VALUE;
        private static final long MAX_CHECK_INTERVAL = TimeUnit.SECONDS.toNanos(1);

        private final String method;
        private final String url;
        private final Duration timeout;
        private final Duration idleTimeout;
        private final LongSupplier wallClock;
        private final long startNanos;
        private final long startMillis;
        private volatile ScheduledFuture<?> pending;
        private volatile long readingSince = NOT_READING;
        private volatile long readingSinceMillis;
        private volatile boolean fired;
        private volatile boolean firedIdle;
        private volatile boolean done;
        private volatile InputStream stream;

        Watchdog(Http http) {
            this.method = http.method;
            this.url = http.url;
            this.timeout = http.timeoutSet ? http.timeout : null;
            this.idleTimeout = http.idleTimeout;
            this.wallClock = http.wallClock;
            this.startNanos = System.nanoTime();
            this.startMillis = wallClock.getAsLong();
            if (timeout != null) tick();
        }

        /** The wait for response headers counts against the idle timeout too, when it's shorter. */
        private boolean headerWaitIsIdle() {
            return idleTimeout != null && (timeout == null || idleTimeout.compareTo(timeout) < 0);
        }

        Duration headerTimeout() {
            return headerWaitIsIdle() ? idleTimeout : timeout;
        }

        StreamTimeoutException headerTimeoutException() {
            var idle = headerWaitIsIdle();
            return new StreamTimeoutException(method, url, idle, idle ? idleTimeout : timeout);
        }

        private static ScheduledThreadPoolExecutor timer() {
            var timer = new ScheduledThreadPoolExecutor(1, r -> {
                var t = new Thread(r, "brace-http-stream-timer");
                t.setDaemon(true);
                return t;
            });
            // Most checks are cancelled long before they're due; don't keep them queued.
            timer.setRemoveOnCancelPolicy(true);
            return timer;
        }

        synchronized InputStream attach(InputStream raw) {
            if (fired) {
                closeQuietly(raw);
                throw timeoutException();
            }
            stream = raw;
            // With a deadline the checks are already running.
            if (idleTimeout != null && timeout == null) tick();
            return new WatchedInputStream(raw, this);
        }

        private void tick() {
            if (done || fired) return;
            long nanos = System.nanoTime();
            long millis = wallClock.getAsLong();
            long next = MAX_CHECK_INTERVAL;
            if (timeout != null) {
                long left = timeout.toNanos() - elapsed(startNanos, startMillis, nanos, millis);
                if (left <= 0) {
                    fire(false);
                    return;
                }
                next = Math.min(next, left);
            }
            long since = readingSince; // before readingSinceMillis; see beginRead
            if (idleTimeout != null && since != NOT_READING) {
                long left = idleTimeout.toNanos() - elapsed(since, readingSinceMillis, nanos, millis);
                if (left <= 0) {
                    fire(true);
                    return;
                }
                next = Math.min(next, left);
            }
            pending = TIMER.schedule(this::tick, next, TimeUnit.NANOSECONDS);
        }

        /** Time since a moment taken on both clocks, by whichever clock has moved further. */
        private static long elapsed(long fromNanos, long fromMillis, long nanos, long millis) {
            return Math.max(nanos - fromNanos, TimeUnit.MILLISECONDS.toNanos(millis - fromMillis));
        }

        private synchronized void fire(boolean idle) {
            if (done || fired) return;
            firedIdle = idle;
            fired = true;
            if (stream != null) closeQuietly(stream);
            cancelTimer();
        }

        void beginRead() {
            check();
            // The wall clock first: a tick that sees this read's readingSince then sees this
            // read's readingSinceMillis or a later one, never an earlier read's.
            readingSinceMillis = wallClock.getAsLong();
            readingSince = System.nanoTime();
        }

        void endRead() { readingSince = NOT_READING; }

        void check() {
            if (fired) throw timeoutException();
        }

        synchronized void close() {
            done = true;
            cancelTimer();
        }

        private void cancelTimer() {
            var p = pending;
            if (p != null) p.cancel(false);
        }

        private StreamTimeoutException timeoutException() {
            return new StreamTimeoutException(method, url, firedIdle, firedIdle ? idleTimeout : timeout);
        }
    }

    /** Routes every read through the {@link Watchdog} so a timeout surfaces as itself. */
    private static final class WatchedInputStream extends FilterInputStream {

        private interface Read { long run() throws IOException; }

        private final Watchdog watchdog;

        WatchedInputStream(InputStream in, Watchdog watchdog) {
            super(in);
            this.watchdog = watchdog;
        }

        @Override public int read() throws IOException { return (int) watched(in::read); }
        @Override public int read(byte[] b, int off, int len) throws IOException { return (int) watched(() -> in.read(b, off, len)); }
        @Override public long skip(long n) throws IOException { return watched(() -> in.skip(n)); }

        private long watched(Read read) throws IOException {
            watchdog.beginRead();
            try {
                long result = read.run();
                watchdog.check();
                return result;
            } catch (IOException e) {
                watchdog.check();
                throw e;
            } finally {
                watchdog.endRead();
            }
        }

        @Override
        public void close() throws IOException {
            watchdog.close();
            in.close();
        }
    }
}

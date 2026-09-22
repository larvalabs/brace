package com.larvalabs.brace;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

/**
 * Reads what the client actually asked for when Brace sits behind a reverse proxy — the case
 * {@code docs/SECURITY.md} expects, since Brace serves cleartext HTTP/1.1 and leaves TLS to a
 * proxy.
 *
 * <p>The trap it exists for: nginx's default {@code proxy_pass} replaces {@code Host} with the
 * upstream address ({@code proxy_set_header Host $proxy_host}), so every request arrives saying
 * {@code Host: 127.0.0.1:8080}. Anything that trusts {@code Host} then believes it is serving
 * local development: the session cookie loses {@code Secure}, and same-origin WebSocket upgrades
 * fail the {@code Origin} check. {@link #effectiveHost} recovers the real host where the proxy
 * says so and is trusted; {@link #httpsPageHost} recovers the browser's own view where it doesn't.
 */
final class ProxyHeaders {

    private static final AtomicBoolean warnedHostRewrite = new AtomicBoolean();
    private static final AtomicBoolean warnedWebSocketRewrite = new AtomicBoolean();

    private ProxyHeaders() {}

    /**
     * The host the client addressed: the leftmost {@code X-Forwarded-Host} when the immediate peer
     * is a configured trusted proxy and sent one, otherwise {@code Host}. The same trust gate as
     * {@code X-Forwarded-Proto} and {@link Request#ip()} — a client that connects directly cannot
     * claim a host by sending the header itself.
     */
    static String effectiveHost(UnaryOperator<String> header, String remoteAddr, TrustedProxies trustedProxies) {
        if (trustedProxies != null && remoteAddr != null && trustedProxies.isTrusted(remoteAddr)) {
            String forwarded = header.apply("X-Forwarded-Host");
            if (forwarded != null && !forwarded.isBlank()) {
                int comma = forwarded.indexOf(',');
                return (comma >= 0 ? forwarded.substring(0, comma) : forwarded).strip();
            }
        }
        return header.apply("Host");
    }

    /**
     * The host of the page the browser says it is on, when that page is served over https from a
     * non-loopback host — taken from {@code Origin}, or {@code Referer} when there is no Origin.
     * {@code null} otherwise (no such header, http, loopback, unparseable, or {@code Origin: null}).
     *
     * <p>A request that reaches Brace with a loopback {@code Host} while its browser is on an
     * https page was necessarily relayed by a TLS-terminating proxy that rewrote {@code Host}.
     */
    static String httpsPageHost(String origin, String referer) {
        String source = origin != null && !origin.isBlank() && !"null".equals(origin.strip()) ? origin : referer;
        if (source == null) {
            return null;
        }
        String v = source.strip();
        if (!v.regionMatches(true, 0, "https://", 0, 8)) {
            return null;
        }
        String host = hostOf(v);
        return host == null || BraceHandler.isLoopbackHost(host) ? null : host;
    }

    /** Host of an origin, URL, or bare host string (port and path dropped), or null. */
    static String hostOf(String value) {
        String v = value.strip();
        int scheme = v.indexOf("://");
        if (scheme >= 0) v = v.substring(scheme + 3);
        int end = v.length();
        for (char c : new char[] {'/', '?', '#'}) {
            int i = v.indexOf(c);
            if (i >= 0 && i < end) end = i;
        }
        v = v.substring(0, end);
        int at = v.lastIndexOf('@');
        if (at >= 0) v = v.substring(at + 1);
        if (v.isEmpty()) return null;
        return Request.stripPort(v);
    }

    /** Warn, once per process, that a proxy is rewriting {@code Host} and how to fix it. */
    static void warnHostRewrite(String pageHost) {
        if (warnedHostRewrite.compareAndSet(false, true)) {
            Log.warn("A browser on https://" + pageHost + " reached this app with a loopback Host header, "
                + "so a reverse proxy in front is rewriting Host (nginx does by default). Brace set the "
                + "session cookie's Secure attribute from the browser's Origin/Referer this time, but "
                + "requests without those headers can't be recognized. Fix the proxy: nginx "
                + "`proxy_set_header Host $host;` plus `proxy_set_header X-Forwarded-Proto $scheme;` "
                + "with app.trustedProxies(...) naming the proxy. See docs/SECURITY.md.");
        }
    }

    /** Warn, once per process, that WebSocket upgrades are failing because of a Host-rewriting proxy. */
    static void warnWebSocketHostRewrite(String origin, String host) {
        if (warnedWebSocketRewrite.compareAndSet(false, true)) {
            Log.warn("Rejected a WebSocket upgrade from Origin " + origin + " because the request's Host is "
                + host + ". A loopback Host usually means a reverse proxy is rewriting it (nginx does by "
                + "default), which makes every same-origin browser socket look cross-origin. Fix the proxy: "
                + "nginx `proxy_set_header Host $host;` in the WebSocket location, or send "
                + "X-Forwarded-Host and name the proxy in app.trustedProxies(...). Only if the socket carries "
                + "no session-based authority, app.wsAllowedOrigins(...) is the alternative.");
        }
    }

    /** Reset the one-time warnings. For tests. */
    static void resetWarnings() {
        warnedHostRewrite.set(false);
        warnedWebSocketRewrite.set(false);
    }
}

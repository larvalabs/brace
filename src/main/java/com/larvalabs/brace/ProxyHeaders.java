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
     * The host the client addressed: the rightmost {@code X-Forwarded-Host} entry, across all its
     * lines, when the immediate peer is a configured trusted proxy and sent one, otherwise
     * {@code Host}. The same trust gate as {@code X-Forwarded-Proto} and {@link Request#ip()}: a
     * client that connects directly cannot claim a host by sending the header itself. Rightmost
     * (L3 review F9): behind a proxy that appends to the header, or passes a client's copy
     * through, the leftmost entry is the client's choice. The proxy should set (overwrite) it.
     * {@code header} must read the request's header map, where the lines are joined.
     */
    static String effectiveHost(UnaryOperator<String> header, String remoteAddr, TrustedProxies trustedProxies) {
        if (trustedProxies != null && remoteAddr != null && trustedProxies.isTrusted(remoteAddr)) {
            String forwarded = BraceHandler.rightmostEntry(header.apply("X-Forwarded-Host"));
            // L3 final review: the entries are split on bare commas, so a quoted comma
            // (`app.example, "evil,x"`) leaves a fragment (`x"`) as the rightmost entry. Anything
            // that is not a host[:port] is ignored and Host is used.
            if (forwarded != null && isHostPort(forwarded)) {
                return forwarded;
            }
        }
        return header.apply("Host");
    }

    /**
     * True for a syntactically valid {@code host[:port]}: a name of dot-separated labels (ASCII
     * letters, digits, {@code -}, {@code _}; one trailing dot allowed), an IPv4 literal, or a
     * bracketed IPv6 literal, then an optional decimal port up to 65535. No resolution.
     */
    static boolean isHostPort(String value) {
        if (value == null || value.isEmpty()) return false;
        String host = value;
        if (value.charAt(0) == '[') {
            int close = value.indexOf(']');
            if (close < 0) return false;
            String inner = value.substring(1, close);
            if (inner.indexOf(':') < 0 || TrustedProxies.parseLiteral(inner) == null) return false;
            String rest = value.substring(close + 1);
            return rest.isEmpty() || rest.charAt(0) == ':' && isPort(rest.substring(1));
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            if (!isPort(value.substring(colon + 1))) return false;
            host = value.substring(0, colon);
        }
        if (host.length() > 1 && host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > 253) return false;
        int labelLength = 0;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c == '.') {
                if (labelLength == 0) return false;
                labelLength = 0;
            } else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_') {
                labelLength++;
            } else {
                return false;
            }
        }
        return labelLength > 0;
    }

    private static boolean isPort(String s) {
        if (s.isEmpty() || s.length() > 5) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return Integer.parseInt(s) <= 65535;
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

package com.larvalabs.brace;

import java.time.Duration;

/**
 * Configuration options for session cookies.
 * Provides secure defaults and allows customization.
 */
public class SessionOptions {

    private final String secret;
    private boolean httpOnly = true;
    /** {@code null} = resolve per request (see {@link #resolveSecure}); non-null = explicit override. */
    private Boolean secure = null;
    private String sameSite = "Lax";
    private Duration maxAge = null;
    private String path = "/";
    private String domain = null;

    private SessionOptions(String secret) {
        this.secret = secret;
    }

    /**
     * Create SessionOptions with secure defaults and the given secret.
     * Defaults:
     * - HttpOnly: true
     * - Secure: resolved per request (see {@link #resolveSecure}) — on everywhere except localhost
     * - SameSite: Lax
     * - Path: /
     */
    public static SessionOptions of(String secret) {
        return new SessionOptions(secret);
    }

    /**
     * Resolve the {@code Secure} attribute for one response. An explicit
     * {@link #secure(boolean)} always wins; otherwise it is decided per request.
     *
     * <p><strong>Why per request (2026-07 review, H2).</strong> Brace serves cleartext HTTP/1.1
     * and expects TLS to be terminated by a reverse proxy (see {@code docs/SECURITY.md}), so the
     * app cannot read the scheme off its own connector. The old fixed {@code false} default meant
     * every app built the documented way — {@code .sessions(secret)} — shipped a session cookie
     * with no {@code Secure} attribute, disclosing it to a network attacker on any cleartext
     * request to the domain. A fixed {@code true} default is no better: it silently breaks every
     * app and test suite running on {@code http://localhost}.
     *
     * <p>The two request-scoped signals settle it without configuration:
     * <ul>
     *   <li>{@code X-Forwarded-Proto: https} from a <em>trusted</em> proxy → {@code Secure} on.
     *       This wins outright, so a proxy that rewrites {@code Host} to the upstream (a common
     *       misconfiguration that would otherwise read as localhost) still gets the attribute.</li>
     *   <li>Otherwise, on unless the request's {@code Host} is a loopback name — the same
     *       localhost-is-a-secure-context special case browsers themselves make, and the reason
     *       local development and in-process test suites keep working untouched.</li>
     * </ul>
     *
     * <p>An attacker cannot use this to strip {@code Secure} from a victim's cookie: the
     * {@code Host} header they control is on their own request, and the {@code Set-Cookie} it
     * shapes comes back to them. A real browser never sends {@code Host: localhost} for a real
     * site.
     *
     * @param loopbackHost   the request's Host header names a loopback address
     * @param forwardedHttps a trusted proxy reported the original scheme as https
     */
    boolean resolveSecure(boolean loopbackHost, boolean forwardedHttps) {
        if (secure != null) return secure;
        return forwardedHttps || !loopbackHost;
    }

    /**
     * Create SessionOptions with secure defaults for production use.
     * Sets Secure=true, which should only be used when the app is served over HTTPS.
     */
    public static SessionOptions secure(String secret) {
        var opts = new SessionOptions(secret);
        opts.secure = true;
        return opts;
    }

    public SessionOptions httpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
        return this;
    }

    public SessionOptions secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * Set SameSite attribute: "Strict", "Lax", or "None".
     * Note: "None" requires Secure=true.
     */
    public SessionOptions sameSite(String sameSite) {
        this.sameSite = sameSite;
        return this;
    }

    public SessionOptions sameSiteStrict() {
        this.sameSite = "Strict";
        return this;
    }

    public SessionOptions sameSiteLax() {
        this.sameSite = "Lax";
        return this;
    }

    public SessionOptions sameSiteNone() {
        this.sameSite = "None";
        this.secure = true; // SameSite=None requires Secure
        return this;
    }

    /**
     * Set the maximum age of the session cookie.
     * If not set, the cookie is a session cookie (expires when browser closes).
     */
    public SessionOptions maxAge(Duration maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    public SessionOptions maxAgeDays(int days) {
        this.maxAge = Duration.ofDays(days);
        return this;
    }

    public SessionOptions path(String path) {
        this.path = path;
        return this;
    }

    public SessionOptions domain(String domain) {
        this.domain = domain;
        return this;
    }

    // Getters

    public String secret() {
        return secret;
    }

    public boolean httpOnly() {
        return httpOnly;
    }

    /**
     * The configured {@code Secure} setting. With no explicit {@link #secure(boolean)} the
     * attribute is resolved per request ({@link #resolveSecure}), and this reports {@code true} —
     * the value that holds for every non-loopback request, i.e. every real deployment.
     */
    public boolean secure() {
        return secure == null || secure;
    }

    public String sameSite() {
        return sameSite;
    }

    public Duration maxAge() {
        return maxAge;
    }

    public String path() {
        return path;
    }

    public String domain() {
        return domain;
    }

    /**
     * Build the Set-Cookie header value for the session cookie, using the configured
     * {@link #secure()} setting. The framework uses the request-aware
     * {@link #buildSetCookie(String, boolean)} instead.
     */
    public String buildSetCookie(String cookieValue) {
        return buildSetCookie(cookieValue, secure());
    }

    /**
     * Build the Set-Cookie header value with the {@code Secure} attribute already resolved for
     * this response (see {@link #resolveSecure}).
     */
    String buildSetCookie(String cookieValue, boolean secureResolved) {
        var sb = new StringBuilder("brace_session=");
        sb.append(cookieValue);

        if (path != null) {
            sb.append("; Path=").append(path);
        }

        if (domain != null) {
            sb.append("; Domain=").append(domain);
        }

        if (maxAge != null) {
            sb.append("; Max-Age=").append(maxAge.getSeconds());
        }

        if (httpOnly) {
            sb.append("; HttpOnly");
        }

        if (secureResolved) {
            sb.append("; Secure");
        }

        if (sameSite != null && !sameSite.isEmpty()) {
            sb.append("; SameSite=").append(sameSite);
        }

        return sb.toString();
    }
}

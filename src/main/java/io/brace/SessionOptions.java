package io.brace;

import java.time.Duration;

/**
 * Configuration options for session cookies.
 * Provides secure defaults and allows customization.
 */
public class SessionOptions {

    private final String secret;
    private boolean httpOnly = true;
    private boolean secure = false;
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
     * - Secure: false (set to true for HTTPS)
     * - SameSite: Lax
     * - Path: /
     */
    public static SessionOptions of(String secret) {
        return new SessionOptions(secret);
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

    public boolean secure() {
        return secure;
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
     * Build the Set-Cookie header value for the session cookie.
     */
    public String buildSetCookie(String cookieValue) {
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

        if (secure) {
            sb.append("; Secure");
        }

        if (sameSite != null && !sameSite.isEmpty()) {
            sb.append("; SameSite=").append(sameSite);
        }

        return sb.toString();
    }
}

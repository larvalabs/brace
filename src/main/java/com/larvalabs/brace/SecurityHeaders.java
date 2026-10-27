package com.larvalabs.brace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Security headers middleware for protecting web applications.
 * Provides sensible defaults for common security headers.
 */
public class SecurityHeaders implements Middleware.After {

    private final Map<String, String> headers;

    private SecurityHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    /**
     * Create security headers middleware with recommended defaults:
     * - X-Content-Type-Options: nosniff
     * - Referrer-Policy: strict-origin-when-cross-origin
     * - X-Frame-Options: DENY
     * - Permissions-Policy: interest-cohort=()
     *
     * <p>No Content-Security-Policy: adding one here would silently break apps with inline
     * scripts. Use {@link #strict()} for these defaults plus a strict CSP.
     */
    public static Middleware.After defaults() {
        return new SecurityHeaders(defaultHeaders());
    }

    /** The Content-Security-Policy {@link #strict()} sets. */
    public static final String STRICT_CSP = "default-src 'self'; script-src 'self'; object-src 'none'; "
        + "base-uri 'none'; frame-ancestors 'none'; form-action 'self'";

    /**
     * The {@link #defaults()} plus a strict Content-Security-Policy ({@link #STRICT_CSP}).
     *
     * <p>Scripts load only from the app's own origin: no inline {@code <script>}, no
     * {@code on*=} handlers, no {@code javascript:} URLs and no {@code eval}, so an HTML injection
     * that gets past escaping can't run code. Brace's own {@code /__brace/htmx.min.js} and
     * {@code /__brace/csrf-htmx.js} are same-origin and load fine. Styles fall back to
     * {@code default-src 'self'}, so inline {@code <style>} blocks and {@code style=""}
     * attributes are blocked too: move them into a stylesheet. htmx inserts a small
     * {@code <style>} for its request indicators unless the page sets
     * {@code <meta name="htmx-config" content='{"includeIndicatorStyles":false}'>}; blocked, it
     * only logs a console violation. The framework's {@code /ops} pages carry their own,
     * stricter policy, which replaces this one on those responses.
     */
    public static Middleware.After strict() {
        var headers = defaultHeaders();
        headers.put("Content-Security-Policy", STRICT_CSP);
        return new SecurityHeaders(headers);
    }

    private static LinkedHashMap<String, String> defaultHeaders() {
        var headers = new LinkedHashMap<String, String>();
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Referrer-Policy", "strict-origin-when-cross-origin");
        headers.put("X-Frame-Options", "DENY");
        headers.put("Permissions-Policy", "interest-cohort=()");
        return headers;
    }

    /**
     * Create a builder for custom security headers configuration.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Apply security headers to the result.
     */
    @Override
    public Result handle(Request req, Result result) {
        for (var entry : headers.entrySet()) {
            result.header(entry.getKey(), entry.getValue());
        }
        return result;
    }

    /**
     * Fluent builder for custom security headers.
     */
    public static class Builder {
        private final Map<String, String> headers = new LinkedHashMap<>();

        /**
         * Prevent MIME type sniffing.
         * Recommended: nosniff
         */
        public Builder contentTypeOptions(String value) {
            headers.put("X-Content-Type-Options", value);
            return this;
        }

        /**
         * Control referrer information sent with requests.
         * Recommended: strict-origin-when-cross-origin
         */
        public Builder referrerPolicy(String value) {
            headers.put("Referrer-Policy", value);
            return this;
        }

        /**
         * Prevent clickjacking by controlling iframe embedding.
         * Options: DENY, SAMEORIGIN, ALLOW-FROM uri
         */
        public Builder frameOptions(String value) {
            headers.put("X-Frame-Options", value);
            return this;
        }

        /**
         * Control which browser features and APIs can be used.
         * Example: "interest-cohort=()" to disable FLoC
         */
        public Builder permissionsPolicy(String value) {
            headers.put("Permissions-Policy", value);
            return this;
        }

        /**
         * Enforce HTTPS connections (only set when serving over HTTPS).
         * Example: "max-age=31536000; includeSubDomains"
         */
        public Builder strictTransportSecurity(String value) {
            headers.put("Strict-Transport-Security", value);
            return this;
        }

        /**
         * Set Content Security Policy.
         * Example: {@link SecurityHeaders#STRICT_CSP}. Avoid {@code 'unsafe-inline'} in
         * {@code script-src}: it lets any injected inline script run, which defeats the policy.
         */
        public Builder contentSecurityPolicy(String value) {
            headers.put("Content-Security-Policy", value);
            return this;
        }

        /**
         * Add a custom header.
         */
        public Builder header(String name, String value) {
            headers.put(name, value);
            return this;
        }

        /**
         * Build the SecurityHeaders middleware.
         */
        public Middleware.After build() {
            return new SecurityHeaders(new LinkedHashMap<>(headers));
        }
    }
}

package io.brace;

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
     */
    public static Middleware.After defaults() {
        var headers = new LinkedHashMap<String, String>();
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Referrer-Policy", "strict-origin-when-cross-origin");
        headers.put("X-Frame-Options", "DENY");
        headers.put("Permissions-Policy", "interest-cohort=()");
        return new SecurityHeaders(headers);
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
         * Example: "default-src 'self'; script-src 'self' 'unsafe-inline'"
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

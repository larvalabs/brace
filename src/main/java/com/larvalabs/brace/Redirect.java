package com.larvalabs.brace;

/**
 * HTTP redirect responses.
 *
 * <p><strong>Security note:</strong> Use {@link #toLocal(String)} for URLs derived from user input
 * (e.g., from a query parameter). {@link #to(String)} accepts absolute URLs and protocol-relative
 * URLs ({@code //}), which can be used for open-redirect attacks. For example:
 *
 * <pre>{@code
 * // Dangerous — user can redirect to a phishing site:
 * return Redirect.to(req.queryParam("next"));   // next=https://attacker.com
 *
 * // Safe — only accepts paths that start with exactly one "/":
 * return Redirect.toLocal(req.queryParam("next"));  // throws if not a local path
 * }</pre>
 */
public class Redirect extends Result {

    private Redirect(int status, String location) {
        super(status, "text/plain", "");
        header("Location", location);
    }

    /**
     * Redirect to the specified location (302 temporary).
     *
     * <p><strong>Security warning:</strong> Do not use this with untrusted input (e.g.,
     * {@code req.queryParam("next")}). This method accepts absolute URLs and protocol-relative
     * URLs, which enable open-redirect attacks. Use {@link #toLocal(String)} instead for
     * user-derived paths.
     *
     * @param location The redirect URL. Can be absolute, relative, or protocol-relative.
     * @return A redirect response with 302 status.
     */
    public static Redirect to(String location) {
        return new Redirect(302, location);
    }

    /**
     * Redirect to a local path only (302 temporary).
     *
     * <p>This method is safe for use with untrusted input (e.g., {@code req.queryParam("next")}).
     * It validates that the path starts with exactly one {@code "/"} — rejecting null/empty,
     * absolute URLs ({@code https:/evil.com}, {@code https://evil.com}), protocol-relative
     * URLs ({@code //evil.com}), and backslash-based bypasses ({@code /\evil.com}).
     * Literal ASCII control characters anywhere in the path are also rejected.
     * Percent-encoded characters (e.g. {@code /%09/x}) are not decoded and are accepted.
     *
     * @param path The local path to redirect to. Must start with exactly one {@code "/"}.
     * @return A redirect response with 302 status.
     * @throws IllegalArgumentException If the path fails the local-path validation rules.
     */
    public static Redirect toLocal(String path) {
        requireLocal(path);
        return new Redirect(302, path);
    }

    /**
     * Permanently redirect to the specified location (301 permanent).
     *
     * <p><strong>Security warning:</strong> Do not use this with untrusted input. Use
     * {@link #permanentLocal(String)} for user-derived paths.
     *
     * @param location The redirect URL. Can be absolute, relative, or protocol-relative.
     * @return A redirect response with 301 status.
     */
    public static Redirect permanent(String location) {
        return new Redirect(301, location);
    }

    /**
     * Permanently redirect to a local path only (301 permanent).
     *
     * <p>This method is safe for use with untrusted input. It applies the same allowlist
     * validation as {@link #toLocal(String)}: the path must start with exactly one {@code "/"},
     * must not contain a backslash or literal ASCII control characters.
     *
     * @param path The local path to redirect to. Must start with exactly one {@code "/"}.
     * @return A redirect response with 301 status.
     * @throws IllegalArgumentException If the path fails the local-path validation rules.
     */
    public static Redirect permanentLocal(String path) {
        requireLocal(path);
        return new Redirect(301, path);
    }

    /**
     * Shared allowlist validator for {@link #toLocal} and {@link #permanentLocal}.
     *
     * <p>Rules (all must hold):
     * <ol>
     *   <li>Path is non-null and non-empty.</li>
     *   <li>First character is {@code '/'}.</li>
     *   <li>Second character (if present) is neither {@code '/'} nor {@code '\\'} — this
     *       rejects protocol-relative ({@code //}) and backslash-normalised ({@code /\}) forms.</li>
     *   <li>No backslash ({@code '\\'}) anywhere in the string — browsers normalize
     *       {@code /\evil.com} to {@code //evil.com}.</li>
     *   <li>No literal ASCII control characters (code points {@code < 0x20}) anywhere — these
     *       can confuse parsers. Percent-encoded sequences (e.g. {@code %09}) are not decoded
     *       and are allowed.</li>
     * </ol>
     *
     * @throws IllegalArgumentException if any rule is violated.
     */
    private static void requireLocal(String path) {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("Redirect path must be non-null and non-empty");
        }
        if (path.charAt(0) != '/') {
            throw new IllegalArgumentException(
                "Redirect path must start with '/'; got: " + path);
        }
        if (path.length() > 1) {
            char second = path.charAt(1);
            if (second == '/' || second == '\\') {
                throw new IllegalArgumentException(
                    "Redirect path must not start with '//' or '/\\'; got: " + path);
            }
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\') {
                throw new IllegalArgumentException(
                    "Redirect path must not contain backslashes; got: " + path);
            }
            if (c < 0x20) {
                throw new IllegalArgumentException(
                    "Redirect path must not contain ASCII control characters; got: " + path);
            }
        }
    }
}

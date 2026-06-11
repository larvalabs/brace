package com.larvalabs.brace;

/**
 * HTTP redirect responses.
 *
 * <p><strong>Security note:</strong> Use {@link #toLocal(String)} for URLs derived from user input
 * (e.g., from a query parameter). {@link #to(String)} accepts absolute URLs and protocol-relative
 * URLs (`//`), which can be used for open-redirect attacks. For example:
 *
 * <pre>{@code
 * // Dangerous—user can redirect to a phishing site:
 * return Redirect.to(req.queryParam("next"));   // next=https://attacker.com
 *
 * // Safe—rejects absolute and protocol-relative URLs:
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
     * <p>This method validates that the path is local (no absolute URLs or protocol-relative
     * URLs), making it safe for use with untrusted input such as query parameters.
     *
     * @param path The local path to redirect to (e.g., "/home", "login", "/path/to/page").
     * @return A redirect response with 302 status.
     * @throws IllegalArgumentException If the path is an absolute URL (contains "://") or
     *                                  protocol-relative ("//").
     */
    public static Redirect toLocal(String path) {
        if (path.contains("://") || path.startsWith("//")) {
            throw new IllegalArgumentException("Redirect path must be local; absolute URLs and protocol-relative URLs are not allowed: " + path);
        }
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
     * <p>This method validates that the path is local (no absolute URLs or protocol-relative
     * URLs), making it safe for use with untrusted input.
     *
     * @param path The local path to redirect to.
     * @return A redirect response with 301 status.
     * @throws IllegalArgumentException If the path is an absolute URL or protocol-relative.
     */
    public static Redirect permanentLocal(String path) {
        if (path.contains("://") || path.startsWith("//")) {
            throw new IllegalArgumentException("Redirect path must be local; absolute URLs and protocol-relative URLs are not allowed: " + path);
        }
        return new Redirect(301, path);
    }
}

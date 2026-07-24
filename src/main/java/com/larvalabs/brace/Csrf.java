package com.larvalabs.brace;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class Csrf {

    static final String TOKEN_KEY = "_csrf";

    // Shared: thread-safe, and per-token construction paid a JCA provider lookup per mint.
    private static final SecureRandom RANDOM = new SecureRandom();

    public static String generateToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static void ensureToken(Session session) {
        if (!session.has(TOKEN_KEY)) {
            session.set(TOKEN_KEY, generateToken());
        }
    }

    public static String getToken(Session session) {
        return session.get(TOKEN_KEY);
    }

    public static boolean validateToken(Session session, String submittedToken) {
        var expected = session.get(TOKEN_KEY);
        if (expected == null || submittedToken == null) return false;
        // Explicit UTF-8 (L4): getBytes() with no charset is platform-dependent. Tokens are
        // base64url so the bytes match under every realistic default today, but a
        // charset-dependent comparison inside a CSRF check is a latent correctness bug.
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                                     submittedToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The hidden form field carrying the CSRF token.
     *
     * <p>The value is HTML-escaped (L6). The token itself is framework-generated base64url, so
     * this is not currently exploitable — but the field is injected into templates as raw HTML,
     * and {@code _csrf} is not a reserved session key, so application code can put an arbitrary
     * value there. Escaping costs nothing and removes the question.
     */
    public static String hiddenField(Session session) {
        var token = getToken(session);
        return "<input type=\"hidden\" name=\"_csrf\" value=\"" + escape(token) + "\">";
    }

    /** Minimal HTML-attribute escaping for the token value. */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}

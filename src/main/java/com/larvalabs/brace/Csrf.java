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

    /**
     * The session's token in masked form, different on every call: {@code base64url(mask ||
     * token XOR mask)} with a fresh random mask. {@link #validateToken} accepts it in place of
     * the raw token. Null when the session has no token yet.
     *
     * <p>Use this rather than {@link #getToken} wherever the token is written into a page (a
     * {@code <meta>} tag for fetch or htmx headers). Brace gzips responses by default, and a
     * secret that is byte-identical on every page can be recovered from compressed response
     * sizes when the same page also reflects attacker-chosen text (BREACH). A masked token
     * never repeats, so there is nothing for compression to match against.
     */
    public static String maskedToken(Session session) {
        var token = getToken(session);
        if (token == null) return null;
        byte[] raw = token.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[raw.length * 2];
        byte[] mask = new byte[raw.length];
        RANDOM.nextBytes(mask);
        for (int i = 0; i < raw.length; i++) {
            out[i] = mask[i];
            out[raw.length + i] = (byte) (raw[i] ^ mask[i]);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    }

    /** Accepts the session's token either as is or masked by {@link #maskedToken}. */
    public static boolean validateToken(Session session, String submittedToken) {
        var expected = session.get(TOKEN_KEY);
        if (expected == null || submittedToken == null) return false;
        // Explicit UTF-8 (L4): getBytes() with no charset is platform-dependent. Tokens are
        // base64url so the bytes match under every realistic default today, but a
        // charset-dependent comparison inside a CSRF check is a latent correctness bug.
        byte[] want = expected.getBytes(StandardCharsets.UTF_8);
        if (MessageDigest.isEqual(want, submittedToken.getBytes(StandardCharsets.UTF_8))) return true;
        byte[] masked;
        try {
            masked = Base64.getUrlDecoder().decode(submittedToken);
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
        if (masked.length != want.length * 2) return false;
        byte[] unmasked = new byte[want.length];
        for (int i = 0; i < want.length; i++) {
            unmasked[i] = (byte) (masked[want.length + i] ^ masked[i]);
        }
        return MessageDigest.isEqual(want, unmasked);
    }

    /**
     * The hidden form field carrying the CSRF token, {@linkplain #maskedToken masked} so the
     * value differs on every render.
     *
     * <p>The value is HTML-escaped (L6). The token itself is framework-generated base64url, so
     * this is not currently exploitable — but the field is injected into templates as raw HTML,
     * and {@code _csrf} is not a reserved session key, so application code can put an arbitrary
     * value there. Escaping costs nothing and removes the question.
     */
    public static String hiddenField(Session session) {
        return hiddenFieldFor(maskedToken(session));
    }

    /** The hidden field for an already-masked token (View keeps the value to recognise it, M1). */
    static String hiddenFieldFor(String maskedToken) {
        return "<input type=\"hidden\" name=\"_csrf\" value=\"" + escape(maskedToken) + "\">";
    }

    /** The {@code <meta name="csrf-token">} tag for an already-masked token (M2's ${csrfMeta}). */
    static String metaTagFor(String maskedToken) {
        return "<meta name=\"csrf-token\" content=\"" + escape(maskedToken) + "\">";
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

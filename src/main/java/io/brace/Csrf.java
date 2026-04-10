package io.brace;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class Csrf {

    static final String TOKEN_KEY = "_csrf";

    public static String generateToken() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
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
        return MessageDigest.isEqual(expected.getBytes(), submittedToken.getBytes());
    }

    public static String hiddenField(Session session) {
        return "<input type=\"hidden\" name=\"_csrf\" value=\"" + getToken(session) + "\">";
    }
}

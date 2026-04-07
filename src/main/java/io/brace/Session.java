package io.brace;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public class Session {

    private final Map<String, String> data = new LinkedHashMap<>();
    private final Map<String, String> flashData = new LinkedHashMap<>();
    private boolean modified = false;

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String get(String key) {
        return data.get(key);
    }

    public int getInt(String key) {
        return Integer.parseInt(data.get(key));
    }

    public long getLong(String key) {
        return Long.parseLong(data.get(key));
    }

    public boolean has(String key) {
        return data.containsKey(key);
    }

    public void set(String key, String value) {
        data.put(key, value);
        modified = true;
    }

    public void set(String key, int value) {
        set(key, String.valueOf(value));
    }

    public void set(String key, long value) {
        set(key, String.valueOf(value));
    }

    public void remove(String key) {
        data.remove(key);
        modified = true;
    }

    public void clear() {
        data.clear();
        modified = true;
    }

    public boolean isModified() {
        return modified;
    }

    // -------------------------------------------------------------------------
    // Flash messages
    // -------------------------------------------------------------------------

    public void flash(String key, String value) {
        set("_flash:" + key, value);
    }

    public String flash(String key) {
        return flashData.get(key);
    }

    void consumeFlash() {
        var iterator = data.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().startsWith("_flash:")) {
                flashData.put(entry.getKey().substring(7), entry.getValue());
                iterator.remove();
                modified = true;
            }
        }
    }

    public Map<String, String> flashData() {
        return flashData;
    }

    // -------------------------------------------------------------------------
    // Cookie serialization
    // -------------------------------------------------------------------------

    /**
     * Serialize to: base64url(json) + "." + base64url(hmac)
     */
    public String toCookie(String secret) {
        String json = toJson(data);
        String payload = base64Encode(json.getBytes(StandardCharsets.UTF_8));
        String sig = sign(payload, secret);
        return payload + "." + sig;
    }

    /**
     * Deserialize and verify signature. Returns an empty Session if the cookie
     * is null, malformed, or the signature does not match.
     */
    public static Session fromCookie(String cookie, String secret) {
        if (cookie == null || cookie.isEmpty()) {
            return new Session();
        }
        int dot = cookie.lastIndexOf('.');
        if (dot < 0) {
            return new Session();
        }
        String payload = cookie.substring(0, dot);
        String providedSig = cookie.substring(dot + 1);

        // Constant-time comparison
        String expectedSig = sign(payload, secret);
        if (!constantTimeEquals(expectedSig, providedSig)) {
            return new Session();
        }

        try {
            byte[] jsonBytes = Base64.getUrlDecoder().decode(payload);
            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            Session session = new Session();
            parseJson(json, session.data);
            return session;
        } catch (Exception e) {
            return new Session();
        }
    }

    // -------------------------------------------------------------------------
    // Factory
    // -------------------------------------------------------------------------

    /**
     * Convenience factory: Session.of("key1", value1, "key2", value2, ...)
     * Values are converted via String.valueOf().
     */
    public static Session of(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected even number of arguments (key-value pairs)");
        }
        Session session = new Session();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            session.set((String) keysAndValues[i], String.valueOf(keysAndValues[i + 1]));
        }
        return session;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Minimal JSON serializer for Map<String,String>. */
    private static String toJson(Map<String, String> map) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(jsonEscape(entry.getKey())).append("\"");
            sb.append(":");
            sb.append("\"").append(jsonEscape(entry.getValue())).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** Minimal JSON parser for Map<String,String> — handles the output of toJson(). */
    private static void parseJson(String json, Map<String, String> out) {
        // Strip leading/trailing whitespace and outer braces
        String s = json.strip();
        if (s.equals("{}") || s.isEmpty()) return;
        // Remove surrounding { }
        s = s.substring(1, s.length() - 1).strip();
        if (s.isEmpty()) return;

        int i = 0;
        while (i < s.length()) {
            // Skip whitespace and commas
            while (i < s.length() && (s.charAt(i) == ',' || Character.isWhitespace(s.charAt(i)))) i++;
            if (i >= s.length()) break;

            // Read key
            if (s.charAt(i) != '"') break;
            var keyResult = readJsonString(s, i + 1);
            String key = keyResult[0];
            i = Integer.parseInt(keyResult[1]);

            // Skip colon
            while (i < s.length() && (s.charAt(i) == ':' || Character.isWhitespace(s.charAt(i)))) i++;

            // Read value
            if (i >= s.length() || s.charAt(i) != '"') break;
            var valResult = readJsonString(s, i + 1);
            String value = valResult[0];
            i = Integer.parseInt(valResult[1]);

            out.put(key, value);
        }
    }

    /**
     * Reads a JSON string starting after the opening quote.
     * Returns [parsed-string, next-index-after-closing-quote].
     */
    private static String[] readJsonString(String s, int start) {
        var sb = new StringBuilder();
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') {
                return new String[]{sb.toString(), String.valueOf(i + 1)};
            } else if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    default -> sb.append(next);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return new String[]{sb.toString(), String.valueOf(i)};
    }

    private static String base64Encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return base64Encode(hmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 signing failed", e);
        }
    }

    /** Constant-time string comparison to prevent timing attacks. */
    private static boolean constantTimeEquals(String a, String b) {
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }
}

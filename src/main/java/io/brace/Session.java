package io.brace;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session data stored in an encrypted and authenticated cookie.
 *
 * <p><strong>Security:</strong> Session cookies are encrypted using <strong>AES-256-GCM</strong>.
 * This provides:
 * <ul>
 *   <li>✅ Confidentiality: Data cannot be read by the client</li>
 *   <li>✅ Integrity: Data cannot be tampered with</li>
 *   <li>✅ Authenticity: Only the server can create valid sessions</li>
 * </ul>
 *
 * <p><strong>What you can safely store:</strong>
 * <ul>
 *   <li>✅ User ID</li>
 *   <li>✅ Email addresses</li>
 *   <li>✅ Permissions, roles, scopes</li>
 *   <li>✅ UI preferences (theme, language)</li>
 *   <li>✅ CSRF tokens</li>
 *   <li>✅ Flash messages</li>
 *   <li>✅ Shopping cart contents (small amounts)</li>
 * </ul>
 *
 * <p><strong>Size considerations:</strong>
 * Cookies have a 4KB size limit. For large session data, consider server-side storage.
 *
 * <p><strong>Encryption details:</strong>
 * Uses AES-256-GCM with a random 12-byte nonce per cookie. The encryption key is derived
 * from the session secret using PBKDF2-HMAC-SHA256.
 */
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
    // Cookie serialization (AES-256-GCM encryption)
    // -------------------------------------------------------------------------

    /**
     * Serialize to encrypted cookie: base64url(nonce || ciphertext || auth_tag)
     */
    public String toCookie(String secret) {
        try {
            String json = toJson(data);
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

            // Derive AES-256 key from secret
            SecretKeySpec key = deriveKey(secret);

            // Generate random 12-byte nonce for GCM
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);

            // Encrypt with AES-256-GCM (includes authentication tag)
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);
            byte[] ciphertext = cipher.doFinal(jsonBytes);

            // Combine: nonce + ciphertext (ciphertext includes 16-byte auth tag)
            byte[] combined = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(ciphertext, 0, combined, nonce.length, ciphertext.length);

            return base64Encode(combined);
        } catch (Exception e) {
            throw new RuntimeException("Session encryption failed", e);
        }
    }

    /**
     * Deserialize and decrypt cookie. Returns an empty Session if the cookie
     * is null, malformed, or fails authentication/decryption.
     */
    public static Session fromCookie(String cookie, String secret) {
        if (cookie == null || cookie.isEmpty()) {
            return new Session();
        }

        try {
            byte[] combined = Base64.getUrlDecoder().decode(cookie);

            // Need at least 12 bytes (nonce) + 16 bytes (auth tag)
            if (combined.length < 28) {
                return new Session();
            }

            // Extract nonce and ciphertext
            byte[] nonce = new byte[12];
            byte[] ciphertext = new byte[combined.length - 12];
            System.arraycopy(combined, 0, nonce, 0, 12);
            System.arraycopy(combined, 12, ciphertext, 0, ciphertext.length);

            // Derive key and decrypt
            SecretKeySpec key = deriveKey(secret);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(128, nonce);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            byte[] jsonBytes = cipher.doFinal(ciphertext);

            // Parse JSON
            String json = new String(jsonBytes, StandardCharsets.UTF_8);
            Session session = new Session();
            parseJson(json, session.data);
            return session;
        } catch (Exception e) {
            // Any decryption or authentication failure returns empty session
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

    /**
     * Derive a 256-bit AES key from the session secret using PBKDF2-HMAC-SHA256.
     * Uses a fixed salt "brace-session" since the secret itself should be random.
     */
    private static SecretKeySpec deriveKey(String secret) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(
                secret.toCharArray(),
                "brace-session".getBytes(StandardCharsets.UTF_8),
                100000, // 100k iterations
                256     // 256-bit key
            );
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            throw new RuntimeException("Key derivation failed", e);
        }
    }
}

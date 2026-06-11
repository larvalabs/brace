package com.larvalabs.brace;

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
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Reserved key holding the server-enforced absolute expiry (epoch seconds) inside the
     * encrypted payload. Written by {@link #toCookie(String)} and checked by
     * {@link #fromCookie(String, String)}; it is stripped from the user-visible data on read
     * and cannot be set through the public {@link #set} API (server-managed only).
     */
    static final String EXPIRY_KEY = "_exp";

    /**
     * Default expiry horizon when no positive {@link SessionOptions#maxAge()} is configured.
     * Bounds replay of a stolen cookie even for "session-lifetime" cookies, whose Max-Age is
     * only a client hint. 14 days.
     */
    static final long DEFAULT_MAX_AGE_SECONDS = 14L * 24 * 60 * 60;

    /**
     * Expiry horizon in seconds used when minting a cookie. 0 means "use the default".
     * Set at construction (or via {@link #maxAgeSeconds(long)}) so {@link #toCookie(String)}
     * can stamp {@code _exp} without changing its public signature.
     */
    private long maxAgeSeconds = 0;

    /**
     * Cache for derived PBKDF2 keys. Since the session secret is fixed for process
     * lifetime, we memoize the derived SecretKeySpec to avoid re-running 100,000 PBKDF2
     * iterations on every session cookie read/write. The PBKDF2 derivation is deterministic
     * (fixed salt, iterations, key length), so caching is safe: same secret always produces
     * the same key. Bounded to ~16 entries to prevent unbounded growth in test suites.
     */
    private static final ConcurrentHashMap<String, SecretKeySpec> keyCache = new ConcurrentHashMap<>();
    static final int MAX_KEY_CACHE_SIZE = 16;

    // Shared: SecureRandom is thread-safe and instantiation does a JCA provider lookup —
    // per-cookie-write construction was measurable garbage on every session response.
    private static final SecureRandom RANDOM = new SecureRandom();

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
        // _exp is server-managed (written at serialize time, enforced at read time). Silently
        // reserve it so a handler can't forge or extend its own expiry via session.set("_exp", ...).
        if (EXPIRY_KEY.equals(key)) {
            return;
        }
        data.put(key, value);
        modified = true;
    }

    /**
     * Set the expiry horizon (seconds) stamped into the encrypted payload by {@link #toCookie}.
     * A value &le; 0 means "use the {@link #DEFAULT_MAX_AGE_SECONDS default}". Package-visible:
     * BraceHandler plumbs the app's {@code SessionOptions.maxAge()} through here. Does not mark
     * the session modified — it only affects serialization, not user data.
     */
    void maxAgeSeconds(long seconds) {
        this.maxAgeSeconds = seconds;
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
            // Stamp a server-enforced absolute expiry into the payload. Max-Age on the cookie is
            // only a client hint; _exp is what bounds replay of a stolen cookie (M2). The horizon
            // is the configured SessionOptions.maxAge (when positive), else a 14-day default.
            long horizon = maxAgeSeconds > 0 ? maxAgeSeconds : DEFAULT_MAX_AGE_SECONDS;
            var payload = new LinkedHashMap<>(data);
            payload.put(EXPIRY_KEY, String.valueOf(System.currentTimeMillis() / 1000 + horizon));

            String json = toJson(payload);
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

            // Derive AES-256 key from secret
            SecretKeySpec key = deriveKey(secret);

            // Generate random 12-byte nonce for GCM
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);

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

            // Server-side expiry enforcement (M2). _exp is an absolute epoch-seconds deadline.
            // - Present and in the past  → reject: return an empty session (stolen cookie expires
            //   regardless of the client-side Max-Age hint).
            // - Present and in the future → strip _exp so it is never visible via get/has/keys, then
            //   keep the data.
            // - Absent (legacy ≤0.1.6 cookie) → accepted for this release; re-minted with _exp on the
            //   next write. A future release will reject expiry-less cookies (see migration guide).
            String exp = session.data.remove(EXPIRY_KEY);
            if (exp != null) {
                try {
                    if (Long.parseLong(exp.strip()) <= System.currentTimeMillis() / 1000) {
                        return new Session();
                    }
                } catch (NumberFormatException e) {
                    // Malformed _exp — treat as expired/invalid rather than indefinitely valid.
                    return new Session();
                }
            }
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
     * Results are cached to avoid re-running 100,000 iterations on every request.
     *
     * <p>The ConcurrentHashMap contract forbids mutating the map inside a
     * {@code computeIfAbsent} mapping function (silent entry loss / size-counter corruption /
     * bin-lock stalls while PBKDF2 runs). The bound check is therefore performed
     * <em>outside</em> the lambda, before the {@code computeIfAbsent} call. The small race
     * where two threads both observe size &ge; limit and both call {@code clear()} is
     * benign — the same key is simply re-derived once.
     */
    private static SecretKeySpec deriveKey(String secret) {
        // Bound the cache BEFORE entering computeIfAbsent to avoid mutating the map
        // from inside its own mapping function (CHM contract violation).
        if (keyCache.size() >= MAX_KEY_CACHE_SIZE) {
            keyCache.clear();
        }
        return keyCache.computeIfAbsent(secret, Session::computeDerivedKey);
    }

    /**
     * Compute the PBKDF2-derived AES key (without caching).
     */
    private static SecretKeySpec computeDerivedKey(String secret) {
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

    /**
     * For testing: return the cached key spec (same object identity) when called twice.
     * Package-visible for test use only.
     */
    static SecretKeySpec getCachedKey(String secret) {
        return deriveKey(secret);
    }

    /**
     * For testing: clear the key cache. Package-visible for test use only.
     */
    static void clearKeyCache() {
        keyCache.clear();
    }
}

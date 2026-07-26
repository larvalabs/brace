package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Field-name-based and value-shaped redaction for log entries and ops diagnostics.
 * Replaces the <em>values</em> of fields whose name looks sensitive (authorization,
 * cookie, token, secret, password, api key, …) with {@code [REDACTED]} before they
 * reach stdout, the log ring buffer, or the {@code /ops/*} endpoints.
 *
 * <p>This is an allowlist-of-shape — it matches field <em>names</em>, not value
 * content; it is not PII detection. It deliberately over-redacts (a field named
 * {@code token_count} is redacted) rather than risk leaking a credential. Matching
 * is on the normalized name (lowercased, {@code -}/{@code _} stripped) containing a
 * sensitive token as a substring.
 *
 * <p><strong>Value-shaped redaction</strong> ({@link #redactPath} and
 * {@link #redactMessage}) targets high-entropy tokens by their shape rather than
 * their field name — covering URL path segments that carry reset/invite/API tokens
 * and exception messages that may contain credentials or SQL literals.
 *
 * <h3>Heuristic (path segments and message tokens)</h3>
 * A segment is redacted when <em>all</em> of these hold:
 * <ol>
 *   <li>Length ≥ {@value #MIN_SECRET_LENGTH} characters.</li>
 *   <li>Every character is in the base64url-or-hex alphabet
 *       ({@code [A-Za-z0-9_\-+/=]}).</li>
 *   <li>Contains at least one ASCII digit <em>and</em> at least one ASCII letter
 *       (rules out purely numeric IDs and purely alphabetic slugs).</li>
 * </ol>
 * Exceptions kept visible (conservative):
 * <ul>
 *   <li>Standard UUIDs (8-4-4-4-12 hex with hyphens) — usually record identifiers
 *       needed for debugging, not bearer secrets. {@code redactPath} leaves them
 *       as-is; callers that need UUID redaction can do so explicitly.</li>
 *   <li>Purely numeric segments (page IDs, user IDs, order numbers).</li>
 *   <li>Short slugs and ordinary words (length &lt; {@value #MIN_SECRET_LENGTH}).</li>
 *   <li>Segments with characters outside the base64url-or-hex set (e.g. dots in
 *       file names, spaces in messages).</li>
 * </ul>
 * JWT-shaped tokens (two dots dividing three base64url parts where the outer parts
 * are long enough) are caught as a special case before the per-segment heuristic.
 */
public class Redactor {

    public static final String PLACEHOLDER = "[REDACTED]";

    /** Normalized substrings that mark a field name as sensitive. */
    private static final Set<String> SENSITIVE = Set.of(
        "authorization", "cookie", "password", "passwd", "pwd", "secret",
        "token", "apikey", "bearer", "credential", "privatekey", "accesskey",
        "sessionid", "csrf");

    // ---- Value-shaped redaction constants ----

    /**
     * Minimum segment length to trigger value-shaped redaction. Segments shorter
     * than this are always kept visible (slugs, short words, small numeric IDs).
     * Tunable: raise to be less aggressive, lower to catch shorter secrets.
     */
    static final int MIN_SECRET_LENGTH = 16;

    /**
     * Characters allowed in base64url / hex / base64 tokens. Segments containing
     * only these characters are candidates for entropy-based redaction.
     */
    private static final Pattern SECRET_CHARS = Pattern.compile("[A-Za-z0-9_\\-+/=]+");

    /**
     * Standard UUID shape (8-4-4-4-12 hex digits with hyphens). UUIDs are usually
     * record identifiers — kept visible for debugging, not redacted.
     */
    private static final Pattern UUID_SHAPE =
        Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * JWT shape: three base64url segments separated by exactly two dots, where the
     * first and last segments are at least 10 characters each.
     */
    private static final Pattern JWT_SHAPE =
        Pattern.compile("[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]+\\.[A-Za-z0-9_\\-]{10,}");

    /**
     * Delimiters used to split exception messages into candidate tokens for
     * value-shaped redaction: whitespace, quotes, commas, colons, semicolons,
     * equals, and parentheses.
     */
    private static final Pattern MESSAGE_DELIMITERS = Pattern.compile("[\\s\"'`,;:=()\\[\\]{}]+");

    private Redactor() {}

    /** True if a field with this name should have its value redacted. */
    public static boolean isSensitive(String name) {
        if (name == null) return false;
        String norm = name.toLowerCase().replace("-", "").replace("_", "");
        for (String token : SENSITIVE) {
            if (norm.contains(token)) return true;
        }
        return false;
    }

    /**
     * Return a deep copy of {@code fields} with the values of sensitive-named keys
     * replaced by {@link #PLACEHOLDER}. Recurses into nested maps and lists.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> redact(Map<String, Object> fields) {
        var out = new LinkedHashMap<String, Object>(fields.size());
        for (var e : fields.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (isSensitive(key)) {
                out.put(key, PLACEHOLDER);
            } else if (value instanceof Map<?, ?> m) {
                out.put(key, redact((Map<String, Object>) m));
            } else if (value instanceof List<?> list) {
                out.put(key, redactList(list));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> redactList(List<?> list) {
        var out = new ArrayList<Object>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(redact((Map<String, Object>) m));
            } else if (item instanceof List<?> l) {
                out.add(redactList(l));
            } else {
                out.add(item);
            }
        }
        return out;
    }

    /**
     * Redact the values of sensitive-named parameters in a URL query string
     * (e.g. {@code a=1&token=xyz&b=2} → {@code a=1&token=[REDACTED]&b=2}). Parameter
     * names are matched the same way as field names. Input without {@code =} is left
     * as-is. A leading {@code ?} is not expected (pass the raw query).
     */
    public static String redactQuery(String query) {
        if (query == null || query.isEmpty()) return query;
        String[] parts = query.split("&");
        var out = new StringBuilder(query.length());
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) out.append('&');
            String part = parts[i];
            int eq = part.indexOf('=');
            if (eq > 0 && isSensitive(part.substring(0, eq))) {
                out.append(part, 0, eq + 1).append(PLACEHOLDER);
            } else {
                out.append(part);
            }
        }
        return out.toString();
    }

    /**
     * Redact high-entropy path segments in a URL path. Each {@code /}-delimited
     * segment is tested with {@link #isSecretShaped(String)}; those that match are
     * replaced with {@code [redacted]}. The leading {@code /} and segment separators
     * are preserved.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code /users/42/profile} → unchanged (numeric id, short slug)</li>
     *   <li>{@code /password-reset/a3f9Bc2d8eF1g4h5} → {@code /password-reset/[redacted]}</li>
     *   <li>{@code /api/eyJhbGci.payload.signature} → {@code /api/[redacted]} (JWT)</li>
     *   <li>{@code /items/550e8400-e29b-41d4-a716-446655440000} → unchanged (UUID)</li>
     * </ul>
     */
    public static String redactPath(String path) {
        if (path == null || path.isEmpty()) return path;
        String[] segments = path.split("/", -1);
        var out = new StringBuilder(path.length());
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) out.append('/');
            String seg = segments[i];
            out.append(isSecretShaped(seg) ? "[redacted]" : seg);
        }
        return out.toString();
    }

    /**
     * Redact high-entropy tokens embedded in an exception message. The message is
     * split on common delimiters (whitespace, quotes, punctuation) and each token
     * that looks like a secret (same heuristic as {@link #redactPath}) is replaced
     * with {@code [redacted]}.
     *
     * <p>Tokens that were joined by a delimiter run are reassembled with a single
     * space so the resulting string is readable; the exact original whitespace is
     * not preserved.
     */
    public static String redactMessage(String message) {
        if (message == null || message.isEmpty()) return message;
        // Check for JWT at the whole-message level first (message may be just a token)
        if (JWT_SHAPE.matcher(message).matches()) return PLACEHOLDER;
        // L12: splice redacted spans into the ORIGINAL string instead of splitting into tokens and
        // rejoining with single spaces. The old rebuild replaced every delimiter run — commas,
        // colons, brackets, quotes, newlines — with one space, so a Hibernate message like
        // `could not execute statement [n/a]; SQL: select ...` lost its punctuation and line
        // structure even when nothing in it was actually redacted. This text is what
        // `ops_errors.message` stores and `/ops/errors` shows.
        var matcher = MESSAGE_DELIMITERS.matcher(message);
        StringBuilder out = null; // stays null until something is actually redacted
        int cursor = 0;
        int tokenStart = 0;
        while (true) {
            boolean atEnd = !matcher.find(cursor);
            int tokenEnd = atEnd ? message.length() : matcher.start();
            if (tokenEnd > tokenStart) {
                String token = message.substring(tokenStart, tokenEnd);
                if (token.length() >= MIN_SECRET_LENGTH && isSecretShaped(token)) {
                    if (out == null) out = new StringBuilder(message.length()).append(message, 0, tokenStart);
                    out.append("[redacted]");
                } else if (out != null) {
                    out.append(token);
                }
            }
            if (atEnd) break;
            // Copy the delimiter run through verbatim — that is the structure being preserved.
            if (out != null) out.append(message, matcher.start(), matcher.end());
            cursor = matcher.end();
            tokenStart = cursor;
            if (cursor >= message.length()) break;
        }
        return out == null ? message : out.toString();
    }

    /**
     * Return {@code true} if {@code value} looks like a bearer secret or high-entropy
     * token that should be redacted. Applies the heuristic described in the class
     * Javadoc:
     * <ol>
     *   <li>Not null or empty.</li>
     *   <li>Not a standard UUID (kept visible for debugging).</li>
     *   <li>Length ≥ {@value #MIN_SECRET_LENGTH}.</li>
     *   <li>JWT shape (two-dot three-part base64url) → secret.</li>
     *   <li>All characters in base64url-or-hex alphabet.</li>
     *   <li>Contains at least one digit AND at least one letter.</li>
     * </ol>
     */
    static boolean isSecretShaped(String value) {
        if (value == null || value.isEmpty()) return false;
        // Length first (M8): the common case — short segments like "users", "42", "edit" —
        // must not touch the regex engine. Safe before the UUID check: UUIDs are 36 chars,
        // always >= MIN_SECRET_LENGTH, so no UUID is ever rejected by the length test.
        if (value.length() < MIN_SECRET_LENGTH) return false;
        // UUIDs are identifiers, not secrets — leave them for debugging.
        if (UUID_SHAPE.matcher(value).matches()) return false;
        // JWT: header.payload.signature — always a secret regardless of char diversity.
        if (JWT_SHAPE.matcher(value).matches()) return true;
        // Must consist entirely of base64url/hex characters.
        if (!SECRET_CHARS.matcher(value).matches()) return false;
        // Must have mixed content: at least one digit and at least one letter.
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') hasDigit = true;
            else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) hasLetter = true;
            if (hasDigit && hasLetter) return true;
        }
        return false;
    }
}

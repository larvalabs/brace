package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Field-name-based redaction for log entries and ops diagnostics. Replaces the
 * <em>values</em> of fields whose name looks sensitive (authorization, cookie,
 * token, secret, password, api key, …) with {@code [REDACTED]} before they reach
 * stdout, the log ring buffer, or the {@code /ops/*} endpoints.
 *
 * <p>This is an allowlist-of-shape — it matches field <em>names</em>, not value
 * content; it is not PII detection. It deliberately over-redacts (a field named
 * {@code token_count} is redacted) rather than risk leaking a credential. Matching
 * is on the normalized name (lowercased, {@code -}/{@code _} stripped) containing a
 * sensitive token as a substring.
 */
public class Redactor {

    public static final String PLACEHOLDER = "[REDACTED]";

    /** Normalized substrings that mark a field name as sensitive. */
    private static final Set<String> SENSITIVE = Set.of(
        "authorization", "cookie", "password", "passwd", "pwd", "secret",
        "token", "apikey", "bearer", "credential", "privatekey", "accesskey",
        "sessionid", "csrf");

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
}

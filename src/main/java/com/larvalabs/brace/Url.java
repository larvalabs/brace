package com.larvalabs.brace;

import java.nio.charset.StandardCharsets;

/**
 * URL generation from route patterns.
 * <p>
 * Usage: {@code Url.to("/users/{id}", 42)} → {@code "/users/42"}
 * <p>
 * Substituted values are percent-encoded for a path segment, so a value containing {@code /},
 * {@code ?}, {@code #}, {@code &} or a space produces a valid URL that routes back to the same
 * value: {@code Url.to("/tags/{name}", "a/b")} → {@code "/tags/a%2Fb"}, which
 * {@code req.pathParam("name")} reads back as {@code "a/b"}. The literal segments of the pattern
 * are emitted as written — they are code, not data.
 */
public class Url {

    public static String to(String pattern, Object... params) {
        var result = new StringBuilder();
        int paramIndex = 0;
        var parts = pattern.split("/");
        for (var part : parts) {
            if (part.isEmpty()) continue;
            result.append("/");
            if (part.startsWith("{") && part.endsWith("}")) {
                if (paramIndex >= params.length) {
                    throw new IllegalArgumentException("Not enough params for pattern: " + pattern
                        + " (expected param for " + part + ")");
                }
                result.append(encodeSegment(String.valueOf(params[paramIndex++])));
            } else {
                result.append(part);
            }
        }
        if (result.isEmpty()) result.append("/");
        return result.toString();
    }

    /**
     * Percent-encode one path segment (M6).
     *
     * <p>Deliberately not {@link java.net.URLEncoder}, which is
     * {@code application/x-www-form-urlencoded}: it encodes a space as {@code +}, and in a path a
     * {@code +} is a literal plus, so the value would not survive the round trip back through
     * {@link Request#decodePathSegment}. This applies the RFC 3986 rule instead — the unreserved
     * set {@code A-Za-z0-9-._~} passes through, everything else becomes {@code %XX} over its UTF-8
     * bytes — making it the exact inverse of the decoder used on the way in.
     */
    static String encodeSegment(String value) {
        if (value == null || value.isEmpty()) return "";
        boolean needsEncoding = false;
        for (int i = 0; i < value.length(); i++) {
            if (!isUnreserved(value.charAt(i))) {
                needsEncoding = true;
                break;
            }
        }
        if (!needsEncoding) return value; // the common case: ids and slugs, no allocation
        var out = new StringBuilder(value.length() + 8);
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (isUnreserved(c)) {
                out.append(c);
            } else {
                out.append('%')
                   .append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                   .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }

    /** The RFC 3986 unreserved set: never percent-encoded, and never decoded to anything else. */
    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || c == '-' || c == '.' || c == '_' || c == '~';
    }
}

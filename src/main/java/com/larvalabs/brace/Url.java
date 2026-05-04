package com.larvalabs.brace;

/**
 * URL generation from route patterns.
 * <p>
 * Usage: {@code Url.to("/users/{id}", 42)} → {@code "/users/42"}
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
                result.append(params[paramIndex++]);
            } else {
                result.append(part);
            }
        }
        if (result.isEmpty()) result.append("/");
        return result.toString();
    }
}

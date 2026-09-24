package com.larvalabs.brace;

/**
 * URL generation from route patterns or route names (reverse routing).
 * <p>
 * By pattern: {@code Url.to("/users/{id}", 42)} → {@code "/users/42"}.
 * <p>
 * By name: register {@code app.get("/users/{id}", ctrl::show).name("users.show")} and then
 * {@code Url.to("users.show", 42)} → {@code "/users/42"}. The first argument is treated as a
 * name unless it starts with {@code /}. Named lookups resolve against the most recently
 * constructed {@link Brace} app (so they work from templates without an app reference) and
 * throw {@link IllegalArgumentException} for an unknown name, listing the registered names.
 */
public class Url {

    private static volatile Router router;

    /** Set by {@link Brace} at construction; the router named routes resolve against. */
    static void router(Router r) {
        router = r;
    }

    public static String to(String patternOrName, Object... params) {
        if (patternOrName.isEmpty() || patternOrName.startsWith("/")) return fromPattern(patternOrName, params);
        var r = router;
        if (r == null) {
            throw new IllegalStateException("Url.to(\"" + patternOrName + "\") looked up a route name, but no Brace app "
                + "has been created — named routes resolve against Brace.app() / Brace.test(). "
                + "Pass a pattern starting with '/' to build a URL without an app.");
        }
        var route = r.byName(patternOrName);
        if (route == null) {
            throw new IllegalArgumentException("No route named \"" + patternOrName + "\". Registered names: "
                + r.names() + ". Name a route with app.get(pattern, handler).name(\"" + patternOrName + "\").");
        }
        return fromPattern(route.pattern(), params);
    }

    private static String fromPattern(String pattern, Object... params) {
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

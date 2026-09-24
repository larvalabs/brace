package com.larvalabs.brace;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
 * <p>
 * Path arguments fill the pattern's {@code {placeholders}} in order and are percent-encoded;
 * too few or too many throws. A value that can't be a single path segment ({@code /},
 * {@code %}, empty, {@code .}, {@code ..}) throws too — pass it as a query parameter instead.
 * <p>
 * Query strings: pass {@link #query(Object...)} as the last argument —
 * {@code Url.to("catalog.list", Url.query("project", "punks", "q", "red hat"))} →
 * {@code "/catalog/list?project=punks&q=red+hat"}.
 */
public class Url {

    private static volatile Router router;

    /** Set by {@link Brace} at construction; the router named routes resolve against. */
    static void router(Router r) {
        router = r;
    }

    public static String to(String patternOrName, Object... params) {
        String pattern = patternOrName.isEmpty() || patternOrName.startsWith("/")
            ? patternOrName : patternFor(patternOrName);

        int pathCount = params.length;
        Query query = null;
        if (pathCount > 0 && params[pathCount - 1] instanceof Query q) {
            query = q;
            pathCount--;
        }
        for (int i = 0; i < pathCount; i++) {
            if (params[i] instanceof Query) {
                throw new IllegalArgumentException("Url.to(\"" + patternOrName + "\", ...): Url.query(...) must be "
                    + "the last argument (it was argument " + (i + 1) + " of " + params.length + ").");
            }
        }

        var path = fromPattern(pattern, params, pathCount);
        if (query == null || query.isEmpty()) return path;
        return path + (path.indexOf('?') >= 0 ? '&' : '?') + query;
    }

    /**
     * A query string for the last argument of {@link #to}: alternating names and values,
     * {@code Url.query("q", q, "page", 2)}. Values are form-encoded. {@code null} and empty
     * values are left out, so optional filters need no {@code if}s. A collection value adds one
     * pair per element ({@code Url.query("tag", List.of("a", "b"))} → {@code tag=a&tag=b}),
     * matching {@code req.queryParams(name)}. Enums are written by {@code name()}.
     */
    public static Query query(Object... namesAndValues) {
        if (namesAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Url.query(...) takes alternating names and values, but got "
                + namesAndValues.length + " arguments.");
        }
        var pairs = new ArrayList<String[]>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (!(namesAndValues[i] instanceof String name) || name.isEmpty()) {
                throw new IllegalArgumentException("Url.query(...): argument " + (i + 1)
                    + " must be a non-empty parameter name, but was " + namesAndValues[i] + ".");
            }
            var value = namesAndValues[i + 1];
            if (value instanceof Iterable<?> values) {
                for (var v : values) addPair(pairs, name, v);
            } else {
                addPair(pairs, name, value);
            }
        }
        return new Query(List.copyOf(pairs));
    }

    private static void addPair(List<String[]> pairs, String name, Object value) {
        var text = format(value);
        if (text != null && !text.isEmpty()) pairs.add(new String[]{name, text});
    }

    /** The query argument to {@link Url#to}, built by {@link Url#query(Object...)}. */
    public static final class Query {
        private final List<String[]> pairs;

        private Query(List<String[]> pairs) {
            this.pairs = pairs;
        }

        boolean isEmpty() {
            return pairs.isEmpty();
        }

        /** The encoded query string, without the leading {@code ?}. */
        @Override
        public String toString() {
            var qs = new StringBuilder();
            for (var pair : pairs) appendPair(qs, pair[0], pair[1]);
            return qs.toString();
        }
    }

    /** Text form of a URL value: enums by {@code name()}, {@code BigDecimal} without exponent. */
    static String format(Object value) {
        if (value == null) return null;
        if (value instanceof Enum<?> e) return e.name();
        // toPlainString, not toString: "1000" rather than "1E+3".
        if (value instanceof java.math.BigDecimal bd) return bd.toPlainString();
        return value.toString();
    }

    /** Append one form-encoded {@code name=value} pair, with a leading {@code &} if needed. */
    static void appendPair(StringBuilder qs, String name, String value) {
        if (!qs.isEmpty()) qs.append('&');
        qs.append(URLEncoder.encode(name, StandardCharsets.UTF_8))
          .append('=')
          .append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String patternFor(String name) {
        var r = router;
        if (r == null) {
            throw new IllegalStateException("Url.to(\"" + name + "\") looked up a route name, but no Brace app "
                + "has been created — named routes resolve against Brace.app() / Brace.test(). "
                + "Pass a pattern starting with '/' to build a URL without an app.");
        }
        var route = r.byName(name);
        if (route == null) {
            throw new IllegalArgumentException("No route named \"" + name + "\". Registered names: "
                + r.names() + ". Name a route with app.get(pattern, handler).name(\"" + name + "\").");
        }
        return route.pattern();
    }

    private static String fromPattern(String pattern, Object[] params, int count) {
        var result = new StringBuilder();
        int paramIndex = 0;
        var parts = pattern.split("/");
        for (var part : parts) {
            if (part.isEmpty()) continue;
            result.append("/");
            if (part.startsWith("{") && part.endsWith("}")) {
                if (paramIndex >= count) {
                    throw new IllegalArgumentException("Not enough params for pattern: " + pattern
                        + " (expected param for " + part + ")");
                }
                result.append(encodeSegment(pattern, part, params[paramIndex++]));
            } else {
                result.append(part);
            }
        }
        if (paramIndex < count) {
            throw new IllegalArgumentException("Too many params for pattern: " + pattern + " — it has "
                + paramIndex + " placeholder(s) but " + count + " path argument(s) were passed. "
                + "For query parameters, pass Url.query(name, value, ...) as the last argument.");
        }
        if (result.isEmpty()) result.append("/");
        return result.toString();
    }

    /**
     * Percent-encode one path segment. RFC 3986 pchar characters stay literal except {@code ;},
     * which Jetty reads as a path-parameter delimiter. {@code /} and {@code %} are refused rather
     * than encoded: Jetty rejects {@code %2F} and {@code %25} in paths as ambiguous (400), so the
     * link would never reach the route.
     */
    private static String encodeSegment(String pattern, String placeholder, Object value) {
        var text = format(value);
        if (text == null || text.isEmpty() || text.equals(".") || text.equals("..")
                || text.indexOf('/') >= 0 || text.indexOf('%') >= 0) {
            throw new IllegalArgumentException("Can't use " + (text == null ? "null" : "\"" + text + "\"")
                + " for " + placeholder + " in " + pattern + ": a path value must be non-empty, not '.' or '..', "
                + "and contain no '/' or '%'. Pass values like this as a query parameter "
                + "(Url.query(name, value)) instead.");
        }
        var out = new StringBuilder(text.length());
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "-._~!$&'()*+,=:@".indexOf(c) >= 0) {
                out.append(c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                   .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }
}

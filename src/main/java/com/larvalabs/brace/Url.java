package com.larvalabs.brace;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * Query strings: if the <b>last</b> argument is a record, it becomes the query string —
 * {@code Url.to("catalog.list", new ListQuery("punks", null, 2))} →
 * {@code "/catalog/list?project=punks&page=2"}. Components are written in declaration order;
 * null and empty-string components are skipped. Use the same record to read the query in the
 * handler with {@code req.form(ListQuery.class)}, so parameter names are written once.
 * Component types are limited to those {@link FormBinder} reads back.
 * <p>
 * The number of path arguments must match the pattern's {@code {placeholders}} exactly; too
 * few or too many throws {@link IllegalArgumentException}.
 */
public class Url {

    private static volatile Router router;

    /** Per-record-class query metadata, resolved once (same rationale as FormBinder's cache). */
    private record QueryMeta(String[] names, Method[] accessors) {}

    private static final Map<Class<?>, QueryMeta> QUERY_META = new ConcurrentHashMap<>();

    /** Set by {@link Brace} at construction; the router named routes resolve against. */
    static void router(Router r) {
        router = r;
    }

    public static String to(String patternOrName, Object... params) {
        String pattern = patternOrName.isEmpty() || patternOrName.startsWith("/")
            ? patternOrName : patternFor(patternOrName);

        Record query = null;
        Object[] pathArgs = params;
        if (params.length > 0 && params[params.length - 1] instanceof Record r) {
            query = r;
            pathArgs = Arrays.copyOf(params, params.length - 1);
        }
        for (int i = 0; i < pathArgs.length; i++) {
            if (pathArgs[i] instanceof Record r) {
                throw new IllegalArgumentException("Url.to(\"" + patternOrName + "\", ...): argument "
                    + (i + 1) + " of " + params.length + " is a record (" + r.getClass().getSimpleName()
                    + "). A record is only accepted as the last argument, where it becomes the query "
                    + "string. If it is a path value, pass its field instead (e.g. id.value()).");
            }
        }

        var path = fromPattern(pattern, pathArgs, query);
        if (query == null) return path;
        if (pattern.indexOf('?') >= 0) {
            throw new IllegalArgumentException("Url.to(\"" + patternOrName + "\", ...): the pattern already "
                + "contains '?', so a query record can't be appended to it. Move those parameters into the record.");
        }
        var qs = queryString(query);
        return qs.isEmpty() ? path : path + "?" + qs;
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

    private static String fromPattern(String pattern, Object[] params, Record query) {
        var result = new StringBuilder();
        int paramIndex = 0;
        var parts = pattern.split("/");
        for (var part : parts) {
            if (part.isEmpty()) continue;
            result.append("/");
            if (part.startsWith("{") && part.endsWith("}")) {
                if (paramIndex >= params.length) {
                    var hint = query == null ? "" : " (the trailing " + query.getClass().getSimpleName()
                        + " record was used as the query string; if it is a path value, pass its field instead)";
                    throw new IllegalArgumentException("Not enough params for pattern: " + pattern
                        + " (expected param for " + part + ")" + hint);
                }
                result.append(params[paramIndex++]);
            } else {
                result.append(part);
            }
        }
        if (paramIndex < params.length) {
            throw new IllegalArgumentException("Too many params for pattern: " + pattern + " — it has "
                + paramIndex + " placeholder(s) but " + params.length + " path argument(s) were passed. "
                + "For query parameters, pass a record as the last argument: "
                + "Url.to(nameOrPattern, pathArgs..., new MyQuery(...)).");
        }
        if (result.isEmpty()) result.append("/");
        return result.toString();
    }

    private static String queryString(Record query) {
        var meta = QUERY_META.computeIfAbsent(query.getClass(), Url::buildQueryMeta);
        var qs = new StringBuilder();
        for (int i = 0; i < meta.names().length; i++) {
            Object value;
            try {
                value = meta.accessors()[i].invoke(query);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to read " + query.getClass().getSimpleName() + "."
                    + meta.names()[i] + "()", e);
            }
            var text = format(value);
            if (text == null || text.isEmpty()) continue;
            if (!qs.isEmpty()) qs.append('&');
            qs.append(URLEncoder.encode(meta.names()[i], StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(text, StandardCharsets.UTF_8));
        }
        return qs.toString();
    }

    /** Text form of a query value, chosen so {@link FormBinder} parses it back to an equal value. */
    private static String format(Object value) {
        if (value == null) return null;
        if (value instanceof Enum<?> e) return e.name();
        // toPlainString, not toString: "1000" rather than "1E+3" (numerically equal on read-back).
        if (value instanceof java.math.BigDecimal bd) return bd.toPlainString();
        return value.toString();
    }

    private static QueryMeta buildQueryMeta(Class<?> recordClass) {
        RecordComponent[] components = recordClass.getRecordComponents();
        var names = new String[components.length];
        var accessors = new Method[components.length];
        for (int i = 0; i < components.length; i++) {
            var comp = components[i];
            if (!isSupportedQueryType(comp.getType())) {
                throw new IllegalArgumentException("Query record " + recordClass.getSimpleName() + " has component "
                    + comp.getName() + " of type " + comp.getType().getSimpleName() + ", which can't be written "
                    + "to a URL and read back by req.form(...). Supported types: String, int/Integer, long/Long, "
                    + "double/Double, float/Float, boolean/Boolean, BigDecimal, enums, LocalDate, Instant.");
            }
            names[i] = comp.getName();
            accessors[i] = comp.getAccessor();
            // Records in app code are often package-private or nested; their public accessors are
            // still unreachable reflectively from this package without this.
            accessors[i].setAccessible(true);
        }
        return new QueryMeta(names, accessors);
    }

    private static boolean isSupportedQueryType(Class<?> t) {
        return t == String.class
            || t == int.class || t == Integer.class
            || t == long.class || t == Long.class
            || t == double.class || t == Double.class
            || t == float.class || t == Float.class
            || t == boolean.class || t == Boolean.class
            || t == java.math.BigDecimal.class
            || t.isEnum()
            || t == java.time.LocalDate.class
            || t == java.time.Instant.class;
    }
}

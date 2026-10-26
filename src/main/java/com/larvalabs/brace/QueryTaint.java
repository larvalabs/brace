package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Catches request data concatenated into a query string (H3, 2026-10 security review). The
 * {@link Database} a request handler receives carries the request; every query method that takes
 * a where-fragment or raw HQL/SQL checks the string it was given against the request's path,
 * query and form values before running it. A hit throws in dev mode and in {@code Brace.test()}
 * apps, so the bug fails a test, and in production logs once per call site.
 *
 * <p>The rules keep coincidences out (a hard-coded {@code status = 'published'} on a page that
 * also has {@code ?status=published} is fine):
 * <ul>
 *   <li>Values shorter than 3 characters and plain numbers are ignored: they can't carry an
 *       injection and they match code by chance.</li>
 *   <li>A single word ({@code title}, {@code -createdAt}, {@code address.city}) counts only as a
 *       whole token after {@code ORDER BY}, the one place a bare word changes what a query does.
 *       Sort direction keywords are ignored.</li>
 *   <li>Anything else (quotes, spaces, parentheses, operators: every injection payload) counts
 *       when it appears anywhere in the string.</li>
 * </ul>
 * Only the string the caller passed is checked, never the converted HQL.
 */
final class QueryTaint {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_$.\\-]+");
    private static final Pattern ORDER_BY = Pattern.compile("(?i)\\border\\s+by\\b");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^A-Za-z0-9_$.]+");
    private static final Set<String> SORT_KEYWORDS = Set.of("asc", "desc", "nulls", "first", "last");

    private record Input(String source, String name, String value) {}

    private final List<Input> inputs;
    private final boolean strict;

    // Call sites already warned about in production, so a hot query logs once. Bounded.
    private static final Set<String> warnedSites = ConcurrentHashMap.newKeySet();

    private QueryTaint(List<Input> inputs, boolean strict) {
        this.inputs = inputs;
        this.strict = strict;
    }

    /** The checkable values of {@code req}; {@code null} when it has none. */
    static QueryTaint of(Request req, boolean strict) {
        var inputs = new ArrayList<Input>();
        add(inputs, "path parameter", req.pathParams());
        for (String[] pair : req.queryPairs()) add(inputs, "query parameter", pair[0], pair[1]);
        // The body is read before the handler runs, so this parses no more than the handler could.
        // Only form bodies: a JSON or raw body's values reach queries through typed fields.
        String type = req.header("Content-Type");
        if (type != null && (type.startsWith("application/x-www-form-urlencoded") || type.startsWith("multipart/form-data"))) {
            add(inputs, "form field", req.parsedFormBody());
        }
        return inputs.isEmpty() ? null : new QueryTaint(inputs, strict);
    }

    private static void add(List<Input> inputs, String source, Map<String, String> values) {
        if (values == null) return;
        values.forEach((name, value) -> add(inputs, source, name, value));
    }

    private static void add(List<Input> inputs, String source, String name, String value) {
        if (value == null || value.length() < 3 || NUMBER.matcher(value).matches()) return;
        inputs.add(new Input(source, name, value));
    }

    /** Throws (strict) or logs once per call site when {@code query} contains request data. */
    void check(String query, String method) {
        if (query == null) return;
        Input hit = find(query);
        if (hit == null) return;
        String message = "The " + hit.source + " \"" + hit.name + "\" appears in the query string passed to db."
            + method + "(...). Concatenating request data into HQL or SQL is an injection: bind values with ?, "
            + "and sort with Sort.from(req, ...).";
        if (strict) throw new IllegalArgumentException(message + " (Thrown in dev mode and tests; logged in production.)");
        String site = callSite();
        if (warnedSites.size() < 1000 && warnedSites.add(site)) {
            Log.warn(message + " At " + site + ".");
        }
    }

    Input find(String query) {
        String orderBy = null;
        for (Input input : inputs) {
            String value = input.value;
            if (WORD.matcher(value).matches()) {
                String word = value.startsWith("-") ? value.substring(1) : value;
                if (word.length() < 3 || SORT_KEYWORDS.contains(word.toLowerCase())) continue;
                if (orderBy == null) orderBy = orderByTail(query);
                if (orderBy.isEmpty()) continue;
                for (String token : TOKEN_SPLIT.split(orderBy)) {
                    if (token.equalsIgnoreCase(word)) return input;
                }
            } else if (query.contains(value)) {
                return input;
            }
        }
        return null;
    }

    private static String orderByTail(String query) {
        var m = ORDER_BY.matcher(query);
        return m.find() ? query.substring(m.end()) : "";
    }

    private static String callSite() {
        return StackWalker.getInstance().walk(frames -> frames
            .filter(f -> !f.getClassName().equals(QueryTaint.class.getName())
                && !f.getClassName().equals(Database.class.getName()))
            .findFirst()
            .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
            .orElse("unknown"));
    }
}

package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.List;
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
 *
 * <p>It is a backstop, not a guarantee. It can't see a value the handler transformed (trimmed,
 * split, re-cased), and a single word concatenated outside {@code ORDER BY} ({@code "id = " +
 * id} with {@code id=id}, a tautology) or a number is not flagged, because flagging those would
 * trip on ordinary code. JSON bodies, headers and cookies are not checked.
 */
final class QueryTaint {

    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_$.\\-]+");
    private static final Pattern ORDER_BY = Pattern.compile("(?i)\\border\\s+by\\b");
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^A-Za-z0-9_$.]+");
    private static final Set<String> SORT_KEYWORDS = Set.of("asc", "desc", "nulls", "first", "last");

    // A request's checked values are capped so a huge form post can't make every query check
    // expensive: past the cap, further values are not checked.
    private static final int MAX_INPUTS = 1000;
    private static final int MAX_VALUE_CHARS = 64 * 1024;

    private record Input(String source, String name, String value) {}

    private final List<Input> inputs;
    private final boolean strict;

    // When each call site + parameter last logged in production: at most once a minute each, so a
    // hot query doesn't flood the log, and a benign hit can't silence a later real one for good.
    private static final long WARN_INTERVAL_MS = 60_000;
    private static final int MAX_WARN_KEYS = 1000;
    private static final ConcurrentHashMap<String, Long> lastWarned = new ConcurrentHashMap<>();

    private QueryTaint(List<Input> inputs, boolean strict) {
        this.inputs = inputs;
        this.strict = strict;
    }

    /** The checkable values of {@code req}; {@code null} when it has none. */
    static QueryTaint of(Request req, boolean strict) {
        var inputs = new Inputs();
        if (req.pathParams() != null) req.pathParams().forEach((k, v) -> inputs.add("path parameter", k, v));
        for (String[] pair : req.queryPairs()) inputs.add("query parameter", pair[0], pair[1]);
        // The body is read before the handler runs, so this parses no more than the handler could.
        // Every value of a repeated field counts (formParams reads them all). A body that isn't
        // valid form encoding is skipped: the handler sees that error only if it reads the form.
        if (isFormBody(req.header("Content-Type"))) {
            try {
                Request.parsePairs(req.body(), false).forEach((name, values) -> {
                    for (String value : values) inputs.add("form field", name, value);
                });
            } catch (IllegalArgumentException malformed) {
                // e.g. a stray "%" — not a form this handler can read either
            }
        }
        return inputs.list.isEmpty() ? null : new QueryTaint(inputs.list, strict);
    }

    /** The body types {@code formParam} reads values from in practice, matched case-insensitively. */
    private static boolean isFormBody(String contentType) {
        if (contentType == null) return false;
        String type = contentType.toLowerCase(java.util.Locale.ROOT);
        return type.startsWith("application/x-www-form-urlencoded") || type.startsWith("multipart/form-data")
            || type.startsWith("text/plain");
    }

    private static final class Inputs {
        final List<Input> list = new ArrayList<>();
        int chars;

        void add(String source, String name, String value) {
            if (value == null || value.length() < 3 || NUMBER.matcher(value).matches()) return;
            if (list.size() >= MAX_INPUTS || chars + value.length() > MAX_VALUE_CHARS) return;
            chars += value.length();
            list.add(new Input(source, name, value));
        }
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
        String key = site + "|" + hit.name;
        long now = System.currentTimeMillis();
        if (lastWarned.size() >= MAX_WARN_KEYS && !lastWarned.containsKey(key)) key = "(other call sites)";
        Long previous = lastWarned.get(key);
        if (previous == null || now - previous >= WARN_INTERVAL_MS) {
            lastWarned.put(key, now);
            Log.warn(message + " At " + site + ".");
        }
    }

    int size() { return inputs.size(); }

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
                    // An aliased column, "ORDER BY p." + sort: the word is one segment of the token.
                    if (token.indexOf('.') >= 0 && !word.contains(".")) {
                        for (String segment : token.split("\\.")) {
                            if (segment.equalsIgnoreCase(word)) return input;
                        }
                    }
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

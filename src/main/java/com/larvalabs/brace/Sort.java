package com.larvalabs.brace;

import java.util.ArrayList;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * An {@code ORDER BY} built from a request's {@code ?sort=} against an allowlist (H3, 2026-10
 * security review). Pass it to {@code db.query}, {@code db.queryPage} or {@code db.paginate}
 * instead of writing the order into the where-fragment:
 *
 * <pre>{@code
 * var sort = Sort.from(req, "sort", Map.of("title", "title", "date", "createdAt"), "createdAt DESC");
 * var posts = db.paginate(Post.class, "published = true", sort, req, 20);
 * }</pre>
 *
 * {@code ?sort=title} sorts ascending, {@code ?sort=-title} descending. A missing or unlisted
 * key gives the default, so the request only ever picks one of the listed fields and never
 * reaches the query text. In a template, {@code req.urlWith("sort", sort.toggle("title"))} links
 * a column header that flips direction.
 */
public final class Sort {

    private static final Pattern FIELD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");
    private static final Pattern ORDER_ITEM = Pattern.compile(
        "(" + FIELD.pattern() + ")(\\s+(?i:asc|desc))?(\\s+(?i:nulls)\\s+(?i:first|last))?");

    private final String key;          // the allowlist key in effect, null for the default
    private final boolean descending;
    private final String orderBy;      // validated "field [ASC|DESC], ..." with no ORDER BY prefix

    private Sort(String key, boolean descending, String orderBy) {
        this.key = key;
        this.descending = descending;
        this.orderBy = orderBy;
    }

    /**
     * The order the request asks for in {@code param}, if its key is in {@code allowed}; otherwise
     * {@code defaultOrder}. {@code allowed} maps request keys to entity field paths (a value may
     * list several, {@code "lastName, firstName"}, all sorted the same way). The field names and
     * the default are checked here, so a typo fails on the first request, not as an HQL error.
     */
    public static Sort from(Request req, String param, Map<String, String> allowed, String defaultOrder) {
        if (allowed == null || allowed.isEmpty()) throw new IllegalArgumentException("Sort.from needs at least one allowed key");
        for (var field : allowed.values()) requireFields(field);
        Sort fallback = of(defaultOrder);
        String requested = req != null && param != null ? req.queryParam(param) : null;
        if (requested == null || requested.isEmpty()) return fallback;
        boolean desc = requested.startsWith("-");
        String key = desc ? requested.substring(1) : requested;
        String fields = allowed.get(key);
        if (fields == null) return fallback;
        var items = new ArrayList<String>();
        for (String field : fields.split(",")) items.add(field.strip() + (desc ? " DESC" : " ASC"));
        return new Sort(key, desc, String.join(", ", items));
    }

    /**
     * A fixed order written in code, {@code Sort.of("createdAt DESC, id")}: field paths with
     * optional {@code ASC}/{@code DESC} and {@code NULLS FIRST}/{@code LAST}. Anything else
     * (a function, a subquery) is refused; write that in the where-fragment.
     */
    public static Sort of(String order) {
        if (order == null || order.isBlank()) throw new IllegalArgumentException("Sort.of needs an order, e.g. \"createdAt DESC\"");
        var items = new ArrayList<String>();
        for (String item : order.split(",")) {
            String trimmed = item.strip().replaceAll("\\s+", " ");
            if (!ORDER_ITEM.matcher(trimmed).matches()) {
                throw new IllegalArgumentException("Invalid sort \"" + order + "\": each item must be a field path "
                    + "with an optional ASC/DESC and NULLS FIRST/LAST, e.g. \"createdAt DESC, id\"");
            }
            // Keywords upper-cased, the field kept as written: "title desc" renders "title DESC".
            var words = trimmed.split(" ");
            var normalized = new StringBuilder(words[0]);
            for (int i = 1; i < words.length; i++) normalized.append(' ').append(words[i].toUpperCase(java.util.Locale.ROOT));
            items.add(normalized.toString());
        }
        return new Sort(null, false, String.join(", ", items));
    }

    private static void requireFields(String fields) {
        if (fields == null) throw new IllegalArgumentException("Sort allowlist values must be field paths");
        for (String field : fields.split(",")) {
            if (!FIELD.matcher(field.strip()).matches()) {
                throw new IllegalArgumentException("Invalid sort field \"" + field.strip() + "\": allowlist values must be "
                    + "entity field paths (\"title\", \"author.name\"), never request input");
            }
        }
    }

    /** The allowlist key the request picked ({@code "title"}), or {@code null} for the default order. */
    public String key() { return key; }

    /** Whether the request asked for descending order ({@code ?sort=-title}). */
    public boolean descending() { return descending; }

    /** Whether the request sorted by {@code key}, for marking the active column. */
    public boolean is(String key) { return key != null && key.equals(this.key); }

    /**
     * The {@code ?sort=} value for a link on {@code key}'s column: ascending, or descending when
     * the page is already sorted by it ascending.
     */
    public String toggle(String key) {
        return is(key) && !descending ? "-" + key : key;
    }

    /** {@code " ORDER BY ..."}, appended after the where-fragment. */
    String hql() {
        return " ORDER BY " + orderBy;
    }

    @Override
    public String toString() {
        return "ORDER BY " + orderBy;
    }
}

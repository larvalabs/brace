package com.larvalabs.brace;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * One page of results plus what a pager needs: page number, totals, and prev/next/numbered
 * links. Build one with {@link Database#paginate(Class, String, Request, int, Object...)}, or
 * {@link #slice(List, Request, int)} for an in-memory list.
 * <p>
 * The page number comes from the {@code page} query parameter. Links are the current request's
 * URL with only {@code page} changed, so active filters and sort order carry over with no extra
 * code; page 1 leaves the parameter out, so the first page has one URL. A {@code Paged} built
 * from a {@code Request} records that URL when it is created, so templates call
 * {@link #links()}, {@link #prevUrl()} and {@link #nextUrl()} with no arguments.
 * <p>
 * Serializes to JSON as {@code {items, page, perPage, totalCount, totalPages}}.
 */
public final class Paged<T> {

    /** The query parameter that carries the page number. */
    public static final String PARAM = "page";

    /** Pages shown either side of the current one by {@link #links()}. */
    static final int DEFAULT_SPREAD = 2;

    /** One entry in the numbered strip: a page link, or a gap ({@code "…"}, no url). */
    public record Link(String label, String url, boolean current, boolean gap) {}

    private final List<T> items;
    private final int page;
    private final int perPage;
    private final long totalCount;
    private final int totalPages;
    // The request's path and its query pairs minus `page`, captured at construction; null
    // when built without a request, in which case the link methods throw.
    private final String path;
    private final List<String[]> query;

    private Paged(List<T> items, int page, int perPage, long totalCount, String path, List<String[]> query) {
        if (perPage <= 0) throw new IllegalArgumentException("perPage must be > 0 (was " + perPage + ")");
        this.items = List.copyOf(items);
        this.perPage = perPage;
        this.totalCount = totalCount;
        this.totalPages = totalPages(totalCount, perPage);
        this.page = Math.max(1, page);
        this.path = path;
        this.query = query;
    }

    /**
     * A page you fetched yourself (native SQL, an external API): {@code pageItems} is that page's
     * rows, {@code totalCount} the total across all pages. Call {@link #linkedTo(Request)} to
     * enable the link methods.
     */
    public static <T> Paged<T> of(List<T> pageItems, int page, int perPage, long totalCount) {
        return new Paged<>(pageItems, page, perPage, totalCount, null, null);
    }

    /**
     * The requested page of an in-memory list, reading {@code ?page=} from the request; an
     * out-of-range page is clamped to the first or last page.
     */
    public static <T> Paged<T> slice(List<T> all, Request req, int perPage) {
        return slice(all, requestedPage(req), perPage).linkedTo(req);
    }

    /** Like {@link #slice(List, Request, int)}, with an explicit page number and no links. */
    public static <T> Paged<T> slice(List<T> all, int page, int perPage) {
        if (perPage <= 0) throw new IllegalArgumentException("perPage must be > 0 (was " + perPage + ")");
        int p = clamp(page, totalPages(all.size(), perPage));
        int from = Math.min(all.size(), (p - 1) * perPage);
        return of(all.subList(from, Math.min(all.size(), from + perPage)), p, perPage, all.size());
    }

    /** A copy whose links are built from {@code req}'s URL. */
    public Paged<T> linkedTo(Request req) {
        var pairs = new ArrayList<String[]>();
        for (var pair : req.queryPairs()) {
            if (!pair[0].equals(PARAM)) pairs.add(pair);
        }
        return new Paged<>(items, page, perPage, totalCount, req.path(), List.copyOf(pairs));
    }

    /**
     * The same page with each item converted, keeping page, totals and links — entities to view
     * records for a template, or to DTOs for {@code Result.json} (never serialize entities).
     */
    public <R> Paged<R> map(Function<? super T, ? extends R> fn) {
        var mapped = new ArrayList<R>(items.size());
        for (var item : items) mapped.add(fn.apply(item));
        return new Paged<>(mapped, page, perPage, totalCount, path, query);
    }

    @JsonProperty public List<T> items() { return items; }
    @JsonProperty public int page() { return page; }
    @JsonProperty public int perPage() { return perPage; }
    @JsonProperty public long totalCount() { return totalCount; }
    /** At least 1: an empty result is page 1 of 1. */
    @JsonProperty public int totalPages() { return totalPages; }

    @JsonIgnore public boolean isEmpty() { return items.isEmpty(); }
    public boolean hasPrev() { return page > 1; }
    public boolean hasNext() { return page < totalPages; }

    /** URL of page {@code n}: this request's URL with {@code page} changed. */
    public String url(int n) {
        if (path == null) {
            throw new IllegalStateException("This Paged was built without a request, so it can't build links. "
                + "Use db.paginate(type, where, req, perPage) or Paged.slice(all, req, perPage), "
                + "or call .linkedTo(req).");
        }
        var qs = new StringBuilder();
        for (var pair : query) Url.appendPair(qs, pair[0], pair[1]);
        if (n > 1) Url.appendPair(qs, PARAM, Integer.toString(n));
        return qs.isEmpty() ? path : path + "?" + qs;
    }

    /** URL of the previous page, or {@code null} on the first page. */
    public String prevUrl() { return hasPrev() ? url(page - 1) : null; }

    /** URL of the next page, or {@code null} on the last page. */
    public String nextUrl() { return hasNext() ? url(page + 1) : null; }

    /**
     * The numbered strip: always the first and last page, two pages either side of the
     * current one, and a gap wherever that skips pages — {@code 1 … 4 5 [6] 7 8 … 20}.
     */
    public List<Link> links() { return links(DEFAULT_SPREAD); }

    /** {@link #links()} with {@code spread} pages either side of the current one. */
    public List<Link> links(int spread) {
        var links = new ArrayList<Link>();
        links.add(link(1));
        if (totalPages == 1) return links;
        int start = Math.max(2, page - spread);
        int end = Math.min(totalPages - 1, page + spread);
        // A gap that would hide a single page shows that page instead.
        if (start == 3) start = 2;
        if (end == totalPages - 2) end = totalPages - 1;
        if (start > 2) links.add(new Link("…", null, false, true));
        for (int p = start; p <= end; p++) links.add(link(p));
        if (end < totalPages - 1) links.add(new Link("…", null, false, true));
        links.add(link(totalPages));
        return links;
    }

    private Link link(int p) {
        return new Link(Integer.toString(p), url(p), p == page, false);
    }

    /** The {@code ?page=} value; missing or unparseable reads as 1. */
    static int requestedPage(Request req) {
        return req.queryInt(PARAM, 1);
    }

    static int totalPages(long totalCount, int perPage) {
        return (int) Math.max(1, (totalCount + perPage - 1) / perPage);
    }

    static int clamp(int page, int totalPages) {
        return Math.max(1, Math.min(totalPages, page));
    }

    @Override
    public String toString() {
        return "Paged{page=" + page + "/" + totalPages + ", items=" + items.size() + ", totalCount=" + totalCount + "}";
    }
}

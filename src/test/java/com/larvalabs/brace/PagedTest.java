package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PagedTest {

    /** A GET request for {@code path?rawQuery}, parsed the way BraceHandler parses it. */
    private static Request get(String path, String rawQuery) {
        var req = new Request("GET", path, Map.of(), Request.parseSingleValues(rawQuery, true), Map.of(), "");
        req.setRawQuery(rawQuery);
        return req;
    }

    private static List<Integer> numbers(int n) {
        return IntStream.rangeClosed(1, n).boxed().toList();
    }

    /** The strip as text: current page in brackets, gaps as "…". */
    private static String strip(Paged<?> paged) {
        var sb = new StringBuilder();
        for (var link : paged.links()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(link.current() ? "[" + link.label() + "]" : link.label());
        }
        return sb.toString();
    }

    private static Paged<Integer> pageOf(int page, int totalPages) {
        return Paged.<Integer>of(List.of(), page, 10, totalPages * 10L).linkedTo(get("/p", null));
    }

    // --- the numbered strip ---

    @Test
    void stripShowsFirstLastWindowAndGaps() {
        assertEquals("1 … 8 9 [10] 11 12 … 20", strip(pageOf(10, 20)));
    }

    @Test
    void stripNearTheEdgesHasOneGap() {
        assertEquals("[1] 2 3 … 20", strip(pageOf(1, 20)));
        assertEquals("1 … 18 19 [20]", strip(pageOf(20, 20)));
    }

    @Test
    void gapThatWouldHideOnePageShowsThePage() {
        // Window 3..6 would leave "1 … 3" hiding only page 2; show 2 instead.
        assertEquals("1 2 3 4 [5] 6 7 … 20", strip(pageOf(5, 20)));
        assertEquals("1 … 14 15 [16] 17 18 19 20", strip(pageOf(16, 20)));
    }

    @Test
    void smallCountsHaveNoGaps() {
        assertEquals("[1]", strip(pageOf(1, 1)));
        assertEquals("1 [2]", strip(pageOf(2, 2)));
        assertEquals("1 2 [3] 4 5", strip(pageOf(3, 5)));
    }

    @Test
    void customSpread() {
        var links = pageOf(10, 20).links(1);
        assertEquals("1 … 9 10 11 … 20",
            String.join(" ", links.stream().map(Paged.Link::label).toList()));
    }

    @Test
    void gapsHaveNoUrl() {
        var gap = pageOf(10, 20).links().get(1);
        assertTrue(gap.gap());
        assertNull(gap.url());
    }

    // --- links from the request ---

    @Test
    void linksKeepTheQueryAndChangeOnlyPage() {
        var paged = Paged.of(List.of(), 3, 10, 100).linkedTo(get("/catalog/list", "project=punks&page=3&tag=a&tag=b+c"));
        assertEquals("/catalog/list?project=punks&tag=a&tag=b+c&page=4", paged.nextUrl());
        assertEquals("/catalog/list?project=punks&tag=a&tag=b+c&page=2", paged.prevUrl());
    }

    @Test
    void pageOneLeavesTheParameterOut() {
        var paged = Paged.of(List.of(), 2, 10, 100).linkedTo(get("/posts", "page=2"));
        assertEquals("/posts", paged.prevUrl());
        assertEquals("/posts", paged.links().get(0).url());
    }

    @Test
    void prevAndNextAreNullAtTheEnds() {
        assertNull(pageOf(1, 3).prevUrl());
        assertNull(pageOf(3, 3).nextUrl());
    }

    @Test
    void queryValuesAreReEncoded() {
        var paged = Paged.of(List.of(), 1, 10, 100).linkedTo(get("/s", "q=%22%3E%3Cscript%3E"));
        assertEquals("/s?q=%22%3E%3Cscript%3E&page=2", paged.nextUrl());
    }

    @Test
    void linksWithoutARequestThrowClearly() {
        var paged = Paged.of(List.of(1), 1, 10, 30);
        var ex = assertThrows(IllegalStateException.class, paged::nextUrl);
        assertTrue(ex.getMessage().contains("linkedTo(req)"), ex.getMessage());
    }

    // --- slice ---

    @Test
    void sliceReadsPageFromRequest() {
        var paged = Paged.slice(numbers(25), get("/n", "page=2"), 10);
        assertEquals(numbers(20).subList(10, 20), paged.items());
        assertEquals(3, paged.totalPages());
        assertEquals(25, paged.totalCount());
        assertEquals("/n?page=3", paged.nextUrl());
    }

    @Test
    void sliceClampsAndToleratesBadInput() {
        assertEquals(List.of(21, 22, 23, 24, 25), Paged.slice(numbers(25), get("/n", "page=99"), 10).items());
        assertEquals(1, Paged.slice(numbers(25), get("/n", "page=abc"), 10).page());
        assertEquals(1, Paged.slice(numbers(25), get("/n", "page=0"), 10).page());
    }

    @Test
    void sliceOfEmptyListIsPageOneOfOne() {
        var paged = Paged.slice(List.of(), 4, 10);
        assertTrue(paged.isEmpty());
        assertEquals(1, paged.page());
        assertEquals(1, paged.totalPages());
        assertFalse(paged.hasNext());
    }

    @Test
    void perPageMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> Paged.slice(numbers(3), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> Paged.of(List.of(), 1, -1, 0));
    }

    // --- JSON ---

    @Test
    void serializesToJson() {
        var json = Json.of(Paged.slice(numbers(25), 3, 10)).body();
        assertEquals("{\"items\":[21,22,23,24,25],\"page\":3,\"perPage\":10,\"totalCount\":25,\"totalPages\":3}", json);
    }

    // --- req.urlWith ---

    @Test
    void urlWithReplacesInPlaceAndKeepsTheRest() {
        var req = get("/posts", "tag=java&sort=date&q=a+b");
        assertEquals("/posts?tag=java&sort=name&q=a+b", req.urlWith("sort", "name"));
        assertEquals("/posts?tag=java&sort=date&q=a+b&page=2", req.urlWith("page", 2));
    }

    @Test
    void urlWithNullOrEmptyRemoves() {
        var req = get("/posts", "tag=java&tag=go&sort=date");
        assertEquals("/posts?sort=date", req.urlWith("tag", null));
        assertEquals("/posts", get("/posts", "sort=date").urlWith("sort", ""));
    }

    @Test
    void urlWithReplacesRepeatedParamWithOneValue() {
        assertEquals("/posts?tag=rust&sort=date", get("/posts", "tag=java&sort=date&tag=go").urlWith("tag", "rust"));
    }

    @Test
    void urlWithOnRequestWithoutQuery() {
        assertEquals("/posts?sort=name", get("/posts", null).urlWith("sort", "name"));
    }
}

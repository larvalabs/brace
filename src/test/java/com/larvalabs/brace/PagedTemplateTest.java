package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** A pager rendered by a real template: links are built after the handler has returned. */
class PagedTemplateTest {

    static TestApp testApp;

    @BeforeAll
    static void setup() throws Exception {
        var all = IntStream.rangeClosed(1, 95).boxed().toList();
        testApp = Brace.test().templates("src/test/resources/views").start(app ->
            app.get("/numbers", req -> View.of("pagedLinks", "numbers", Paged.slice(all, req, 10))));
    }

    @AfterAll
    static void teardown() throws Exception {
        testApp.stop();
        // Static engine is process-wide; reset so stub-mode tests (ResultTest) are unaffected.
        View.setEngine(null);
    }

    @Test
    void rendersThePageAndLinksThatKeepTheFilters() {
        var response = testApp.get("/numbers?q=red+hat&page=5");
        assertEquals(200, response.status());
        var body = response.body();
        assertTrue(body.contains("<li>41</li>") && body.contains("<li>50</li>"), body);
        assertFalse(body.contains("<li>51</li>"), body);
        // JTE escapes '&' in attribute values; browsers decode it back.
        assertTrue(body.contains("rel=\"prev\" href=\"/numbers?q=red+hat&amp;page=4\""), body);
        assertTrue(body.contains("rel=\"next\" href=\"/numbers?q=red+hat&amp;page=6\""), body);
        assertTrue(body.contains("<a href=\"/numbers?q=red+hat\">1</a>"), body);
        assertTrue(body.contains("<b>5</b>"), body);
        assertTrue(body.contains("<a href=\"/numbers?q=red+hat&amp;page=10\">10</a>"), body);
        assertTrue(body.contains("<span>…</span>"), body);
    }

    @Test
    void firstPageHasNoPrev() {
        var body = testApp.get("/numbers").body();
        assertFalse(body.contains("rel=\"prev\""), body);
        assertTrue(body.contains("rel=\"next\" href=\"/numbers?page=2\""), body);
    }
}

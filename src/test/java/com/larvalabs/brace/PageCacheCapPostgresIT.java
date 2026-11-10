package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L4 review F5 on real Postgres. The row cap covered the whole {@code brace_cache} table and
 * trimmed in expiry order, so cached pages (cheap to mint) evicted the app's own entries: with 50
 * app entries (1 h TTL), 300 page rows (2 h TTL) and a cap of 100, none of the 50 survived.
 */
class PageCacheCapPostgresIT extends PostgresTestBase {

    static DatabaseFactory factory;

    @BeforeAll
    static void buildFactory() {
        factory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
    }

    @AfterAll
    static void closeFactory() {
        if (factory != null) factory.close();
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("brace_cache", "brace_cache_counters");
    }

    private static long rows(String where) throws Exception {
        try (var c = connect(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM brace_cache" + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void reviewerScenarioKeepsEveryAppEntry() throws Exception {
        var backend = new PostgresBackend(factory, 100);
        var cache = new Cache(backend);
        try {
            PageCacheReviewFixesTest.pageFloodLeavesAppEntries(cache, backend::evictExpired);
            assertTrue(rows("") <= 100, "overall cap: " + rows(""));
        } finally {
            cache.close();
        }
    }

    @Test
    void pageRowsHaveTheirOwnCap() throws Exception {
        var backend = new PostgresBackend(factory, 1_000, 20);
        var cache = new Cache(backend);
        try {
            for (int i = 0; i < 30; i++) cache.set("app:" + i, "v", "1m"); // expire before the pages
            var pages = cache.wrap("1h", req -> Result.text("page"));
            for (int i = 0; i < 200; i++) pages.apply(PageCacheReviewFixesTest.request("/p/" + i));
            backend.evictExpired();
            assertEquals(20, rows(" WHERE cache_key LIKE 'brace:page:%'"));
            assertEquals(30, rows(" WHERE cache_key NOT LIKE 'brace:page:%'"), "app rows untouched by the page cap");
        } finally {
            cache.close();
        }
    }
}

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

    // ---- L4 final review: page rows are trimmed least recently used first, not soonest-expiring ----

    private static int render(Cache.CachedHandler page, String path, java.util.Map<String, Integer> renders) {
        page.apply(PageCacheReviewFixesTest.request(path));
        return renders.getOrDefault(path, 0);
    }

    @Test
    void shortTtlPagesSurviveALongTtlFlood() throws Exception {
        // The reviewer's scenario: 200 rows on a 1d route, then 30 pages on a 5m route requested 20
        // times each. Trimmed soonest-expiring first, the 5m pages were deleted on every check:
        // 600 of 600 requests ran the handler.
        var backend = new PostgresBackend(factory, 100_000, 100);
        var cache = new Cache(backend);
        try {
            var renders = new java.util.HashMap<String, Integer>();
            var flood = cache.wrap("1d", req -> Result.text("flood"));
            for (int i = 0; i < 200; i++) flood.apply(PageCacheReviewFixesTest.request("/flood/" + i));
            var pages = cache.wrap("5m", req -> {
                renders.merge(req.path(), 1, Integer::sum);
                return Result.text("page");
            });
            for (int round = 0; round < 20; round++) {
                for (int i = 0; i < 30; i++) render(pages, "/p/" + i, renders);
            }
            int ran = renders.values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(ran <= 40, "handler runs for 600 requests on 30 pages: " + ran);
            assertTrue(rows(" WHERE cache_key LIKE 'brace:page:%'") <= 100 + backend.trimEvery());
        } finally {
            cache.close();
        }
    }

    @Test
    void hotPagesSurviveALaterFloodThroughRecordedHits() throws Exception {
        var backend = new PostgresBackend(factory, 100_000, 100);
        backend.touchInterval(java.time.Duration.ZERO); // record every hit, for the test
        var cache = new Cache(backend);
        try {
            var renders = new java.util.HashMap<String, Integer>();
            var pages = cache.wrap("1h", req -> {
                renders.merge(req.path(), 1, Integer::sum);
                return Result.text("page");
            });
            for (int i = 0; i < 30; i++) render(pages, "/hot/" + i, renders);
            var flood = cache.wrap("1h", req -> Result.text("flood"));
            for (int f = 0; f < 400; f++) {
                flood.apply(PageCacheReviewFixesTest.request("/flood/" + f));
                if (f % 20 == 19) {
                    for (int i = 0; i < 30; i++) render(pages, "/hot/" + i, renders);
                }
            }
            int ran = renders.values().stream().mapToInt(Integer::intValue).sum();
            assertTrue(ran <= 40, "hot pages re-rendered during the flood: " + ran + " (30 first renders)");
        } finally {
            cache.close();
        }
    }

    @Test
    void aHitRecordsUseAtMostOncePerInterval() throws Exception {
        var backend = new PostgresBackend(factory, 100_000, 100);
        var cache = new Cache(backend);
        try {
            var page = cache.wrap("1h", req -> Result.text("page"));
            page.apply(PageCacheReviewFixesTest.request("/once"));
            String before = usedAt();
            for (int i = 0; i < 5; i++) page.apply(PageCacheReviewFixesTest.request("/once"));
            assertEquals(before, usedAt(), "a hit within the interval must not write");
        } finally {
            cache.close();
        }
    }

    // ---- verification: V19 adds used_at without building an index under the ALTER's lock ----

    /**
     * V19 ran {@code ALTER TABLE ... ADD COLUMN} then {@code CREATE INDEX} in one transaction, so
     * the ALTER's ACCESS EXCLUSIVE lock blocked every read and write of {@code brace_cache} (old
     * instances in a rolling deploy included) for the whole index build. The trim does not need
     * the index (see {@link #theTrimHoldsTheCapsWithoutAUsedAtIndex}), so V19 no longer builds it.
     */
    @Test
    void v19BuildsNoIndex() throws Exception {
        try (var c = connect(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'brace_cache' "
                 + "AND indexdef LIKE '%used_at%'")) {
            assertNull(rs.next() ? rs.getString(1) : null, "V19 must not build an index on used_at");
        }
    }

    /**
     * The page trim over a full table: 100,000 app rows plus 11,000 page rows, page cap 10,000.
     * Without an index on used_at the page subquery is one scan of the table plus a top-N sort of
     * the page rows; the trim already counts with full scans, so this adds one scan per trim
     * (every trimEvery inserts), over a table the cap bounds. The {@code LIKE} prefix cannot use
     * the primary key under a non-C collation, and with app rows older than the pages an index on
     * used_at walked all of them too (measured: 7.5 ms with it, 8.5 ms without, for this query).
     * Prints the plan and the time.
     */
    @Test
    void theTrimHoldsTheCapsWithoutAUsedAtIndex() throws Exception {
        try (var c = connect(); var st = c.createStatement()) {
            st.execute("INSERT INTO brace_cache (cache_key, value, expires_at) "
                + "SELECT 'app:' || g, '\\x00'::bytea, now() + interval '1 hour' FROM generate_series(1, 100000) g");
            st.execute("INSERT INTO brace_cache (cache_key, value, expires_at, used_at) "
                + "SELECT 'brace:page:GET:path:/p/' || g, '\\x00'::bytea, now() + interval '1 hour', "
                + "now() - (g || ' seconds')::interval FROM generate_series(1, 11000) g");
            st.execute("ANALYZE brace_cache");
            try (var rs = st.executeQuery("EXPLAIN (ANALYZE, COSTS OFF) SELECT cache_key FROM brace_cache "
                    + "WHERE cache_key LIKE 'brace:page:%' ESCAPE '\\' ORDER BY used_at ASC LIMIT 1000")) {
                var plan = new StringBuilder();
                while (rs.next()) plan.append(rs.getString(1)).append('\n');
                System.out.println("[V19 probe] page-trim subquery plan:\n" + plan);
            }
        }
        var backend = new PostgresBackend(factory, 1_000_000, 10_000);
        long start = System.nanoTime();
        long removed = backend.trimToCap();
        long ms = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[V19 probe] trimToCap over 111,000 rows removed " + removed + " in " + ms + " ms");
        assertEquals(1_000, removed);
        assertEquals(10_000, rows(" WHERE cache_key LIKE 'brace:page:%'"));
        assertEquals(100_000, rows(" WHERE cache_key NOT LIKE 'brace:page:%'"), "app rows untouched");
        // The oldest pages (largest g) went first.
        assertEquals(0, rows(" WHERE cache_key = 'brace:page:GET:path:/p/11000'"));
        assertEquals(1, rows(" WHERE cache_key = 'brace:page:GET:path:/p/1'"));
    }

    private static String usedAt() throws Exception {
        try (var c = connect(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT used_at::text FROM brace_cache WHERE cache_key LIKE 'brace:page:%'")) {
            rs.next();
            return rs.getString(1);
        }
    }
}

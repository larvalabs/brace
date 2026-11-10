package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L4 on real Postgres: {@code brace_cache} had no row cap, so anything that minted keys (a
 * page-cached route with a free-form path parameter) grew the table without bound, while the
 * in-memory backend stopped at 10,000 entries.
 */
class PostgresCacheCapIT extends PostgresTestBase {

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

    private static long rows() throws Exception {
        try (var c = connect(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT count(*) FROM brace_cache")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void insertsAreHeldNearTheCapAndTheSweepEnforcesIt() throws Exception {
        var backend = new PostgresBackend(factory, 50); // checks every 5 inserts
        var cache = new Cache(backend);
        try {
            for (int i = 0; i < 500; i++) {
                cache.set("page:GET:/posts/" + i, "body " + i, "5m");
            }
            assertTrue(rows() <= 55, "held within one check interval of the cap, was " + rows());
            backend.evictExpired();
            assertEquals(50, rows(), "the sweep trims to the cap");
            // The newest entries (latest expiry) are the ones kept.
            assertEquals("body 499", cache.get("page:GET:/posts/499", String.class));
            assertNull(cache.get("page:GET:/posts/0", String.class));
        } finally {
            cache.close();
        }
    }

    @Test
    void entriesWithoutExpiryAreEvictedLast() throws Exception {
        var backend = new PostgresBackend(factory, 20);
        var cache = new Cache(backend);
        try {
            for (int i = 0; i < 10; i++) {
                cache.set("config:" + i, "keep " + i); // no TTL
            }
            for (int i = 0; i < 100; i++) {
                cache.set("page:" + i, "page " + i, "5m");
            }
            backend.evictExpired();
            assertEquals(20, rows());
            for (int i = 0; i < 10; i++) {
                assertEquals("keep " + i, cache.get("config:" + i, String.class));
            }
        } finally {
            cache.close();
        }
    }

    @Test
    void defaultCapIsConfiguredThroughTheFactory() throws Exception {
        assertEquals(100_000, PostgresBackend.DEFAULT_MAX_ENTRIES);
        var cache = new Cache(CacheBackend.postgres(factory, 3));
        try {
            for (int i = 0; i < 10; i++) cache.set("k" + i, "v", "5m");
            assertTrue(rows() <= 3, "a cap of 3 checks every insert, was " + rows());
        } finally {
            cache.close();
        }
    }
}

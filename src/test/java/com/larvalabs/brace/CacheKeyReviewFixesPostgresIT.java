package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** L5 review fixes on real Postgres: cut-prefix clears and unpaired surrogates. */
class CacheKeyReviewFixesPostgresIT extends PostgresTestBase {

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

    @Test
    void deletePrefixWithACutPrefixClearsOnlyWhatItMayHaveCovered() {
        var cache = new Cache(CacheBackend.postgres(factory));
        try {
            CacheKeyReviewFixesTest.deletePrefixWithACutPrefixClearsOnlyWhatItMayHaveCovered(cache);
        } finally {
            cache.close();
        }
    }

    /** pgjdbc sends a lone surrogate as "?", so before the fix these two keys were one row. */
    @Test
    void unpairedSurrogatesDoNotCollide() {
        var cache = new Cache(CacheBackend.postgres(factory));
        try {
            cache.set("k:\uD800", "one");
            cache.set("k:\uD801", "two");
            cache.set("k:?", "three");
            assertEquals("one", cache.get("k:\uD800", String.class));
            assertEquals("two", cache.get("k:\uD801", String.class));
            assertEquals("three", cache.get("k:?", String.class));
        } finally {
            cache.close();
        }
    }
}

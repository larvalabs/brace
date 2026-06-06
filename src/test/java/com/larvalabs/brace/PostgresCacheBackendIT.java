package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared cache backend against <em>real</em> Postgres (docs/2026-06-04-brace-shared-cache.md,
 * Phase 1). Building a {@link DatabaseFactory} on the container applies the framework migrations —
 * including the Postgres-only {@code migration_pg/V8__brace_cache.sql} — so this also proves that
 * DDL applies on Postgres.
 *
 * <p>Runs the same {@link CacheBackendContract} as the H2-free unit suite (proving the facade +
 * serialization behave identically on a real DB), then adds the Postgres-specific guarantees the
 * in-memory fixtures can't express: atomic {@code INCR} under concurrency, GIN-backed {@code TEXT[]}
 * tag invalidation, and read-time expiry via the {@code expires_at} predicate.
 */
class PostgresCacheBackendIT extends PostgresTestBase {

    static DatabaseFactory factory;
    PostgresBackend backend;

    @BeforeAll
    static void buildFactory() {
        // Base @BeforeAll started the shared container. DatabaseFactory's constructor runs the
        // framework migrations on Postgres, applying V8 from the migration_pg tier (creating
        // brace_cache with TEXT[] + GIN).
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
        backend = new PostgresBackend(factory);
    }

    @Test
    void valueAndCounterWithSameKeyDoNotCollide() {
        var cache = new Cache(backend);
        // Values and counters live in separate tables, so a shared key must not clobber either —
        // parity with the in-memory backend's two maps.
        cache.set("k", "the-value", "1h");
        assertEquals(1, cache.incr("k"));
        assertEquals(2, cache.incr("k"));
        assertEquals("the-value", cache.get("k", String.class), "incr must not wipe the value");
        cache.set("k", "new-value", "1h");
        assertEquals(3, cache.incr("k"), "set must not reset the counter");
        assertEquals("new-value", cache.get("k", String.class));
    }

    @Test
    void conformanceOnPostgres() {
        CacheBackendContract.assertContract(backend);
    }

    @Test
    void incrIsAtomicUnderConcurrency() throws Exception {
        int threads = 8, perThread = 100;
        var workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = Thread.ofVirtual().start(() -> {
                for (int j = 0; j < perThread; j++) {
                    backend.incr("counter", 1);
                }
            });
        }
        for (var w : workers) w.join();
        // If incr were a read-modify-write rather than an atomic UPDATE, concurrent writers would
        // lose increments and the total would be < threads*perThread.
        assertEquals((long) threads * perThread, backend.incr("counter", 0));
    }

    @Test
    void clearTagUsesArrayContainment() {
        var cache = new Cache(backend);
        cache.set("a", "1", "1h", "x", "y");
        cache.set("b", "2", "1h", "y");
        cache.set("c", "3", "1h", "z");

        cache.clearTag("y"); // DELETE ... WHERE tags @> ARRAY['y'] (GIN)

        assertNull(cache.get("a", String.class));
        assertNull(cache.get("b", String.class));
        assertEquals("3", cache.get("c", String.class));
    }

    @Test
    void expiredRowsAreNotServedAndSweepReclaims() throws Exception {
        var cache = new Cache(backend);
        cache.set("temp", "value", "1s");
        Thread.sleep(1200);
        // Enforced on read by the expires_at predicate, even before any sweep runs.
        assertNull(cache.get("temp", String.class));
        // The sweep then reclaims the dead row.
        assertTrue(backend.evictExpired() >= 1);
    }

    @Test
    void pageCacheServedAcrossInstancesOnPostgres() {
        // Phase 2: a RenderedResponse round-trips through BYTEA, so a page rendered on one server is
        // replayed by another from the shared table — without re-running the handler.
        var serverA = new Cache(backend);
        var serverB = new Cache(backend);
        var renders = new AtomicInteger();
        Handler handler = req -> {
            renders.incrementAndGet();
            return Result.html("<h1>shared</h1>").header("X-Page", "home");
        };
        var req = new Request("GET", "/home", Map.of(), Map.of(), Map.of(), null);

        var first = serverA.wrap("5m", handler).apply(req);   // miss → render + cache to Postgres
        var second = serverB.wrap("5m", handler).apply(req);  // hit → replay from Postgres

        assertEquals(1, renders.get(), "B serves A's cached render from Postgres");
        assertEquals("<h1>shared</h1>", new String(first.rawBytes() != null ? first.rawBytes()
                : first.body().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertEquals("<h1>shared</h1>", new String(second.rawBytes(), StandardCharsets.UTF_8));
        assertEquals("text/html", second.contentType());
        assertEquals("home", second.header("X-Page"));
    }

    @Test
    void noExpiryWhenTtlOmitted() {
        var cache = new Cache(backend);
        cache.set("forever", "value");
        assertEquals("value", cache.get("forever", String.class));
        assertEquals(0, backend.evictExpired(), "a no-TTL row is never swept");
        assertEquals("value", cache.get("forever", String.class));
    }
}

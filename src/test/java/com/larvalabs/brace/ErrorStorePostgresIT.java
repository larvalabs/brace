package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * {@link ErrorStore} on real Postgres — the Tier 2a {@code INSERT ... ON CONFLICT DO UPDATE}
 * upsert (postgres-native doc §2a), backed by the partial unique index
 * {@code ops_errors_unresolved_dedupe} from {@code migration_pg/V6}.
 *
 * <p>The H2 {@code ErrorStoreTest} exercises the check-then-insert branch and proves the CRUD
 * semantics, but it physically cannot show the bug this tier exists to close: under load two
 * instances both pass the "is there an existing unresolved row?" check, both INSERT, and you get a
 * <em>duplicate row plus a lost increment</em>. That's a READ COMMITTED multi-connection race H2
 * in-memory can't reproduce. {@link #concurrentDuplicatesFoldIntoOneRowWithExactCount} is the guard
 * for the fix: the partial unique index makes one writer take {@code DO UPDATE} under the row lock,
 * so concurrent records of the same {@code (type, route)} fold into exactly one row with an exact
 * count. See {@code docs/2026-06-05-pg-testcontainers.md} (Phase 3) and the ErrorStore Javadoc.
 */
class ErrorStorePostgresIT extends PostgresTestBase {

    static DatabaseFactory factory;
    ErrorStore errorStore;

    @BeforeAll
    static void buildFactory() {
        // Pointed at the container: this runs the framework Flyway migrations on real Postgres,
        // including the postgres-only V6 partial unique index the upsert relies on.
        factory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
    }

    @AfterAll
    static void closeFactory() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("ops_errors");
        errorStore = new ErrorStore(factory, 1000);
    }

    @org.junit.jupiter.api.AfterEach
    void closeStore() {
        errorStore.close();
    }

    // --- Parity: the Postgres branch must behave like the H2 one on the non-racy paths ---

    @Test
    void duplicateUnresolvedErrorsFoldIntoOneRowWithIncrementingCount() {
        // Flush between the two records so the second upsert exercises the DO UPDATE branch
        // (a single flush would coalesce them into one INSERT with count 2 — see H9).
        errorStore.record("RuntimeException", "error 1", "GET /test", "stack1", "req1");
        errorStore.flush();
        errorStore.record("RuntimeException", "error 2", "GET /test", "stack2", "req2");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size(), "same (type, route) must fold into one row");
        assertEquals(2, errors.get(0).get("occurrenceCount"));
        // DO UPDATE writes EXCLUDED.* — the latest message/stack win, matching the H2 branch.
        assertEquals("error 2", errors.get(0).get("message"));
        assertEquals("stack2", errors.get(0).get("stackTrace"));
    }

    @Test
    void resolvedErrorRecurrenceGetsANewRow() {
        errorStore.record("RuntimeException", "error", "GET /test", "stack", "req");
        errorStore.flush();
        long id = ((Number) errorStore.list(null).get(0).get("id")).longValue();
        errorStore.resolve(id);

        // The recurrence must NOT fold into the resolved row: the unique index is partial
        // (WHERE resolved_at IS NULL), so the resolved row isn't a conflict target and a fresh
        // unresolved row is inserted.
        errorStore.record("RuntimeException", "error again", "GET /test", "stack2", "req2");
        errorStore.flush();

        var unresolved = errorStore.list(null);
        assertEquals(1, unresolved.size());
        assertEquals("error again", unresolved.get(0).get("message"));
        assertEquals(1, unresolved.get(0).get("occurrenceCount"));
        assertEquals(1, errorStore.list("resolved").size());
    }

    @Test
    void differentRoutesSameTypeStaySeparateRows() {
        errorStore.record("RuntimeException", "error", "GET /a", "stack", "req");
        errorStore.record("RuntimeException", "error", "GET /b", "stack", "req");
        errorStore.flush();
        assertEquals(2, errorStore.list(null).size());
    }

    // --- The point of the tier: the upsert closes the duplicate-row race H2 can't show ---

    @Test
    void concurrentDuplicatesFoldIntoOneRowWithExactCount() throws Exception {
        // Post-H9, records coalesce in memory per store, so the DB-level race lives BETWEEN
        // instances: two stores (two simulated servers) buffer the same (type, route) and flush
        // concurrently. Without the atomic upsert + partial unique index, both flushes could pass
        // an existence check and INSERT, leaving duplicate rows and a lost count.
        int writers = 50;
        var storeB = new ErrorStore(factory, 1000);
        try {
            var threads = new ArrayList<Thread>();
            for (int i = 0; i < writers; i++) {
                int n = i;
                var store = (n % 2 == 0) ? errorStore : storeB;
                threads.add(Thread.startVirtualThread(() ->
                    store.record("RuntimeException", "boom-" + n, "GET /hot", "stack-" + n, "req-" + n)));
            }
            for (var t : threads) {
                t.join();
            }
            var flushA = Thread.startVirtualThread(errorStore::flush);
            var flushB = Thread.startVirtualThread(storeB::flush);
            flushA.join();
            flushB.join();
        } finally {
            storeB.close();
        }

        var errors = errorStore.list(null);
        assertEquals(1, errors.size(), "concurrent same-key records must fold into exactly one row");
        assertEquals(writers, errors.get(0).get("occurrenceCount"),
                "every concurrent record must be counted — no lost increment");

        // And the DB physically holds one row (belt-and-suspenders over the list() view).
        try (var c = connect(); var st = c.createStatement();
             var rs = st.executeQuery("SELECT COUNT(*) FROM ops_errors WHERE route = 'GET /hot'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "exactly one physical row for the hot key");
        }
    }

    @Test
    void concurrentDistinctRoutesEachGetTheirOwnRow() throws Exception {
        int routes = 40;
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < routes; i++) {
            int n = i;
            threads.add(Thread.startVirtualThread(() ->
                errorStore.record("RuntimeException", "boom", "GET /r" + n, "stack", "req")));
        }
        for (var t : threads) {
            t.join();
        }
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(routes, errors.size(), "distinct keys must not collide under concurrency");
        for (var e : errors) {
            assertEquals(1, e.get("occurrenceCount"));
            assertNotEquals(null, e.get("route"));
        }
    }
}

package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // --- M5 (2026-11 review): one poisoned entry must not stall the store ---

    @Test
    void nulBytesAreStrippedAndDoNotPoisonTheBatch() {
        // `GET /x?page=1%00` + req.queryInt("page") puts a NUL in the NumberFormatException
        // message and stack trace. pgjdbc rejects a NUL in a string parameter client-side, which
        // used to fail the whole flush, merge it back, and fail every later flush the same way.
        String nul = "\u0000";
        errorStore.record("NumberFormatException", "For input string: \"1" + nul + "\"",
                "GET /x", "java.lang.NumberFormatException: For input string: \"1" + nul + "\"\n\tat X.y(X.java:1)\n",
                "GET /x?page=1" + nul, "{\"count\":0}", "{\"user-agent\":\"a" + nul + "b\"}");
        errorStore.record("IllegalStateException", "healthy", "GET /healthy", "stack", "GET /healthy");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(2, errors.size(), "both kinds in the batch must be stored: " + errors);
        var poisoned = errors.stream().filter(e -> "NumberFormatException".equals(e.get("errorType")))
                .findFirst().orElseThrow();
        // Replaced with U+FFFD, not deleted: deleting would join already-redacted tokens.
        assertEquals("For input string: \"1�\"", poisoned.get("message"));
        assertEquals("GET /x?page=1�", poisoned.get("requestDetail"));
        assertEquals("{\"user-agent\":\"a�b\"}", poisoned.get("requestHeaders"));
        assertFalse(((String) poisoned.get("stackTrace")).contains(nul));

        // And the store keeps working afterwards.
        errorStore.record("LaterException", "later", "GET /later", "stack", "req");
        errorStore.flush();
        assertEquals(3, errorStore.list(null).size());
    }

    @Test
    void overlongTypeAndRouteAreClippedToTheColumnWidth() {
        // route is "METHOD /pattern"; the method is client-chosen, and both columns are VARCHAR(255).
        String longMethod = "X".repeat(400);
        errorStore.record("E".repeat(300), "m", longMethod + " /r", "s", "req");
        errorStore.record("Healthy", "m", "GET /ok", "s", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(2, errors.size(), "an over-long kind must not fail the batch: " + errors);
        for (var e : errors) {
            assertTrue(((String) e.get("errorType")).length() <= 255);
            assertTrue(((String) e.get("route")).length() <= 255);
        }
    }

    @Test
    void aRowThePostgresServerRejectsDoesNotAbortTheOthers() throws Exception {
        // A server-side error aborts the whole Postgres transaction ("current transaction is
        // aborted, commands ignored until end of transaction block"), so isolating a row needs a
        // savepoint, not just a catch. A CHECK constraint stands in for any row-specific rejection
        // sanitizing doesn't foresee.
        try (var c = connect(); var st = c.createStatement()) {
            st.execute("ALTER TABLE ops_errors ADD CONSTRAINT it_poison CHECK (message NOT LIKE '%POISON%')");
        }
        try {
            errorStore.record("A", "fine", "GET /a", "s", "req");
            errorStore.record("B", "POISON", "GET /b", "s POISON", "req POISON");
            errorStore.record("C", "fine", "GET /c", "s", "req");
            errorStore.flush();

            var errors = errorStore.list(null);
            var types = errors.stream().map(e -> e.get("errorType")).sorted().toList();
            assertEquals(List.of("A", "B", "C"), types, "B is kept by the payload-stripped retry");
            var b = errors.stream().filter(e -> "B".equals(e.get("errorType"))).findFirst().orElseThrow();
            assertEquals(ErrorStore.STRIPPED_MESSAGE, b.get("message"));
            assertEquals(null, b.get("stackTrace"));
            assertEquals(null, b.get("requestDetail"));
        } finally {
            try (var c = connect(); var st = c.createStatement()) {
                st.execute("ALTER TABLE ops_errors DROP CONSTRAINT IF EXISTS it_poison");
            }
        }
    }

    @Test
    void aKindTheServerAlwaysRejectsIsDroppedAfterMaxFlushFailures() throws Exception {
        // On error_type, which the stripped retry keeps, so both attempts are refused.
        try (var c = connect(); var st = c.createStatement()) {
            st.execute("ALTER TABLE ops_errors ADD CONSTRAINT it_poison_type CHECK (error_type <> 'B')");
        }
        try {
            errorStore.record("A", "fine", "GET /a", "s", "req");
            errorStore.record("B", "fine", "GET /b", "s", "req");
            errorStore.flush();
            assertEquals(List.of("A"), types());

            for (int i = 1; i < ErrorStore.MAX_FLUSH_FAILURES; i++) {
                errorStore.flush();
            }
            try (var c = connect(); var st = c.createStatement()) {
                st.execute("ALTER TABLE ops_errors DROP CONSTRAINT it_poison_type");
            }
            errorStore.flush();
            assertEquals(List.of("A"), types(), "the poisoned entry was dropped, not retried forever");
        } finally {
            try (var c = connect(); var st = c.createStatement()) {
                st.execute("ALTER TABLE ops_errors DROP CONSTRAINT IF EXISTS it_poison_type");
            }
        }
    }

    @Test
    void aServerSideStatementTimeoutOnOneRowIsARowRejectionNotAStall() throws Exception {
        // 57014 (query_canceled, e.g. statement_timeout on a huge row) is in a class the store
        // treats as connection-level. The connection is still fine, so it must count against the
        // row: before the review fix it aborted the flush and retained the batch, uncounted, forever.
        try (var c = connect(); var st = c.createStatement()) {
            st.execute("CREATE OR REPLACE FUNCTION it_cancel_huge() RETURNS trigger AS $$ BEGIN "
                + "IF NEW.error_type = 'Huge' THEN RAISE EXCEPTION 'canceling statement due to statement timeout' "
                + "USING ERRCODE = '57014'; END IF; RETURN NEW; END $$ LANGUAGE plpgsql");
            st.execute("CREATE TRIGGER it_cancel_huge BEFORE INSERT OR UPDATE ON ops_errors "
                + "FOR EACH ROW EXECUTE FUNCTION it_cancel_huge()");
        }
        try {
            errorStore.record("Huge", "x".repeat(10_000), "GET /huge", "s", "req");
            errorStore.record("Healthy", "fine", "GET /ok", "s", "req");
            errorStore.flush();
            assertEquals(List.of("Healthy"), types(), "the healthy kind commits on the same connection");

            for (int i = 1; i < ErrorStore.MAX_FLUSH_FAILURES; i++) {
                errorStore.flush();
            }
            try (var c = connect(); var st = c.createStatement()) {
                st.execute("DROP TRIGGER it_cancel_huge ON ops_errors");
            }
            errorStore.flush();
            assertEquals(List.of("Healthy"), types(), "counted and dropped, not retained forever");
        } finally {
            try (var c = connect(); var st = c.createStatement()) {
                st.execute("DROP TRIGGER IF EXISTS it_cancel_huge ON ops_errors");
                st.execute("DROP FUNCTION IF EXISTS it_cancel_huge()");
            }
        }
    }

    private List<Object> types() {
        return errorStore.list(null).stream().map(e -> e.get("errorType")).sorted().toList();
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

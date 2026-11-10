package com.larvalabs.brace;

import com.larvalabs.brace.testmodels.Post;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ErrorStoreTest {

    static DatabaseFactory dbFactory;
    static ErrorStore errorStore;

    @BeforeAll
    static void setup() {
        dbFactory = new DatabaseFactory(
            "jdbc:h2:mem:errorstoredb;DB_CLOSE_DELAY=-1", null, null,
            List.of(Post.class));
    }

    @AfterAll
    static void teardown() {
        dbFactory.close();
    }

    @BeforeEach
    void cleanTable() {
        var db = new Database(dbFactory.openSession());
        db.beginTransaction();
        db.sql("DELETE FROM ops_errors");
        db.commitTransaction();
        db.close();
        errorStore = new ErrorStore(dbFactory, 1000);
    }

    @AfterEach
    void closeStore() {
        errorStore.close();
    }

    @Test
    void recordsCoalescePerKindWithinOneFlush() {
        // H9: a storm of one kind is a single upsert carrying the coalesced count + latest payload.
        for (int i = 1; i <= 50; i++) {
            errorStore.record("RuntimeException", "msg " + i, "GET /storm", "stack " + i, "req");
        }
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        assertEquals(50, errors.get(0).get("occurrenceCount"));
        assertEquals("msg 50", errors.get(0).get("message"), "latest payload wins");
        assertEquals("stack 50", errors.get(0).get("stackTrace"));
    }

    @Test
    void newKindsBeyondPendingCapAreDroppedKnownKindsAreNot() {
        // Fill the pending buffer with MAX_PENDING_KINDS distinct kinds, then push more.
        for (int i = 0; i < ErrorStore.MAX_PENDING_KINDS; i++) {
            errorStore.record("E" + i, "m", "GET /r" + i, "s", "req");
        }
        // A new kind past the cap is dropped...
        errorStore.record("Overflow", "m", "GET /overflow", "s", "req");
        // ...but more occurrences of a buffered kind still count.
        errorStore.record("E0", "m2", "GET /r0", "s2", "req");
        errorStore.flush();

        // since-filtered: an unfiltered list is capped at LIST_LIMIT, below MAX_PENDING_KINDS.
        var errors = errorStore.list(null, java.time.Instant.EPOCH);
        assertEquals(ErrorStore.MAX_PENDING_KINDS, errors.size(), "overflow kind must not appear");
        for (var e : errors) {
            assertNotEquals("Overflow", e.get("errorType"));
            if ("E0".equals(e.get("errorType"))) {
                assertEquals(2, e.get("occurrenceCount"), "known-kind occurrences are never dropped");
            }
        }
    }

    @Test
    void stackTraceMessagesAreRedactedInTheStoredRow() {
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String trace = "java.lang.RuntimeException: outer\n"
            + "\tat com.example.Foo.bar(Foo.java:12)\n"
            + "Caused by: java.lang.IllegalStateException: bad key " + secret + "\n"
            + "\tat com.example.Baz.qux(Baz.java:7)\n";
        errorStore.record("RuntimeException", "outer", "GET /test", trace, "GET /test");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        String stored = (String) errors.get(0).get("stackTrace");
        assertFalse(stored.contains(secret), "secret in a Caused by: message must not be persisted");
        assertEquals(trace.replace(secret, "[redacted]"), stored, "frame lines are unchanged");
    }

    // --- M5 (2026-11 review): one bad entry must not stall the store ---

    @Test
    void nulIsReplacedSoAlreadyRedactedTokensAreNotJoined() {
        // H2 stores a NUL happily; Postgres can't (see ErrorStorePostgresIT). Replacing it with
        // U+FFFD rather than deleting keeps `abc\0def` (two short tokens to the redactor that ran
        // upstream) from becoming one long secret-shaped token in the stored row.
        errorStore.record("NumberFormatException", "For input string: \"abc\u0000def\"", "GET /x",
            "java.lang.NumberFormatException: abc\u0000def\n", "GET /x?page=abc\u0000def",
            "{\"count\":0}", "{\"x-h\":\"a\u0000b\"}");
        errorStore.flush();

        var err = errorStore.list(null).get(0);
        assertEquals("For input string: \"abc�def\"", err.get("message"));
        assertEquals("GET /x?page=abc�def", err.get("requestDetail"));
        assertEquals("{\"x-h\":\"a�b\"}", err.get("requestHeaders"));
        assertFalse(((String) err.get("stackTrace")).contains("\u0000"));
    }

    @Test
    void overlongTypeAndRouteAreClippedNotRejected() {
        errorStore.record("E".repeat(300), "m", "X".repeat(400) + " /r", "s", "req");
        errorStore.record("Healthy", "m", "GET /ok", "s", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(2, errors.size());
        for (var e : errors) {
            assertTrue(((String) e.get("errorType")).length() <= ErrorStore.MAX_KEY_LENGTH);
            assertTrue(((String) e.get("route")).length() <= ErrorStore.MAX_KEY_LENGTH);
        }
        // A surrogate pair straddling the limit is dropped whole, not split.
        String clipped = ErrorStore.clip("a".repeat(254) + "😀");
        assertEquals(254, clipped.length());
    }

    @Test
    void anEntryTheDatabaseKeepsRejectingIsDroppedAndLoggedOthersCommit() {
        // A CHECK constraint stands in for any row-level rejection that sanitizing doesn't foresee.
        // It is on error_type, which the payload-stripped retry keeps, so the retry fails too.
        sql("ALTER TABLE ops_errors ADD CONSTRAINT poison_check CHECK (error_type NOT LIKE '%Poison%')");
        LogTap.clear();
        try {
            errorStore.record("PoisonException", "POISON secret-payload", "GET /p", "s", "req");
            errorStore.record("Healthy", "fine", "GET /ok", "s", "req");
            errorStore.flush();
            assertEquals(List.of("Healthy"), types(), "the healthy kind commits despite its neighbour");

            for (int i = 1; i < ErrorStore.MAX_FLUSH_FAILURES; i++) {
                errorStore.flush();
            }
            sql("ALTER TABLE ops_errors DROP CONSTRAINT poison_check");
            errorStore.flush();
            assertEquals(List.of("Healthy"), types(), "dropped after MAX_FLUSH_FAILURES, not retried forever");
        } finally {
            sql("ALTER TABLE ops_errors DROP CONSTRAINT IF EXISTS poison_check");
        }

        var warnings = errorStoreWarnings();
        assertEquals(1, warnings.size(), "rate-limited to one line per interval: " + warnings);
        assertTrue(warnings.get(0).contains("database rejected"), warnings.get(0));
        assertFalse(warnings.get(0).contains("secret-payload"), "request-derived content is never logged");
        // The drop happened after the first (immediate) warning, inside the rate-limit window, so
        // it is reported by the next line once the window passes.
        assertFalse(warnings.get(0).contains("dropped"));
    }

    @Test
    void dropIsLoggedWhenTheWindowAllows() {
        sql("ALTER TABLE ops_errors ADD CONSTRAINT poison_check2 CHECK (error_type NOT LIKE '%Poison%')");
        LogTap.clear();
        try {
            errorStore.record("PoisonException", "POISON", "GET /p", "s", "req");
            for (int i = 0; i < ErrorStore.MAX_FLUSH_FAILURES; i++) {
                errorStore.flush();
            }
            LogTap.clear();
            // Next flush is past the window (simulated): the summary names the dropped kind.
            errorStore.expireFailureLogWindow();
            errorStore.flush();
        } finally {
            sql("ALTER TABLE ops_errors DROP CONSTRAINT IF EXISTS poison_check2");
        }
        var warnings = errorStoreWarnings();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("dropped 1 error kind(s)"), warnings.get(0));
        assertTrue(warnings.get(0).contains("PoisonException"), warnings.get(0));
    }

    @Test
    void anUnreachableDatabaseKeepsEntriesAndRetries() {
        // DB down / pool exhausted: not any entry's fault. Entries must survive any number of
        // failed flushes (bounded by kind cardinality, as before) and land once it's back.
        var real = errorStore.flushSession;
        LogTap.clear();
        errorStore.flushSession = () -> {
            throw new org.hibernate.exception.JDBCConnectionException("Unable to acquire JDBC Connection",
                new java.sql.SQLTransientConnectionException("Connection is not available", "08001"));
        };
        errorStore.record("RuntimeException", "first", "GET /down", "s", "req");
        for (int i = 0; i < ErrorStore.MAX_FLUSH_FAILURES + 3; i++) {
            errorStore.flush();
        }
        errorStore.record("RuntimeException", "second", "GET /down", "s", "req");
        errorStore.flush();

        errorStore.flushSession = real;
        errorStore.flush();
        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        assertEquals(2, errors.get(0).get("occurrenceCount"), "both occurrences survived the outage");
        assertEquals("second", errors.get(0).get("message"));

        var warnings = errorStoreWarnings();
        assertEquals(1, warnings.size(), "rate-limited: " + warnings);
        assertTrue(warnings.get(0).contains("kept for retry"), warnings.get(0));
        assertTrue(warnings.get(0).contains("SQLState 08001"), warnings.get(0));
    }

    @Test
    void connectionFailureClassification() {
        assertTrue(ErrorStore.isConnectionFailure(new java.sql.SQLException("x", "08006")));
        assertTrue(ErrorStore.isConnectionFailure(new java.sql.SQLException("x", "57P01")));
        assertTrue(ErrorStore.isConnectionFailure(new java.sql.SQLException("x", "40P01")));
        assertTrue(ErrorStore.isConnectionFailure(new java.sql.SQLTransientConnectionException("x")));
        assertFalse(ErrorStore.isConnectionFailure(new java.sql.SQLException("x", "22023")), "pgjdbc's NUL rejection");
        assertFalse(ErrorStore.isConnectionFailure(new java.sql.SQLException("x", "22001")), "value too long");
        assertFalse(ErrorStore.isConnectionFailure(new java.sql.SQLException("x", "23514")), "check violation");
        assertFalse(ErrorStore.isConnectionFailure(new java.sql.SQLException("x")));
    }

    // --- M5 review fixes ---

    @Test
    void aRetainedClassRowErrorOnALiveConnectionIsARowRejection() {
        // 57014 (statement_timeout on a huge row) is in a "connection-level" SQLState class, but
        // the connection is fine: it must count against the entry, not abort and retain the batch
        // forever (the original stall). The fault fires on the stripped retry too.
        errorStore.rowFault = (type, message) -> {
            if ("HugeRowException".equals(type)) {
                throw new java.sql.SQLException("canceling statement due to statement timeout", "57014");
            }
        };
        errorStore.record("HugeRowException", "x".repeat(1000), "GET /huge", "s", "req");
        errorStore.record("Healthy", "fine", "GET /ok", "s", "req");
        errorStore.flush();
        assertEquals(List.of("Healthy"), types(), "the healthy kind commits despite its neighbour");

        for (int i = 1; i < ErrorStore.MAX_FLUSH_FAILURES; i++) {
            errorStore.flush();
        }
        errorStore.rowFault = null;
        errorStore.flush();
        assertEquals(List.of("Healthy"), types(), "dropped after MAX_FLUSH_FAILURES, not retained forever");
    }

    @Test
    void aConnectionClassedErrorOnADeadConnectionRetainsTheBatchUncounted() {
        errorStore.rowFault = (type, message) -> {
            throw new java.sql.SQLException("An I/O error occurred while sending to the backend", "08006");
        };
        errorStore.connectionCheck = connection -> false;
        errorStore.record("RuntimeException", "first", "GET /down", "s", "req");
        errorStore.record("Healthy", "fine", "GET /ok", "s", "req");
        for (int i = 0; i < ErrorStore.MAX_FLUSH_FAILURES + 3; i++) {
            errorStore.flush();
        }
        assertEquals(List.of(), types(), "nothing commits while the connection is dead");

        errorStore.rowFault = null;
        errorStore.connectionCheck = connection -> connection.isValid(1);
        errorStore.flush();
        assertEquals(List.of("Healthy", "RuntimeException"), types(), "kept, not counted or dropped");
    }

    @Test
    void aRejectedRowIsRetriedOnceWithoutItsPayload() {
        // e.g. a value the database refuses in the message, trace or request detail. The kind's
        // count and the regression signal must survive; the payload is replaced by fixed text.
        var seen = new java.util.ArrayList<String>();
        errorStore.setRegressionListener(new ErrorStore.RegressionListener() {
            public void onNew(String type, String route, String message, java.time.Instant firstSeen) {
                seen.add(type + ": " + message);
            }
            public void onRepeat(String type, String route, long count) { }
        });
        errorStore.rowFault = (type, message) -> {
            if ("BigPayloadException".equals(type) && !ErrorStore.STRIPPED_MESSAGE.equals(message)) {
                throw new java.sql.SQLException("value too long", "22001");
            }
        };
        errorStore.record("BigPayloadException", "request text token=abc", "GET /big",
            "trace with request text", "GET /big?q=request-text", "{\"count\":1}", "{\"x-h\":\"request-text\"}");
        errorStore.record("BigPayloadException", "request text again", "GET /big", "trace", "detail");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size(), errors.toString());
        var err = errors.get(0);
        assertEquals("BigPayloadException", err.get("errorType"));
        assertEquals("GET /big", err.get("route"));
        assertEquals(2, err.get("occurrenceCount"), "the count survives");
        assertEquals(ErrorStore.STRIPPED_MESSAGE, err.get("message"));
        assertNull(err.get("stackTrace"));
        assertNull(err.get("requestDetail"));
        assertNull(err.get("queriesBefore"));
        assertNull(err.get("requestHeaders"));
        assertEquals(List.of("BigPayloadException: " + ErrorStore.STRIPPED_MESSAGE), seen,
            "the regression signal fires, with the placeholder rather than the rejected payload");
        assertFalse(ErrorStore.STRIPPED_MESSAGE.contains("request"), "fixed text only");

        // Nothing left pending: a later flush with the fault gone adds nothing.
        errorStore.rowFault = null;
        errorStore.flush();
        assertEquals(2, errorStore.list(null).get(0).get("occurrenceCount"));
    }

    // --- Final review fixes (docs/2026-11-09-security-review-todos.md, M5) ---

    @Test
    void aTransientRowErrorIsNotRetriedStrippedAndNeverOverwritesStoredDetails() {
        // L9: a 55P03 lock timeout on the first attempt, then the payload-stripped retry succeeded
        // and overwrote the existing row's message, trace, detail and headers with the placeholder.
        errorStore.record("LockedException", "first details", "GET /l", "first trace", "GET /l?a=1",
            "{\"count\":1}", "{\"x-h\":\"first\"}");
        errorStore.flush();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        errorStore.rowFault = (type, message) -> {
            if ("LockedException".equals(type) && attempts.getAndIncrement() == 0) {
                throw new java.sql.SQLException("canceling statement due to lock timeout", "55P03");
            }
        };
        errorStore.record("LockedException", "second details", "GET /l", "second trace", "GET /l?a=2",
            "{\"count\":2}", "{\"x-h\":\"second\"}");
        errorStore.record("Healthy", "fine", "GET /ok", "s", "req");
        errorStore.flush();
        var locked = find("LockedException");
        assertEquals("first details", locked.get("message"), "never overwritten with the placeholder");
        assertEquals("first trace", locked.get("stackTrace"));
        assertEquals("{\"x-h\":\"first\"}", locked.get("requestHeaders"));
        assertEquals(1, locked.get("occurrenceCount"));

        errorStore.flush(); // the lock is gone: the full payload lands
        locked = find("LockedException");
        assertEquals("second details", locked.get("message"));
        assertEquals("second trace", locked.get("stackTrace"));
        assertEquals(2, locked.get("occurrenceCount"));
        errorStore.rowFault = null;
    }

    @Test
    void aStrippedWriteOnlyCountsWhenTheRowExists() {
        // L9: a data-class rejection (22001 value too long) is retried without its payload; when
        // the kind already has a row, the retry adds the count and keeps the stored details.
        errorStore.record("BigPayloadException", "stored details", "GET /big", "stored trace", "GET /big",
            "{\"count\":1}", "{\"x-h\":\"stored\"}");
        errorStore.flush();
        errorStore.rowFault = (type, message) -> {
            if ("BigPayloadException".equals(type) && !ErrorStore.STRIPPED_MESSAGE.equals(message)) {
                throw new java.sql.SQLException("value too long", "22001");
            }
        };
        errorStore.record("BigPayloadException", "x".repeat(100), "GET /big", "huge trace", "detail");
        errorStore.flush();
        var big = find("BigPayloadException");
        assertEquals(2, big.get("occurrenceCount"), "the count survives");
        assertEquals("stored details", big.get("message"), "existing details are kept, not replaced");
        assertEquals("stored trace", big.get("stackTrace"));
        assertEquals("{\"x-h\":\"stored\"}", big.get("requestHeaders"));
        errorStore.rowFault = null;
    }

    @Test
    void onlyDataClassRejectionsAreRetriedStripped() {
        // A rejection with no SQLState, or a non-data one, is not retried without its payload:
        // it is counted like any rejection and retried in full on the next flush.
        var seenMessages = new java.util.ArrayList<String>();
        errorStore.rowFault = (type, message) -> {
            if ("Odd".equals(type)) {
                seenMessages.add(message);
                throw new java.sql.SQLException("driver said no");
            }
        };
        errorStore.record("Odd", "payload", "GET /odd", "s", "req");
        errorStore.record("Healthy", "fine", "GET /ok", "s", "req");
        errorStore.flush();
        assertEquals(List.of("payload"), seenMessages, "no stripped retry");
        assertEquals(List.of("Healthy"), types());
        errorStore.rowFault = null;
        errorStore.flush();
        assertEquals("payload", find("Odd").get("message"));
    }

    @Test
    void aReadOnlyWindowKeepsEveryKindUncounted() {
        // L10: SQLState 25006 (read-only transaction, e.g. during a failover) on a connection that
        // still passes isValid counted against every row, so five flushes dropped every kind.
        for (String state : List.of("25006", "53100", "53300", "57P01", "57P03", "58030")) {
            errorStore.close();
            cleanTable();
            errorStore.rowFault = (type, message) -> {
                throw new java.sql.SQLException("server-wide failure " + state, state);
            };
            errorStore.record("A", "a", "GET /a", "s", "req");
            errorStore.record("B", "b", "GET /b", "s", "req");
            for (int i = 0; i < ErrorStore.MAX_FLUSH_FAILURES + 3; i++) {
                errorStore.flush();
            }
            errorStore.record("Lone", "lone", "GET /lone", "s", "req");
            errorStore.rowFault = null;
            errorStore.flush();
            assertEquals(List.of("A", "B", "Lone"), types(), "kept uncounted through the window: " + state);
        }
    }

    @Test
    void aSingleServerWideFailureIsNotBlamedOnALoneRow() {
        // A one-kind batch in a read-only window is still not the row's fault.
        errorStore.rowFault = (type, message) -> {
            throw new java.sql.SQLException("cannot execute INSERT in a read-only transaction", "25006");
        };
        errorStore.record("Lone", "lone", "GET /lone", "s", "req");
        for (int i = 0; i < ErrorStore.MAX_FLUSH_FAILURES + 3; i++) {
            errorStore.flush();
        }
        errorStore.rowFault = null;
        errorStore.flush();
        assertEquals(List.of("Lone"), types());
    }

    @Test
    void rowsAlwaysFailingWithOneStateThatIsNotServerWideAreCountedAndDropped() {
        // Verification pass: L10 kept a batch whose rows all failed with one non-data SQLState
        // uncounted, with no bound, so two rows that always fail that way were retried forever.
        // Only a server-wide state (read-only, out of resources, shutdown, connection) is the
        // database's; a lock timeout on every row is counted like any row rejection.
        errorStore.rowFault = (type, message) -> {
            throw new java.sql.SQLException("canceling statement due to lock timeout", "55P03");
        };
        errorStore.record("A", "a", "GET /a", "s", "req");
        errorStore.record("B", "b", "GET /b", "s", "req");
        for (int i = 0; i < ErrorStore.MAX_FLUSH_FAILURES; i++) {
            errorStore.flush();
        }
        errorStore.rowFault = null;
        errorStore.flush();
        assertEquals(List.of(), types(), "dropped after MAX_FLUSH_FAILURES, not retried forever");
    }

    @Test
    void aFullBufferOfAlwaysFailingKindsDoesNotLockOutANewKind() {
        // 1,000 such kinds filled the pending buffer for good, so a healthy new kind was dropped
        // at record().
        errorStore.rowFault = (type, message) -> {
            if (type.startsWith("Stuck")) {
                throw new java.sql.SQLException("canceling statement due to lock timeout", "55P03");
            }
        };
        for (int i = 0; i < ErrorStore.MAX_PENDING_KINDS; i++) {
            errorStore.record("Stuck" + i, "m", "GET /s" + i, "s", "req");
        }
        for (int i = 0; i < ErrorStore.MAX_FLUSH_FAILURES; i++) {
            errorStore.flush();
        }
        errorStore.record("Healthy", "fine", "GET /ok", "s", "req");
        errorStore.flush();
        assertEquals(List.of("Healthy"), types());
    }

    @Test
    void anOutageRetainsEntriesUpToTheCapThenDropsThemWithOneWarning() {
        var real = errorStore.flushSession;
        java.util.function.Supplier<Database> down = () -> {
            throw new org.hibernate.exception.JDBCConnectionException("Unable to acquire JDBC Connection",
                new java.sql.SQLTransientConnectionException("Connection is not available", "08001"));
        };
        // While the outage lasts up to the cap, everything is kept.
        errorStore.flushSession = down;
        errorStore.record("Kept", "k", "GET /k", "s", "req");
        for (int i = 0; i < ErrorStore.MAX_UNCOUNTED_RETENTIONS; i++) {
            errorStore.flush();
        }
        errorStore.flushSession = real;
        errorStore.flush();
        assertEquals(List.of("Kept"), types(), "kept through an outage as long as the cap");

        // Past the cap, an entry is counted like a row rejection and dropped; a kind that arrived
        // later is still kept.
        errorStore.close();
        cleanTable();
        LogTap.clear();
        errorStore.flushSession = down;
        errorStore.record("Old", "secret-payload", "GET /old", "s", "req");
        for (int i = 0; i < ErrorStore.MAX_UNCOUNTED_RETENTIONS + ErrorStore.MAX_FLUSH_FAILURES - 1; i++) {
            errorStore.flush();
        }
        errorStore.record("New", "n", "GET /new", "s", "req");
        errorStore.expireFailureLogWindow();
        errorStore.flush();
        errorStore.flushSession = real;
        errorStore.flush();
        assertEquals(List.of("New"), types());
        var dropWarnings = errorStoreWarnings().stream().filter(w -> w.contains("dropped")).toList();
        assertEquals(1, dropWarnings.size(), errorStoreWarnings().toString());
        assertTrue(dropWarnings.get(0).contains("dropped 1 error kind(s)") && dropWarnings.get(0).contains("Old"),
            dropWarnings.get(0));
        assertFalse(dropWarnings.get(0).contains("secret-payload"));
    }

    @Test
    void newKindsArrivingWhileAFlushWaitsOnADeadDatabaseDoNotGrowTheBufferPastTheCap() {
        // Second review: flushBatch drained `pending`, then blocked opening a session. Meanwhile
        // record() saw an empty map and admitted another MAX_PENDING_KINDS kinds, and the failed
        // batch was merged back on top with no cap check: 1,000 more per flush, 65,000 held by
        // the time the outage cap dropped the first ones.
        var real = errorStore.flushSession;
        int[] minted = {0};
        int[] worst = {0};
        errorStore.flushSession = () -> {
            // What the request threads do while the flusher waits for a connection.
            for (int i = 0; i < ErrorStore.MAX_PENDING_KINDS; i++) {
                int n = minted[0]++;
                errorStore.record("Storm" + n, "m", "GET /storm" + n, "s", "req");
            }
            // A kind that is in the batch being flushed recurs: a counter bump, never dropped.
            errorStore.record("First", "again", "GET /first", "s", "req");
            worst[0] = Math.max(worst[0], errorStore.bufferedKinds());
            throw new org.hibernate.exception.JDBCConnectionException("Unable to acquire JDBC Connection",
                new java.sql.SQLTransientConnectionException("Connection is not available", "08001"));
        };
        errorStore.record("First", "first", "GET /first", "s", "req");
        int flushes = 8;
        for (int i = 0; i < flushes; i++) {
            errorStore.flush();
            assertTrue(errorStore.pendingKinds() <= ErrorStore.MAX_PENDING_KINDS,
                "after failed flush " + (i + 1) + ": " + errorStore.pendingKinds() + " kinds pending");
        }
        assertTrue(worst[0] <= ErrorStore.MAX_PENDING_KINDS,
            "during a flush, pending plus in flight: " + worst[0] + " kinds");

        errorStore.flushSession = real;
        errorStore.flush();
        var stored = errorStore.list(null, java.time.Instant.EPOCH);
        assertEquals(ErrorStore.MAX_PENDING_KINDS, stored.size());
        // The kinds buffered first are the ones kept: the oldest entry and its whole count survive.
        var first = stored.stream().filter(e -> "First".equals(e.get("errorType"))).findFirst().orElseThrow();
        assertEquals(1 + flushes, first.get("occurrenceCount"));
        assertTrue(stored.stream().anyMatch(e -> "Storm0".equals(e.get("errorType"))));
        assertFalse(stored.stream().anyMatch(e -> ("Storm" + ErrorStore.MAX_PENDING_KINDS).equals(e.get("errorType"))));
        // Nothing is left in flight, so a new kind is admitted again.
        assertEquals(0, errorStore.bufferedKinds());
        errorStore.record("After", "a", "GET /after", "s", "req");
        errorStore.flush();
        assertTrue(errorStore.list(null, java.time.Instant.EPOCH).stream()
            .anyMatch(e -> "After".equals(e.get("errorType"))));
        assertEquals(0, errorStore.bufferedKinds());
    }

    @Test
    void oneRowDrawingAServerWideStateDoesNotStarveTheHealthyRowsBesideIt() {
        // Second review: a server-wide SQLState on any row aborted the flush, so one row that
        // always draws one (an oversized row and 53100 disk full, 53200 out of memory) kept every
        // healthy row beside it out of the table for as long as it stayed buffered (0 of 400).
        for (String state : List.of("53100", "53200", "58030", "08P01")) {
            errorStore.close();
            cleanTable();
            errorStore.rowFault = (type, message) -> {
                if (type.startsWith("Poison")) throw new java.sql.SQLException("server-wide failure " + state, state);
            };
            // Several poisoned rows, so some come before and some after a healthy row in the batch.
            for (int i = 0; i < 400; i++) {
                errorStore.record("Healthy" + i, "m", "GET /h" + i, "s", "req");
                if (i % 50 == 0) errorStore.record("Poison" + i, "m", "GET /p" + i, "s", "req");
            }
            errorStore.flush();
            var stored = errorStore.list(null, java.time.Instant.EPOCH);
            assertEquals(400, stored.size(), "healthy rows stored on the first flush: " + state);
            assertEquals(8, errorStore.bufferedKinds(), "the poisoned rows are kept for retry: " + state);

            // The poisoned rows are counted, like any row rejection, while a healthy row is written
            // beside them, and dropped after MAX_FLUSH_FAILURES such flushes.
            for (int i = 1; i < ErrorStore.MAX_FLUSH_FAILURES; i++) {
                errorStore.record("Healthy0", "m", "GET /h0", "s", "req");
                errorStore.flush();
            }
            assertEquals(0, errorStore.bufferedKinds(), "poisoned rows dropped: " + state);
            errorStore.rowFault = null;
            errorStore.flush();
            assertEquals(400, errorStore.list(null, java.time.Instant.EPOCH).size());
        }
    }

    @Test
    void anOutageThatStartsMidBatchKeepsTheWholeBatchUncounted() {
        // The other side of the rule above: rows written, then the server goes away. The next
        // statement on the dead connection fails the flush, nothing commits, and no row is
        // counted, however many flushes the outage lasts (up to the uncounted cap).
        int[] calls = {0};
        errorStore.rowFault = (type, message) -> {
            if (++calls[0] > 3) throw new java.sql.SQLException("terminating connection", "57P01");
        };
        errorStore.connectionCheck = connection -> false;
        for (int i = 0; i < 10; i++) {
            errorStore.record("E" + i, "m", "GET /e" + i, "s", "req");
        }
        for (int i = 0; i < ErrorStore.MAX_FLUSH_FAILURES + 3; i++) {
            calls[0] = 0;
            errorStore.flush();
            assertEquals(List.of(), types(), "nothing commits from an aborted flush");
        }
        errorStore.rowFault = null;
        errorStore.connectionCheck = connection -> connection.isValid(1);
        errorStore.flush();
        assertEquals(10, types().size());
    }

    @Test
    void aServerThatStopsTakingWritesMidBatchOnALiveConnectionDropsNothingEarly() {
        // Disk fills up mid-batch and the connection stays usable: the rows after that point are
        // counted once (rows before them were written in the same flush). Every later flush of
        // the outage writes nothing, so it is kept uncounted, and they are stored when it ends.
        boolean[] full = {false};
        int[] calls = {0};
        errorStore.rowFault = (type, message) -> {
            if (full[0] || ++calls[0] > 3) {
                full[0] = true;
                throw new java.sql.SQLException("could not extend file: No space left on device", "53100");
            }
        };
        for (int i = 0; i < 10; i++) {
            errorStore.record("E" + i, "m", "GET /e" + i, "s", "req");
        }
        errorStore.flush();
        assertEquals(3, types().size());
        for (int i = 0; i < ErrorStore.MAX_FLUSH_FAILURES + 3; i++) {
            errorStore.flush();
        }
        assertEquals(7, errorStore.bufferedKinds(), "kept through the outage");
        errorStore.rowFault = null;
        errorStore.flush();
        assertEquals(10, types().size());
    }

    private static java.util.Map<String, Object> find(String type) {
        return errorStore.list(null).stream().filter(e -> type.equals(e.get("errorType"))).findFirst().orElseThrow();
    }

    private static void sql(String statement) {
        var db = new Database(dbFactory.openSession());
        db.beginTransaction();
        db.sql(statement);
        db.commitTransaction();
        db.close();
    }

    private static List<Object> types() {
        return errorStore.list(null).stream().map(e -> e.get("errorType")).sorted().toList();
    }

    private static List<String> errorStoreWarnings() {
        return LogTap.snapshot().stream()
            .filter(e -> "WARN".equals(e.fields().get("level")))
            .map(e -> String.valueOf(e.fields().get("message")))
            .filter(m -> m.startsWith("error store:"))
            .toList();
    }

    @Test
    void recordCreatesRow() {
        errorStore.record("RuntimeException", "test error", "GET /test", "stack...", "GET /test");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        assertEquals("RuntimeException", errors.get(0).get("errorType"));
        assertEquals("test error", errors.get(0).get("message"));
        assertEquals("GET /test", errors.get(0).get("route"));
        assertEquals(1, errors.get(0).get("occurrenceCount"));
    }

    @Test
    void recordCapturesQueriesBeforeAndRequestHeaders() {
        errorStore.record("RuntimeException", "boom", "GET /test", "stack",
            "GET /test", "{\"count\":3,\"durationMs\":12.4}",
            "{\"user-agent\":\"curl/8\",\"authorization\":\"[REDACTED]\"}");
        errorStore.flush();

        var err = errorStore.list(null).get(0);
        assertEquals("{\"count\":3,\"durationMs\":12.4}", err.get("queriesBefore"));
        String headers = (String) err.get("requestHeaders");
        assertNotNull(headers);
        assertTrue(headers.contains("curl/8"));
        assertTrue(headers.contains("[REDACTED]"), "sensitive headers must already be redacted at capture");
    }

    @Test
    void legacyFiveArgRecordLeavesContextNull() {
        errorStore.record("RuntimeException", "boom", "GET /test", "stack", "GET /test");
        errorStore.flush();
        var err = errorStore.list(null).get(0);
        assertNull(err.get("queriesBefore"));
        assertNull(err.get("requestHeaders"));
    }

    @Test
    void duplicateErrorsIncrementCount() {
        errorStore.record("RuntimeException", "error 1", "GET /test", "stack1", "req1");
        errorStore.flush();
        errorStore.record("RuntimeException", "error 2", "GET /test", "stack2", "req2");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        assertEquals(2, errors.get(0).get("occurrenceCount"));
        // Should have the latest message and stack
        assertEquals("error 2", errors.get(0).get("message"));
        assertEquals("stack2", errors.get(0).get("stackTrace"));
    }

    @Test
    void resolvedErrorsGetNewRowsOnRecurrence() {
        errorStore.record("RuntimeException", "error", "GET /test", "stack", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(1, errors.size());
        long id = ((Number) errors.get(0).get("id")).longValue();

        // Resolve it
        errorStore.resolve(id);

        // Record same error again - should create new row since previous is resolved
        errorStore.record("RuntimeException", "error again", "GET /test", "stack2", "req2");
        errorStore.flush();

        var unresolved = errorStore.list(null);
        assertEquals(1, unresolved.size());
        assertEquals("error again", unresolved.get(0).get("message"));
        assertEquals(1, unresolved.get(0).get("occurrenceCount"));

        var resolved = errorStore.list("resolved");
        assertEquals(1, resolved.size());
    }

    @Test
    void listReturnsUnresolvedByDefault() {
        errorStore.record("RuntimeException", "unresolved", "GET /a", "stack", "req");
        errorStore.flush();
        errorStore.record("NullPointerException", "to-resolve", "GET /b", "stack", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(2, errors.size());

        // Find the NullPointerException and resolve it
        long npeId = 0;
        for (var e : errors) {
            if ("NullPointerException".equals(e.get("errorType"))) {
                npeId = ((Number) e.get("id")).longValue();
                break;
            }
        }
        errorStore.resolve(npeId);

        var unresolved = errorStore.list(null);
        assertEquals(1, unresolved.size());
        assertEquals("RuntimeException", unresolved.get(0).get("errorType"));

        // status=unresolved should also return unresolved
        var unresolvedExplicit = errorStore.list("unresolved");
        assertEquals(1, unresolvedExplicit.size());
    }

    @Test
    void listWithStatusResolvedReturnsResolved() {
        errorStore.record("RuntimeException", "error", "GET /test", "stack", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        long id = ((Number) errors.get(0).get("id")).longValue();
        errorStore.resolve(id);

        var resolved = errorStore.list("resolved");
        assertEquals(1, resolved.size());
        assertNotNull(resolved.get(0).get("resolvedAt"));
    }

    @Test
    void resolveSetsResolvedAt() {
        errorStore.record("RuntimeException", "error", "GET /test", "stack", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        long id = ((Number) errors.get(0).get("id")).longValue();

        var resolved = errorStore.resolve(id);
        assertNotNull(resolved);
        assertNotNull(resolved.get("resolvedAt"));
        assertEquals(id, ((Number) resolved.get("id")).longValue());
    }

    @Test
    void resolveReturnsSameShapeAsFind() {
        // c941ccc: resolve() maps its re-fetch through the shared mapRow, so its result has
        // exactly find()'s fields — a hand-built copy once drifted and omitted
        // queriesBefore/requestHeaders. Compare key sets and values, not a few fields.
        errorStore.record("RuntimeException", "error", "GET /test", "stack", "req",
            "{\"count\":2}", "{\"Accept\":\"text/html\"}");
        errorStore.flush();
        long id = ((Number) errorStore.list(null).get(0).get("id")).longValue();

        var resolved = errorStore.resolve(id);
        var found = errorStore.find(id);
        assertNotNull(resolved);
        assertEquals(List.copyOf(found.keySet()), List.copyOf(resolved.keySet()));
        assertEquals(found, resolved);
    }

    @Test
    void pruningRemovesOldestResolvedFirst() {
        var smallStore = new ErrorStore(dbFactory, 3);

        // Insert 3 errors
        smallStore.record("Error1", "msg1", "GET /a", "stack", "req");
        smallStore.flush();
        smallStore.record("Error2", "msg2", "GET /b", "stack", "req");
        smallStore.flush();
        smallStore.record("Error3", "msg3", "GET /c", "stack", "req");
        smallStore.flush();

        // Resolve the first one
        var errors = smallStore.list(null);
        assertEquals(3, errors.size());

        // Find Error1 and resolve it
        long error1Id = 0;
        for (var e : errors) {
            if ("Error1".equals(e.get("errorType"))) {
                error1Id = ((Number) e.get("id")).longValue();
                break;
            }
        }
        smallStore.resolve(error1Id);

        // Insert a 4th error - should prune the resolved one (Error1)
        smallStore.record("Error4", "msg4", "GET /d", "stack", "req");
        smallStore.flush();

        var allUnresolved = smallStore.list(null);
        var allResolved = smallStore.list("resolved");

        // Error1 was resolved and should be pruned
        assertEquals(0, allResolved.size());
        assertEquals(3, allUnresolved.size());

        // Verify Error1 is gone
        for (var e : allUnresolved) {
            assertNotEquals("Error1", e.get("errorType"));
        }
    }

    @Test
    void differentRoutesSameTypeCreateSeparateRows() {
        errorStore.record("RuntimeException", "error", "GET /a", "stack", "req");
        errorStore.flush();
        errorStore.record("RuntimeException", "error", "GET /b", "stack", "req");
        errorStore.flush();

        var errors = errorStore.list(null);
        assertEquals(2, errors.size());
    }

    @Test
    void listSinceReturnsOnlyErrorsAfterTimestamp() throws Exception {
        var store = new ErrorStore(dbFactory, 100);
        store.record("OldError", "old", "/old", "stack", null);
        store.flush();
        Thread.sleep(50);
        var cutoff = java.time.Instant.now();
        Thread.sleep(50);
        store.record("NewError", "new", "/new", "stack", null);
        store.flush();

        var all = store.list(null);
        assertEquals(2, all.size());

        var recent = store.list(null, cutoff);
        assertEquals(1, recent.size());
        assertEquals("NewError", recent.get(0).get("errorType"));
    }

    @Test
    void unfilteredListIsCappedAtListLimitNewestFirst() {
        // Token-efficiency R7: an unfiltered list carries every heavy column, so it is capped at
        // LIST_LIMIT, newest first. A since-filtered list is a window the caller chose and is
        // returned in full. Seeded via SQL since record() coalesces per flush.
        var base = java.time.Instant.parse("2026-01-01T00:00:00Z");
        int total = ErrorStore.LIST_LIMIT + 1;
        var db = new Database(dbFactory.openSession());
        db.beginTransaction();
        for (int i = 0; i < total; i++) {
            var ts = java.sql.Timestamp.from(base.plusSeconds(i));
            db.sql("INSERT INTO ops_errors (error_type, message, stack_trace, route, request_detail, first_seen, last_seen, occurrence_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "E" + i, "m", "s", "GET /r" + i, "req", ts, ts, 1);
        }
        db.commitTransaction();
        db.close();

        var capped = errorStore.list(null);
        assertEquals(ErrorStore.LIST_LIMIT, capped.size());
        assertEquals("E" + (total - 1), capped.get(0).get("errorType"), "newest first");
        assertTrue(capped.stream().noneMatch(e -> "E0".equals(e.get("errorType"))), "oldest row is the one cut");

        assertEquals(total, errorStore.list(null, base).size(), "since-filtered list is not capped");
    }

    // --- Integration tests for ops endpoints ---

    static Brace app;
    static HttpClient client = HttpClient.newHttpClient();
    static int port;

/** A v2 ops-auth body: signature over publicKey + timestamp + nonce (v1 was removed in 0.1.8). */
    private static String v3AuthBody(OpsKeys.Keypair kp) {
        String timestamp = java.time.Instant.now().toString();
        byte[] nonceBytes = new byte[16];
        new java.security.SecureRandom().nextBytes(nonceBytes);
        String nonce = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes);
        String signature = OpsKeys.sign(
            OpsKeys.v3AuthMessage("http://localhost", kp.publicKey(), timestamp, nonce), kp.privateKey());
        return "{\"v\":\"3\",\"origin\":\"http://localhost\",\"publicKey\":\"" + kp.publicKey() + "\","
            + "\"timestamp\":\"" + timestamp + "\",\"nonce\":\"" + nonce + "\","
            + "\"signature\":\"" + signature + "\"}";
    }

    private static String authenticateOps(int targetPort, OpsKeys.Keypair kp) throws Exception {
        String body = v3AuthBody(kp);
        var response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + targetPort + "/ops/auth"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString());
        String respBody = response.body();
        int start = respBody.indexOf("\"token\":\"") + 9;
        int end = respBody.indexOf("\"", start);
        return respBody.substring(start, end);
    }

    @Test
    void opsErrorsEndpointReturnsErrors(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, kp.publicKey() + " scope:control\n");

        app = Brace.app().opsUnpinnedForTests().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:opserrorsdb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops(keysFile.toString());

        app.get("/boom", req -> { throw new RuntimeException("kaboom"); });

        app.start();
        try {
            port = app.actualPort();

            // Trigger an error
            var errorResp = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/boom")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(500, errorResp.statusCode());

            // Persist the H9-buffered error deterministically
            app.errorStore().flush();

            // Check errors endpoint
            String token = authenticateOps(port, kp);
            var response = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("RuntimeException"));
            assertTrue(response.body().contains("kaboom"));
        } finally {
            app.stop();
        }
    }

    @Test
    void opsResolveErrorEndpoint(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, kp.publicKey() + " scope:control\n");

        app = Brace.app().opsUnpinnedForTests().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:opsresolvedb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops(keysFile.toString());

        app.get("/boom", req -> { throw new RuntimeException("kaboom"); });

        app.start();
        try {
            port = app.actualPort();

            // Trigger an error
            client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/boom")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

            app.errorStore().flush();

            // Get errors to find the ID
            String token = authenticateOps(port, kp);
            var listResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listResp.statusCode());

            // Extract ID from JSON (simple parsing)
            String body = listResp.body();
            int idStart = body.indexOf("\"id\":") + 5;
            int idEnd = body.indexOf(",", idStart);
            String idStr = body.substring(idStart, idEnd).trim();
            long errorId = Long.parseLong(idStr);

            // Resolve it
            token = authenticateOps(port, kp);
            var resolveResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors/" + errorId + "/resolve"))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resolveResp.statusCode());
            assertTrue(resolveResp.body().contains("Brace Ops"));

            // Verify it's now resolved
            token = authenticateOps(port, kp);
            var resolvedResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors?status=resolved"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resolvedResp.statusCode());
            assertTrue(resolvedResp.body().contains("RuntimeException"));
        } finally {
            app.stop();
        }
    }

    @Test
    void opsErrorsRequiresAuth(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, kp.publicKey() + " scope:control\n");

        app = Brace.app().opsUnpinnedForTests().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:opserrorsauthdb" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops(keysFile.toString());

        app.start();
        try {
            port = app.actualPort();

            var response = client.send(
                HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + "/ops/errors")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
        } finally {
            app.stop();
        }
    }

    /**
     * End-to-end: a route that throws with a secret-bearing path and an exception
     * message that contains a long token — the stored error record must not contain
     * the raw secret in either the route/requestDetail or the message field.
     */
    @Test
    void secretBearingPathAndMessageAreRedactedInStoredError(@TempDir Path tmpDir) throws Exception {
        var kp = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, kp.publicKey() + " scope:control\n");

        // A 32-char hex-like secret that should trigger value-shaped redaction
        String secret = "a3f9bc2d8ef14a5b6c7d8e9f01234567";

        app = Brace.app().opsUnpinnedForTests().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:redacttest" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops(keysFile.toString());

        // Route whose path contains a secret token and whose exception message does too
        app.get("/password-reset/{token}", req ->
            { throw new RuntimeException("invalid token: " + secret); });

        app.start();
        try {
            port = app.actualPort();

            // Hit the route with the secret in the path
            var errorResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/password-reset/" + secret))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(500, errorResp.statusCode());

            // Persist the H9-buffered error deterministically
            app.errorStore().flush();

            // Fetch the stored error record via /ops/errors
            String token = authenticateOps(port, kp);
            var listResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors"))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listResp.statusCode());
            String body = listResp.body();

            // The redaction placeholder must be present
            assertTrue(body.contains("[redacted]"),
                "redaction placeholder must appear in stored error record; body: " + body);

            // Parse out the specific fields we care about — route, requestDetail, message and
            // stackTrace (which repeats the exception message) must not contain the raw secret.
            String routeField = extractJsonStringField(body, "route");
            String requestDetailField = extractJsonStringField(body, "requestDetail");
            String messageField = extractJsonStringField(body, "message");

            assertFalse(routeField != null && routeField.contains(secret),
                "route field must not contain raw secret: " + routeField);
            assertFalse(requestDetailField != null && requestDetailField.contains(secret),
                "requestDetail field must not contain raw secret: " + requestDetailField);
            assertFalse(messageField != null && messageField.contains(secret),
                "message field must not contain raw secret: " + messageField);

            // The stack trace is only on the per-error detail endpoint.
            String id = body.replaceFirst("(?s).*?\"id\":(\\d+).*", "$1");
            var detailResp = client.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors/" + id))
                    .header("Authorization", "Bearer " + token)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, detailResp.statusCode());
            String stackTraceField = extractJsonStringField(detailResp.body(), "stackTrace");
            assertNotNull(stackTraceField, "stackTrace field expected in: " + detailResp.body());
            assertTrue(stackTraceField.contains("RuntimeException: invalid token: [redacted]"), stackTraceField);
            assertFalse(stackTraceField.contains(secret),
                "stackTrace field must not contain raw secret: " + stackTraceField);
        } finally {
            app.stop();
        }
    }

    @Test
    void secretBearingHeadersAndQueryValuesAreRedactedInStoredError(@TempDir Path tmpDir) throws Exception {
        // M6 (docs/2026-11-09-security-review-todos.md): headers and query strings were redacted
        // by NAME only, so a reset token in a Referer, a JWT in an identity-proxy header and an
        // OAuth code in ?code= reached /ops/errors verbatim, readable with a scope:read key.
        var kp = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, kp.publicKey() + " scope:read\n");

        String resetToken = "a3f9bc2d8ef14a5b6c7d8e9f01234567";
        String pathToken = "Zx81kLm02PqR7sTuV4wXy9AbCd";
        String jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ1c2VyLTQyIiwiZXhwIjoxOTk5OTk5OTk5fQ.c2lnbmF0dXJlLWJ5dGVzLWhlcmUtMTIzNDU2";
        String oauthCode = "4/0AX4XfWhq8Lp3mN7vB2cD9eF1gH5jK";
        String requestId = "550e8400-e29b-41d4-a716-446655440000";

        app = Brace.app().opsUnpinnedForTests().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:redacthdrs" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops(keysFile.toString());
        app.get("/reset/submit", req -> { throw new IllegalStateException("reset failed"); });
        app.get("/oauth/callback", req -> { throw new IllegalStateException("exchange failed"); });

        app.start();
        try {
            port = app.actualPort();
            var resetResp = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/reset/submit"))
                    .header("Referer", "https://app.example.com/reset?token=" + resetToken + "&lang=en")
                    .header("Origin", "https://app.example.com")
                    .header("X-Original-URL", "/reset/" + pathToken + "/confirm")
                    .header("Cf-Access-Jwt-Assertion", jwt)
                    .header("X-Amzn-Oidc-Data", jwt)
                    .header("X-Auth-Key", "k3y-" + resetToken)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("X-Request-Id", requestId)
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(500, resetResp.statusCode());
            var oauthResp = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/oauth/callback?code="
                        + java.net.URLEncoder.encode(oauthCode, java.nio.charset.StandardCharsets.UTF_8)
                        + "&state=xyz&%74oken=abc&page=2"))
                    .GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(500, oauthResp.statusCode());
            app.errorStore().flush();

            String token = authenticateOps(port, kp);
            var listResp = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors"))
                    .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listResp.statusCode());
            var list = Json.mapper().readTree(listResp.body());
            var all = new StringBuilder();
            for (var row : list.isArray() ? list : list.path("errors")) {
                var detail = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/ops/errors/" + row.get("id").asText()))
                        .header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, detail.statusCode());
                all.append(detail.body()).append('\n');
            }
            String stored = all.toString();
            assertTrue(stored.contains("reset failed") && stored.contains("exchange failed"),
                "both errors stored: " + stored);
            for (String secret : List.of(resetToken, pathToken, jwt, oauthCode,
                    java.net.URLEncoder.encode(oauthCode, java.nio.charset.StandardCharsets.UTF_8))) {
                assertFalse(stored.contains(secret), "secret " + secret + " leaked into: " + stored);
            }
            assertFalse(stored.contains("%74oken=abc"), "percent-encoded name must be decoded: " + stored);
            // Ordinary values survive.
            assertTrue(stored.contains("app.example.com/reset?token=[REDACTED]&lang=en"), stored);
            assertTrue(stored.contains("/reset/[redacted]/confirm"), stored);
            assertTrue(stored.contains("en-US,en;q=0.9"), stored);
            assertTrue(stored.contains(requestId), stored);
            assertTrue(stored.contains("state=xyz"), stored);
            assertTrue(stored.contains("page=2"), stored);
        } finally {
            app.stop();
        }
    }

    @Test
    void finalReviewSecretsDoNotReachTheStoreOrTheLogs(@TempDir Path tmpDir) throws Exception {
        // Final review (docs/2026-11-09-security-review-todos.md, M6 final review fixes): a token
        // next to punctuation in the path (/dl/T.pdf), a percent-encoded path token, and a URL
        // quoted in an exception message were stored verbatim and served on /ops/logs.
        var kp = OpsKeys.generateKeypair();
        Path keysFile = tmpDir.resolve("authorized-keys");
        Files.writeString(keysFile, kp.publicKey() + " scope:read\n");
        String t = "Tt1aB2cD3eF4gH5iJ6kL7mN8";
        String encoded = "Xx1aB2cD3eF4%2BgH5iJ6kL7%3D%3D";
        String urlToken = "Uu1aB2cD3eF4gH5iJ6kL7mN8";
        String dbPassword = "Ww1aB2cD3eF4gH5iJ6kL7";

        app = Brace.app().opsUnpinnedForTests().port(0)
            .database(new DatabaseFactory(
                "jdbc:h2:mem:redactfinal" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", null, null,
                List.of(Post.class)))
            .ops(keysFile.toString());
        app.get("/dl/{file}", req -> { throw new IllegalStateException("download failed"); });
        app.get("/reset/{token}", req -> { throw new IllegalStateException("reset failed"); });
        app.get("/fetch", req -> {
            throw new IllegalStateException("upstream: Server returned HTTP response code: 500 for URL: "
                + "https://api.partner.example/v1/reset/" + urlToken,
                new java.sql.SQLException("Connection to postgres://dbuser:" + dbPassword + "@db.internal:5432/app refused"));
        });

        LogTap.clear();
        app.start();
        try {
            port = app.actualPort();
            for (String path : List.of("/dl/" + t + ".pdf", "/reset/" + encoded, "/fetch")) {
                var resp = client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(500, resp.statusCode(), path);
            }
            app.errorStore().flush();

            String token = authenticateOps(port, kp);
            var listResp = client.send(HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/ops/errors"))
                    .header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            var list = Json.mapper().readTree(listResp.body());
            var all = new StringBuilder();
            for (var row : list.isArray() ? list : list.path("errors")) {
                all.append(client.send(HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/ops/errors/" + row.get("id").asText()))
                        .header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body()).append('\n');
            }
            String stored = all.toString();
            assertTrue(stored.contains("download failed") && stored.contains("reset failed")
                && stored.contains("upstream"), "all three errors stored: " + stored);
            String logs = LogTap.snapshot().toString();
            for (String secret : List.of(t, encoded, "Xx1aB2cD3eF4+gH5iJ6kL7", urlToken, dbPassword)) {
                assertFalse(stored.contains(secret), "secret " + secret + " stored: " + stored);
                assertFalse(logs.contains(secret), "secret " + secret + " logged: " + logs);
            }
            assertTrue(stored.contains("/dl/[redacted].pdf"), stored);
            assertTrue(stored.contains("https://api.partner.example/v1/reset/[redacted]"), stored);
            assertTrue(stored.contains("postgres://[REDACTED]@db.internal:5432/app"), stored);
        } finally {
            app.stop();
        }
    }

    /**
     * Minimal JSON string-field extractor — finds the value of the first occurrence
     * of {@code "fieldName":"<value>"} in the given JSON string. Returns null if the
     * field is not found.
     */
    private static String extractJsonStringField(String json, String fieldName) {
        String key = "\"" + fieldName + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        // Walk forward, treating \" as an escaped quote inside the value
        var sb = new StringBuilder();
        int i = start;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == '"') { sb.append('"'); i += 2; continue; }
                if (next == '\\') { sb.append('\\'); i += 2; continue; }
                if (next == 'n') { sb.append('\n'); i += 2; continue; }
            }
            if (c == '"') break; // end of string value
            sb.append(c);
            i++;
        }
        return sb.toString();
    }
}

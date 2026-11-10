package com.larvalabs.brace;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Persists exception data to the ops_errors table.
 *
 * <p>Recording is coalesced (perf review H9): {@link #record} is a cheap in-memory merge per
 * {@code (type, route)}, and a single flusher thread writes one upsert per distinct error kind
 * every {@value #FLUSH_INTERVAL_MS}ms. Error floods happen exactly when the DB is degraded — the
 * old per-occurrence path (one virtual thread + 1–2 transactions each) starved the connection pool
 * that healthy requests needed. Coalescing loses nothing the table keeps ({@code ops_errors} is
 * already one row per kind with a count and the latest payload); the costs are bounded and
 * deliberate: a crash drops at most one flush interval of counts, dashboards and regression
 * notifications lag by at most one interval, and past {@value #MAX_PENDING_KINDS} distinct buffered
 * kinds new kinds are dropped (counted, warned on flush). Occurrences of already-buffered kinds are
 * never dropped — they're a counter bump. Tests and shutdown call {@link #flush()} directly.
 *
 * <p>The cap covers everything the store holds: the pending map and the batch a flush has drained
 * and not yet written or put back (security review 2026-11, second review of M5). A flush that
 * waits on a dead database holds its batch for as long as the connection attempt takes; counting
 * only the pending map let {@link #record} admit another full cap of kinds in that window, on
 * every flush of an outage. The kinds buffered first are the ones kept: they carry the counts and
 * first-seen times accumulated so far, and a flood of new kinds cannot push them out.
 *
 * <p>One bad entry cannot stall the store (security review 2026-11, M5). {@link #record} replaces
 * NUL characters (Postgres text cannot hold them, and pgjdbc rejects them client-side) and clips
 * {@code type} and {@code route} to their {@code VARCHAR(255)} columns. Each upsert runs under its
 * own savepoint, so a row the database rejects is rolled back alone while the rest of the batch
 * commits. A row rejected for its data (SQLState class 22, 23 or 54) is retried once at once with
 * its payload stripped (a fixed placeholder message, no trace, detail or headers), so the kind's
 * count and the regression signal survive; that retry inserts the placeholder for a new kind but
 * only adds the count to an existing row, never replacing its stored details. Any other row
 * rejection (a lock timeout) is not retried stripped. A rejected entry is retried on later
 * flushes and dropped after {@value #MAX_FLUSH_FAILURES} rejections. Not the entry's fault, so
 * the whole batch is kept uncounted and retried, as for an outage: a connection-level failure
 * (database down, pool exhausted, the session cannot be opened or begun, a connection-classed
 * SQLState on a connection that then fails {@code isValid}) and a server-wide SQLState
 * ({@link #isServerWide}: 25006 read-only during a failover, 53xxx, 57P0x, 58xxx, 08xxx) when no
 * row of that flush could be written. When another row of the same flush was written, the server
 * is taking writes, so such a state is that row's own (one oversized row drawing 53100 or 53200)
 * and counts against it alone, instead of starving the rows beside it. That retention is capped at {@value #MAX_UNCOUNTED_RETENTIONS} consecutive flushes per
 * entry (about two minutes), after which each failed flush counts like a row rejection. Any other
 * rejection counts, even when every row of the batch got it (a lock timeout on all of them), and
 * so does a connection-classed SQLState on a connection that is still valid (a statement timeout
 * on one huge row, a deadlock). Failures are logged at most once a minute, naming the exception
 * class, SQLState and error type, never the request-derived payload. A {@link RegressionListener}
 * that throws costs only its own notification (counted in the same warning), and an
 * {@link Error} during a flush keeps the batch and the flusher thread like any failed flush.
 */
public class ErrorStore {

    /**
     * Notified when a flush commits, so a {@link RegressionTracker} can detect new error kinds
     * since startup without ErrorStore re-doing the existence check. {@link #onNew} fires when a
     * brand-new {@code (type, route)} is inserted; {@link #onRepeat} when an existing one recurs,
     * with the number of occurrences coalesced into that flush.
     */
    public interface RegressionListener {
        void onNew(String type, String route, String message, Instant firstSeen);
        void onRepeat(String type, String route, long count);
    }

    static final long FLUSH_INTERVAL_MS = 2_000;
    static final int MAX_PENDING_KINDS = 1_000;
    /** Flushes in which the database rejected an entry before that entry is dropped. */
    static final int MAX_FLUSH_FAILURES = 5;
    /**
     * Consecutive flushes an entry is kept uncounted through a server-wide failure (an outage,
     * a read-only failover window), about two minutes; after that each further one counts like a
     * row rejection, so an entry is dropped after {@value #MAX_UNCOUNTED_RETENTIONS} plus
     * {@value #MAX_FLUSH_FAILURES} failed flushes.
     */
    static final int MAX_UNCOUNTED_RETENTIONS = 60;
    /** Width of the {@code error_type} and {@code route} columns ({@code VARCHAR(255)}). */
    static final int MAX_KEY_LENGTH = 255;
    static final long FAILURE_LOG_INTERVAL_NANOS = 60_000_000_000L;
    /**
     * Stored in place of the message when the database rejected the full record and the retry
     * without its payload succeeded. Fixed text: it must never carry request-derived content.
     */
    static final String STRIPPED_MESSAGE = "[details not stored: the database rejected this error record]";

    /** One coalesced error kind awaiting flush: occurrence count + the latest payload. */
    private static final class Pending {
        final String type;
        final String route;
        final Instant firstSeen;
        /** Flushes in which the database rejected this entry; reset only by a successful write. */
        int failures;
        /** Consecutive flushes this entry was kept uncounted through a server-wide failure. */
        int uncounted;
        long count;
        String message;
        String stackTrace;
        String requestDetail;
        String queriesBefore;
        String requestHeaders;
        Instant lastSeen;
        /**
         * The payload-stripped retry of a row the database refused for its data: inserted with
         * {@link #STRIPPED_MESSAGE}, but on an existing row only the count and last-seen time are
         * updated, so the stored details are never replaced by the placeholder.
         */
        boolean strippedPayload;

        Pending(String type, String route, Instant firstSeen) {
            this.type = type;
            this.route = route;
            this.firstSeen = firstSeen;
        }

        /**
         * Same kind, count, times and failure counters; the payload replaced by
         * {@link #STRIPPED_MESSAGE}. The copy lives for one flush only: it is written and
         * reported to the listener, and never put back in the pending map (a flush that fails
         * keeps the entry it was made from), so {@link #record} never refreshes one.
         */
        Pending stripped() {
            var s = new Pending(type, route, firstSeen);
            s.failures = failures;
            s.uncounted = uncounted;
            s.count = count;
            s.lastSeen = lastSeen;
            s.message = STRIPPED_MESSAGE;
            s.strippedPayload = true;
            return s;
        }
    }

    private final DatabaseFactory databaseFactory;
    private final int maxErrors;
    private volatile RegressionListener regressionListener;
    private final java.util.concurrent.ConcurrentHashMap<String, Pending> pending =
        new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Keys of the batch a flush has drained from {@link #pending} and not yet settled (written,
     * dropped or merged back). Changed only inside {@code pending.compute} for that key, as is
     * {@link #bufferedKinds}, so the two stay consistent with the map under concurrent records.
     */
    private final Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Distinct kinds held in {@link #pending} or {@link #inFlight}: what the cap bounds. */
    private final java.util.concurrent.atomic.AtomicInteger bufferedKinds =
        new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.LongAdder droppedKinds =
        new java.util.concurrent.atomic.LongAdder();
    private final Thread flusher;

    /** Opens the flush session. A seam for tests that simulate an unreachable database. */
    volatile java.util.function.Supplier<Database> flushSession;

    /** Test seam: runs before each row write; throwing simulates the database rejecting that row. */
    interface RowFault {
        void check(String type, String message) throws SQLException;
    }

    /** Test seam: whether the flush connection is still usable after a connection-classed error. */
    interface ConnectionCheck {
        boolean alive(java.sql.Connection connection) throws SQLException;
    }

    volatile RowFault rowFault;
    volatile ConnectionCheck connectionCheck = connection -> connection.isValid(1);

    // Failure accounting for the rate-limited log line. Guarded by flush()'s monitor.
    private long nextFailureLogNanos = System.nanoTime();
    private long rejectedRows;
    private long failedFlushes;
    private long droppedEntries;
    private final Set<String> droppedTypes = new LinkedHashSet<>();
    /** Entries dropped because no flush could be written for too long, not because they were rejected. */
    private long outageDroppedEntries;
    private final Set<String> outageDroppedTypes = new LinkedHashSet<>();
    private String lastFailure;
    private long listenerFailures;
    private String lastListenerFailure;

    public ErrorStore(DatabaseFactory databaseFactory, int maxErrors) {
        this.databaseFactory = databaseFactory;
        this.maxErrors = maxErrors;
        this.flushSession = () -> new Database(databaseFactory.openSession());
        this.flusher = Thread.ofVirtual().name("brace-error-flush").start(() -> {
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                try {
                    flush();
                } catch (Throwable e) {
                    // Never let the flusher die; the next interval retries. That includes an
                    // Error, an OutOfMemoryError among them: flushBatch has already put the batch
                    // back, a JVM that should not outlive one is run with -XX:+ExitOnOutOfMemoryError,
                    // and in one that carries on, a dead flusher would mean no error is persisted
                    // and no regression is reported until restart, with nothing to show for it.
                    try {
                        Log.warn("error store: flush failed with " + e.getClass().getName() + ", retrying next interval");
                    } catch (Throwable ignored) {
                        // Logging allocates; if that fails too there is nothing left to do here.
                    }
                }
            }
        });
    }

    /** Stop the flusher and write out anything still buffered. Called by {@code Brace.stop()}. */
    public void close() {
        flusher.interrupt();
        try {
            flush();
        } catch (Exception e) {
            // Best effort on shutdown.
        }
    }

    public void setRegressionListener(RegressionListener listener) {
        this.regressionListener = listener;
    }

    public void record(String type, String message, String route, String stackTrace, String requestDetail) {
        record(type, message, route, stackTrace, requestDetail, null, null);
    }

    /**
     * Record an error with the instant-of-failure context captured at the catch point:
     * {@code queriesBefore} (a small JSON summary of DB work done before the throw) and
     * {@code requestHeaders} (the redacted request headers). Both may be null.
     *
     * <p>Cheap and non-blocking: merges into the in-memory buffer; the flusher persists it within
     * {@value #FLUSH_INTERVAL_MS}ms. Safe to call on the request thread.
     */
    public void record(String type, String message, String route, String stackTrace,
                       String requestDetail, String queriesBefore, String requestHeaders) {
        var now = Instant.now();
        // M5: a NUL from `?page=1%00` reaches the message, trace and request detail. Postgres text
        // can't store it, so it is replaced here rather than failing the write. And both key
        // columns are VARCHAR(255), while the route starts with the client-chosen HTTP method.
        String cleanType = clip(withoutNul(type));
        String cleanRoute = clip(withoutNul(route));
        String cleanMessage = withoutNul(message);
        String cleanDetail = withoutNul(requestDetail);
        String cleanQueries = withoutNul(queriesBefore);
        String cleanHeaders = withoutNul(requestHeaders);
        var key = cleanType + '\u0000' + cleanRoute;
        // The cap counts the batch a flush is holding too; a kind in that batch is a known kind.
        if (bufferedKinds.get() >= MAX_PENDING_KINDS && !pending.containsKey(key) && !inFlight.contains(key)) {
            droppedKinds.increment();
            return;
        }
        // Served on /ops/errors; the trace repeats the message (and every cause's) verbatim.
        String redactedTrace = Redactor.redactStackTrace(withoutNul(stackTrace));
        pending.compute(key, (k, p) -> {
            if (p == null) {
                p = new Pending(cleanType, cleanRoute, now);
                if (!inFlight.contains(k)) bufferedKinds.incrementAndGet();
            }
            p.count++;
            p.message = cleanMessage;
            p.stackTrace = redactedTrace;
            p.requestDetail = cleanDetail;
            p.queriesBefore = cleanQueries;
            p.requestHeaders = cleanHeaders;
            p.lastSeen = now;
            return p;
        });
    }

    /**
     * Replaces each NUL with U+FFFD rather than deleting it. Deleting would join the text on
     * either side, and the message arrives already redacted: {@code abc\0def} passed the redactor
     * as two short tokens and must not be stored as one long, secret-shaped one.
     */
    static String withoutNul(String s) {
        return s == null || s.indexOf('\u0000') < 0 ? s : s.replace('\u0000', '�');
    }

    /** Clips to {@link #MAX_KEY_LENGTH} UTF-16 units without splitting a surrogate pair. */
    static String clip(String s) {
        if (s == null || s.length() <= MAX_KEY_LENGTH) return s;
        int end = MAX_KEY_LENGTH;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }

    /** Per-row outcome of a flush. */
    private static final byte UPDATED = 0, INSERTED = 1, REJECTED = 2;

    /**
     * Drain the buffer and persist it: one upsert per distinct {@code (type, route)} carrying the
     * coalesced occurrence count, plus one prune check, in a single transaction on a single
     * connection. Each upsert (and the prune) runs under its own savepoint, so a row the database
     * rejects is rolled back alone and the rest commit. A rejected entry is merged back and
     * retried, and dropped after {@value #MAX_FLUSH_FAILURES} rejections. A connection-level
     * failure merges the whole batch back without counting against any entry, and the next
     * interval retries. Serialized so the periodic, shutdown, and test-triggered flushes can't
     * interleave.
     */
    public synchronized void flush() {
        try {
            long dropped = droppedKinds.sumThenReset();
            if (dropped > 0) {
                Log.warn("error storm: dropped " + dropped + " new error kinds beyond the "
                    + MAX_PENDING_KINDS + "-kind pending buffer");
            }
            if (!pending.isEmpty()) {
                flushBatch();
            }
        } finally {
            logFailures();
        }
    }

    private void flushBatch() {
        // Drain a snapshot; records arriving during the flush re-create entries for the next one.
        // A drained key stays counted in bufferedKinds (it moves to inFlight) until it is settled.
        var batch = new ArrayList<Pending>();
        for (var key : pending.keySet()) {
            pending.compute(key, (k, p) -> {
                if (p != null) {
                    inFlight.add(k);
                    batch.add(p);
                }
                return null;
            });
        }
        if (batch.isEmpty()) {
            return;
        }

        // The entries as drained. batch.set() below swaps in a payload-stripped copy where that is
        // what got written; if the flush then fails, these (with their payloads) are what is kept.
        var drained = new ArrayList<>(batch);
        var outcome = new byte[batch.size()];
        var rowErrors = new Throwable[batch.size()];
        // Rows written in this flush, and the first server-wide SQLState a row drew, if any.
        int[] written = {0};
        Throwable[] serverWide = {null};
        boolean postgres = databaseFactory.isPostgres();
        boolean committed = false;
        Database db = null;
        try {
            db = flushSession.get();
            db.beginTransaction();
            db.jdbc(connection -> {
                for (int i = 0; i < batch.size(); i++) {
                    var p = batch.get(i);
                    try {
                        outcome[i] = writeRow(connection, p, postgres);
                        written[0]++;
                    } catch (RowRejected first) {
                        outcome[i] = REJECTED;
                        rowErrors[i] = first.getCause();
                        if (serverWide[0] == null && isServerWide(sqlState(first.getCause()))) {
                            serverWide[0] = first.getCause();
                        }
                        // Only a rejection of the DATA (22xxx, 23xxx, 54xxx) is retried without
                        // the payload, so the count and the regression signal survive what was
                        // refused. A transient error (a lock timeout) is retried in full on the
                        // next flush instead (final review L9).
                        if (!isDataError(first.getCause())) continue;
                        var stripped = p.stripped();
                        try {
                            outcome[i] = writeRow(connection, stripped, postgres);
                            batch.set(i, stripped);
                            written[0]++;
                        } catch (RowRejected second) {
                            // stays REJECTED with the first error
                        }
                    }
                }
                // A server-wide SQLState (read-only, out of resources, shutting down) is the
                // database's, not a row's, when nothing in this flush could be written: abort,
                // so the whole batch is kept uncounted. When another row was written, the server
                // is taking writes and the state is that row's own (one oversized row drawing
                // 53100 or 53200), so it is counted below like any rejection. Decided after the
                // loop, not at the failing row, so the order of the batch does not matter: before
                // this, one such row aborted every flush and starved the healthy rows beside it
                // for as long as it stayed buffered (second review).
                if (serverWide[0] != null && written[0] == 0) {
                    throw new FlushAborted(serverWide[0]);
                }
                prune(connection);
            });
            db.commitTransaction();
            committed = true;
        } catch (Throwable e) {
            // Throwable, not Exception: an Error here (a NoClassDefFoundError out of a driver, an
            // OutOfMemoryError building a statement) must not lose the drained batch, nor leave
            // its keys counted as in flight for good.
            if (db != null) {
                try {
                    db.rollbackTransaction();
                } catch (Exception ignored) {
                    // The connection is likely gone; closing below releases it.
                }
            }
            // DB unavailable (the storm case): put the batch back so counts keep accumulating —
            // bounded by kind cardinality, not error rate — and retry next interval, up to the
            // MAX_UNCOUNTED_RETENTIONS cap.
            retainUncounted(drained);
            failedFlushes++;
            lastFailure = describe(e instanceof FlushAborted ? e.getCause() : e);
            // The batch is safe; a VirtualMachineError is still the caller's to see. The flusher
            // thread logs it and carries on (see the constructor).
            if (e instanceof VirtualMachineError vme) throw vme;
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Exception ignored) {
                    // Nothing more to do; the pool reclaims or evicts the connection.
                }
            }
        }
        if (!committed) {
            return;
        }

        // Rows the database rejected: retry a few times, then give up on that entry. A server-wide
        // SQLState gets here only beside a row that was written (with none written it aborts the
        // flush, above); every other rejection counts,
        // even when every row of the batch got the same one (a lock timeout on all of them): kept
        // uncounted, two rows that always fail that way were retried forever (verification pass).
        for (int i = 0; i < batch.size(); i++) {
            if (outcome[i] != REJECTED) continue;
            var p = batch.get(i);
            rejectedRows++;
            lastFailure = describe(rowErrors[i]);
            p.uncounted = 0;
            if (++p.failures >= MAX_FLUSH_FAILURES) {
                droppedEntries++;
                if (droppedTypes.size() < 5) droppedTypes.add(forLog(p.type));
                settle(p);
            } else {
                mergeBack(p);
            }
        }
        for (int i = 0; i < batch.size(); i++) {
            if (outcome[i] != REJECTED) settle(batch.get(i));
        }

        // Notify the regression listener only after a successful commit, off the DB path.
        var listener = regressionListener;
        if (listener != null) {
            for (int i = 0; i < batch.size(); i++) {
                var p = batch.get(i);
                // Per row: a listener that throws for one kind must not cost the rest of the
                // batch its notifications, nor fail a flush whose rows are already committed.
                try {
                    if (outcome[i] == INSERTED) listener.onNew(p.type, p.route, p.message, p.firstSeen);
                    else if (outcome[i] == UPDATED) listener.onRepeat(p.type, p.route, p.count);
                } catch (VirtualMachineError e) {
                    throw e;
                } catch (Throwable e) {
                    listenerFailures++;
                    lastListenerFailure = describe(e);
                }
            }
        }
    }

    /** No row could be written and one drew a server-wide SQLState (the cause): nothing commits. */
    private static final class FlushAborted extends RuntimeException {
        FlushAborted(Throwable cause) {
            super(null, cause, false, false);
        }
    }

    /** The database refused this row; the connection is still usable. Wraps the row's error. */
    private static final class RowRejected extends Exception {
        RowRejected(Throwable cause) {
            super(null, cause, false, false);
        }
    }

    /**
     * Writes one entry under its own savepoint. Throws {@link RowRejected} when the database
     * refused the row and the connection is still usable (the savepoint is rolled back), and the
     * original {@link SQLException} when the connection is gone, which aborts the whole flush and
     * keeps the batch without counting against any entry.
     */
    private byte writeRow(java.sql.Connection connection, Pending p, boolean postgres)
            throws SQLException, RowRejected {
        var savepoint = connection.setSavepoint();
        try {
            var fault = rowFault;
            if (fault != null) fault.check(p.type, p.message);
            byte result = (postgres ? upsertPostgres(connection, p) : upsertH2(connection, p)) ? INSERTED : UPDATED;
            connection.releaseSavepoint(savepoint);
            return result;
        } catch (SQLException | RuntimeException e) {
            recoverOrAbort(connection, savepoint, e);
            throw new RowRejected(e);
        }
    }

    /**
     * Rolls back to {@code savepoint} after {@code e}, or rethrows {@code e} when the connection
     * is gone. A connection-classed SQLState (08, 53, 57, 40, 25) is not trusted on its own:
     * 57014 is a statement timeout on one huge row, 40P01 a deadlock on one key. Only when the
     * connection also fails its liveness check does the flush abort here; otherwise the error is
     * handed back as the row's (M5 review). The rollback itself throws if the connection is gone.
     *
     * <p>A server-wide SQLState ({@link #isServerWide}: 25006 read-only transaction, during a
     * failover; 53xxx out of resources; 57P0x shutdown; 58xxx system error; 08xxx connection) on
     * a connection that is still usable is handed back too. {@code flushBatch} aborts the flush
     * for it when no row of the flush could be written, so the batch is kept uncounted (final
     * review L10), and counts it against the row when another row was (second review).
     */
    private void recoverOrAbort(java.sql.Connection connection, java.sql.Savepoint savepoint, Exception e)
            throws SQLException {
        try {
            connection.rollback(savepoint);
        } catch (SQLException rollbackFailed) {
            if (e instanceof SQLException se) throw se;
            throw rollbackFailed;
        }
        if (e instanceof SQLException se && isConnectionFailure(se)) {
            boolean alive;
            try {
                alive = connectionCheck.alive(connection);
            } catch (SQLException | RuntimeException checkFailed) {
                alive = false;
            }
            if (!alive) throw se;
        }
    }

    /** Prune if over limit — once per flush, not per occurrence. Best effort under a savepoint. */
    private void prune(java.sql.Connection connection) throws SQLException {
        var savepoint = connection.setSavepoint();
        try {
            long count;
            try (var st = connection.createStatement();
                 var rs = st.executeQuery("SELECT COUNT(*) FROM ops_errors")) {
                rs.next();
                count = rs.getLong(1);
            }
            if (count > maxErrors) {
                // Delete resolved first (oldest), then unresolved oldest
                try (var ps = connection.prepareStatement("DELETE FROM ops_errors WHERE id IN (" +
                        "SELECT id FROM ops_errors ORDER BY " +
                        "CASE WHEN resolved_at IS NOT NULL THEN 0 ELSE 1 END, " +
                        "last_seen ASC " +
                        "LIMIT ?)")) {
                    ps.setInt(1, (int) (count - maxErrors));
                    ps.executeUpdate();
                }
            }
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            // Keep the upserts; the next flush prunes again. Aborts only if the connection is gone.
            recoverOrAbort(connection, savepoint, e);
            lastFailure = describe(e);
            rejectedRows++;
        }
    }

    /**
     * True when an exception means the connection or server is unavailable, rather than that the
     * database rejected this row: SQLState classes 08 (connection), 53 (insufficient resources),
     * 57 (operator intervention, e.g. shutdown or statement timeout), 40 (rollback: serialization
     * failure, deadlock) and 25 (invalid transaction state).
     */
    static boolean isConnectionFailure(SQLException e) {
        if (e instanceof java.sql.SQLRecoverableException
                || e instanceof java.sql.SQLTransientConnectionException
                || e instanceof java.sql.SQLNonTransientConnectionException) {
            return true;
        }
        String state = e.getSQLState();
        return state != null && (state.startsWith("08") || state.startsWith("53")
            || state.startsWith("57") || state.startsWith("40") || state.startsWith("25"));
    }

    /**
     * SQLStates that mean the server cannot take writes at all, never one row's fault: 25006
     * (read-only transaction, e.g. a replica promoted mid-failover), class 53 (insufficient
     * resources: disk full, out of memory, too many connections), 57P01 to 57P05 (shutdown,
     * cannot connect now), class 58 (system error) and class 08 (connection exception).
     */
    static boolean isServerWide(String state) {
        return state != null && (state.equals("25006") || state.startsWith("53") || state.startsWith("57P")
            || state.startsWith("58") || state.startsWith("08"));
    }

    /**
     * A rejection of the row's DATA, which a retry without the payload can get past: class 22
     * (data exception: value too long, invalid encoding, NUL), 23 (integrity constraint: a check
     * violation) and 54 (program limit exceeded: an index row too large).
     */
    static boolean isDataError(Throwable e) {
        String state = sqlState(e);
        return state != null && (state.startsWith("22") || state.startsWith("23") || state.startsWith("54"));
    }

    private static String sqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException se && se.getSQLState() != null) return se.getSQLState();
        }
        return null;
    }

    /**
     * Puts back a batch the database could not take at all (the connection could not be opened or
     * begun, or a server-wide SQLState aborted the flush), without counting it against any entry,
     * for up to {@value #MAX_UNCOUNTED_RETENTIONS} consecutive flushes per entry. Past that cap a
     * further failure counts like a row rejection, so a long outage drops entries after
     * {@value #MAX_FLUSH_FAILURES} more, reported by the rate-limited warning, instead of keeping
     * the pending buffer full for good.
     */
    private void retainUncounted(List<Pending> batch) {
        for (var p : batch) {
            if (++p.uncounted > MAX_UNCOUNTED_RETENTIONS && ++p.failures >= MAX_FLUSH_FAILURES) {
                outageDroppedEntries++;
                if (outageDroppedTypes.size() < 5) outageDroppedTypes.add(forLog(p.type));
                settle(p);
            } else {
                mergeBack(p);
            }
        }
    }

    /** Exception class and SQLState only: driver messages can echo the request-derived values. */
    private static String describe(Throwable e) {
        String state = null;
        for (Throwable t = e; t != null && state == null; t = t.getCause()) {
            if (t instanceof SQLException se) state = se.getSQLState();
        }
        return e.getClass().getName() + (state != null ? " (SQLState " + state + ")" : "");
    }

    /** An error type for a log line: printable ASCII, short. It's an exception class name. */
    private static String forLog(String s) {
        if (s == null) return "null";
        var sb = new StringBuilder();
        for (int i = 0; i < s.length() && sb.length() < 100; i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c < 0x7f ? c : '?');
        }
        return sb.toString();
    }

    /** At most one warning per {@link #FAILURE_LOG_INTERVAL_NANOS}, summarising what failed since the last. */
    private void logFailures() {
        boolean notPersisted = rejectedRows > 0 || failedFlushes > 0 || droppedEntries > 0 || outageDroppedEntries > 0;
        if (!notPersisted && listenerFailures == 0) return;
        long now = System.nanoTime();
        if (now - nextFailureLogNanos < 0) return;
        nextFailureLogNanos = now + FAILURE_LOG_INTERVAL_NANOS;
        var msg = new StringBuilder("error store: ");
        if (notPersisted) {
            msg.append("could not persist errors");
            if (failedFlushes > 0) {
                msg.append("; ").append(failedFlushes).append(" flush(es) failed, entries kept for retry");
            }
            if (rejectedRows > 0) {
                msg.append("; database rejected ").append(rejectedRows).append(" row write(s)");
            }
            if (droppedEntries > 0) {
                msg.append("; dropped ").append(droppedEntries).append(" error kind(s) after ")
                    .append(MAX_FLUSH_FAILURES).append(" rejected flushes: ").append(String.join(", ", droppedTypes));
            }
            if (outageDroppedEntries > 0) {
                msg.append("; dropped ").append(outageDroppedEntries)
                    .append(" error kind(s) the database could not take through ")
                    .append(MAX_UNCOUNTED_RETENTIONS + MAX_FLUSH_FAILURES).append(" failed flushes: ")
                    .append(String.join(", ", outageDroppedTypes));
            }
            msg.append("; last failure: ").append(lastFailure);
        }
        if (listenerFailures > 0) {
            // Exception class only, like the rest: its message can carry the error's payload.
            msg.append(notPersisted ? "; " : "").append("regression listener threw for ").append(listenerFailures)
                .append(" notification(s), the errors themselves are stored; last: ").append(lastListenerFailure);
        }
        Log.warn(msg.toString());
        rejectedRows = 0;
        failedFlushes = 0;
        droppedEntries = 0;
        droppedTypes.clear();
        outageDroppedEntries = 0;
        outageDroppedTypes.clear();
        listenerFailures = 0;
    }

    /** Test hook: distinct kinds waiting in the pending map (not counting a batch being flushed). */
    int pendingKinds() {
        return pending.size();
    }

    /** Test hook: distinct kinds held in all, pending or in the batch being flushed. */
    int bufferedKinds() {
        return bufferedKinds.get();
    }

    /** Test hook: lets the next flush write its failure summary without waiting out the interval. */
    synchronized void expireFailureLogWindow() {
        nextFailureLogNanos = System.nanoTime();
    }

    /**
     * A flushed entry leaves the store (written, or dropped): its key is no longer in flight, and
     * no longer buffered unless the same kind was recorded again meanwhile.
     */
    private void settle(Pending done) {
        pending.compute(done.type + '\u0000' + done.route, (k, p) -> {
            if (inFlight.remove(k) && p == null) bufferedKinds.decrementAndGet();
            return p;
        });
    }

    /**
     * Re-merge a failed-flush entry without clobbering anything newer that arrived meanwhile. The
     * key moves from in flight back to pending, so the number of buffered kinds does not change:
     * {@link #record} already counted the batch against the cap while it was in flight.
     */
    private void mergeBack(Pending old) {
        pending.compute(old.type + '\u0000' + old.route, (k, p) -> {
            inFlight.remove(k);
            if (p == null) return old;
            p.count += old.count; // keep p's payload — it's newer
            // Carry the rejection count, so a key the database always rejects is still dropped.
            p.failures = Math.max(p.failures, old.failures);
            p.uncounted = Math.max(p.uncounted, old.uncounted);
            return p;
        });
    }

    /**
     * Postgres path: a single atomic upsert. Two instances (or threads) recording the same
     * {@code (error_type, route)} concurrently can't both insert — the partial unique index
     * {@code ops_errors_unresolved_dedupe} (see {@code migration_pg/V6}) forces one to take the
     * {@code DO UPDATE} branch and bump the count under the row lock, so no duplicate row and no
     * lost increment. The H2 check-then-insert ({@link #upsertH2}) has that race; H2 can't model
     * it, which is why this branch is proven by {@code ErrorStorePostgresIT}, not the unit suite.
     *
     * <p>Runs through raw JDBC (like the durable-job claim) so Hibernate doesn't try to classify an
     * {@code INSERT ... RETURNING} as a plain mutation. {@code RETURNING (xmax = 0)} is the standard
     * idiom for "was this a fresh insert?": {@code xmax} is 0 on a row this statement inserted,
     * non-zero on one it updated — that's the new-vs-repeat signal the regression listener needs.
     */
    private static boolean upsertPostgres(java.sql.Connection connection, Pending p) throws SQLException {
        // A payload-stripped retry only counts on an existing row (final review L9).
        String update = p.strippedPayload
            ? "occurrence_count = ops_errors.occurrence_count + EXCLUDED.occurrence_count, "
                + "last_seen = EXCLUDED.last_seen "
            : "occurrence_count = ops_errors.occurrence_count + EXCLUDED.occurrence_count, "
                + "message = EXCLUDED.message, stack_trace = EXCLUDED.stack_trace, "
                + "request_detail = EXCLUDED.request_detail, queries_before = EXCLUDED.queries_before, "
                + "request_headers = EXCLUDED.request_headers, last_seen = EXCLUDED.last_seen ";
        try (var ps = connection.prepareStatement(
            "INSERT INTO ops_errors " +
            "(error_type, message, stack_trace, route, request_detail, queries_before, request_headers, first_seen, last_seen, occurrence_count) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (error_type, route) WHERE resolved_at IS NULL DO UPDATE SET " + update +
            "RETURNING (xmax = 0) AS inserted")) {
            ps.setString(1, p.type);
            ps.setString(2, p.message);
            ps.setString(3, p.stackTrace);
            ps.setString(4, p.route);
            ps.setString(5, p.requestDetail);
            ps.setString(6, p.queriesBefore);
            ps.setString(7, p.requestHeaders);
            ps.setTimestamp(8, Timestamp.from(p.firstSeen));
            ps.setTimestamp(9, Timestamp.from(p.lastSeen));
            ps.setLong(10, p.count);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean("inserted");
            }
        }
    }

    /**
     * H2 path: check for an existing unresolved {@code (type, route)}, then UPDATE-or-INSERT. Not
     * atomic — see {@link #upsertPostgres} for the race this carries and why H2 keeps it anyway
     * (no partial unique index, different upsert syntax). Returns true if a new row was inserted.
     * Raw JDBC like the Postgres path, so a rejected row stays inside its savepoint and never
     * passes through Hibernate's exception handling (which could mark the transaction rollback-only).
     */
    private static boolean upsertH2(java.sql.Connection connection, Pending p) throws SQLException {
        Long id = null;
        long count = 0;
        try (var ps = connection.prepareStatement(
                "SELECT id, occurrence_count FROM ops_errors WHERE error_type = ? AND route = ? AND resolved_at IS NULL")) {
            ps.setString(1, p.type);
            ps.setString(2, p.route);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) {
                    id = rs.getLong(1);
                    count = rs.getLong(2);
                }
            }
        }

        if (id != null && p.strippedPayload) {
            // A payload-stripped retry only counts on an existing row (final review L9).
            try (var ps = connection.prepareStatement(
                    "UPDATE ops_errors SET occurrence_count = ?, last_seen = ? WHERE id = ?")) {
                ps.setLong(1, count + p.count);
                ps.setTimestamp(2, Timestamp.from(p.lastSeen));
                ps.setLong(3, id);
                ps.executeUpdate();
            }
            return false;
        }
        if (id != null) {
            try (var ps = connection.prepareStatement(
                    "UPDATE ops_errors SET occurrence_count = ?, message = ?, stack_trace = ?, request_detail = ?, queries_before = ?, request_headers = ?, last_seen = ? WHERE id = ?")) {
                ps.setLong(1, count + p.count);
                ps.setString(2, p.message);
                ps.setString(3, p.stackTrace);
                ps.setString(4, p.requestDetail);
                ps.setString(5, p.queriesBefore);
                ps.setString(6, p.requestHeaders);
                ps.setTimestamp(7, Timestamp.from(p.lastSeen));
                ps.setLong(8, id);
                ps.executeUpdate();
            }
            return false;
        }
        try (var ps = connection.prepareStatement(
                "INSERT INTO ops_errors (error_type, message, stack_trace, route, request_detail, queries_before, request_headers, first_seen, last_seen, occurrence_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, p.type);
            ps.setString(2, p.message);
            ps.setString(3, p.stackTrace);
            ps.setString(4, p.route);
            ps.setString(5, p.requestDetail);
            ps.setString(6, p.queriesBefore);
            ps.setString(7, p.requestHeaders);
            ps.setTimestamp(8, Timestamp.from(p.firstSeen));
            ps.setTimestamp(9, Timestamp.from(p.lastSeen));
            ps.setLong(10, p.count);
            ps.executeUpdate();
        }
        return true;
    }

    /**
     * Cap on rows returned by an <em>unfiltered</em> {@link #list}. The store itself is pruned
     * to {@code maxErrors} (default 1000), but a list response carrying every heavy column
     * unbounded is still a needlessly large payload — recent-first covers the browse use.
     * A {@code since}-filtered list is NOT capped: the caller asked for a bounded window and
     * gets it completely (the prune bounds the worst case), so the window never silently
     * loses rows while {@code errors.count} says otherwise.
     */
    static final int LIST_LIMIT = 500;

    public List<Map<String, Object>> list(String status) {
        return list(status, null);
    }

    public List<Map<String, Object>> list(String status, java.time.Instant since) {
        var db = new Database(databaseFactory.openSession());
        try {
            // L17: filter both the resolved/unresolved status AND the since-cutoff in SQL. The old
            // path fetched every matching row — each carrying the full stack_trace / request_detail /
            // request_headers text — then dropped the too-old ones in a Java loop. `first_seen >= ?`
            // preserves the prior Java semantics exactly: a NULL first_seen is never `>= ?`, so those
            // rows are excluded as before. `status` maps to a fixed clause (never user SQL).
            String where = "resolved".equals(status) ? "resolved_at IS NOT NULL" : "resolved_at IS NULL";
            String sql = "SELECT " + FULL_COLUMNS + " FROM ops_errors WHERE " + where;
            List<Object[]> rows;
            if (since != null) {
                rows = db.sqlQuery(sql + " AND first_seen >= ? ORDER BY last_seen DESC", Timestamp.from(since));
            } else {
                rows = db.sqlQuery(sql + " ORDER BY last_seen DESC LIMIT ?", LIST_LIMIT);
            }

            var result = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                result.add(mapRow(row));
            }
            return result;
        } finally {
            db.close();
        }
    }

    /** The full-detail column list — one definition for {@link #list}, {@link #find}, {@link #resolve} (mapped by {@link #mapRow}). */
    private static final String FULL_COLUMNS =
        "id, error_type, message, stack_trace, route, request_detail, first_seen, last_seen, "
        + "occurrence_count, resolved_at, queries_before, request_headers";

    /** Maps a full {@link #FULL_COLUMNS} ops_errors row to the JSON shape the ops endpoints return. */
    private static Map<String, Object> mapRow(Object[] row) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", ((Number) row[0]).longValue());
        map.put("errorType", row[1]);
        map.put("message", row[2]);
        map.put("stackTrace", row[3]);
        map.put("route", row[4]);
        map.put("requestDetail", row[5]);
        map.put("firstSeen", toInstant(row[6]));
        map.put("lastSeen", toInstant(row[7]));
        map.put("occurrenceCount", ((Number) row[8]).intValue());
        map.put("resolvedAt", toInstant(row[9]));
        map.put("queriesBefore", row[10]);
        map.put("requestHeaders", row[11]);
        return map;
    }

    /** Count of unresolved errors — for the ops dashboard summary (token-efficiency review). */
    public long countUnresolved() {
        var db = new Database(databaseFactory.openSession());
        try {
            Long n = db.sqlQueryLong("SELECT COUNT(*) FROM ops_errors WHERE resolved_at IS NULL");
            return n != null ? n : 0;
        } finally {
            db.close();
        }
    }

    /** Most-recent unresolved errors (compact shape + first app stack frame) for the dashboard. */
    public List<Map<String, Object>> recentUnresolved(int limit) {
        var db = new Database(databaseFactory.openSession());
        try {
            var rows = db.sqlQuery(
                "SELECT id, error_type, message, route, occurrence_count, first_seen, last_seen, stack_trace " +
                "FROM ops_errors WHERE resolved_at IS NULL ORDER BY last_seen DESC LIMIT ?", limit);
            var result = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                var m = new LinkedHashMap<String, Object>();
                m.put("id", ((Number) row[0]).longValue());
                m.put("errorType", row[1]);
                m.put("message", row[2]);
                m.put("route", row[3]);
                m.put("occurrenceCount", ((Number) row[4]).intValue());
                m.put("firstSeen", toInstant(row[5]));
                m.put("lastSeen", toInstant(row[6]));
                String at = Log.appFrame((String) row[7]);
                if (at != null) m.put("at", at);
                result.add(m);
            }
            return result;
        } finally {
            db.close();
        }
    }

    /** Full detail for one error by id, or null if not found. */
    public Map<String, Object> find(long id) {
        var db = new Database(databaseFactory.openSession());
        try {
            var rows = db.sqlQuery("SELECT " + FULL_COLUMNS + " FROM ops_errors WHERE id = ?", id);
            if (rows.isEmpty()) return null;
            return mapRow(rows.get(0));
        } finally {
            db.close();
        }
    }

    /**
     * Normalize whatever temporal type the JDBC driver surfaces for a {@code TIMESTAMP WITH TIME
     * ZONE} column into an absolute {@link Instant}. Hibernate native queries hand back an
     * {@link OffsetDateTime} on both H2 and Postgres, but {@link Timestamp} and {@link Instant} are
     * accepted too. This replaces the old multi-format string parser ({@code parseFirstSeen}): the
     * column stores a real instant now, so this is type dispatch, not zone guessing — and an
     * unexpected type fails loudly rather than silently returning null.
     */
    private static Instant toInstant(Object o) {
        return switch (o) {
            case null -> null;
            case Instant i -> i;
            case OffsetDateTime odt -> odt.toInstant();
            case Timestamp ts -> ts.toInstant();
            default -> throw new IllegalStateException(
                "Unexpected timestamp type from DB: " + o.getClass().getName());
        };
    }

    public Map<String, Object> resolve(long id) {
        var db = new Database(databaseFactory.openSession());
        db.beginTransaction();
        try {
            var now = Timestamp.from(Instant.now());
            db.sql("UPDATE ops_errors SET resolved_at = ? WHERE id = ?", now, id);
            // Re-fetch on the same session — shared FULL_COLUMNS + mapRow keep the row
            // shape from drifting (this method once hand-built the map and had drifted).
            var rows = db.sqlQuery("SELECT " + FULL_COLUMNS + " FROM ops_errors WHERE id = ?", id);
            db.commitTransaction();
            return rows.isEmpty() ? null : mapRow(rows.get(0));
        } catch (Exception e) {
            db.rollbackTransaction();
            return null;
        } finally {
            db.close();
        }
    }
}

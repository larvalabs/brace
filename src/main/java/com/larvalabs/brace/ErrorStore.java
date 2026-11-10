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
 * notifications lag by at most one interval, and past {@value #MAX_PENDING_KINDS} distinct pending
 * kinds new kinds are dropped (counted, warned on flush). Occurrences of already-pending kinds are
 * never dropped — they're a counter bump. Tests and shutdown call {@link #flush()} directly.
 *
 * <p>One bad entry cannot stall the store (security review 2026-11, M5). {@link #record} replaces
 * NUL characters (Postgres text cannot hold them, and pgjdbc rejects them client-side) and clips
 * {@code type} and {@code route} to their {@code VARCHAR(255)} columns. Each upsert runs under its
 * own savepoint, so a row the database rejects is rolled back alone while the rest of the batch
 * commits. A rejected row is retried once at once with its payload stripped (a fixed placeholder
 * message, no trace, detail or headers), so the kind's count and the regression signal survive;
 * if that is rejected too, the entry is retried on later flushes and dropped after
 * {@value #MAX_FLUSH_FAILURES} rejections. A connection-level failure (database down, pool
 * exhausted, or a connection-classed SQLState on a connection that then fails
 * {@code isValid}) is not the entry's fault: the whole batch is kept and retried, as before. A
 * connection-classed SQLState on a connection that is still valid (a statement timeout on one
 * huge row, a deadlock) counts as a row rejection. Failures are
 * logged at most once a minute, naming the exception class, SQLState and error type, never the
 * request-derived payload.
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
        long count;
        String message;
        String stackTrace;
        String requestDetail;
        String queriesBefore;
        String requestHeaders;
        Instant lastSeen;

        Pending(String type, String route, Instant firstSeen) {
            this.type = type;
            this.route = route;
            this.firstSeen = firstSeen;
        }

        /** Same kind, count and times; the payload replaced by {@link #STRIPPED_MESSAGE}. */
        Pending stripped() {
            var s = new Pending(type, route, firstSeen);
            s.failures = failures;
            s.count = count;
            s.lastSeen = lastSeen;
            s.message = STRIPPED_MESSAGE;
            return s;
        }
    }

    private final DatabaseFactory databaseFactory;
    private final int maxErrors;
    private volatile RegressionListener regressionListener;
    private final java.util.concurrent.ConcurrentHashMap<String, Pending> pending =
        new java.util.concurrent.ConcurrentHashMap<>();
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
    private String lastFailure;

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
                } catch (Exception e) {
                    // Never let the flusher die; the next interval retries.
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
        if (pending.size() >= MAX_PENDING_KINDS && !pending.containsKey(key)) {
            droppedKinds.increment();
            return;
        }
        // Served on /ops/errors; the trace repeats the message (and every cause's) verbatim.
        String redactedTrace = Redactor.redactStackTrace(withoutNul(stackTrace));
        pending.compute(key, (k, p) -> {
            if (p == null) p = new Pending(cleanType, cleanRoute, now);
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
        var batch = new ArrayList<Pending>();
        for (var key : pending.keySet()) {
            var p = pending.remove(key);
            if (p != null) batch.add(p);
        }
        if (batch.isEmpty()) {
            return;
        }

        var outcome = new byte[batch.size()];
        var rowErrors = new Throwable[batch.size()];
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
                    } catch (RowRejected first) {
                        // Retry once without the payload, so the kind's count and the regression
                        // signal survive whatever in the message, trace or detail was refused.
                        var stripped = p.stripped();
                        try {
                            outcome[i] = writeRow(connection, stripped, postgres);
                            batch.set(i, stripped);
                        } catch (RowRejected second) {
                            outcome[i] = REJECTED;
                            rowErrors[i] = first.getCause();
                        }
                    }
                }
                prune(connection);
            });
            db.commitTransaction();
            committed = true;
        } catch (Exception e) {
            if (db != null) {
                try {
                    db.rollbackTransaction();
                } catch (Exception ignored) {
                    // The connection is likely gone; closing below releases it.
                }
            }
            // DB unavailable (the storm case): put the batch back so counts keep accumulating —
            // bounded by kind cardinality, not error rate — and retry next interval.
            for (var p : batch) {
                mergeBack(p);
            }
            failedFlushes++;
            lastFailure = describe(e);
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

        // Rows the database rejected: retry a few times, then give up on that entry.
        for (int i = 0; i < batch.size(); i++) {
            if (outcome[i] != REJECTED) continue;
            var p = batch.get(i);
            rejectedRows++;
            lastFailure = describe(rowErrors[i]);
            if (++p.failures >= MAX_FLUSH_FAILURES) {
                droppedEntries++;
                if (droppedTypes.size() < 5) droppedTypes.add(forLog(p.type));
            } else {
                mergeBack(p);
            }
        }

        // Notify the regression listener only after a successful commit, off the DB path.
        var listener = regressionListener;
        if (listener != null) {
            for (int i = 0; i < batch.size(); i++) {
                var p = batch.get(i);
                if (outcome[i] == INSERTED) listener.onNew(p.type, p.route, p.message, p.firstSeen);
                else if (outcome[i] == UPDATED) listener.onRepeat(p.type, p.route, p.count);
            }
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
     * is gone. A connection-classed SQLState (08, 53, 57, 40, 25) is not trusted on its own: 57014
     * is a statement timeout on one huge row, 40P01 a deadlock on one key. Only when the
     * connection also fails its liveness check does the flush abort; otherwise the error is the
     * row's (M5 review). The rollback itself throws if the connection is gone.
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
        if (rejectedRows == 0 && failedFlushes == 0 && droppedEntries == 0) return;
        long now = System.nanoTime();
        if (now - nextFailureLogNanos < 0) return;
        nextFailureLogNanos = now + FAILURE_LOG_INTERVAL_NANOS;
        var msg = new StringBuilder("error store: could not persist errors");
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
        msg.append("; last failure: ").append(lastFailure);
        Log.warn(msg.toString());
        rejectedRows = 0;
        failedFlushes = 0;
        droppedEntries = 0;
        droppedTypes.clear();
    }

    /** Test hook: lets the next flush write its failure summary without waiting out the interval. */
    synchronized void expireFailureLogWindow() {
        nextFailureLogNanos = System.nanoTime();
    }

    /** Re-merge a failed-flush entry without clobbering anything newer that arrived meanwhile. */
    private void mergeBack(Pending old) {
        pending.compute(old.type + '\u0000' + old.route, (k, p) -> {
            if (p == null) return old;
            p.count += old.count; // keep p's payload — it's newer
            // Carry the rejection count, so a key the database always rejects is still dropped.
            p.failures = Math.max(p.failures, old.failures);
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
        try (var ps = connection.prepareStatement(
            "INSERT INTO ops_errors " +
            "(error_type, message, stack_trace, route, request_detail, queries_before, request_headers, first_seen, last_seen, occurrence_count) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (error_type, route) WHERE resolved_at IS NULL DO UPDATE SET " +
            "occurrence_count = ops_errors.occurrence_count + EXCLUDED.occurrence_count, " +
            "message = EXCLUDED.message, stack_trace = EXCLUDED.stack_trace, " +
            "request_detail = EXCLUDED.request_detail, queries_before = EXCLUDED.queries_before, " +
            "request_headers = EXCLUDED.request_headers, last_seen = EXCLUDED.last_seen " +
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

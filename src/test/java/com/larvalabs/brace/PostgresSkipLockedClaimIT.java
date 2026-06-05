package com.larvalabs.brace;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first Postgres testcontainer test (see {@code docs/2026-06-05-pg-testcontainers.md}).
 * The container + connection + truncation plumbing lives in {@link PostgresTestBase}; this
 * class adds only what it proves against <em>real</em> Postgres:
 *
 * <ol>
 *   <li><b>The shipped framework migrations (V1–V5) apply on Postgres.</b> Run through Brace's
 *       own Flyway config (same locations + history table as
 *       {@code DatabaseFactory.runFrameworkMigrations}). It deliberately does not go through
 *       {@code DatabaseFactory} — see {@link DatabaseFactoryPostgresIT} for that path — so this
 *       test stays focused on the raw SKIP LOCKED semantics.</li>
 *   <li><b>{@code FOR UPDATE SKIP LOCKED} hands disjoint batches to concurrent claimers.</b>
 *       The property H2 in-memory physically cannot express, and the reason the durable-job
 *       work (B7, the future batch claim) needs a real-Postgres tier.</li>
 * </ol>
 */
class PostgresSkipLockedClaimIT extends PostgresTestBase {

    @BeforeAll
    static void migrate() {
        // Base @BeforeAll has already started the shared container (and skipped the class if
        // Docker is absent). Apply the framework migrations exactly as DatabaseFactory does; if
        // any shipped framework DDL were Postgres-incompatible, this would fail here.
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:brace/db/migration")
                .table("flyway_brace_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    @BeforeEach
    void cleanTable() throws Exception {
        truncate("scheduled_jobs");
    }

    @Test
    void frameworkMigrationsApplyOnPostgres() throws Exception {
        // One success row per applied framework migration (V1–V5). >= 5 proves the shipped DDL
        // ran on real Postgres, not just H2.
        try (Connection conn = connect();
             var st = conn.createStatement();
             var rs = st.executeQuery(
                     "SELECT count(*) FROM flyway_brace_history WHERE success = true AND version IS NOT NULL")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 5,
                    "expected the V1–V5 framework migrations to be applied on Postgres");
        }
    }

    @Test
    void skipLockedHandsDisjointBatchesToConcurrentClaimers() throws Exception {
        seedClaimableJobs(4);

        // The batch claim the durable-job poller wants to move to (postgres-native doc, Tier 1a):
        // select + lock the head of the queue, skipping rows another transaction already holds.
        String claim = "SELECT id FROM scheduled_jobs " +
                "WHERE started_at IS NULL AND run_at <= CURRENT_TIMESTAMP " +
                "ORDER BY run_at LIMIT 2 " +
                "FOR UPDATE SKIP LOCKED";

        try (Connection a = connect(); Connection b = connect()) {
            a.setAutoCommit(false);
            b.setAutoCommit(false);

            // A claims (and locks) the first 2 rows but does not commit.
            Set<Long> batchA = claimIds(a, claim);
            // B runs the identical claim while A still holds its locks. Under SKIP LOCKED it must
            // skip A's locked rows and take the next 2 — not block, not re-claim the same rows.
            Set<Long> batchB = claimIds(b, claim);

            assertEquals(2, batchA.size(), "A should claim exactly 2 rows");
            assertEquals(2, batchB.size(), "B should claim exactly 2 rows");
            assertTrue(Collections.disjoint(batchA, batchB),
                    "SKIP LOCKED must hand B a disjoint batch, never A's locked rows");

            Set<Long> union = new HashSet<>(batchA);
            union.addAll(batchB);
            assertEquals(4, union.size(),
                    "the two concurrent claimers together cover all 4 rows exactly once");

            a.rollback();
            b.rollback();
        }
    }

    // --- helpers ---

    private static void seedClaimableJobs(int n) throws Exception {
        try (Connection conn = connect();
             var ps = conn.prepareStatement(
                     "INSERT INTO scheduled_jobs (name, job_class, run_at) VALUES (?, ?, ?)")) {
            Instant now = Instant.now();
            for (int i = 0; i < n; i++) {
                ps.setString(1, "job-" + i);
                ps.setString(2, "com.example.NoopJob");
                // Strictly increasing run_at so ORDER BY run_at is deterministic.
                ps.setTimestamp(3, Timestamp.from(now.minusSeconds(n - i)));
                ps.addBatch();
            }
            ps.executeBatch(); // autocommit on → committed and visible to other connections
        }
    }

    private static Set<Long> claimIds(Connection conn, String sql) throws Exception {
        Set<Long> ids = new LinkedHashSet<>();
        try (var ps = conn.prepareStatement(sql); var rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
            }
        }
        return ids;
    }
}

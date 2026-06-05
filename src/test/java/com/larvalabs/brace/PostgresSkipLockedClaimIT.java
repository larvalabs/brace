package com.larvalabs.brace;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 0 pilot for the Postgres testcontainer test tier
 * (see {@code docs/2026-06-05-pg-testcontainers.md}).
 *
 * <p>This is an {@code *IT}, so it runs in the {@code integration-test}/{@code verify}
 * phases via maven-failsafe-plugin — {@code mvn test} (surefire, H2) never sees it and
 * stays Docker-free. It exists to de-risk the whole toolchain before any existing test is
 * migrated, by proving three things end-to-end against <em>real</em> Postgres:
 *
 * <ol>
 *   <li><b>Container boots with the test-speed knobs.</b> tmpfs data dir + {@code fsync=off}
 *       + {@code full_page_writes=off} + {@code synchronous_commit=off} — safe only because a
 *       test DB is disposable; never do this in production.</li>
 *   <li><b>The shipped framework migrations (V1–V5) apply on Postgres.</b> Run through Brace's
 *       own Flyway config (same locations + history table as
 *       {@code DatabaseFactory.runFrameworkMigrations}). Note: the pilot deliberately does
 *       <em>not</em> go through {@code DatabaseFactory}, because that also runs the test-app
 *       migration {@code db/migration/V1__create_posts.sql}, which uses H2/MySQL-only
 *       {@code AUTO_INCREMENT} and fails on Postgres — itself an example of the dialect bug
 *       this tier is meant to catch.</li>
 *   <li><b>{@code FOR UPDATE SKIP LOCKED} hands disjoint batches to concurrent claimers.</b>
 *       This is the property H2 in-memory physically cannot express, and the reason the
 *       multi-server durable-job work (B7, the future batch claim) needs a real Postgres tier:
 *       today those fixes are validated on a DB that can't exhibit the race they fix.</li>
 * </ol>
 *
 * <p>Isolation is by truncation (not transaction-rollback): the concurrency test needs really
 * committed rows visible across two separate JDBC connections, which rollback isolation forbids.
 */
class PostgresSkipLockedClaimIT {

    static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startContainerAndMigrate() {
        // Skip cleanly (rather than fail) when Docker is absent — e.g. a contributor running
        // `mvn verify` on a machine without Docker. CI (ubuntu-latest) has Docker.
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available — skipping Postgres integration test");

        postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("bracetest")
                .withUsername("brace")
                .withPassword("brace")
                // Disposable test DB: trade all durability for speed. tmpfs puts the data dir in
                // RAM; the flags skip the fsync/WAL work Postgres does to survive a crash we don't
                // care about here. NEVER use any of this in production.
                .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"))
                .withCommand("postgres",
                        "-c", "fsync=off",
                        "-c", "full_page_writes=off",
                        "-c", "synchronous_commit=off")
                // Honored only if ~/.testcontainers.properties has testcontainers.reuse.enable=true;
                // keeps the container alive across local `mvn verify` runs. Ignored in CI.
                .withReuse(true);
        postgres.start();

        // Apply the *framework* migrations (V1–V5) to real Postgres, exactly as
        // DatabaseFactory.runFrameworkMigrations does. If any shipped framework DDL were
        // Postgres-incompatible, this would fail here — which is the point.
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:brace/db/migration")
                .table("flyway_brace_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    // No @AfterAll stop(): this is the singleton-container pattern. With reuse disabled,
    // Ryuk reaps the container at JVM exit; with reuse enabled it intentionally lingers for
    // the next `mvn verify`. Either way we don't tear it down per class.

    @BeforeEach
    void cleanTable() throws Exception {
        try (Connection conn = connect(); var st = conn.createStatement()) {
            st.execute("TRUNCATE scheduled_jobs RESTART IDENTITY CASCADE");
        }
    }

    @Test
    void frameworkMigrationsApplyOnPostgres() throws Exception {
        // The history table records one success row per applied framework migration (V1–V5).
        // Asserting >= 5 proves the shipped DDL ran on real Postgres, not just H2.
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

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

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

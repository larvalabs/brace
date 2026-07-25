package com.larvalabs.brace;

import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Map;

/**
 * Base for the Postgres testcontainer tier (see {@code docs/2026-06-05-pg-testcontainers.md}).
 * Subclasses are {@code *IT} tests run by maven-failsafe in the {@code integration-test}/
 * {@code verify} phases — {@code mvn test} (surefire, H2) never runs them, so the fast unit
 * suite stays Docker-free.
 *
 * <p>Provides the shared container and the isolation/connection helpers, so an IT only writes
 * its own setup and assertions:
 * <ul>
 *   <li><b>One container for the whole run.</b> {@link #POSTGRES} is a single {@code static}
 *       field on this base, so every IT subclass resolves to the same instance (the
 *       singleton-container pattern). Construction happens at class load (cheap, needs no
 *       Docker); it's started lazily in {@link #ensureStarted()} behind a Docker-availability
 *       check, which <b>fails the build</b> rather than skipping when Docker is absent — see the
 *       comment there for why silently skipping made {@code mvn verify} useless as a gate.</li>
 *   <li><b>Disposable-DB speed knobs.</b> tmpfs data dir + {@code fsync=off} +
 *       {@code full_page_writes=off} + {@code synchronous_commit=off}. Safe only because the DB
 *       is thrown away — NEVER use any of this in production.</li>
 *   <li><b>Truncation isolation</b> ({@link #truncate}). Not transaction-rollback: Brace manages
 *       its own per-request transactions, and concurrency tests need committed rows visible
 *       across separate connections, which rollback isolation forbids.</li>
 * </ul>
 *
 * <p>No teardown hook: this is the singleton pattern — with reuse disabled Ryuk reaps the
 * container at JVM exit; with {@code testcontainers.reuse.enable=true} it intentionally lingers
 * for the next {@code mvn verify}.
 */
abstract class PostgresTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("bracetest")
                    .withUsername("brace")
                    .withPassword("brace")
                    .withTmpFs(Map.of("/var/lib/postgresql/data", "rw"))
                    .withCommand("postgres",
                            "-c", "fsync=off",
                            "-c", "full_page_writes=off",
                            "-c", "synchronous_commit=off")
                    // Honored only if ~/.testcontainers.properties opts in; keeps the container
                    // alive across local `mvn verify` runs. Ignored in CI (ephemeral runner).
                    .withReuse(true);

    @BeforeAll
    static void ensureStarted() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            // Fail closed, deliberately. This used to be a JUnit assumption, which aborts the class
            // — and failsafe reports an aborted class as `Tests run: 0`, NOT as skipped. So
            // `mvn verify` printed BUILD SUCCESS while the entire Postgres tier silently tested
            // nothing. Since AGENTS.md makes `mvn verify` the real-Postgres merge gate, a tier that
            // cannot run must break the build rather than quietly pass it.
            //
            // This is not hypothetical: on Docker 29.x, docker-java negotiates API 1.32 while the
            // daemon requires >= 1.40, so every connection strategy fails on a perfectly healthy
            // Docker install and the gate went green regardless. See the docker.api.version
            // property in pom.xml, which pins a version both ends accept.
            //
            // To skip this tier on purpose, use Maven's own switch: `mvn verify -DskipITs`. Don't
            // reintroduce an assumption here — the whole point is that "couldn't run" != "passed".
            throw new IllegalStateException(
                    "Docker is not available, so the Postgres integration tier cannot run. "
                    + "Refusing to pass `mvn verify` without it — that would report success while "
                    + "testing nothing. Start Docker, run the Docker-free unit suite with "
                    + "`mvn test`, or skip this tier explicitly with `mvn verify -DskipITs`. "
                    + "If Docker IS running, check the Docker Engine API version "
                    + "(see the docker.api.version property in pom.xml).");
        }
        // Idempotent across subclasses: only the first to run actually starts the shared container.
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    /** A fresh raw JDBC connection to the container — for multi-connection concurrency tests. */
    protected static Connection connect() throws Exception {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    /** Truncate tables ({@code RESTART IDENTITY CASCADE}) for between-test isolation. */
    protected static void truncate(String... tables) throws Exception {
        try (Connection c = connect(); var st = c.createStatement()) {
            for (String t : tables) {
                st.execute("TRUNCATE " + t + " RESTART IDENTITY CASCADE");
            }
        }
    }
}

package com.larvalabs.brace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M13: third-party startup log noise is quieted by default and restorable via
 * {@code -Dlog.level.<logger>=<level>}.
 *
 * <p>Assertions use a recording {@link Handler} on the JUL root logger rather than swapping
 * {@code System.err}: JUL's {@code ConsoleHandler} captures the real stderr stream when the
 * LogManager initializes (long before any test runs), so a {@code System.setErr} capture never
 * sees its output. The recording handler observes exactly the records that pass the logger-level
 * filter — i.e. the lines that would reach the console.
 */
class JulLoggingTest {

    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Handler recorder = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void attachRecorder() {
        recorder.setLevel(Level.ALL);
        Logger.getLogger("").addHandler(recorder);
    }

    @AfterEach
    void detachRecorder() {
        Logger.getLogger("").removeHandler(recorder);
        // Restore defaults in case a test changed levels via overrides.
        System.clearProperty("log.level.org.hibernate");
        JulLogging.apply();
    }

    private List<LogRecord> infoOrBelowFrom(String prefix) {
        return records.stream()
            .filter(r -> r.getLoggerName() != null && r.getLoggerName().startsWith(prefix))
            .filter(r -> r.getLevel().intValue() < Level.WARNING.intValue())
            .toList();
    }

    @Test
    void bootIsQuietByDefault() throws Exception {
        JulLogging.apply();
        records.clear();

        // The noisy startup path: Flyway migrations (framework + app) and a Hibernate
        // SessionFactory with HikariCP — then a full Jetty boot via TestApp.
        var factory = new DatabaseFactory("jdbc:h2:mem:julquiet;DB_CLOSE_DELAY=-1", null, null, List.of());
        var app = Brace.test().start(a -> a.get("/jul-quiet", req -> Result.text("ok")));
        try {
            assertEquals("ok", app.get("/jul-quiet").body());
        } finally {
            app.stop();
            factory.close();
        }

        for (var lib : JulLogging.QUIET_LOGGERS) {
            var leaked = infoOrBelowFrom(lib);
            assertTrue(leaked.isEmpty(), () -> "expected no INFO-level startup records from " + lib
                + " but got: " + leaked.stream().map(LogRecord::getMessage).toList());
        }
    }

    @Test
    void logLevelOverrideRestoresInfoLines() throws Exception {
        System.setProperty("log.level.org.hibernate", "INFO");
        try {
            JulLogging.apply();
            records.clear();

            // Building a SessionFactory re-logs Hibernate INFO lines (connection provider,
            // database info, JTA platform).
            var factory = new DatabaseFactory("jdbc:h2:mem:juloverride;DB_CLOSE_DELAY=-1", null, null, List.of());
            factory.close();

            assertFalse(infoOrBelowFrom("org.hibernate").isEmpty(),
                "expected INFO records from org.hibernate with -Dlog.level.org.hibernate=INFO");
        } finally {
            System.clearProperty("log.level.org.hibernate");
            JulLogging.apply();
        }
    }

    @Test
    void warningsFromQuietedLibsStillGetThrough() {
        JulLogging.apply();
        records.clear();

        Logger.getLogger("org.hibernate.engine.jdbc").warning("synthetic warning");
        Logger.getLogger("org.flywaydb.core.internal").severe("synthetic error");

        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.WARNING
                && r.getLoggerName().startsWith("org.hibernate")),
            "WARNING from a quieted namespace must still be published");
        assertTrue(records.stream().anyMatch(r -> r.getLevel() == Level.SEVERE
                && r.getLoggerName().startsWith("org.flywaydb")),
            "SEVERE from a quieted namespace must still be published");
    }

    @Test
    void singleLineFormatterEmitsOneLine() {
        var record = new LogRecord(Level.WARNING, "something happened");
        record.setLoggerName("org.hibernate.orm.deprecation");

        var formatted = new JulLogging.SingleLineFormatter().format(record);

        assertTrue(formatted.endsWith(System.lineSeparator()));
        assertEquals(1, formatted.lines().count(), "expected a single line, got: " + formatted);
        assertTrue(formatted.contains("WARN org.hibernate.orm.deprecation something happened"));
    }

    @Test
    void parseLevelAcceptsSlf4jAndJulNames() {
        assertEquals(Level.SEVERE, JulLogging.parseLevel("error"));
        assertEquals(Level.WARNING, JulLogging.parseLevel("WARN"));
        assertEquals(Level.INFO, JulLogging.parseLevel("Info"));
        assertEquals(Level.FINE, JulLogging.parseLevel("DEBUG"));
        assertEquals(Level.FINEST, JulLogging.parseLevel("TRACE"));
        assertEquals(Level.OFF, JulLogging.parseLevel("off"));
        assertNull(JulLogging.parseLevel("bogus"));
        assertNull(JulLogging.parseLevel(null));
        assertNull(JulLogging.parseLevel("  "));
    }
}

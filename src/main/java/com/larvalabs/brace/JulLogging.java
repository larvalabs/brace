package com.larvalabs.brace;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Tames third-party startup log noise by configuring java.util.logging (JUL) once, before the
 * noisy libraries initialize.
 *
 * <p>Everything third-party funnels into JUL: Hibernate (via jboss-logging) and Flyway prefer
 * slf4j when a provider is present, Jetty and HikariCP log to slf4j directly, and Brace ships
 * {@code slf4j-jdk14} so slf4j itself is backed by JUL. This class then makes that single sink
 * quiet by default:
 * <ul>
 *   <li>single-line output (JUL's default {@code SimpleFormatter} prints two lines per record),</li>
 *   <li>level {@code WARNING} for {@code org.hibernate}, {@code org.flywaydb},
 *       {@code com.zaxxer.hikari} and {@code org.eclipse.jetty} — startup banners, dialect info
 *       and migration progress disappear; warnings and errors still print.</li>
 * </ul>
 *
 * <p>Override with system properties: {@code -Dlog.level.<logger>=<level>}, e.g.
 * {@code -Dlog.level.org.hibernate=INFO} or {@code -Dlog.level.org.flywaydb=DEBUG}. Levels accept
 * slf4j names (ERROR, WARN, INFO, DEBUG, TRACE) and JUL names (SEVERE, WARNING, FINE, …), plus
 * OFF/ALL. System properties are used (not {@link Config}) because apps construct
 * {@link DatabaseFactory} before any Brace API sees their config — there is no config object
 * available at the moment the noisy libraries boot.
 *
 * <p>Apps that configure JUL themselves via {@code -Djava.util.logging.config.file} or
 * {@code -Djava.util.logging.config.class} are left completely alone.
 */
final class JulLogging {

    /** Loggers quieted to WARNING by default (children inherit). */
    static final String[] QUIET_LOGGERS = {
        "org.hibernate", "org.flywaydb", "com.zaxxer.hikari", "org.eclipse.jetty"
    };

    /** JUL's LogManager holds loggers weakly; pin ours so the configured levels survive GC. */
    private static final List<Logger> pinned = new ArrayList<>();
    private static volatile boolean initialized;

    private JulLogging() {
    }

    /**
     * Idempotent entry point, called from {@link Brace#app()} and the {@link DatabaseFactory}
     * constructor — whichever the app touches first, before Hibernate/Flyway/Jetty log anything.
     */
    static void init() {
        if (initialized) {
            return;
        }
        synchronized (JulLogging.class) {
            if (initialized) {
                return;
            }
            // The app brought its own JUL configuration — don't touch anything.
            if (System.getProperty("java.util.logging.config.file") == null
                && System.getProperty("java.util.logging.config.class") == null) {
                apply();
            }
            initialized = true;
        }
    }

    /**
     * The re-runnable core: formatter + default levels + {@code log.level.*} overrides.
     * Package-private so tests can re-apply after changing override properties.
     */
    static synchronized void apply() {
        var root = Logger.getLogger("");
        for (var handler : root.getHandlers()) {
            if (handler instanceof ConsoleHandler) {
                handler.setFormatter(new SingleLineFormatter());
                // Let logger levels do ALL the filtering. The default ConsoleHandler level
                // is INFO, which silently drops the records a -Dlog.level.<logger>=DEBUG/
                // TRACE override just enabled — the logger says loggable, the handler
                // discards. Loggers without an explicit level still inherit the root
                // logger's INFO, so output is unchanged for everything else.
                handler.setLevel(Level.ALL);
            }
        }
        for (var name : QUIET_LOGGERS) {
            setLevel(name, Level.WARNING);
        }
        // Overrides — applied after defaults so they win; also work for any other logger.
        for (var key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("log.level.")) {
                var level = parseLevel(System.getProperty(key));
                if (level != null) {
                    setLevel(key.substring("log.level.".length()), level);
                }
            }
        }
    }

    private static void setLevel(String loggerName, Level level) {
        var logger = Logger.getLogger(loggerName);
        logger.setLevel(level);
        synchronized (pinned) {
            pinned.add(logger);
        }
    }

    /** Accepts slf4j-style and JUL-style level names; returns null for unrecognized values. */
    static Level parseLevel(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase()) {
            case "ERROR", "SEVERE" -> Level.SEVERE;
            case "WARN", "WARNING" -> Level.WARNING;
            case "INFO" -> Level.INFO;
            case "DEBUG", "FINE" -> Level.FINE;
            case "FINER" -> Level.FINER;
            case "TRACE", "FINEST" -> Level.FINEST;
            case "OFF" -> Level.OFF;
            case "ALL" -> Level.ALL;
            default -> null;
        };
    }

    /** One line per record: {@code HH:mm:ss LEVEL logger message}, plus a stack trace if thrown. */
    static final class SingleLineFormatter extends Formatter {
        private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

        @Override
        public String format(LogRecord record) {
            var sb = new StringBuilder();
            sb.append(TIME.format(LocalTime.ofInstant(record.getInstant(), ZoneId.systemDefault())))
                .append(' ').append(levelName(record.getLevel()))
                .append(' ').append(record.getLoggerName())
                .append(' ').append(formatMessage(record))
                .append(System.lineSeparator());
            if (record.getThrown() != null) {
                var sw = new StringWriter();
                record.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(sw);
            }
            return sb.toString();
        }

        private static String levelName(Level level) {
            if (level == Level.SEVERE) return "ERROR";
            if (level == Level.WARNING) return "WARN";
            if (level == Level.FINE) return "DEBUG";
            if (level == Level.FINER || level == Level.FINEST) return "TRACE";
            return level.getName();
        }
    }
}

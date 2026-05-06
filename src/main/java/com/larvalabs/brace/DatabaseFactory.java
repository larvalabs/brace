package com.larvalabs.brace;

import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.hibernate.cfg.Configuration;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages database lifecycle: runs Flyway migrations then builds a Hibernate SessionFactory.
 * No XML config files — everything is programmatic.
 */
public class DatabaseFactory {

    private final SessionFactory sessionFactory;
    private final List<Class<?>> entityClasses;

    public DatabaseFactory(String url, String user, String password, List<Class<?>> entityClasses) {
        this(url, user, password, entityClasses, 10);
    }

    public DatabaseFactory(String url, String user, String password, List<Class<?>> entityClasses, int poolSize) {
        var cfg = parseDbConfig(url, user, password);
        runFrameworkMigrations(cfg.url(), cfg.user(), cfg.pass());
        runAppMigrations(cfg.url(), cfg.user(), cfg.pass());
        this.sessionFactory = buildSessionFactory(cfg.url(), cfg.user(), cfg.pass(), entityClasses, poolSize);
        this.entityClasses = List.copyOf(entityClasses);
    }

    public List<Class<?>> entityClasses() {
        return entityClasses;
    }

    public StatelessSession openSession() {
        return sessionFactory.openStatelessSession();
    }

    public <T> T withSession(java.util.function.Function<Database, T> action) {
        var db = new Database(openSession());
        db.beginTransaction();
        try {
            T result = action.apply(db);
            db.commitTransaction();
            return result;
        } catch (Exception e) {
            db.rollbackTransaction();
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            db.close();
        }
    }

    public void withSession(java.util.function.Consumer<Database> action) {
        var db = new Database(openSession());
        db.beginTransaction();
        try {
            action.accept(db);
            db.commitTransaction();
        } catch (Exception e) {
            db.rollbackTransaction();
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        } finally {
            db.close();
        }
    }

    public void close() {
        sessionFactory.close();
    }

    private void runFrameworkMigrations(String url, String user, String password) {
        // Framework migrations live in their own classpath dir and use their own history table,
        // so app and framework version spaces are independent — apps can use V1 even though
        // framework also has a V1. baselineVersion(0) keeps a pre-existing schema (e.g. an app
        // upgrading from a Brace release that didn't ship migrations) from causing V1 to be
        // marked already-applied. Don't customize sqlMigrationPrefix: "B" is reserved by Flyway
        // for baseline migrations and silently breaks multi-file scans.
        Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:brace/db/migration")
                .table("flyway_brace_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private void runAppMigrations(String url, String user, String password) {
        var locations = new ArrayList<String>();
        locations.add("classpath:db/migration");
        if (Files.isDirectory(Path.of("migrations"))) {
            locations.add("filesystem:migrations");
        }
        // baselineVersion(0) is critical here: the framework migrations run first, so the app's
        // schema is non-empty by the time this runs. Default baselineVersion is "1" — which would
        // mark V1 as already-applied and silently skip it. Anchoring the baseline at 0 keeps every
        // app V-migration on the apply path.
        Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations.toArray(String[]::new))
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private SessionFactory buildSessionFactory(String url, String user, String password,
                                               List<Class<?>> entityClasses, int poolSize) {
        var configuration = new Configuration();
        configuration.setProperty("hibernate.connection.url", url);
        if (user != null) {
            configuration.setProperty("hibernate.connection.username", user);
        }
        if (password != null) {
            configuration.setProperty("hibernate.connection.password", password);
        }
        configuration.setProperty("hibernate.dialect", detectDialect(url));
        configuration.setProperty("hibernate.hbm2ddl.auto", "none");
        configuration.setProperty("hibernate.connection.provider_class",
                "org.hibernate.hikaricp.internal.HikariCPConnectionProvider");
        configuration.setProperty("hibernate.hikari.maximumPoolSize", String.valueOf(poolSize));
        configuration.setProperty("hibernate.hikari.minimumIdle", String.valueOf(poolSize));

        for (var entityClass : entityClasses) {
            configuration.addAnnotatedClass(entityClass);
        }

        return configuration.buildSessionFactory();
    }

    private static String detectDialect(String url) {
        if (url.startsWith("jdbc:h2:")) {
            return "org.hibernate.dialect.H2Dialect";
        } else if (url.startsWith("jdbc:postgresql:")) {
            return "org.hibernate.dialect.PostgreSQLDialect";
        } else if (url.startsWith("jdbc:mysql:")) {
            return "org.hibernate.dialect.MySQLDialect";
        }
        throw new IllegalArgumentException("Unsupported JDBC URL: " + url
                + " — expected jdbc:h2:, jdbc:postgresql:, or jdbc:mysql:");
    }

    /** Parsed database configuration: a JDBC URL stripped of credentials, plus user/pass. */
    public record DbConfig(String url, String user, String pass) {}

    /**
     * Normalize a database URL and split out any embedded credentials so the JDBC driver gets
     * a clean URL plus separate user/password.
     *
     * <p>Every PaaS we deploy on (Dokploy, Heroku, Render, Railway, Fly) injects {@code DATABASE_URL}
     * in the form {@code postgresql://user:pass@host:port/db}. Two things break with that as a raw
     * JDBC URL: the missing {@code jdbc:} prefix (Flyway logs "No database found to handle &lt;url&gt;")
     * and the {@code user:pass@} authority (pgjdbc treats it as a hostname → UnknownHostException).
     *
     * <p>This method handles both: bare {@code postgresql://} / {@code postgres://} schemes get a
     * {@code jdbc:} prefix, and {@code user:pass@} authorities are stripped and returned separately.
     * Explicit {@code user} / {@code password} args win when set; URL-embedded credentials are the
     * fallback. URL-encoded characters in the password are decoded (libpq parity).
     */
    public static DbConfig parseDbConfig(String rawUrl, String explicitUser, String explicitPass) {
        String normalized = normalizeJdbcUrl(rawUrl);
        if (normalized == null) {
            return new DbConfig(null, blankToNull(explicitUser), blankToNull(explicitPass));
        }

        String embeddedUser = null;
        String embeddedPass = null;
        String stripped = normalized;

        int schemeEnd = normalized.indexOf("://");
        if (schemeEnd > 0) {
            int hostStart = schemeEnd + 3;
            int pathStart = indexOfAny(normalized, hostStart, '/', '?');
            int searchEnd = pathStart >= 0 ? pathStart : normalized.length();
            int authEnd = normalized.lastIndexOf('@', searchEnd - 1);
            if (authEnd > hostStart) {
                String auth = normalized.substring(hostStart, authEnd);
                int colon = auth.indexOf(':');
                if (colon >= 0) {
                    embeddedUser = decode(auth.substring(0, colon));
                    embeddedPass = decode(auth.substring(colon + 1));
                } else {
                    embeddedUser = decode(auth);
                }
                stripped = normalized.substring(0, hostStart) + normalized.substring(authEnd + 1);
            }
        }

        String user = blankToNull(explicitUser);
        if (user == null) user = embeddedUser;
        String pass = blankToNull(explicitPass);
        if (pass == null) pass = embeddedPass;

        return new DbConfig(stripped, user, pass);
    }

    static String normalizeJdbcUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.startsWith("jdbc:")) return u;
        if (u.startsWith("postgresql://")) return "jdbc:" + u;
        if (u.startsWith("postgres://")) return "jdbc:postgresql://" + u.substring("postgres://".length());
        return u;
    }

    private static int indexOfAny(String s, int from, char a, char b) {
        int ai = s.indexOf(a, from);
        int bi = s.indexOf(b, from);
        if (ai < 0) return bi;
        if (bi < 0) return ai;
        return Math.min(ai, bi);
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}

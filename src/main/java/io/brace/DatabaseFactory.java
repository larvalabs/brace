package io.brace;

import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.hibernate.StatelessSession;
import org.hibernate.cfg.Configuration;

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
        runMigrations(url, user, password);
        this.sessionFactory = buildSessionFactory(url, user, password, entityClasses, poolSize);
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

    private void runMigrations(String url, String user, String password) {
        var locations = new ArrayList<String>();
        locations.add("classpath:db/migration");
        if (Files.isDirectory(Path.of("migrations"))) {
            locations.add("filesystem:migrations");
        }
        Flyway.configure()
                .dataSource(url, user, password)
                .locations(locations.toArray(String[]::new))
                .baselineOnMigrate(true)
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
}

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

    public DatabaseFactory(String url, String user, String password, List<Class<?>> entityClasses) {
        runMigrations(url, user, password);
        this.sessionFactory = buildSessionFactory(url, user, password, entityClasses);
    }

    public StatelessSession openSession() {
        return sessionFactory.openStatelessSession();
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
                .load()
                .migrate();
    }

    private SessionFactory buildSessionFactory(String url, String user, String password,
                                               List<Class<?>> entityClasses) {
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

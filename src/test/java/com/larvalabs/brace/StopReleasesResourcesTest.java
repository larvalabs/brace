package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness review M4 and M5: {@code Brace.stop()} must release what {@code start()} took.
 * It previously left the HikariCP pool and Hibernate SessionFactory open (poolSize live
 * connections per stopped app, since {@code minimumIdle == maximumPoolSize}) and left the
 * rate limiter's process-global statics pointing at the app that just went away.
 */
class StopReleasesResourcesTest {


    @Test
    void stopClosesTheDatabaseFactoryItOwns() throws Exception {
        var app = Brace.test().entities(com.larvalabs.brace.testmodels.User.class).start(a -> {
            a.get("/", req -> Result.text("ok"));
        });
        var factory = app.app().databaseFactory();
        // Live before stop.
        factory.openSession().close();

        app.stop();

        assertThrows(Exception.class, factory::openSession,
            "stop() must close the pool it owns, so opening a session afterwards fails");
    }

    @Test
    void ownsDatabaseFalseLeavesTheFactoryToTheCaller() throws Exception {
        var factory = new DatabaseFactory(
            "jdbc:h2:mem:owns-database-false;DB_CLOSE_DELAY=-1", null, null,
            java.util.List.of(com.larvalabs.brace.testmodels.User.class));

        var app = Brace.app().port(0).banner(false).database(factory).ownsDatabase(false);
        app.get("/", req -> Result.text("ok"));
        app.start();
        app.stop();

        // Still usable — the caller said it owns the lifecycle.
        factory.openSession().close();
        factory.close();
    }

    /**
     * A failed start() calls stop() to undo what it started, and stop() closes an owned factory.
     * A shared one ({@code ownsDatabase(false)}) must survive that cleanup too, or one app's
     * startup failure would take the pool down under every other app using it.
     */
    @Test
    void failedStartLeavesASharedFactoryOpen(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        var factory = new DatabaseFactory(
            "jdbc:h2:mem:owns-database-false-failed-start;DB_CLOSE_DELAY=-1", null, null,
            java.util.List.of(com.larvalabs.brace.testmodels.User.class));

        // .ops(...) with a missing keys file makes start() throw after the app is wired up.
        var app = Brace.app().port(0).banner(false).database(factory).ownsDatabase(false)
            .ops(dir.resolve("missing-authorized-keys").toString());
        app.get("/", req -> Result.text("ok"));
        assertThrows(RuntimeException.class, app::start);

        factory.openSession().close();
        factory.close();
    }

    @Test
    void stopReleasesTheRateLimiterRegistry() throws Exception {
        var app = Brace.test().start(a ->
            a.before("/limited", RateLimiter.perIp(5, "1m")));
        assertFalse(RateLimiter.allStats().isEmpty(), "the limiter should be registered while running");

        app.stop();

        assertTrue(RateLimiter.allStats().isEmpty(),
            "stop() must drop limiters from the process-global registry, "
                + "or /ops keeps reporting limiters whose app is gone");
    }
}

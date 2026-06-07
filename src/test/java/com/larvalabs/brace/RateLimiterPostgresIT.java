package com.larvalabs.brace;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B4 on real Postgres: a rate limit is enforced once across the fleet, not once per instance. Two
 * {@link RateLimiter} instances — standing in for the same {@code perIp(5,"1m")} limiter running on
 * two boxes — share one {@link Counters} over Postgres. Requests split across both must allow only
 * the cluster-wide budget (5), not 5 per instance.
 *
 * <p>The H2 unit suite ({@code RateLimiterTest}) covers the per-process path; only real Postgres
 * exercises the shared atomic counter that makes the count cluster-wide.
 */
class RateLimiterPostgresIT extends PostgresTestBase {

    static DatabaseFactory dbFactory;

    @BeforeAll
    static void installBackend() {
        // Base @BeforeAll started the shared container (skips the class if Docker is absent).
        dbFactory = new DatabaseFactory(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), List.of());
        RateLimiter.useSharedBackend(new Counters(dbFactory));
    }

    @AfterAll
    static void removeBackend() {
        RateLimiter.disableSharedBackend();
        if (dbFactory != null) dbFactory.close();
    }

    @BeforeEach
    void clean() throws Exception {
        truncate("brace_counters");
    }

    private Request fakeRequest(String ip) {
        return new Request("GET", "/test", Map.of(), Map.of(), Map.of(), null, Map.of(), ip, null);
    }

    @Test
    void limitIsEnforcedClusterWideNotPerInstance() {
        // Two limiters with identical config = the same logical limiter on two instances.
        var instanceA = RateLimiter.perIp(5, "1m");
        var instanceB = RateLimiter.perIp(5, "1m");

        int allowed = 0;
        int blocked = 0;
        for (int i = 0; i < 10; i++) {
            var limiter = (i % 2 == 0) ? instanceA : instanceB; // alternate across "instances"
            var result = limiter.handle(fakeRequest("9.9.9.9"));
            if (result == null) {
                allowed++;
            } else {
                blocked++;
                assertEquals(429, result.status());
                assertNotNull(result.header("Retry-After"));
            }
        }

        // Per-process counting would allow 5 on EACH limiter = 10. Shared counting allows 5 total.
        assertEquals(5, allowed, "shared limit should allow only the cluster-wide budget");
        assertEquals(5, blocked, "requests past the cluster-wide budget should be blocked on either instance");
    }

    @Test
    void differentKeysAreCountedIndependently() {
        var instanceA = RateLimiter.perIp(2, "1m");
        var instanceB = RateLimiter.perIp(2, "1m");

        // Each IP gets its own cluster-wide budget of 2, spread across both instances.
        assertNull(instanceA.handle(fakeRequest("1.1.1.1")));
        assertNull(instanceB.handle(fakeRequest("1.1.1.1")));
        assertNotNull(instanceA.handle(fakeRequest("1.1.1.1")), "1.1.1.1 over its budget");

        assertNull(instanceA.handle(fakeRequest("2.2.2.2")));
        assertNull(instanceB.handle(fakeRequest("2.2.2.2")));
        assertNotNull(instanceB.handle(fakeRequest("2.2.2.2")), "2.2.2.2 over its budget");
    }
}

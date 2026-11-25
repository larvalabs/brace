package com.larvalabs.brace;

import org.junit.jupiter.api.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CheckThresholdsTest {

    @Test
    void overridesFromConfig() {
        var t = CheckThresholds.fromConfig(Map.of(
            "check.slow_route_ms", "1000",
            "check.heap_fail_percent", "90",
            "check.cache_hit_rate", "0.8"
        ));
        assertEquals(1000, t.slowRouteMs());
        assertEquals(70, t.heapWarnPercent());  // not overridden
        assertEquals(90, t.heapFailPercent());
        assertEquals(0.8, t.cacheHitRate(), 0.001);
    }

    @Test
    void defaultsWhenNoConfigKeys() {
        var t = CheckThresholds.fromConfig(Map.of());
        assertEquals(500, t.slowRouteMs());
        assertEquals(70, t.heapWarnPercent());
        assertEquals(80, t.heapFailPercent());
        assertEquals(50, t.gcPauseMs());
        assertEquals(0.5, t.cacheHitRate(), 0.001);
        assertEquals(30, t.logWindowMinutes());
        assertEquals(1, t.dbPoolWaitMinutes());
        assertEquals(1.0, t.dbLockSessions(), 0.001);
        assertEquals(0.0, t.dbMaxLoad(), 0.001, "off: the database server's core count isn't known");
        assertEquals(90, t.dbConnectionsPercent());
    }

    @Test
    void databaseThresholdsFromConfig() {
        var t = CheckThresholds.fromConfig(Map.of(
            "check.db_pool_wait_minutes", "3",
            "check.db_lock_sessions", "0.5",
            "check.db_max_load", "8",
            "check.db_connections_percent", "80"
        ));
        assertEquals(3, t.dbPoolWaitMinutes());
        assertEquals(0.5, t.dbLockSessions(), 0.001);
        assertEquals(8.0, t.dbMaxLoad(), 0.001);
        assertEquals(80, t.dbConnectionsPercent());
        assertEquals(500, t.slowRouteMs());
    }

    @Test
    void sixArgumentConstructorKeepsDatabaseDefaults() {
        var t = new CheckThresholds(1000, 60, 90, 40, 0.7, 15);
        assertEquals(1000, t.slowRouteMs());
        assertEquals(CheckThresholds.DEFAULTS.dbPoolWaitMinutes(), t.dbPoolWaitMinutes());
        assertEquals(CheckThresholds.DEFAULTS.dbConnectionsPercent(), t.dbConnectionsPercent());
    }
}

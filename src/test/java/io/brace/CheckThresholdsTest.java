package io.brace;

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
    }
}

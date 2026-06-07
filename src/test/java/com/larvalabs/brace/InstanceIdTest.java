package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstanceIdTest {

    @Test
    void formatIsHostPortRandom() {
        String id = InstanceId.generate(8080);
        // <host>:<port>-<8 hex>
        assertTrue(id.matches("^.+:8080-[0-9a-f]{8}$"), "unexpected instance id: " + id);
    }

    @Test
    void suffixDisambiguatesCoLocatedInstances() {
        String a = InstanceId.generate(8080);
        String b = InstanceId.generate(8080);
        // Same host and port, but the random suffix must differ so two instances are distinct.
        assertNotEquals(a, b);
    }

    @Test
    void bracExposesInstanceIdAfterStart() throws Exception {
        TestApp app = Brace.test().start(a -> a.get("/", req -> Result.text("ok")));
        try {
            String id = app.app().instanceId();
            assertNotNull(id, "instanceId should be set after start()");
            // Reflects the actually-bound ephemeral port, not the configured 0.
            assertTrue(id.matches("^.+:" + app.port() + "-[0-9a-f]{8}$"), "unexpected: " + id);
        } finally {
            app.stop();
        }
    }
}

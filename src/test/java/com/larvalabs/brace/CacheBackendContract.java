package com.larvalabs.brace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend-agnostic conformance assertions for {@link CacheBackend}, driven through the {@link Cache}
 * facade. Run against both the serializing in-memory fixture ({@code CacheSerializationTest}) and
 * real Postgres ({@code PostgresCacheBackendIT}).
 *
 * <p>Every check uses <b>two facades on one backend</b> — the multi-server model: a write through
 * facade {@code a} must be visible / invalidated through facade {@code b}. That is the property the
 * shared backend exists to provide and the per-process in-memory default cannot.
 */
final class CacheBackendContract {

    private CacheBackendContract() {}

    /** A Jackson-round-trippable value, to exercise serialization on byte backends. */
    record Widget(String name, int qty) {}

    static void assertContract(CacheBackend backend) {
        var a = new Cache(backend);
        var b = new Cache(backend);
        a.clear();

        crossInstanceValue(a, b);
        crossInstanceCounter(a, b);
        crossInstanceTagInvalidation(a, b);
        crossInstanceDelete(a, b);
        crossInstanceDeletePrefix(a, b);
        expiryOnRead(a, b);

        a.clear();
    }

    private static void crossInstanceValue(Cache a, Cache b) {
        a.set("widget:1", new Widget("sprocket", 7), "5m");
        // Visible through the OTHER facade — the cross-server guarantee.
        assertEquals(new Widget("sprocket", 7), b.get("widget:1", Widget.class),
                "a write on one facade must be readable on another sharing the backend");
        // Overwrite through b, read through a.
        b.set("widget:1", new Widget("sprocket", 9), "5m");
        assertEquals(new Widget("sprocket", 9), a.get("widget:1", Widget.class));
    }

    private static void crossInstanceCounter(Cache a, Cache b) {
        a.clear();
        assertEquals(1, a.incr("hits"));
        assertEquals(2, b.incr("hits"), "incr must be atomic and shared across facades");
        assertEquals(3, a.incr("hits"));
        assertEquals(2, b.decr("hits"));
    }

    private static void crossInstanceTagInvalidation(Cache a, Cache b) {
        a.clear();
        a.set("page:home", "home", "1h", "site");
        a.set("page:api", "api", "1h", "site", "api");
        a.set("page:about", "about", "1h", "static");

        // Invalidate through b; a must no longer see the tagged entries.
        b.clearTag("site");

        assertNull(a.get("page:home", String.class));
        assertNull(a.get("page:api", String.class));
        assertEquals("about", a.get("page:about", String.class), "untagged entry survives");
    }

    private static void crossInstanceDelete(Cache a, Cache b) {
        a.clear();
        a.set("k", "v", "5m");
        b.delete("k");
        assertNull(a.get("k", String.class), "delete on one facade is visible on another");
    }

    private static void crossInstanceDeletePrefix(Cache a, Cache b) {
        a.clear();
        a.set("team:TOR", "Toronto", "5m");
        a.set("team:NYR", "New York", "5m");
        a.set("player:1", "Gretzky", "5m");
        b.deletePrefix("team:");
        assertNull(a.get("team:TOR", String.class));
        assertNull(a.get("team:NYR", String.class));
        assertEquals("Gretzky", a.get("player:1", String.class));
    }

    private static void expiryOnRead(Cache a, Cache b) {
        a.clear();
        a.set("ephemeral", "soon gone", "1s");
        assertEquals("soon gone", b.get("ephemeral", String.class));
        try {
            Thread.sleep(1200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertNull(b.get("ephemeral", String.class), "expired entry must not be served, even on read");
        assertTrue(a.size() == 0 || a.get("ephemeral", String.class) == null);
    }
}

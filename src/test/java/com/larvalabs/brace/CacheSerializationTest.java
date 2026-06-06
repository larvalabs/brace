package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Facade behavior on a serializing (byte) backend: the conformance contract plus the
 * serialization-specific guarantees (type-tag round-trip, loud failure on type mismatch, fail-fast
 * on non-serializable values). Uses {@link SerializingMapBackend} so it stays Docker-free; the real
 * SQL backend is covered by {@code PostgresCacheBackendIT}.
 */
class CacheSerializationTest {

    @Test
    void conformanceOnSerializingBackend() {
        CacheBackendContract.assertContract(new SerializingMapBackend());
    }

    @Test
    void recordRoundTripsThroughJackson() {
        var cache = new Cache(new SerializingMapBackend());
        cache.set("w", new CacheBackendContract.Widget("gear", 3));
        var read = cache.get("w", CacheBackendContract.Widget.class);
        assertEquals(new CacheBackendContract.Widget("gear", 3), read);
    }

    @Test
    void getWithWrongTypeFailsLoudly() {
        var cache = new Cache(new SerializingMapBackend());
        cache.set("w", new CacheBackendContract.Widget("gear", 3));
        // The class-name header makes a wrong-type get fail loudly instead of mis-binding.
        assertThrows(ClassCastException.class, () -> cache.get("w", String.class));
    }

    @Test
    void getOrSetRoundTripsWithoutTypeToken() {
        var cache = new Cache(new SerializingMapBackend());
        var computed = cache.getOrSet("w", "5m", () -> new CacheBackendContract.Widget("cog", 5));
        assertEquals(new CacheBackendContract.Widget("cog", 5), computed);
        // Second call hits the cache and deserializes via the stored class header (no Class<T> arg).
        var hit = cache.getOrSet("w", "5m", () -> new CacheBackendContract.Widget("WRONG", 0));
        assertEquals(new CacheBackendContract.Widget("cog", 5), hit);
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void nonSerializableValueThrowsAtSet() {
        var cache = new Cache(new SerializingMapBackend());
        // An InputStream is not Jackson-round-trippable — must fail fast at set time, not on a
        // later get from another server.
        assertThrows(IllegalArgumentException.class,
                () -> cache.set("stream", InputStream.nullInputStream()));
    }

    @Test
    void inMemoryBackendStoresNonSerializableValuesFine() {
        // The default in-memory backend stores live objects, so it has no serialization restriction.
        var cache = new Cache(); // in-memory
        var stream = InputStream.nullInputStream();
        cache.set("stream", stream);
        assertSame(stream, cache.get("stream", InputStream.class));
    }
}

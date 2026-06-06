package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    // --- Phase 2: rendered page caching ---

    @Test
    void pageCacheServedAcrossInstances() {
        // Two facades on one shared backend = two servers. A page rendered on one is served by the
        // other without re-running the handler — the cross-server page-cache guarantee.
        var backend = new SerializingMapBackend();
        var serverA = new Cache(backend);
        var serverB = new Cache(backend);

        var renders = new AtomicInteger();
        Handler handler = req -> {
            renders.incrementAndGet();
            return Result.html("<p>hello</p>");
        };
        var onA = serverA.wrap("5m", handler);
        var onB = serverB.wrap("5m", handler);

        var req = new Request("GET", "/page", Map.of(), Map.of(), Map.of(), null);
        var first = onA.apply(req);   // miss on A → renders, caches to shared backend
        var second = onB.apply(req);  // hit on B → served from shared backend, no render

        assertEquals(1, renders.get(), "B must serve A's cached render without re-running the handler");
        assertEquals("<p>hello</p>", bodyOf(first));
        assertEquals("<p>hello</p>", bodyOf(second));
        assertEquals("text/html", second.contentType());
    }

    @Test
    void pageCachePreservesStatusAndHeadersThroughSerialization() {
        var backend = new SerializingMapBackend();
        var cache = new Cache(backend);
        Handler handler = req -> Result.error(503, "down for maintenance").header("Retry-After", "120");
        var wrapped = cache.wrap("5m", handler);

        var req = new Request("GET", "/status", Map.of(), Map.of(), Map.of(), null);
        wrapped.apply(req);                 // miss → caches the RenderedResponse as bytes
        var hit = wrapped.apply(req);       // hit → rebuilt from bytes

        assertEquals(503, hit.status(), "status survives the serialization round-trip");
        assertEquals("text/plain", hit.contentType());
        assertEquals("120", hit.header("Retry-After"), "custom headers survive the round-trip");
        assertEquals("down for maintenance", bodyOf(hit));
    }

    private static String bodyOf(Result result) {
        return result.rawBytes() != null
                ? new String(result.rawBytes(), StandardCharsets.UTF_8)
                : result.body();
    }

    // --- Robustness: corrupt/unreadable stored bytes become a miss, not a crash ---

    @Test
    void corruptLengthPrefixIsTreatedAsMiss() {
        var backend = new SerializingMapBackend();
        var cache = new Cache(backend);
        // Inject bytes whose class-name length header (127) exceeds the payload — a truncated/corrupt
        // row. get() must return null (miss), not throw NegativeArraySize/OOM/BufferUnderflow.
        backend.setBytes("k", new byte[]{0, 0, 0, 127, 1, 2, 3}, null, new String[0]);
        assertNull(cache.get("k", String.class));
        assertEquals(1, cache.misses());
    }

    @Test
    void unknownStoredClassIsTreatedAsMiss() {
        var backend = new SerializingMapBackend();
        var cache = new Cache(backend);
        // Simulates a cross-version deploy: bytes name a class this process no longer has.
        backend.setBytes("k", craft("com.example.RemovedInThisDeploy", "{}".getBytes(StandardCharsets.UTF_8)),
                null, new String[0]);
        assertNull(cache.get("k", String.class));
        assertEquals(1, cache.misses());
    }

    @Test
    void getOrSetRecomputesOnCorruptEntry() {
        var backend = new SerializingMapBackend();
        var cache = new Cache(backend);
        backend.setBytes("k", new byte[]{-1, -1, -1, -1}, null, new String[0]);
        var value = cache.getOrSet("k", "5m", () -> "recomputed");
        assertEquals("recomputed", value, "a corrupt cached entry must fall through to the supplier");
    }

    // --- Null values are rejected on both backends (null is reserved for 'missing') ---

    @Test
    void nullValueRejectedOnSerializingBackend() {
        var cache = new Cache(new SerializingMapBackend());
        assertThrows(IllegalArgumentException.class, () -> cache.set("k", null));
    }

    private static byte[] craft(String className, byte[] body) {
        byte[] cn = className.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + cn.length + body.length).putInt(cn.length).put(cn).put(body).array();
    }
}

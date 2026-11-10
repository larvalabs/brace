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
        assertEquals("text/html; charset=utf-8", second.contentType());
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
        assertEquals("text/plain; charset=utf-8", hit.contentType());
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

    // --- L7: getOrSet only deserializes classes this process has itself stored ---
    // The allowlist is process-wide static state, so each test uses its own value class that no
    // other test in the JVM stores through a Cache.

    /** Counts constructions, so a test can prove Jackson never bound a row to this class. */
    record Foreign(String name) {
        static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        Foreign { CONSTRUCTED.incrementAndGet(); }
    }

    record WrittenByPeer(String name) {}

    record ReadWithExplicitType(String name) {}

    @Test
    void getOrSetDoesNotDeserializeAClassNamedByTheRow() {
        var backend = new SerializingMapBackend();
        var cache = new Cache(backend);
        // A row, written by someone with access to the shared store, naming a class this process
        // never stored. It must not be bound to that class.
        backend.setBytes("k", craft(Foreign.class.getName(), "{\"name\":\"evil\"}".getBytes(StandardCharsets.UTF_8)),
                null, new String[0]);
        var value = cache.getOrSet("k", "5m", () -> "recomputed");
        assertEquals("recomputed", value);
        assertEquals(0, Foreign.CONSTRUCTED.get(), "the foreign class must not be instantiated");
        assertEquals(1, cache.misses());
        assertEquals(0, cache.hits());
    }

    @Test
    void getOrSetSelfHealsWhenAnotherInstanceWroteTheEntryFirst() {
        var backend = new SerializingMapBackend();
        var cache = new Cache(backend);
        // Another server stored a WrittenByPeer; this process has not yet stored one itself.
        backend.setBytes("k", craft(WrittenByPeer.class.getName(), "{\"name\":\"peer\"}".getBytes(StandardCharsets.UTF_8)),
                null, new String[0]);
        var calls = new AtomicInteger();
        var first = cache.getOrSet("k", "5m", () -> { calls.incrementAndGet(); return new WrittenByPeer("mine"); });
        assertEquals(new WrittenByPeer("mine"), first, "first read recomputes and stores");
        // The write registered the class, so the next read is a normal hit.
        var second = cache.getOrSet("k", "5m", () -> { calls.incrementAndGet(); return new WrittenByPeer("WRONG"); });
        assertEquals(new WrittenByPeer("mine"), second);
        assertEquals(1, calls.get());
        assertEquals(1, cache.hits());
        assertEquals(1, cache.misses());
    }

    @Test
    void explicitTypeGetStillReadsAnyAssignableStoredClass() {
        var backend = new SerializingMapBackend();
        var cache = new Cache(backend);
        backend.setBytes("k", craft(ReadWithExplicitType.class.getName(), "{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8)),
                null, new String[0]);
        assertEquals(new ReadWithExplicitType("x"), cache.get("k", ReadWithExplicitType.class));
        assertEquals(1, cache.hits());
    }

    record ForeignViaObject(String name) {
        static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        ForeignViaObject { CONSTRUCTED.incrementAndGet(); }
    }

    record ForeignViaInterface(String name) implements java.io.Serializable {
        static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        ForeignViaInterface { CONSTRUCTED.incrementAndGet(); }
    }

    record StoredHere(String name) implements java.io.Serializable {}

    @Test
    void getWithBroadTypeDoesNotBindAClassNamedByTheRow() {
        // L15: with Object or an interface as the type, isAssignableFrom let a writer to the
        // shared table pick the class Jackson binds. An unregistered strict subtype is a miss.
        var backend = new SerializingMapBackend();
        var cache = new Cache(backend);
        backend.setBytes("o", craft(ForeignViaObject.class.getName(), "{\"name\":\"evil\"}".getBytes(StandardCharsets.UTF_8)),
                null, new String[0]);
        backend.setBytes("i", craft(ForeignViaInterface.class.getName(), "{\"name\":\"evil\"}".getBytes(StandardCharsets.UTF_8)),
                null, new String[0]);
        assertNull(cache.get("o", Object.class));
        assertNull(cache.get("i", java.io.Serializable.class));
        assertEquals(0, ForeignViaObject.CONSTRUCTED.get());
        assertEquals(0, ForeignViaInterface.CONSTRUCTED.get());
        assertEquals(2, cache.misses());
    }

    @Test
    void getWithBroadTypeStillReadsAClassThisProcessStored() {
        var cache = new Cache(new SerializingMapBackend());
        cache.set("k", new StoredHere("mine"));
        assertEquals(new StoredHere("mine"), cache.get("k", Object.class));
        assertEquals(new StoredHere("mine"), cache.get("k", java.io.Serializable.class));
    }

    // --- L15 final review: supertype reads on an instance that never stored the class ---
    // Two Cache instances over one backend stand for two servers; forgetStoredClasses() makes the
    // reader a fresh process (the stored-class registry is per JVM).

    interface Named { String name(); }

    record Rule(String name) implements Named {}

    record PlantedViaObject(String name) {
        static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        PlantedViaObject { CONSTRUCTED.incrementAndGet(); }
    }

    @Test
    void jdkValuesWrittenByOneInstanceAreReadThroughTheirInterfaceByAFreshOne() {
        var backend = new SerializingMapBackend();
        var writer = new Cache(backend);
        var reader = new Cache(backend);
        writer.set("denylist", new java.util.ArrayList<>(java.util.List.of("mallory", "eve")));
        writer.set("linked", new java.util.LinkedList<>(java.util.List.of("a")));
        writer.set("flags", new java.util.HashMap<>(Map.of("beta", true)));
        writer.set("sorted", new java.util.TreeMap<>(Map.of("k", "v")));
        writer.set("revoked", new java.util.HashSet<>(java.util.Set.of("token-1")));
        writer.set("listOf", java.util.List.of("x", "y", "z"));
        writer.set("listOf1", java.util.List.of("only"));
        writer.set("mapOf", Map.of("k", 1));
        writer.set("setOf", java.util.Set.of("s"));
        writer.set("count", 42);
        writer.set("price", new java.math.BigDecimal("9.99"));
        writer.set("when", java.time.Instant.parse("2026-11-10T00:00:00Z"));
        writer.set("names", new String[] {"a", "b"});
        Cache.forgetStoredClasses();

        assertEquals(java.util.List.of("mallory", "eve"), reader.get("denylist", java.util.List.class));
        assertEquals(java.util.List.of("mallory", "eve"), reader.get("denylist", java.util.Collection.class));
        assertEquals(java.util.List.of("a"), reader.get("linked", java.util.List.class));
        assertEquals(Map.of("beta", true), reader.get("flags", Map.class));
        assertEquals(Map.of("k", "v"), reader.get("sorted", Map.class));
        assertEquals(java.util.Set.of("token-1"), reader.get("revoked", java.util.Set.class));
        assertEquals(java.util.List.of("x", "y", "z"), reader.get("listOf", java.util.List.class));
        assertEquals(java.util.List.of("only"), reader.get("listOf1", java.util.List.class));
        assertEquals(Map.of("k", 1), reader.get("mapOf", Map.class));
        assertEquals(java.util.Set.of("s"), reader.get("setOf", java.util.Set.class));
        assertEquals(42, reader.get("count", Number.class));
        assertEquals(new java.math.BigDecimal("9.99"), reader.get("price", Number.class));
        assertEquals(java.time.Instant.parse("2026-11-10T00:00:00Z"), reader.get("when", java.time.temporal.Temporal.class));
        assertArrayEquals(new String[] {"a", "b"}, (Object[]) reader.get("names", Object.class));
        assertEquals(0, reader.misses());
    }

    @Test
    void getOrSetOnAFreshInstanceReadsAJdkValueInsteadOfOverwritingIt() {
        var backend = new SerializingMapBackend();
        new Cache(backend).set("denylist", new java.util.ArrayList<>(java.util.List.of("mallory")));
        Cache.forgetStoredClasses();
        var fresh = new Cache(backend);
        var calls = new AtomicInteger();
        java.util.List<String> read = fresh.getOrSet("denylist", "5m", () -> {
            calls.incrementAndGet();
            return new java.util.ArrayList<>();
        });
        assertEquals(java.util.List.of("mallory"), read);
        assertEquals(0, calls.get(), "the fresh instance must not recompute and overwrite the stored list");
    }

    @Test
    void anAppClassReadThroughItsInterfaceNeedsAllowType() {
        var backend = new SerializingMapBackend();
        new Cache(backend).set("rule", new Rule("block"));
        Cache.forgetStoredClasses();
        var fresh = new Cache(backend);
        assertNull(fresh.get("rule", Named.class), "unregistered app class through an interface: a miss");
        assertTrue(Cache.warnedRefusals().contains(Rule.class.getName() + " as " + Named.class.getName()),
            "the refusal is logged at WARN, once per pair: " + Cache.warnedRefusals());
        assertTrue(Cache.refusalMessage(Rule.class.getName(), Named.class.getName()).contains("allowType"));
        // The exact type is always readable.
        assertEquals(new Rule("block"), fresh.get("rule", Rule.class));
        Cache.forgetStoredClasses();
        fresh.allowType(Rule.class);
        assertEquals(new Rule("block"), fresh.get("rule", Named.class));
        assertEquals(new Rule("block"), fresh.getOrSet("rule", "5m", () -> new Rule("WRONG")));
        assertThrows(IllegalArgumentException.class, () -> fresh.allowType(Named.class),
            "allowType takes the concrete class that is stored, not the type it is read as");
    }

    @Test
    void aPlantedClassThroughObjectStillMisses() {
        var backend = new SerializingMapBackend();
        var cache = new Cache(backend);
        backend.setBytes("o", craft(PlantedViaObject.class.getName(), "{\"name\":\"evil\"}".getBytes(StandardCharsets.UTF_8)),
                null, new String[0]);
        backend.setBytes("t", craft("java.lang.ProcessBuilder", "{}".getBytes(StandardCharsets.UTF_8)), null, new String[0]);
        backend.setBytes("a", craft("[L" + PlantedViaObject.class.getName() + ";", "[{\"name\":\"evil\"}]".getBytes(StandardCharsets.UTF_8)),
                null, new String[0]);
        assertNull(cache.get("o", Object.class));
        assertNull(cache.get("t", Object.class));
        assertNull(cache.get("a", Object.class), "an array of a foreign class is not a built-in value");
        assertEquals("recomputed", cache.getOrSet("o", "5m", () -> "recomputed"));
        assertEquals(0, PlantedViaObject.CONSTRUCTED.get());
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

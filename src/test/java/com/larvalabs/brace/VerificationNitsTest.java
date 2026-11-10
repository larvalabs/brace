package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.larvalabs.brace.StaticMappingReviewFixesTest.send;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Small cache and response fixes from the verification pass (docs/2026-11-09-security-review-todos.md,
 * L15 and M3): a stored body that makes Jackson throw a RuntimeException is a miss; allowlisted
 * container classes come back as the collection type they were stored as; {@code Result.headers()}
 * cannot add an invalid header name; a plain GET of a socket path is 426 even when the path also
 * has a non-GET route.
 */
class VerificationNitsTest {

    private static byte[] craft(String className, String body) {
        byte[] cn = className.getBytes(StandardCharsets.UTF_8);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + cn.length + b.length).putInt(cn.length).put(cn).put(b).array();
    }

    // ---- (a) a RuntimeException from deserialization is a miss ----

    @Test
    void aBadRowUnderAnAllowlistedSingletonIsAMiss() {
        var backend = new SerializingMapBackend();
        var cache = new Cache(backend);
        backend.setBytes("s", craft("java.util.Collections$SingletonList", "[1,2]"), null, new String[0]);
        assertNull(assertDoesNotThrow(() -> cache.get("s", List.class)));
        assertEquals(List.of("fresh"), cache.getOrSet("s", "1m", () -> List.of("fresh")));
    }

    // ---- (b) every allowlisted container round-trips to its collection type ----

    private static final List<Class<?>> COLLECTION_TYPES = List.of(
        List.class, RandomAccess.class, Set.class, SortedSet.class, NavigableSet.class,
        Map.class, SortedMap.class, NavigableMap.class, Queue.class, Deque.class);

    private static Map<String, Object> containerSamples() {
        var samples = new ArrayList<Object>();
        samples.add(new ArrayList<>(List.of(1, 2)));
        samples.add(new LinkedList<>(List.of(1, 2)));
        samples.add(new ArrayDeque<>(List.of(1, 2)));
        samples.add(new HashMap<>(Map.of("a", 1)));
        samples.add(new LinkedHashMap<>(Map.of("a", 1)));
        samples.add(new TreeMap<>(Map.of("a", 1)));
        samples.add(new HashSet<>(List.of(1, 2)));
        samples.add(new LinkedHashSet<>(List.of(1, 2)));
        samples.add(new TreeSet<>(List.of(1, 2)));
        samples.add(List.of(1));
        samples.add(List.of(1, 2, 3));
        samples.add(Set.of(1));
        samples.add(Set.of(1, 2, 3));
        samples.add(Map.of("a", 1));
        samples.add(Map.of("a", 1, "b", 2));
        samples.add(Arrays.asList(1, 2));
        samples.add(Collections.unmodifiableList(new LinkedList<>(List.of(1, 2))));
        samples.add(Collections.unmodifiableList(new ArrayList<>(List.of(1, 2))));
        samples.add(Collections.unmodifiableSet(new HashSet<>(List.of(1, 2))));
        samples.add(Collections.unmodifiableSortedSet(new TreeSet<>(List.of(1, 2))));
        samples.add(Collections.unmodifiableMap(new HashMap<>(Map.of("a", 1))));
        samples.add(Collections.unmodifiableSortedMap(new TreeMap<>(Map.of("a", 1))));
        samples.add(Collections.emptyList());
        samples.add(Collections.emptySet());
        samples.add(Collections.emptyMap());
        samples.add(Collections.singletonList(1));
        samples.add(Collections.singleton(1));
        samples.add(Collections.singletonMap("a", 1));
        var byName = new LinkedHashMap<String, Object>();
        for (var sample : samples) byName.put(sample.getClass().getName(), sample);
        return byName;
    }

    @Test
    void everyAllowlistedContainerComesBackAsTheTypeItWasStoredAs() throws Exception {
        var samples = containerSamples();
        var broken = new ArrayList<String>();
        for (var name : Cache.BUILT_IN_VALUE_CLASSES) {
            var type = Class.forName(name);
            if (!Collection.class.isAssignableFrom(type) && !Map.class.isAssignableFrom(type)) continue;
            var sample = samples.get(name);
            assertNotNull(sample, "no round-trip sample for allowlisted container " + name);
            byte[] json = Json.mapper().writeValueAsBytes(sample);
            Object back;
            try {
                back = Json.mapper().readValue(json, type);
            } catch (Exception e) {
                broken.add(name + " (does not deserialize: " + e.getClass().getSimpleName() + ")");
                continue;
            }
            for (var iface : COLLECTION_TYPES) {
                if (iface.isInstance(sample) && !iface.isInstance(back)) {
                    broken.add(name + " (comes back as " + back.getClass().getName() + ", not a " + iface.getSimpleName() + ")");
                }
            }
        }
        assertEquals(List.of(), broken);
    }

    @Test
    void aSortedSetReadAsSortedSetIsNeverAClassCastException() {
        var cache = new Cache(new SerializingMapBackend());
        cache.set("s", Collections.unmodifiableSortedSet(new TreeSet<>(List.of(1, 2))));
        cache.set("m", Collections.unmodifiableSortedMap(new TreeMap<>(Map.of("a", 1))));
        assertDoesNotThrow(() -> {
            SortedSet<?> set = cache.get("s", SortedSet.class);
            SortedMap<?, ?> map = cache.get("m", SortedMap.class);
            assertTrue(set == null || set.size() == 2);
            assertTrue(map == null || map.size() == 1);
        });
    }

    // ---- (c) Result.headers() cannot add an invalid header name ----

    @Test
    void headersViewValidatesNames() {
        var result = Result.text("x");
        assertThrows(IllegalArgumentException.class, () -> result.headers().put("X Bad", "v"));
        assertThrows(IllegalArgumentException.class, () -> result.headers().put("X-Split\r\nInjected", "v"));
        assertThrows(IllegalArgumentException.class, () -> result.headers().putAll(Map.of("", "v")));
        assertNull(result.header("X Bad"));
        result.headers().put("x-ok", "1");
        assertEquals("1", result.header("X-OK"), "valid names still work, case-insensitively");
        assertTrue(result.headers().containsKey("X-Ok"));
        result.headers().keySet().removeIf(n -> n.equalsIgnoreCase("x-ok"));
        assertNull(result.header("x-ok"), "removal through the view still works");
    }

    // ---- (d) a socket path with another method's route: GET is 426 ----

    @Test
    void plainGetOfASocketPathWithAPostRouteIsUpgradeRequired(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("live"), "file-bytes");
        var app = Brace.app().host("127.0.0.1").port(0).banner(false)
            .staticFiles("/files", dir.toString()).publicFiles();
        app.ws("/files/live", ctx -> new Object()).publicRoute();
        app.post("/files/live", req -> Result.text("posted")).publicRoute().csrf(false, "raw-socket test client");
        app.start();
        try {
            var get = send(app, "GET", "/files/live", null);
            assertEquals(426, get.status(), get.raw());
            assertEquals("websocket", get.header("Upgrade"));
            assertFalse(get.raw().contains("file-bytes"));
            assertEquals(426, send(app, "HEAD", "/files/live", null).status());
            var put = send(app, "PUT", "/files/live", null);
            assertEquals(405, put.status(), put.raw());
        } finally {
            app.stop();
        }
    }
}

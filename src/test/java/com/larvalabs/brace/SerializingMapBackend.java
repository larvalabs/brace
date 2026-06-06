package com.larvalabs.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only serializing {@link CacheBackend}: a {@code byte[]}-storing in-memory backend that
 * mirrors {@link PostgresBackend}'s observable semantics (bytes in/out, expiry enforced on read,
 * {@code TEXT[]}-style tags, atomic counters) without needing Docker. It lets the fast unit suite
 * exercise the facade's serialization and cross-instance paths; the real SQL is covered by
 * {@code PostgresCacheBackendIT} against actual Postgres.
 */
class SerializingMapBackend implements CacheBackend {

    private record Entry(byte[] value, Instant expiry, String[] tags) {
        boolean expired() {
            return expiry != null && Instant.now().isAfter(expiry);
        }
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public boolean requiresSerialization() {
        return true;
    }

    @Override
    public byte[] getBytes(String key) {
        var entry = store.get(key);
        if (entry == null) return null;
        if (entry.expired()) {
            store.remove(key);
            return null;
        }
        return entry.value();
    }

    @Override
    public void setBytes(String key, byte[] value, Duration ttl, String[] tags) {
        var expiry = ttl == null ? null : Instant.now().plus(ttl);
        store.put(key, new Entry(value, expiry, tags == null ? new String[0] : tags));
    }

    @Override
    public void delete(String key) {
        store.remove(key);
    }

    @Override
    public void deletePrefix(String prefix) {
        store.keySet().removeIf(k -> k.startsWith(prefix));
    }

    @Override
    public long clearTag(String tag) {
        long[] removed = {0};
        store.entrySet().removeIf(e -> {
            for (var t : e.getValue().tags()) {
                if (t.equals(tag)) {
                    removed[0]++;
                    return true;
                }
            }
            return false;
        });
        return removed[0];
    }

    @Override
    public void clear() {
        store.clear();
        counters.clear();
    }

    @Override
    public long incr(String key, long delta) {
        return counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
    }

    @Override
    public int size() {
        return (int) store.entrySet().stream().filter(e -> !e.getValue().expired()).count();
    }

    @Override
    public long evictExpired() {
        long[] removed = {0};
        store.entrySet().removeIf(e -> {
            if (e.getValue().expired()) {
                removed[0]++;
                return true;
            }
            return false;
        });
        return removed[0];
    }
}

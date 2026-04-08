package io.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public class Cache {

    private record Entry(Object value, Instant expiry, String[] tags) {
        boolean expired() {
            return expiry != null && Instant.now().isAfter(expiry);
        }
    }

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> tagIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder evictions = new LongAdder();

    public Cache() {
        Thread.ofVirtual().name("cache-cleanup").start(() -> {
            while (true) {
                try {
                    Thread.sleep(Duration.ofSeconds(30));
                } catch (InterruptedException e) {
                    break;
                }
                evictExpired();
            }
        });
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        var entry = store.get(key);
        if (entry == null) {
            misses.increment();
            return null;
        }
        if (entry.expired()) {
            remove(key);
            misses.increment();
            return null;
        }
        hits.increment();
        return (T) entry.value();
    }

    public void set(String key, Object value) {
        store.put(key, new Entry(value, null, new String[0]));
    }

    public void set(String key, Object value, String ttl) {
        store.put(key, new Entry(value, Instant.now().plus(parseTtl(ttl)), new String[0]));
    }

    public void set(String key, Object value, String ttl, String... tags) {
        var expiry = Instant.now().plus(parseTtl(ttl));
        store.put(key, new Entry(value, expiry, tags));
        for (var tag : tags) {
            tagIndex.computeIfAbsent(tag, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getOrSet(String key, String ttl, Supplier<T> supplier) {
        var current = store.get(key);
        if (current != null && !current.expired()) {
            hits.increment();
            return (T) current.value();
        }
        misses.increment();
        var entry = store.compute(key, (k, existing) -> {
            if (existing != null && !existing.expired()) return existing;
            return new Entry(supplier.get(), Instant.now().plus(parseTtl(ttl)), new String[0]);
        });
        return (T) entry.value();
    }

    public void delete(String key) {
        remove(key);
    }

    public void deletePrefix(String prefix) {
        for (var key : store.keySet()) {
            if (key.startsWith(prefix)) {
                remove(key);
            }
        }
    }

    public void clear() {
        store.clear();
        tagIndex.clear();
        counters.clear();
        hits.reset();
        misses.reset();
        evictions.reset();
    }

    public long incr(String key) {
        return counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public long decr(String key) {
        return counters.computeIfAbsent(key, k -> new AtomicLong()).decrementAndGet();
    }

    public void clearTag(String tag) {
        var keys = tagIndex.remove(tag);
        if (keys != null) {
            for (var key : keys) {
                if (store.remove(key) != null) {
                    evictions.increment();
                }
            }
        }
    }

    public int size() { return store.size(); }
    public int counterCount() { return counters.size(); }
    public int tagCount() { return tagIndex.size(); }
    public long hits() { return hits.sum(); }
    public long misses() { return misses.sum(); }
    public long evictions() { return evictions.sum(); }
    public long drainHits() { return hits.sumThenReset(); }
    public long drainMisses() { return misses.sumThenReset(); }
    public long drainEvictions() { return evictions.sumThenReset(); }

    // Route-level page caching

    public CachedHandler wrap(String ttl, Handler handler) {
        return new CachedHandler(this, ttl, handler);
    }

    // Internal

    private void remove(String key) {
        var entry = store.remove(key);
        if (entry != null && entry.tags().length > 0) {
            for (var tag : entry.tags()) {
                var keys = tagIndex.get(tag);
                if (keys != null) keys.remove(key);
            }
        }
    }

    private void evictExpired() {
        var now = Instant.now();
        for (var entry : store.entrySet()) {
            if (entry.getValue().expiry() != null && now.isAfter(entry.getValue().expiry())) {
                remove(entry.getKey());
                evictions.increment();
            }
        }
    }

    void setInternal(String key, Object value, String ttl, String[] tags) {
        var expiry = Instant.now().plus(parseTtl(ttl));
        store.put(key, new Entry(value, expiry, tags));
        for (var tag : tags) {
            tagIndex.computeIfAbsent(tag, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }

    private static final Pattern TTL_PATTERN = Pattern.compile("(\\d+)([smhd])");

    static Duration parseTtl(String ttl) {
        var matcher = TTL_PATTERN.matcher(ttl);
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid TTL format: " + ttl);
        long amount = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalArgumentException("Unknown TTL unit: " + matcher.group(2));
        };
    }

    // Cached handler wrapper

    public static class CachedHandler implements Handler {
        private final Cache cache;
        private final String ttl;
        private final Handler handler;
        private String[] tags = new String[0];

        CachedHandler(Cache cache, String ttl, Handler handler) {
            this.cache = cache;
            this.ttl = ttl;
            this.handler = handler;
        }

        public CachedHandler tags(String... tags) {
            this.tags = tags;
            return this;
        }

        @Override
        public Result apply(Request request) {
            var key = "page:" + request.method() + ":" + request.path() + queryKey(request);
            var cached = cache.get(key, Result.class);
            if (cached != null) return cached;
            var result = handler.apply(request);
            cache.setInternal(key, result, ttl, tags);
            return result;
        }

        private String queryKey(Request request) {
            var params = request.queryParams();
            if (params.isEmpty()) return "";
            var sb = new StringBuilder("?");
            params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    if (sb.length() > 1) sb.append("&");
                    sb.append(e.getKey()).append("=").append(e.getValue());
                });
            return sb.toString();
        }
    }
}

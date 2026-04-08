package io.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

public class RateLimiter {

    // Global registry of all rate limiters for ops visibility
    private static final List<RateLimiter> ALL = new ArrayList<>();

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final Duration windowDuration;
    private final Function<Request, String> keyExtractor;
    private final String label;
    private final LongAdder allowed = new LongAdder();
    private final LongAdder blocked = new LongAdder();

    private RateLimiter(int maxRequests, Duration windowDuration, Function<Request, String> keyExtractor, String label) {
        this.maxRequests = maxRequests;
        this.windowDuration = windowDuration;
        this.keyExtractor = keyExtractor;
        this.label = label;
        startCleanup();
        synchronized (ALL) { ALL.add(this); }
    }

    public static Middleware.Before perIp(int maxRequests, String duration) {
        var limiter = new RateLimiter(maxRequests, Cache.parseTtl(duration),
            req -> {
                var ip = req.ip();
                return ip != null ? ip : "unknown";
            }, "perIp(" + maxRequests + "/" + duration + ")");
        return limiter::check;
    }

    public static Middleware.Before perKey(Function<Request, String> keyExtractor, int maxRequests, String duration) {
        var limiter = new RateLimiter(maxRequests, Cache.parseTtl(duration), keyExtractor,
            "perKey(" + maxRequests + "/" + duration + ")");
        return limiter::check;
    }

    Result check(Request req) {
        var key = keyExtractor.apply(req);
        if (key == null) return null;

        var now = Instant.now();
        var window = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.expired(now)) {
                return new Window(now, 1);
            }
            return new Window(existing.start, existing.count + 1);
        });

        if (window.count > maxRequests) {
            blocked.increment();
            long retryAfter = Duration.between(now, window.start.plus(windowDuration)).getSeconds();
            if (retryAfter < 1) retryAfter = 1;
            return Result.error(429, "Too Many Requests")
                .header("Retry-After", String.valueOf(retryAfter));
        }
        allowed.increment();
        return null;
    }

    /**
     * Returns stats for all registered rate limiters, for use by OpsHandler.
     */
    public static List<Map<String, Object>> allStats() {
        var result = new ArrayList<Map<String, Object>>();
        synchronized (ALL) {
            for (var limiter : ALL) {
                var map = new LinkedHashMap<String, Object>();
                map.put("label", limiter.label);
                map.put("allowed", limiter.allowed.sum());
                map.put("blocked", limiter.blocked.sum());
                map.put("activeWindows", limiter.windows.size());
                map.put("maxRequests", limiter.maxRequests);
                map.put("windowSeconds", limiter.windowDuration.getSeconds());
                result.add(map);
            }
        }
        return result;
    }

    private void startCleanup() {
        Thread.ofVirtual().name("rate-limiter-cleanup").start(() -> {
            while (true) {
                try {
                    Thread.sleep(Duration.ofSeconds(60));
                } catch (InterruptedException e) {
                    break;
                }
                evictExpired();
            }
        });
    }

    private void evictExpired() {
        var now = Instant.now();
        windows.entrySet().removeIf(entry -> entry.getValue().expired(now));
    }

    // Visible for testing
    int windowCount() {
        return windows.size();
    }

    private class Window {
        final Instant start;
        final int count;

        Window(Instant start, int count) {
            this.start = start;
            this.count = count;
        }

        boolean expired(Instant now) {
            return now.isAfter(start.plus(windowDuration));
        }
    }
}

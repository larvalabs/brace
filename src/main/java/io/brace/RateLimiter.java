package io.brace;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class RateLimiter {

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxRequests;
    private final Duration windowDuration;
    private final Function<Request, String> keyExtractor;

    private RateLimiter(int maxRequests, Duration windowDuration, Function<Request, String> keyExtractor) {
        this.maxRequests = maxRequests;
        this.windowDuration = windowDuration;
        this.keyExtractor = keyExtractor;
        startCleanup();
    }

    public static Middleware.Before perIp(int maxRequests, String duration) {
        var limiter = new RateLimiter(maxRequests, Cache.parseTtl(duration),
            req -> {
                var ip = req.ip();
                return ip != null ? ip : "unknown";
            });
        return limiter::check;
    }

    public static Middleware.Before perKey(Function<Request, String> keyExtractor, int maxRequests, String duration) {
        var limiter = new RateLimiter(maxRequests, Cache.parseTtl(duration), keyExtractor);
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
            long retryAfter = Duration.between(now, window.start.plus(windowDuration)).getSeconds();
            if (retryAfter < 1) retryAfter = 1;
            return Result.error(429, "Too Many Requests")
                .header("Retry-After", String.valueOf(retryAfter));
        }
        return null;
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

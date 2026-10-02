package com.larvalabs.brace;

import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Bounded polling for server-side effects that land after the client already has its response.
 *
 * <p>BraceHandler records stats and the {@code http.request} log line, and releases spilled
 * uploads, after it hands the response to Jetty. That write is asynchronous, so the client can
 * read the whole response before the bookkeeping has run, and a test that asserts it the instant
 * the call returns is racing the server. That is the intended production order (bookkeeping
 * stays off the response's critical path), so the test waits rather than the server: this
 * returns as soon as the condition holds and fails with the message if it never does.
 */
final class TestWait {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private TestWait() {}

    static void until(BooleanSupplier condition, String message) {
        until(DEFAULT_TIMEOUT, condition, () -> message);
    }

    /** The message is built on timeout, so it can report the state the wait gave up on. */
    static void until(BooleanSupplier condition, Supplier<String> message) {
        until(DEFAULT_TIMEOUT, condition, message);
    }

    static void until(Duration timeout, BooleanSupplier condition, Supplier<String> message) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                fail(message.get() + " (waited " + timeout.toMillis() + "ms)");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted while waiting: " + message.get());
            }
        }
    }
}

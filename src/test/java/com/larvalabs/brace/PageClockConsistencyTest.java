package com.larvalabs.brace;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification fix for L4 (docs/2026-11-09-security-review-todos.md): the in-memory page clock
 * must stay in step with the store under concurrent page writes mixed with {@code clear()},
 * {@code clearTag} and {@code deletePrefix}. A page that falls off the clock can never be evicted
 * before its TTL, so orphans used to wedge the cache and push out the app's own entries.
 */
class PageClockConsistencyTest {

    private static final Duration HOUR = Duration.ofHours(1);
    private static final String[] NO_TAGS = new String[0];

    private static String page(String id) {
        return Cache.PAGE_KEY_PREFIX + "GET:path:/" + id;
    }

    /** Runs {@code threads} page writers until {@code stop} is set, each with distinct keys. */
    private static List<Thread> writers(InMemoryBackend backend, int threads, AtomicBoolean stop,
                                        CountDownLatch started, boolean tagged) {
        var list = new ArrayList<Thread>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            var thread = new Thread(() -> {
                started.countDown();
                int i = 0;
                while (!stop.get()) {
                    String[] tags = tagged ? new String[] {"t" + (i % 7)} : NO_TAGS;
                    backend.setObject(page("w" + id + "/" + (i % 3 == 0 ? "x/" : "y/") + i), "p", HOUR, tags);
                    i++;
                }
            });
            thread.start();
            list.add(thread);
        }
        return list;
    }

    private static void assertConsistent(InMemoryBackend backend) {
        assertEquals(0, backend.pagesOffClock(), "stored pages missing from the clock");
        assertEquals(0, backend.pageCountDrift(), "page count disagrees with the store");
    }

    /** After the run: 1,000 app entries into a cap-1,000 store must all survive. */
    private static void assertAppEntriesWin(InMemoryBackend backend) {
        for (int i = 0; i < 1_000; i++) backend.setObject("app:" + i, "v" + i, HOUR, NO_TAGS);
        int survived = 0;
        for (int i = 0; i < 1_000; i++) if (backend.getObject("app:" + i) != null) survived++;
        assertEquals(1_000, survived, "app entries displaced while pages stayed: " + backend.size());
    }

    @Test
    void aClearDuringAPageFloodLeavesNoPageOffTheClock() throws Exception {
        for (int round = 0; round < 3; round++) {
            var backend = new InMemoryBackend(1_000);
            var stop = new AtomicBoolean();
            var started = new CountDownLatch(16);
            var threads = writers(backend, 16, stop, started, false);
            started.await();
            Thread.sleep(150);
            backend.clear();
            Thread.sleep(150);
            stop.set(true);
            for (var t : threads) t.join();
            assertConsistent(backend);
            // 5,000 more distinct pages must cycle through every slot, orphans included.
            for (int i = 0; i < 5_000; i++) backend.setObject(page("after/" + i), "p", HOUR, NO_TAGS);
            assertConsistent(backend);
            assertAppEntriesWin(backend);
        }
    }

    @Test
    void tagAndPrefixDeletesDuringAPageFloodLeaveNoPageOffTheClock() throws Exception {
        var backend = new InMemoryBackend(1_000);
        var stop = new AtomicBoolean();
        var started = new CountDownLatch(16);
        var threads = writers(backend, 16, stop, started, true);
        started.await();
        var deletes = new AtomicInteger();
        long end = System.nanoTime() + 1_500_000_000L;
        while (System.nanoTime() < end) {
            backend.clearTag("t" + (deletes.get() % 7));
            backend.deletePrefix(page("w" + (deletes.get() % 16) + "/x/"));
            deletes.incrementAndGet();
        }
        stop.set(true);
        for (var t : threads) t.join();
        assertConsistent(backend);
        assertAppEntriesWin(backend);
    }

    @Test
    void appEntriesAreNeverDisplacedByPagesAfterAClear() throws Exception {
        var backend = new InMemoryBackend(1_000);
        for (int i = 0; i < 500; i++) backend.setObject("keep:" + i, "v", HOUR, NO_TAGS);
        var stop = new AtomicBoolean();
        var started = new CountDownLatch(16);
        var threads = writers(backend, 16, stop, started, false);
        started.await();
        Thread.sleep(100);
        backend.clear();
        for (int i = 0; i < 500; i++) backend.setObject("keep:" + i, "v", HOUR, NO_TAGS);
        Thread.sleep(200);
        stop.set(true);
        for (var t : threads) t.join();
        for (int i = 0; i < 500; i++) {
            assertNotNull(backend.getObject("keep:" + i), "app entry keep:" + i + " displaced by a page");
        }
        assertConsistent(backend);
    }
}

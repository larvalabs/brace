package com.larvalabs.brace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class Log {

    public enum Level { DEBUG, INFO, WARN, ERROR }

    /**
     * Minimum level written to stdout and the /ops/logs ring buffer. Entries below it are
     * skipped before the entry map is even built. Defaults to DEBUG (everything, the
     * historical behavior); set via {@code -Dbrace.log.level=INFO}, the
     * {@code BRACE_LOG_LEVEL} env var, or {@link #level(String)}.
     */
    private static volatile Level minLevel = initLevel();

    private static Level initLevel() {
        String configured = System.getProperty("brace.log.level",
            System.getenv().getOrDefault("BRACE_LOG_LEVEL", "DEBUG"));
        try {
            return Level.valueOf(configured.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Level.DEBUG;
        }
    }

    public static void level(String level) {
        minLevel = Level.valueOf(level.trim().toUpperCase());
    }

    static boolean enabled(Level level) {
        return level.ordinal() >= minLevel.ordinal();
    }

    // ---- Async writer (H1) ----
    //
    // Stdout is a shared, synchronized, line-flushing PrintStream: writing one JSON line per
    // request from every request thread serializes the whole app on one monitor and performs
    // a write syscall inline (and pins virtual-thread carriers on JDK < 25). Instead, request
    // threads enqueue the redacted entry map and a single daemon thread serializes and writes
    // batches — one locked print + flush per batch, not per line. The queue is bounded;
    // when full the OLDEST entry is dropped (recent context matters most for debugging) and
    // a synthetic WARN with the drop count is emitted with the next batch.
    //
    // LogTap.append still happens synchronously on the caller's thread, so /ops/logs sees
    // entries immediately and in caller order regardless of stdout batching.

    private static final int QUEUE_CAPACITY = 8192;
    private static final int MAX_BATCH = 512;
    private static final ArrayBlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final AtomicLong dropped = new AtomicLong();

    static {
        Thread t = new Thread(Log::writeLoop, "brace-log-writer");
        t.setDaemon(true);
        t.start();
        // Daemon thread: flush whatever is queued when the JVM exits normally.
        Runtime.getRuntime().addShutdownHook(new Thread(Log::flush, "brace-log-flush"));
    }

    private static void writeLoop() {
        while (true) {
            try {
                Map<String, Object> first = queue.take();
                var batch = new StringBuilder();
                appendLine(batch, first);
                Map<String, Object> next;
                int n = 1;
                while (n < MAX_BATCH && (next = queue.poll()) != null) {
                    appendLine(batch, next);
                    n++;
                }
                long d = dropped.getAndSet(0);
                if (d > 0) {
                    batch.append("{\"ts\":\"").append(Instant.now())
                        .append("\",\"level\":\"WARN\",\"event\":\"log.dropped\",\"count\":").append(d)
                        .append("}\n");
                }
                // One synchronized write + flush per batch, from this thread only.
                // (PrintStream auto-flushes a print(String) containing '\n'.)
                System.out.print(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable ignored) {
                // Never let the writer die; the next take() continues.
            }
        }
    }

    private static void appendLine(StringBuilder batch, Map<String, Object> map) {
        try {
            batch.append(Json.mapper().writeValueAsString(map)).append('\n');
        } catch (Exception e) {
            batch.append(map).append('\n');
        }
    }

    /**
     * Drains and writes everything currently queued. Called from the JVM shutdown hook and
     * {@code Brace.stop()}; also useful in tests that assert on captured stdout.
     */
    static void flush() {
        var batch = new StringBuilder();
        Map<String, Object> entry;
        while ((entry = queue.poll()) != null) {
            appendLine(batch, entry);
        }
        if (!batch.isEmpty()) {
            System.out.print(batch);
        }
        System.out.flush();
    }

    public static void event(String event, Map<String, Object> data) {
        if (!enabled(Level.INFO)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "INFO");
        entry.put("event", event);
        entry.putAll(data);
        println(entry);
    }

    static void request(String method, String path, int status, long durationUs,
                        int queryCount, long queryUs) {
        if (!enabled(status >= 500 ? Level.ERROR : Level.INFO)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", status >= 500 ? "ERROR" : "INFO");
        entry.put("event", "http.request");
        entry.put("method", method);
        // Value-shaped redaction at the sink: high-entropy path segments (reset tokens,
        // invite links) must not reach stdout or the log ring buffer on any request.
        entry.put("path", Redactor.redactPath(path));
        entry.put("status", status);
        entry.put("durationMs", Math.round(durationUs / 100.0) / 10.0);
        entry.put("queries", queryCount);
        entry.put("queryMs", Math.round(queryUs / 100.0) / 10.0);
        println(entry);
    }

    static void error(String method, String path, Throwable error) {
        if (!enabled(Level.ERROR)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "ERROR");
        entry.put("event", "http.error");
        entry.put("method", method);
        // Same value-shaped pass as the error store: paths and exception messages can
        // embed bearer tokens or SQL literals and this entry goes to stdout + /ops/logs.
        entry.put("path", Redactor.redactPath(path));
        entry.put("error", error.getClass().getSimpleName());
        entry.put("message", Redactor.redactMessage(error.getMessage()));
        String at = appFrame(error);
        if (at != null) entry.put("at", at);
        println(entry);
    }

    /**
     * The first stack frame outside the framework and the JDK/server libraries — the
     * line of app code that threw. Frame class/method/line are code locations, not
     * user data, so no redaction pass is needed.
     */
    private static final String[] NON_APP_PACKAGES = {
        "com.larvalabs.brace.", "java.", "javax.", "jdk.", "sun.", "jakarta.",
        "org.eclipse.jetty.", "org.hibernate.", "org.flywaydb.", "org.h2.",
        "org.postgresql.", "org.junit.", "com.fasterxml.", "com.zaxxer."
    };

    static String appFrame(Throwable error) {
        for (var frame : error.getStackTrace()) {
            String cls = frame.getClassName();
            boolean library = false;
            for (var prefix : NON_APP_PACKAGES) {
                if (cls.startsWith(prefix)) { library = true; break; }
            }
            if (!library) return frame.toString();
        }
        // Framework-internal failure (no app frame): fall back to the top frame.
        return error.getStackTrace().length > 0 ? error.getStackTrace()[0].toString() : null;
    }

    /**
     * Same first-app-frame heuristic as {@link #appFrame(Throwable)}, but over a stored
     * stack-trace <em>string</em> (the {@code printStackTrace} text persisted in the error
     * store). Scans the {@code \tat ...} lines with the same {@link #NON_APP_PACKAGES} list;
     * falls back to the first frame when every line is framework/library, null when the
     * string has no frames at all.
     */
    static String appFrame(String stackTrace) {
        if (stackTrace == null) return null;
        String firstFrame = null;
        for (var line : stackTrace.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("at ")) continue;
            String frame = t.substring(3).trim();
            if (firstFrame == null) firstFrame = frame;
            if (!isLibraryFrame(frame)) return frame;
        }
        return firstFrame;
    }

    private static boolean isLibraryFrame(String frame) {
        String cls = frame;
        int paren = cls.indexOf('(');
        if (paren >= 0) cls = cls.substring(0, paren);
        // printStackTrace can prefix a frame with classloader/module info
        // ("java.base/java.lang.Thread.run"). Hidden-class suffixes also use '/'
        // ("Foo$$Lambda$1/0x0000...") — those start with "0x", so don't strip them.
        int slash = cls.lastIndexOf('/');
        if (slash >= 0 && !cls.startsWith("0x", slash + 1)) cls = cls.substring(slash + 1);
        for (var prefix : NON_APP_PACKAGES) {
            if (cls.startsWith(prefix)) return true;
        }
        return false;
    }

    public static void warn(String message) {
        if (!enabled(Level.WARN)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "WARN");
        entry.put("message", message);
        println(entry);
    }

    public static void debug(String message) {
        if (!enabled(Level.DEBUG)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "DEBUG");
        entry.put("message", message);
        println(entry);
    }

    public static void debug(String message, Map<String, Object> data) {
        if (!enabled(Level.DEBUG)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "DEBUG");
        entry.put("message", message);
        entry.putAll(data);
        println(entry);
    }

    public static void info(String message) {
        if (!enabled(Level.INFO)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "INFO");
        entry.put("message", message);
        println(entry);
    }

    public static void info(String message, Map<String, Object> data) {
        if (!enabled(Level.INFO)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "INFO");
        entry.put("message", message);
        entry.putAll(data);
        println(entry);
    }

    public static void error(String message) {
        if (!enabled(Level.ERROR)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "ERROR");
        entry.put("message", message);
        println(entry);
    }

    public static void error(String message, Throwable throwable) {
        if (!enabled(Level.ERROR)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "ERROR");
        entry.put("message", message);
        entry.put("error", throwable.getClass().getSimpleName());
        entry.put("errorMessage", throwable.getMessage());
        println(entry);
    }

    public static void error(String message, Map<String, Object> data) {
        if (!enabled(Level.ERROR)) return;
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "ERROR");
        entry.put("message", message);
        entry.putAll(data);
        println(entry);
    }

    private static void println(Map<String, Object> map) {
        // Redact sensitive-named fields once, here, so nothing sensitive reaches either the
        // ring buffer (served over /ops/logs) or stdout — regardless of which Log method or
        // which app code produced the entry. Fixed fields (ts, level, message, path…) have
        // non-sensitive names and pass through untouched.
        map = Redactor.redact(map);
        // redact() returned a fresh private map: the tap may adopt it without copying, and
        // the writer thread may serialize it later without racing the caller.
        LogTap.appendTrusted(map);
        if (!queue.offer(map)) {
            // Full: drop the oldest queued entry to make room (recent context wins). The
            // race between concurrent droppers is benign; if the retry still loses, the new
            // entry is lost instead — either way every lost line is counted.
            queue.poll();
            dropped.incrementAndGet();
            if (!queue.offer(map)) {
                dropped.incrementAndGet();
            }
        }
    }
}

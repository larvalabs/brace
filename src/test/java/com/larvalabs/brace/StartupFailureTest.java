package com.larvalabs.brace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A start() that fails part-way must not leave threads behind: a non-daemon one kept the JVM
 * alive with no server after main() died (the JFR event stream, started before the ops keys
 * were loaded).
 */
class StartupFailureTest {

    @Test
    void failedStartLeavesNoThreadsBehind(@TempDir Path dir) throws Exception {
        Set<Thread> before = Thread.getAllStackTraces().keySet();

        // .ops(...) starts the JFR profiler, then fails loading the missing keys file.
        var app = Brace.app().port(0).ops(dir.resolve("missing-authorized-keys").toString());
        var e = assertThrows(RuntimeException.class, app::start);
        assertTrue(e.getMessage().contains("authorized keys"), e.getMessage());

        List<Thread> leftover = List.of();
        for (int i = 0; i < 50; i++) {
            leftover = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> !before.contains(t) && t.isAlive())
                // Non-daemon threads hold the JVM open. The profiler's stream is daemon now, so
                // check it by name too: it must be closed by the cleanup, not just harmless.
                // (Process-wide daemons such as Log's brace-log-writer legitimately stay.)
                .filter(t -> !t.isDaemon() || t.getName().equals("brace-jfr-stream"))
                .toList();
            if (leftover.isEmpty()) break;
            Thread.sleep(100);
        }
        assertTrue(leftover.isEmpty(), "threads left running after a failed start: "
            + leftover.stream().map(t -> t.getName() + (t.isDaemon() ? " (daemon)" : ""))
                .collect(Collectors.joining(", ")));
    }
}

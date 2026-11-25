package com.larvalabs.brace;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CliCheckTest {

    static JsonNode json(Object obj) {
        return Json.mapper().valueToTree(obj);
    }

    static JsonNode healthyStatus() {
        var status = new LinkedHashMap<String, Object>();
        status.put("app", Map.of(
            "uptime", "2h 15m",
            "startedAt", "2026-04-15T10:00:00Z",
            "javaVersion", "21.0.1"
        ));
        status.put("http", Map.of(
            "statusCodes", Map.of("200", 100, "302", 5),
            "slowestRoutes", List.of(
                Map.of("route", "GET /api/posts", "count", 50, "avgMs", 45.2)
            )
        ));
        status.put("jvm", Map.of(
            "heap", Map.of("usedMB", 100, "maxMB", 512),
            "gc", Map.of("avgPauseMs", 5.0)
        ));
        status.put("errors", Map.of("count", 0, "recent", List.of()));
        status.put("jobs", Map.of("scheduled", List.of(
            Map.of("name", "cleanup", "lastStatus", "ok", "failCount", 0)
        )));
        status.put("cache", Map.of(
            "entries", 50, "hits", 800, "misses", 200
        ));
        return json(status);
    }

    @Test
    void healthyAppPassesAllChecks() {
        var result = CliCheck.evaluate(
            healthyStatus(), json(List.of()), json(List.of()),
            CheckThresholds.DEFAULTS);
        assertTrue(result.healthy());
        assertEquals("All checks passed", result.summary());
        for (var check : result.checks()) {
            assertEquals("pass", check.status(), check.name() + " should pass");
        }
    }

    @Test
    void errorsCheckFailsWithUnresolvedErrors() {
        var errors = json(List.of(
            Map.of("errorType", "NullPointerException", "route", "GET /posts/{id}",
                   "occurrenceCount", 3, "id", 42)
        ));
        var check = CliCheck.checkErrors(json(Map.of()), errors, "prod");
        assertEquals("fail", check.status());
        assertEquals("1 unresolved error", check.message());
        assertEquals(1, check.details().size());
        assertEquals("NullPointerException", check.details().get(0).get("type"));
        assertEquals("brace errors --env prod --json", check.followUp());
    }

    @Test
    void http5xxCheckFailsWith500s() {
        var status = json(Map.of("http", Map.of(
            "statusCodes", Map.of("200", 100, "500", 3)
        )));
        var check = CliCheck.checkHttp5xx(status);
        assertEquals("fail", check.status());
        assertTrue(check.message().contains("3 server errors"));
    }

    @Test
    void http5xxCheckSkipsNonIntegerStatusCodeKeys() {
        var status = json(Map.of("http", Map.of(
            "statusCodes", Map.of("200", 100, "500", 3, "abc", 7, "", 1, "99999999999", 2, "5xx", 4)
        )));
        var check = CliCheck.checkHttp5xx(status);
        assertEquals("fail", check.status());
        assertTrue(check.message().contains("3 server errors in 103 requests"), check.message());
    }

    @Test
    void checksToleratePayloadsOfTheWrongType() throws Exception {
        // A server (or an old/new version) may send any JSON type for any field: no check may throw.
        String[] wrong = {"null", "\"text\"", "42", "[1,\"a\",null,{}]", "{\"x\":[]}", "true"};
        var mapper = Json.mapper();
        for (String w : wrong) {
            String status = "{\"app\":" + w + ",\"http\":{\"statusCodes\":" + w + ",\"slowestRoutes\":" + w
                + ",\"topRoutes\":" + w + "},\"jvm\":{\"heap\":" + w + ",\"gc\":{\"avgPauseMs\":" + w
                + ",\"recentPauses\":" + w + "}},\"jobs\":{\"scheduled\":" + w + "},\"cache\":" + w
                + ",\"errors\":" + w + "}";
            var s = mapper.readTree(status);
            var e = mapper.readTree(w);
            assertDoesNotThrow(() -> CliCheck.evaluate(s, e, e, CheckThresholds.DEFAULTS, "prod"), w);
            assertDoesNotThrow(() -> CliCommands.renderStatus(s), w);
        }
    }

    @Test
    void slowRoutesWarnOverThreshold() {
        var status = json(Map.of("http", Map.of(
            "slowestRoutes", List.of(
                Map.of("route", "GET /search", "avgMs", 750.0, "count", 20),
                Map.of("route", "GET /api/posts", "avgMs", 45.0, "count", 100)
            )
        )));
        var check = CliCheck.checkSlowRoutes(status, CheckThresholds.DEFAULTS);
        assertEquals("warn", check.status());
        assertTrue(check.message().contains("1 route over 500ms"));
    }

    @Test
    void heapWarnAndFail() {
        var warnStatus = json(Map.of("jvm", Map.of("heap", Map.of("usedMB", 380, "maxMB", 512))));
        assertEquals("warn", CliCheck.checkHeap(warnStatus, CheckThresholds.DEFAULTS).status());

        var failStatus = json(Map.of("jvm", Map.of("heap", Map.of("usedMB", 430, "maxMB", 512))));
        assertEquals("fail", CliCheck.checkHeap(failStatus, CheckThresholds.DEFAULTS).status());
    }

    @Test
    void gcPressureFailOverThreshold() {
        var status = json(Map.of("jvm", Map.of("gc", Map.of("avgPauseMs", 75.0))));
        var check = CliCheck.checkGcPressure(status, CheckThresholds.DEFAULTS, "prod");
        assertEquals("fail", check.status());
        assertEquals("brace status --env prod --include profiling --json", check.followUp());
    }

    @Test
    void gcPressureWarnsOnG1FullCollections() {
        var status = json(Map.of("jvm", Map.of("gc", Map.of(
            "avgPauseMs", 4.0,
            "recentPauses", List.of(
                Map.of("ts", "2026-10-02T12:00:05Z", "collector", "G1Full", "cause", "G1 Compaction Pause",
                       "durationMs", 420.0, "cycleMs", 420.0, "full", true),
                Map.of("ts", "2026-10-02T12:00:00Z", "collector", "G1Old", "cause", "G1 Evacuation Pause",
                       "durationMs", 6.0, "cycleMs", 120.0)
            )
        ))));
        var check = CliCheck.checkGcPressure(status, CheckThresholds.DEFAULTS, "prod");
        assertEquals("warn", check.status());
        assertTrue(check.message().startsWith("1 G1 full GC in recent collections"), check.message());
        assertEquals(1, check.details().size());
        assertEquals("G1 Compaction Pause", check.details().get(0).get("cause"));
    }

    @Test
    void gcPressureIgnoresConcurrentCycleLengthAndRoutineOldGenCollections() {
        // A 120ms G1Old concurrent cycle with 6ms of real pause, and a SerialOld collection
        // (Serial's normal old-gen GC), must not trip the check while the average is low.
        var status = json(Map.of("jvm", Map.of("gc", Map.of(
            "avgPauseMs", 6.0,
            "recentPauses", List.of(
                Map.of("collector", "G1Old", "durationMs", 6.0, "cycleMs", 120.0),
                Map.of("collector", "SerialOld", "durationMs", 30.0, "cycleMs", 30.0, "full", true)
            )
        ))));
        var check = CliCheck.checkGcPressure(status, CheckThresholds.DEFAULTS, "prod");
        assertEquals("pass", check.status());
        assertEquals("Avg pause 6.0ms", check.message());
    }

    @Test
    void gcPressurePassesWhenGcSectionAbsent() {
        // H6: status from a profiler-less app no longer emits an all-zeros gc stub —
        // the gc key is simply absent, and the check must treat that as healthy.
        var status = json(Map.of("jvm", Map.of("heap", Map.of("usedMB", 100, "maxMB", 512))));
        var check = CliCheck.checkGcPressure(status, CheckThresholds.DEFAULTS, "prod");
        assertEquals("pass", check.status());
    }

    @Test
    void jobsFailWhenLastStatusNotOk() {
        var status = json(Map.of("jobs", Map.of("scheduled", List.of(
            Map.of("name", "cleanup", "lastStatus", "ok", "failCount", 0),
            Map.of("name", "email-digest", "lastStatus", "error", "failCount", 3,
                   "lastError", "Connection refused")
        ))));
        var check = CliCheck.checkJobs(status);
        assertEquals("fail", check.status());
        assertTrue(check.message().contains("1 of 2 jobs failing"));
        assertEquals("Connection refused", check.details().get(0).get("lastError"));
    }

    @Test
    void jobsWarnWhenFailCountPositiveButCurrentlyOk() {
        var status = json(Map.of("jobs", Map.of("scheduled", List.of(
            Map.of("name", "cleanup", "lastStatus", "ok", "failCount", 2)
        ))));
        var check = CliCheck.checkJobs(status);
        assertEquals("warn", check.status());
    }

    @Test
    void jobsPassWhenNoneHaveRunYet() {
        // A just-started app: every job is "pending". That is every deploy, not a failure.
        var status = json(Map.of("jobs", Map.of("scheduled", List.of(
            Map.of("name", "brace-jobs-prune", "lastStatus", "pending", "failCount", 0),
            Map.of("name", "ops-flush-http", "lastStatus", "pending", "failCount", 0)
        ))));
        var check = CliCheck.checkJobs(status);
        assertEquals("pass", check.status(), check.message());
        assertEquals("2 jobs, none run yet", check.message());
    }

    @Test
    void jobsPendingAlongsideOkPass() {
        var status = json(Map.of("jobs", Map.of("scheduled", List.of(
            Map.of("name", "cleanup", "lastStatus", "ok", "failCount", 0),
            Map.of("name", "digest", "lastStatus", "pending", "failCount", 0)
        ))));
        var check = CliCheck.checkJobs(status);
        assertEquals("pass", check.status(), check.message());
        assertEquals("2 jobs, all ok (1 not run yet)", check.message());
    }

    @Test
    void jobsFailOnErrorEvenWhenOthersArePending() {
        var status = json(Map.of("jobs", Map.of("scheduled", List.of(
            Map.of("name", "digest", "lastStatus", "pending", "failCount", 0),
            Map.of("name", "email", "lastStatus", "error", "failCount", 1, "lastError", "boom")
        ))));
        var check = CliCheck.checkJobs(status);
        assertEquals("fail", check.status());
        assertEquals("1 of 2 jobs failing", check.message());
        assertEquals(1, check.details().size());
        assertEquals("email", check.details().get(0).get("name"));
    }

    @Test
    void jobsCheckAgainstRealOpsStatus() throws Exception {
        // End to end through the real /ops/status payload: registered-but-unrun jobs pass, and
        // once one has actually failed the check fails.
        var scheduler = new JobScheduler();
        scheduler.every("1h", "idle", (db, ctx) -> {});
        var failed = new java.util.concurrent.CountDownLatch(1);
        scheduler.every("1s", "broken", (db, ctx) -> {
            try {
                throw new IllegalStateException("boom");
            } finally {
                failed.countDown();
            }
        });
        String secret = OpsToken.generateSecret();
        var kp = OpsKeys.generateKeypair();
        var handler = new OpsHandler(new Stats(), scheduler, null, new Router(),
            Map.of(kp.publicKey(), OpsScope.READ), secret);
        String token = OpsToken.create(secret, 60, OpsScope.READ, OpsKeys.fingerprint(kp.publicKey()));
        java.util.function.Supplier<JsonNode> status = () -> {
            var req = new Request("GET", "/ops/status", Map.of(), Map.of(),
                Map.of("Authorization", "Bearer " + token), null);
            try {
                return Json.mapper().readTree(handler.status(req).body());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };

        var before = CliCheck.checkJobs(status.get());
        assertEquals("pass", before.status(), before.message());

        scheduler.start(null);
        try {
            assertTrue(failed.await(5, java.util.concurrent.TimeUnit.SECONDS));
            CliCheck.Check after = null;
            for (int i = 0; i < 100; i++) { // the status update lands just after the job body
                after = CliCheck.checkJobs(status.get());
                if ("fail".equals(after.status())) break;
                Thread.sleep(20);
            }
            assertEquals("fail", after.status(), after.message());
            assertEquals("1 of 2 jobs failing", after.message());
            assertEquals("broken", after.details().get(0).get("name"));
            assertEquals("boom", after.details().get(0).get("lastError"));
        } finally {
            scheduler.stop();
        }
    }

    @Test
    void cacheWarnWhenHitRateLow() {
        var status = json(Map.of("cache", Map.of("hits", 20, "misses", 80)));
        var check = CliCheck.checkCache(status, CheckThresholds.DEFAULTS);
        assertEquals("warn", check.status());
        assertTrue(check.message().contains("20%"));
    }

    @Test
    void cachePassWhenNotConfigured() {
        var check = CliCheck.checkCache(json(Map.of()), CheckThresholds.DEFAULTS);
        assertEquals("pass", check.status());
    }

    @Test
    void recentLogsFailWithErrorEntries() {
        var logs = json(List.of(
            Map.of("level", "ERROR", "message", "NPE in handler"),
            Map.of("level", "INFO", "message", "normal log")
        ));
        var check = CliCheck.checkRecentLogs(logs, CheckThresholds.DEFAULTS, "prod");
        assertEquals("fail", check.status());
        assertTrue(check.message().contains("1 error-level entry"));
        assertNotNull(check.followUp());
    }

    @Test
    void recentLogsWarnWithWarnings() {
        var logs = json(List.of(
            Map.of("level", "WARN", "message", "slow query")
        ));
        var check = CliCheck.checkRecentLogs(logs, CheckThresholds.DEFAULTS, "prod");
        assertEquals("warn", check.status());
    }

    @Test
    void followUpCommandsUseEnvName() {
        var errors = json(List.of(
            Map.of("errorType", "NPE", "route", "GET /x", "occurrenceCount", 1)
        ));
        var result = CliCheck.evaluate(healthyStatus(), errors, json(List.of()),
            CheckThresholds.DEFAULTS, "staging");
        var errCheck = result.checks().stream()
            .filter(c -> "errors".equals(c.name())).findFirst().orElseThrow();
        assertTrue(errCheck.followUp().contains("--env staging"));
    }

    @Test
    void summaryListsIssues() {
        var status = healthyStatus();
        var errors = json(List.of(
            Map.of("errorType", "NPE", "route", "GET /x", "occurrenceCount", 1)
        ));
        var result = CliCheck.evaluate(status, errors, json(List.of()), CheckThresholds.DEFAULTS);
        assertFalse(result.healthy());
        assertTrue(result.summary().contains("1 issue"));
        assertTrue(result.summary().contains("1 unresolved error"));
    }

    // --- Integration tests (with real Brace app) ---

    static Brace integrationApp;
    static int integrationPort;
    static OpsKeys.Keypair integrationKeypair;
    @TempDir static Path integrationProjectDir;

    @BeforeAll
    static void startApp() throws Exception {
        integrationKeypair = OpsKeys.generateKeypair();
        Path keysFile = integrationProjectDir.resolve("ops-authorized-keys");
        Files.writeString(keysFile, integrationKeypair.publicKey() + " scope:control test\n");
        Files.writeString(integrationProjectDir.resolve("ops-private.key"),
            integrationKeypair.privateKey() + "\n" + integrationKeypair.publicKey() + "\n");

        integrationApp = Brace.app().opsUnpinnedForTests().port(0).ops(keysFile.toString());
        integrationApp.start();
        integrationPort = integrationApp.actualPort();

        Files.writeString(integrationProjectDir.resolve(".brace"),
            "ops.local.url=http://localhost:" + integrationPort + "\n");
        Files.writeString(integrationProjectDir.resolve(".brace.local"),
            "ops.key=" + integrationProjectDir.resolve("ops-private.key") + "\n");
    }

    @AfterAll
    static void stopApp() throws Exception {
        if (integrationApp != null) integrationApp.stop();
    }

    @Test
    void checkCommandReturnsZeroForHealthyApp() throws Exception {
        CliAuth.clearCache(integrationProjectDir);
        // LogTap is process-wide static state — any Log.error()/Log.warn() from
        // earlier tests in this Surefire JVM lands in the buffer that
        // /ops/logs?level=warn returns, which would flip checkRecentLogs to fail.
        LogTap.clear();
        var bout = new ByteArrayOutputStream();
        var prev = System.out;
        System.setOut(new PrintStream(bout));
        String output;
        try {
            int code = CliCheck.run(integrationProjectDir, new String[]{"--json"});
            output = bout.toString();
            assertEquals(0, code, "Expected exit 0 but got " + code + ". Output:\n" + output);
        } finally {
            System.setOut(prev);
        }
        // Output may contain log lines (JSON per line) before the pretty-printed result.
        // The check result spans multiple lines and contains "healthy"; find it.
        String[] lines = output.split("\n");
        StringBuilder jsonBuf = new StringBuilder();
        boolean capturing = false;
        for (String line : lines) {
            if (!capturing && line.trim().startsWith("{") && !line.contains("\"event\"")) {
                capturing = true;
            }
            if (capturing) jsonBuf.append(line).append("\n");
        }
        JsonNode result = Json.mapper().readTree(jsonBuf.toString());
        assertTrue(result.has("healthy"), "Missing 'healthy' field in: " + jsonBuf);
        assertTrue(result.path("healthy").asBoolean(), "Expected healthy=true but got: " + jsonBuf);
        // Fresh app has 0m uptime so reachability check warns; healthy is still true
        assertTrue(result.has("summary"));
        assertTrue(result.has("checks"));
    }

    // --- M6: brace check flags an app without a usable Content-Security-Policy ---

    @Test
    void cspCheckWarnsWhenTheAppSendsNone() {
        var check = CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(302, null));
        assertEquals("warn", check.status());
        assertEquals("No Content-Security-Policy on GET /: use app.after(SecurityHeaders.strict())", check.message());
        assertNull(check.followUp(), "followUp is for commands; the fix is a code change");
    }

    @Test
    void cspCheckWarnsOnUnsafeInlineScripts() {
        assertEquals("warn", CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(200,
            "default-src 'self'; script-src 'self' 'unsafe-inline'")).status());
        // No script-src: default-src governs scripts.
        assertEquals("warn", CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(200,
            "default-src 'self' 'unsafe-inline'")).status());
        // Neither: scripts are unrestricted.
        assertEquals("warn", CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(200,
            "frame-ancestors 'none'")).status());
        // A nonce makes CSP2+ browsers ignore 'unsafe-inline' (a CSP1 fallback).
        assertEquals("pass", CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(200,
            "script-src 'self' 'nonce-abc' 'unsafe-inline'")).status());
    }

    @Test
    void cspCheckPassesTheStrictPolicy() {
        var check = CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(200, SecurityHeaders.STRICT_CSP));
        assertEquals("pass", check.status());
        assertNull(CliCheck.checkContentSecurityPolicy(null), "an unreachable app is reported by the other checks");
    }

    @Test
    void cspCheckIsAddedToTheResultAndAWarnStaysHealthy() {
        var result = CliCheck.evaluate(healthyStatus(), json(List.of()), json(List.of()),
            CheckThresholds.DEFAULTS, "prod", CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(200, null)));
        assertTrue(result.healthy(), "a missing CSP is a warning, not a failure");
        assertTrue(result.checks().stream().anyMatch(c -> c.name().equals("csp")));
        assertTrue(result.summary().contains("No Content-Security-Policy"), result.summary());
    }

    @Test
    void probeReadsTheRootResponseWithoutFollowingRedirects() throws Exception {
        var app = Brace.app().opsUnpinnedForTests().port(0);
        app.after(SecurityHeaders.strict());
        app.get("/", req -> Redirect.to("/login"));
        app.start();
        try {
            var probe = CliCheck.probeRoot("http://localhost:" + app.actualPort());
            assertEquals(302, probe.status());
            assertEquals(List.of(SecurityHeaders.STRICT_CSP), probe.policies());
        } finally {
            app.stop();
        }
        assertNull(CliCheck.probeRoot("http://localhost:1"), "unreachable: null, not a throw");
    }

    @Test
    void cspCheckSkipsANonHtmlNotFoundAndReadsEveryPolicy() {
        // An ops-only hostname answers 404 at "/": there is no page to judge.
        assertNull(CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(404, "text/plain", List.of())));
        assertEquals("warn", CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(404, "text/html", List.of())).status());
        // Two policies are both enforced: one that restricts scripts is enough.
        assertEquals("pass", CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(200, "text/html",
            List.of("frame-ancestors 'none'", SecurityHeaders.STRICT_CSP))).status());
        for (String weak : List.of("script-src *", "script-src https:", "default-src 'self' data:")) {
            var check = CliCheck.checkContentSecurityPolicy(new CliCheck.RootProbe(200, weak));
            assertEquals("warn", check.status(), weak);
            assertTrue(check.message().contains("any host"), check.message());
        }
    }

    // --- db ---

    /** A {@code db} block as a 0.1.11 server reports it. */
    static Map<String, Object> dbBlock(int waitMinutes, double avgActive, double lockWaits, int connections, String error) {
        var pool = new LinkedHashMap<String, Object>();
        pool.put("active", 3);
        pool.put("idle", 7);
        pool.put("total", 10);
        pool.put("max", 10);
        pool.put("waiting", 0);
        pool.put("recent", Map.of("minutes", 5, "minutesWithWaits", waitMinutes, "peakWaiting", waitMinutes > 0 ? 4 : 0, "peakActive", 10));
        var pg = new LinkedHashMap<String, Object>();
        pg.put("version", "16.4");
        if (error != null) pg.put("error", error);
        pg.put("activity", Map.of("connections", connections, "maxConnections", 100, "active", 2));
        pg.put("loadRecent", Map.of("minutes", 5, "avgActive", avgActive, "onCpu", avgActive - lockWaits,
            "waitingIo", 0.0, "waitingLock", lockWaits, "waitingOther", 0.0));
        var db = new LinkedHashMap<String, Object>();
        db.put("pool", pool);
        db.put("postgres", pg);
        return db;
    }

    static JsonNode statusWithDb(Map<String, Object> db) {
        Map<String, Object> status = Json.mapper().convertValue(healthyStatus(), Map.class);
        status.put("db", db);
        return json(status);
    }

    @Test
    void dbCheckPassesWithoutADbBlock() {
        var check = CliCheck.checkDatabase(healthyStatus(), CheckThresholds.DEFAULTS, "prod");
        assertEquals("db", check.name());
        assertEquals("pass", check.status());
        assertTrue(check.message().startsWith("Not reported"), check.message());
    }

    @Test
    void dbCheckPassesForAQuietDatabase() {
        var check = CliCheck.checkDatabase(statusWithDb(dbBlock(0, 1.5, 0, 20, null)), CheckThresholds.DEFAULTS, "prod");
        assertEquals("pass", check.status(), check.message());
        assertEquals("Pool 3/10 in use, no waits in the last 5 min; load 1.5", check.message());
    }

    @Test
    void dbCheckWarnsWhenRequestsWaitedForAConnection() {
        var check = CliCheck.checkDatabase(statusWithDb(dbBlock(2, 1.5, 0, 20, null)), CheckThresholds.DEFAULTS, "prod");
        assertEquals("warn", check.status());
        assertEquals("Database: requests waited for a database connection in 2 of the last 5 min (peak 4 waiting, pool of 10)",
            check.message());
        assertEquals("pool_waits", check.details().getFirst().get("signal"));
        assertEquals("brace status --env prod --json", check.followUp());

        var tolerant = new CheckThresholds(500, 70, 80, 50, 0.5, 30, 3, 1.0, 0, 90);
        assertEquals("pass", CliCheck.checkDatabase(statusWithDb(dbBlock(2, 1.5, 0, 20, null)), tolerant, "prod").status());
        var off = new CheckThresholds(500, 70, 80, 50, 0.5, 30, 0, 1.0, 0, 90);
        assertEquals("pass", CliCheck.checkDatabase(statusWithDb(dbBlock(5, 1.5, 0, 20, null)), off, "prod").status());
    }

    @Test
    void dbCheckWarnsOnSustainedLockWaits() {
        var check = CliCheck.checkDatabase(statusWithDb(dbBlock(0, 3.0, 1.2, 20, null)), CheckThresholds.DEFAULTS, "prod");
        assertEquals("warn", check.status());
        assertEquals("Database: 1.2 sessions blocked on locks on average over 5 min", check.message());
        assertEquals("pass", CliCheck.checkDatabase(statusWithDb(dbBlock(0, 3.0, 0.4, 20, null)), CheckThresholds.DEFAULTS, "prod").status());
    }

    @Test
    void dbLoadIsJudgedOnlyAgainstAConfiguredMaximum() {
        var busy = statusWithDb(dbBlock(0, 12.0, 0, 20, null));
        assertEquals("pass", CliCheck.checkDatabase(busy, CheckThresholds.DEFAULTS, "prod").status(),
            "no default: the database server's core count isn't known");
        var t = CheckThresholds.fromConfig(Map.of("check.db_max_load", "8"));
        var check = CliCheck.checkDatabase(busy, t, "prod");
        assertEquals("warn", check.status());
        assertEquals("Database: database load 12.0 active sessions over 5 min (cpu 12.0, io 0.0, lock 0.0, other 0.0)",
            check.message());
        assertEquals(8.0, check.details().getFirst().get("threshold"));
    }

    @Test
    void dbCheckWarnsNearMaxConnectionsAndOnUnreadableStats() {
        var check = CliCheck.checkDatabase(statusWithDb(dbBlock(0, 1.0, 0, 92, "Postgres statistics unavailable: timeout")),
            CheckThresholds.DEFAULTS, "staging");
        assertEquals("warn", check.status());
        assertEquals("Database: Postgres statistics unavailable; 92/100 database connections in use", check.message());
        assertEquals("brace status --env staging --json", check.followUp());
        assertEquals("Postgres statistics unavailable: timeout", check.details().getFirst().get("error"));
    }

    @Test
    void dbWarningLeavesTheAppHealthy() {
        var result = CliCheck.evaluate(statusWithDb(dbBlock(3, 1.5, 0, 20, null)), json(List.of()), json(List.of()),
            CheckThresholds.DEFAULTS);
        assertTrue(result.healthy(), "a warning, not a failure");
        assertTrue(result.summary().startsWith("1 issue: Database: requests waited"), result.summary());
        assertTrue(result.checks().stream().anyMatch(c -> c.name().equals("db")));
    }
}

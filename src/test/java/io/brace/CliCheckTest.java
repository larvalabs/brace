package io.brace;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
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
        status.put("errors", Map.of("recent", List.of()));
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
        var check = CliCheck.checkGcPressure(status, CheckThresholds.DEFAULTS);
        assertEquals("fail", check.status());
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
}

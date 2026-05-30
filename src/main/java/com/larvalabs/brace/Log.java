package com.larvalabs.brace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class Log {

    public static void event(String event, Map<String, Object> data) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "INFO");
        entry.put("event", event);
        entry.putAll(data);
        println(entry);
    }

    static void request(String method, String path, int status, long durationUs,
                        int queryCount, long queryUs) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", status >= 500 ? "ERROR" : "INFO");
        entry.put("event", "http.request");
        entry.put("method", method);
        entry.put("path", path);
        entry.put("status", status);
        entry.put("durationMs", Math.round(durationUs / 100.0) / 10.0);
        entry.put("queries", queryCount);
        entry.put("queryMs", Math.round(queryUs / 100.0) / 10.0);
        println(entry);
    }

    static void error(String method, String path, Throwable error) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "ERROR");
        entry.put("event", "http.error");
        entry.put("method", method);
        entry.put("path", path);
        entry.put("error", error.getClass().getSimpleName());
        entry.put("message", error.getMessage());
        println(entry);
    }

    public static void warn(String message) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "WARN");
        entry.put("message", message);
        println(entry);
    }

    public static void debug(String message) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "DEBUG");
        entry.put("message", message);
        println(entry);
    }

    public static void debug(String message, Map<String, Object> data) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "DEBUG");
        entry.put("message", message);
        entry.putAll(data);
        println(entry);
    }

    public static void info(String message) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "INFO");
        entry.put("message", message);
        println(entry);
    }

    public static void info(String message, Map<String, Object> data) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "INFO");
        entry.put("message", message);
        entry.putAll(data);
        println(entry);
    }

    public static void error(String message) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "ERROR");
        entry.put("message", message);
        println(entry);
    }

    public static void error(String message, Throwable throwable) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("ts", Instant.now().toString());
        entry.put("level", "ERROR");
        entry.put("message", message);
        entry.put("error", throwable.getClass().getSimpleName());
        entry.put("errorMessage", throwable.getMessage());
        println(entry);
    }

    public static void error(String message, Map<String, Object> data) {
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
        LogTap.append(map);
        try {
            System.out.println(Json.mapper().writeValueAsString(map));
        } catch (Exception e) {
            System.out.println(map);
        }
    }
}

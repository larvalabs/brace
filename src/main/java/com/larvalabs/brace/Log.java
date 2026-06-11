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

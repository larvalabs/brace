package com.larvalabs.brace;

import java.util.LinkedHashMap;

/**
 * Reports a failed job run to the same places a failed request goes: a {@code job.error} line in
 * the structured log, and the ops error store (so {@code /ops/errors} and {@code brace errors}
 * list it, and a new kind raises a regression).
 *
 * <p>Before this, a failed scheduled run reached only {@code JobStatus} ({@code lastError},
 * {@code failCount}), and the next successful run cleared {@code lastError}; a failed durable run
 * reached only {@code scheduled_jobs.error}. A failure left no trace in the log except whatever
 * the throwing library printed (in production: a bare Hibernate {@code HHH000247} warning, with no
 * hint of which job ran the statement).
 *
 * <p>Never throws: a failure to report must not change the job's own outcome handling.
 */
final class JobFailures {

    private JobFailures() {}

    /** Route prefix for recurring {@link JobScheduler} jobs in the error store. */
    static final String RECURRING = "job";
    /** Route prefix for durable {@link JobPoller} jobs in the error store. */
    static final String DURABLE = "durable-job";

    /**
     * @param kind   {@link #RECURRING} or {@link #DURABLE}
     * @param name   the job's name (the error kind is {@code (exception type, "kind name")})
     * @param detail per-run context stored as the error's request detail (schedule, attempt, id)
     * @param store  the app's error store, or null when there is no database
     */
    static void report(String kind, String name, String detail, Throwable error, ErrorStore store) {
        String rawMessage;
        try {
            rawMessage = error.getMessage();
        } catch (RuntimeException ex) {
            rawMessage = "[exception message unavailable]";
        }
        String route = kind + " " + name;
        try {
            var data = new LinkedHashMap<String, Object>();
            data.put("event", "job.error");
            data.put("job", name);
            data.put("kind", kind);
            if (detail != null) data.put("detail", detail);
            data.put("error", error.getClass().getSimpleName());
            data.put("errorMessage", Redactor.redactMessage(rawMessage));
            // The root cause is usually the useful part: a Hibernate wrapper says "could not
            // execute statement", the JDBC exception under it says which value was too long.
            Throwable root = rootCause(error);
            if (root != error) {
                data.put("cause", root.getClass().getSimpleName());
                data.put("causeMessage", Redactor.redactMessage(safeMessage(root)));
            }
            String at = Log.appFrame(error);
            if (at != null) data.put("at", at);
            Log.error("Job " + name + " failed", data);
        } catch (RuntimeException ex) {
            failed("log", ex);
        }
        if (store != null) {
            try {
                store.record(error.getClass().getSimpleName(), Redactor.redactMessage(rawMessage),
                    route, stackTrace(error, rawMessage), detail);
            } catch (RuntimeException ex) {
                failed("error store", ex);
            }
        }
    }

    static Throwable rootCause(Throwable t) {
        Throwable root = t;
        // Bounded: a cause cycle would otherwise spin forever.
        for (int i = 0; i < 32 && root.getCause() != null && root.getCause() != root; i++) {
            root = root.getCause();
        }
        return root;
    }

    private static String safeMessage(Throwable t) {
        try {
            return t.getMessage();
        } catch (RuntimeException ex) {
            return "[exception message unavailable]";
        }
    }

    private static String stackTrace(Throwable t, String message) {
        try {
            var sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            return sw.toString();
        } catch (RuntimeException ex) {
            var sb = new StringBuilder(t.getClass().getName()).append(": ").append(message).append('\n');
            try {
                for (StackTraceElement frame : t.getStackTrace()) sb.append("\tat ").append(frame).append('\n');
            } catch (RuntimeException ignored) {
                // the header line alone
            }
            return sb.toString();
        }
    }

    /** Last resort, by class name only: the message would be unredacted text. */
    private static void failed(String sink, RuntimeException ex) {
        System.err.println("brace: could not record a job failure in the " + sink + ": "
            + ex.getClass().getName());
    }
}

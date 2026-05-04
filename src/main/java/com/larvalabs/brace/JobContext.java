package com.larvalabs.brace;

/**
 * Per-run context passed to a scheduled {@link Job}. Use {@link #message(String)} to
 * report a short status string that's shown on the ops dashboard alongside the job's
 * last-run timestamp.
 */
public class JobContext {

    private String message;

    /**
     * Sets a short status message for this run (e.g. {@code "Retrieved 4 new listings"}).
     * Shown on the ops dashboard. Only the most recent message per job is retained;
     * if a run doesn't call {@code message(...)}, the previous run's message is kept.
     */
    public void message(String message) {
        this.message = message;
    }

    /** Internal: read the message set by the job during this run. */
    String consumeMessage() {
        return message;
    }
}

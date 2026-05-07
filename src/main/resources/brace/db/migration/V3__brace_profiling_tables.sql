-- Idempotent — see note in V1__brace_scheduled_jobs.sql.
CREATE TABLE IF NOT EXISTS ops_profiling_snapshots (
    ts TIMESTAMP NOT NULL,
    "type" VARCHAR(20) NOT NULL,
    "name" VARCHAR(255) NOT NULL,
    "value" BIGINT NOT NULL,
    PRIMARY KEY (ts, "type", "name")
);

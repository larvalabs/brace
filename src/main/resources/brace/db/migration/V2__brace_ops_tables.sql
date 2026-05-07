-- Idempotent — see note in V1__brace_scheduled_jobs.sql.
CREATE TABLE IF NOT EXISTS ops_errors (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    error_type VARCHAR(255),
    message TEXT,
    stack_trace TEXT,
    route VARCHAR(255),
    request_detail TEXT,
    queries_before TEXT,
    first_seen TIMESTAMP,
    last_seen TIMESTAMP,
    occurrence_count INT DEFAULT 1,
    resolved_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ops_timeseries (
    ts TIMESTAMP NOT NULL,
    metric VARCHAR(100) NOT NULL,
    val BIGINT,
    PRIMARY KEY (ts, metric)
);

CREATE TABLE ops_errors (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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

CREATE TABLE ops_timeseries (
    ts TIMESTAMP NOT NULL,
    metric VARCHAR(100) NOT NULL,
    val BIGINT,
    PRIMARY KEY (ts, metric)
);

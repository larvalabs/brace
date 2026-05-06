CREATE TABLE scheduled_jobs (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    job_class VARCHAR(255) NOT NULL,
    job_data TEXT,
    run_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    failed_at TIMESTAMP,
    error TEXT,
    attempts INT DEFAULT 0,
    max_attempts INT DEFAULT 3,
    backoff_seconds BIGINT DEFAULT 60,
    depends_on_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (depends_on_id) REFERENCES scheduled_jobs(id)
);

CREATE INDEX idx_scheduled_jobs_run_at ON scheduled_jobs(run_at);

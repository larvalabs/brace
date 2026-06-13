-- Perf review H3: the poller's claim query selects rows WHERE run_at <= now AND
-- completed_at IS NULL AND failed_at IS NULL AND started_at IS NULL, ORDER BY run_at.
-- The V1 index covers run_at over ALL rows, so every poll walks an ever-growing prefix
-- of finished jobs. This partial index contains only claimable-shaped rows, so claim
-- cost tracks live work instead of table history. Postgres-only (migration_pg tier):
-- H2 is the test tier, and the daily brace-jobs-prune retention job bounds the table
-- size everywhere anyway.
CREATE INDEX IF NOT EXISTS idx_scheduled_jobs_claimable ON scheduled_jobs (run_at)
    WHERE completed_at IS NULL AND failed_at IS NULL AND started_at IS NULL;

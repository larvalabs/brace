-- Cluster-wide run coordination for the in-memory recurring scheduler
-- (JobScheduler: every()/daily()). Without this, every running instance fires
-- every recurring job each interval, so a job with side effects (email, billing,
-- webhooks) runs N times across N instances.
--
-- Coordination is by time-slot claim: each tick computes slot = floor(now / period)
-- and row-locks the job's row (SELECT ... FOR UPDATE). The first instance to reach
-- a new slot advances last_run_slot and runs; any instance still on an already-run
-- slot skips. Because all instances derive the same slot number from wall-clock,
-- this is exactly-once per interval regardless of tick stagger (assuming clocks are
-- synced within one period, which NTP guarantees for any non-trivial interval).
-- Portable across H2 (tests) and Postgres — a row lock, not a Postgres-only
-- advisory-lock function.
--
-- IF NOT EXISTS for the same upgrade-safety reason as the other framework
-- migrations (an app adopting bundled migrations on a populated schema).
CREATE TABLE IF NOT EXISTS brace_scheduled_runs (
    job_name VARCHAR(255) PRIMARY KEY,
    last_run_slot BIGINT,
    last_run_at TIMESTAMP
);

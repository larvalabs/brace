-- Convert every framework instant column from zone-less TIMESTAMP to TIMESTAMP WITH TIME ZONE
-- (Postgres: TIMESTAMPTZ). Two payoffs (see docs/2026-06-04-brace-postgres-native.md §1b):
--   1. Correctness: zone-less TIMESTAMP stores a bare wall-clock with no anchor, so an instant
--      round-tripped through a server whose JVM/OS zone isn't UTC can shift. WITH TIME ZONE stores
--      an absolute instant (UTC internally), removing the ambiguity by construction.
--   2. Cleanup: reads come back as typed instants, so the hand-rolled multi-format timestamp
--      parsers that existed only to normalize driver/dialect-specific zone-less strings
--      (ErrorStore.parseFirstSeen, RegressionTracker.parseInstant) are deleted.
--
-- Numbered V7, not V6: the Postgres-only migration_pg/V6 already occupies version 6 in the shared
-- flyway_brace_history sequence on Postgres, and Flyway forbids two migrations with the same
-- version. The base location continues V7+ so the two locations never collide.
--
-- Portability: the SQL-standard "SET DATA TYPE" form parses on both H2 (tests) and Postgres (prod);
-- the Postgres-only "USING ... AT TIME ZONE" clause is deliberately omitted so this one file runs
-- on both tiers. The implicit cast interprets any existing zone-less rows in the session time zone
-- — correct on the UTC-session deployments Brace targets (containers default to UTC); it cannot
-- retroactively disambiguate rows a non-UTC server already mis-stored, but it makes every write
-- from here on unambiguous.

ALTER TABLE scheduled_jobs ALTER COLUMN run_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE scheduled_jobs ALTER COLUMN started_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE scheduled_jobs ALTER COLUMN completed_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE scheduled_jobs ALTER COLUMN failed_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE scheduled_jobs ALTER COLUMN created_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
-- Re-assert the default: some dialects drop a column default when its type changes.
ALTER TABLE scheduled_jobs ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE ops_errors ALTER COLUMN first_seen SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE ops_errors ALTER COLUMN last_seen SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE ops_errors ALTER COLUMN resolved_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE ops_timeseries ALTER COLUMN ts SET DATA TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE ops_profiling_snapshots ALTER COLUMN ts SET DATA TYPE TIMESTAMP WITH TIME ZONE;

ALTER TABLE brace_scheduled_runs ALTER COLUMN last_run_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;

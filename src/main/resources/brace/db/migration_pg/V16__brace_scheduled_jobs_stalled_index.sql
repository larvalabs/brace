-- Supports JobPoller.reclaimStalledJobs, which sweeps for rows claimed by an instance that died
-- before writing a terminal mark (started_at set, both terminal timestamps NULL). The V15 claim
-- index cannot serve that scan: its predicate includes started_at IS NULL, so the very rows the
-- sweep looks for are the ones it excludes, leaving a sequential scan every sweep interval.
--
-- This partial index is the complement: it contains only currently-claimed, unfinished rows. In
-- steady state that is at most poolSize/2 per instance, so it is nearly empty, cheap to maintain
-- on the claim path, and makes the sweep an index scan whose cost tracks in-flight work rather
-- than table size.
--
-- Postgres-only (migration_pg tier), same reasoning as V15: H2 is the test tier, where table
-- sizes are trivial and the sweep's scan cost is irrelevant.
CREATE INDEX IF NOT EXISTS idx_scheduled_jobs_stalled ON scheduled_jobs (started_at)
    WHERE completed_at IS NULL AND failed_at IS NULL AND started_at IS NOT NULL;

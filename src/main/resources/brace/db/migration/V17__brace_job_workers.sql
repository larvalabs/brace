-- Durable-job ownership (0.1.8): recover jobs whose instance died, without a fixed lease.
--
-- Each JobPoller registers one row here and refreshes heartbeat_at every few seconds while it
-- runs. A claim records its owner in scheduled_jobs.claimed_by. The sweep returns a claimed,
-- unfinished job to the queue only when its owner's heartbeat has gone stale — so a job that is
-- merely slow is never picked up a second time while the instance running it is alive, however
-- long it runs. See JobPoller for the full lifecycle (heartbeat, sweep, graceful release).
--
-- Base tier (runs on H2 + Postgres): the claim, heartbeat and sweep all run on both dialects.
-- IF NOT EXISTS for upgrade-safety, consistent with the other framework migrations.
CREATE TABLE IF NOT EXISTS brace_job_workers (
    id VARCHAR(64) PRIMARY KEY,
    instance_id VARCHAR(255),
    started_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- NULL for rows never claimed, and for rows claimed by a pre-0.1.8 instance (which does not write
-- it). The sweep treats a NULL owner conservatively; see JobPoller.sweepOrphans.
ALTER TABLE scheduled_jobs ADD COLUMN IF NOT EXISTS claimed_by VARCHAR(64);

-- Shared regression detection state for multi-server deployments (B6)
-- (docs/2026-06-06-brace-0.1.7-multiserver-plan.md).
--
-- RegressionTracker kept the "new error kinds since deploy" set in process heap, so behind a load
-- balancer each instance had a different set: GET /ops/regressions returned whatever the serving box
-- knew, acknowledge only acked one box, and the numeric id meant different things per box. This table
-- makes the set fleet-wide: one row per (error_type, route, deploy), a stable string id, an atomic
-- INSERT ... ON CONFLICT DO NOTHING claim so exactly one instance notifies, and acknowledge/list that
-- every instance sees identically.
--
-- The baseline is anchored to the deploy marker (Brace.deploy(...) / BRACE_DEPLOY), not per-JVM
-- start time: a rolling deploy classifies the same error identically on every box, and a new deploy
-- (new marker) re-evaluates regressions from a clean baseline.
--
-- Postgres-only (migration_pg): only PostgresRegressionStore touches this table; single-process /
-- H2 apps keep the in-memory store. Latest base version is V9, latest overall is V11, so this is V12.
CREATE TABLE IF NOT EXISTS brace_regressions (
    id              VARCHAR(64) PRIMARY KEY,   -- stable hash of (error_type, route, deploy)
    error_type      TEXT NOT NULL,
    route           TEXT,
    message         TEXT,
    deploy          TEXT NOT NULL,
    first_seen      TIMESTAMPTZ NOT NULL,
    count           BIGINT NOT NULL DEFAULT 1,
    acknowledged_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_brace_regressions_deploy ON brace_regressions (deploy, first_seen DESC);

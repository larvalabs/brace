-- Shared cluster-wide counters with per-key expiry
-- (docs/2026-06-06-brace-0.1.7-multiserver-plan.md, F2).
--
-- Backs the multi-server rate limiter (B4) so a windowed count is enforced across the whole
-- fleet instead of N times too loosely (each box counting only its own traffic). Lives in the
-- BASE migration tier (runs on H2 *and* Postgres) because the rate limiter must work on the H2
-- test path too; coordination is a portable SELECT ... FOR UPDATE + UPDATE/INSERT (see
-- Counters.java), not a Postgres-only ON CONFLICT ... RETURNING.
--
-- The caller encodes the window in the key (e.g. ratelimit:<scope>:<slot>), so successive
-- windows become distinct rows reaped by expires_at; an expired row read mid-window is treated
-- as a fresh start (Counters resets rather than continuing a stale count).
--
-- IF NOT EXISTS for the same upgrade-safety reason as the other framework migrations (an app
-- adopting bundled migrations on an already-populated schema).
CREATE TABLE IF NOT EXISTS brace_counters (
    counter_key VARCHAR(255) PRIMARY KEY,
    n           BIGINT NOT NULL,
    expires_at  TIMESTAMP        -- null = never expires
);
CREATE INDEX IF NOT EXISTS idx_brace_counters_expires ON brace_counters (expires_at);

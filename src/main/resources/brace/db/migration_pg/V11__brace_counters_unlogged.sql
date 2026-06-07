-- Postgres performance tuning for the ephemeral counter tables (B4)
-- (docs/2026-06-07-rate-limiter-load.md).
--
-- brace_counters (rate-limiter windows) and brace_cache_counters (cache incr/decr) hold purely
-- ephemeral, best-effort, windowed data. Losing them on a crash or failover is acceptable — the
-- worst case is a limit/counter resets, allowing a brief burst — so we trade durability for speed:
--
--   * SET UNLOGGED — skip WAL entirely. No fsync on the commit path; writes hit shared_buffers and
--     are flushed lazily by the checkpointer. The table is truncated on crash recovery and is empty
--     on a streaming standby after failover; both are fine for ephemeral counters. This is the big
--     lever that makes a busy server's rate-limit writes cheap.
--   * fillfactor = 70 — leave free space in each page so the repeated UPDATEs on a hot counter row
--     are HOT (Heap-Only Tuple): new version in the same page, no index churn, dead tuples pruned on
--     page access. The PK (counter_key) never changes on increment, so updates are HOT-eligible.
--   * aggressive autovacuum — a flat low threshold so cleanup keeps pace with the churn.
--
-- We deliberately do NOT make the cache *value* table (brace_cache) unlogged: losing cached values
-- en masse on restart causes a cold-cache stampede, a different tradeoff. Only the counters.
--
-- Postgres-only (migration_pg tier): UNLOGGED/fillfactor/autovacuum are Postgres syntax. H2 (the
-- single-process test path) keeps the plain tables from V8/V9. Latest base version is V10, so V11.
ALTER TABLE brace_counters SET UNLOGGED;
ALTER TABLE brace_counters SET (
    fillfactor = 70,
    autovacuum_vacuum_scale_factor = 0,
    autovacuum_vacuum_threshold = 500
);

ALTER TABLE brace_cache_counters SET UNLOGGED;
ALTER TABLE brace_cache_counters SET (
    fillfactor = 70,
    autovacuum_vacuum_scale_factor = 0,
    autovacuum_vacuum_threshold = 500
);

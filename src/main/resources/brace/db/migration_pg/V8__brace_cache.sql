-- Shared cache backend (docs/2026-06-04-brace-shared-cache.md, Phase 1).
-- Postgres-only: these tables are touched solely by PostgresBackend. H2/in-memory apps never
-- create or read them, so this lives in the migration_pg tier (like V6) and uses native TEXT[] +
-- GIN freely. Shares flyway_brace_history with the base tier; latest base version is V7, so this
-- is V8.
--
-- Values and counters live in SEPARATE tables, mirroring the in-memory backend's two maps, so a
-- key used as both a value and a counter never clobbers itself (a single shared table keyed only
-- by cache_key cannot hold both). Value expiry is enforced on READ via the expires_at predicate,
-- so a missed sweep never serves stale data; the background sweep is only space reclamation.
CREATE TABLE IF NOT EXISTS brace_cache (
    cache_key   TEXT PRIMARY KEY,
    value       BYTEA,
    tags        TEXT[],          -- Postgres array; GIN-indexed for clearTag
    expires_at  TIMESTAMPTZ      -- null = no expiry
);
CREATE INDEX IF NOT EXISTS idx_brace_cache_expires ON brace_cache (expires_at);
CREATE INDEX IF NOT EXISTS idx_brace_cache_tags    ON brace_cache USING GIN (tags);

-- Counters: incr/decr only. Separate namespace from values; never expire.
CREATE TABLE IF NOT EXISTS brace_cache_counters (
    cache_key   TEXT PRIMARY KEY,
    counter     BIGINT NOT NULL
);

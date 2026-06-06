-- Shared cache backend (docs/2026-06-04-brace-shared-cache.md, Phase 1).
-- Postgres-only: the cache table is touched solely by PostgresBackend. H2/in-memory apps
-- never create or read it, so this lives in the migration_pg tier (like V6) and uses native
-- TEXT[] + GIN freely. Shares flyway_brace_history with the base tier; latest base version is
-- V7, so the cache table is V8.
--
-- A row is either a value row (value non-null, counter null) or a counter row (counter
-- non-null, value null). Expiry is enforced on READ via the expires_at predicate, so a missed
-- sweep never serves stale data; the background sweep is only space reclamation.
CREATE TABLE IF NOT EXISTS brace_cache (
    cache_key   TEXT PRIMARY KEY,
    value       BYTEA,
    counter     BIGINT,          -- non-null only for incr/decr keys
    tags        TEXT[],          -- Postgres array; GIN-indexed for clearTag
    expires_at  TIMESTAMPTZ      -- null = no expiry
);
CREATE INDEX IF NOT EXISTS idx_brace_cache_expires ON brace_cache (expires_at);
CREATE INDEX IF NOT EXISTS idx_brace_cache_tags    ON brace_cache USING GIN (tags);

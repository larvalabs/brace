-- Page-cache trim order (2026-11 security review, L4 final review fixes).
-- PostgresBackend trimmed page rows soonest-expiring first, so rows on a long-TTL route pinned the
-- page cap and every page on a short-TTL route was trimmed as soon as it was written. used_at is
-- when a row was last written, or last read (recorded at most once a minute per row, so a hot page
-- costs no write per hit); page rows are now trimmed least recently used first.
--
-- now() is STABLE, so Postgres 11+ adds the column without rewriting the table; existing rows get
-- the migration time.
ALTER TABLE brace_cache ADD COLUMN IF NOT EXISTS used_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX IF NOT EXISTS idx_brace_cache_used_at ON brace_cache (used_at);

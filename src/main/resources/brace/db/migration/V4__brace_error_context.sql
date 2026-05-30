-- Idempotent — see note in V1__brace_scheduled_jobs.sql.
-- Phase 1.5 richer error capture: store the redacted request headers present at the throw.
-- queries_before already exists (V2) but was never populated; it now carries a small JSON
-- summary of DB work done before the error. Both H2 and PostgreSQL support IF NOT EXISTS here.
ALTER TABLE ops_errors ADD COLUMN IF NOT EXISTS request_headers TEXT;

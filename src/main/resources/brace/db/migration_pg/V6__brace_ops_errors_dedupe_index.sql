-- Postgres-only framework migration. Lives in its own top-level location
-- (brace/db/migration_pg) rather than under brace/db/migration, because Flyway scans
-- locations recursively — a subdirectory of the base location would also be picked up on
-- the H2 test tier, where this DDL does not parse.
--
-- Backs ErrorStore's INSERT ... ON CONFLICT (...) DO UPDATE upsert (the Postgres branch of
-- ErrorStore.record). The dedupe key is (error_type, route) but ONLY among UNRESOLVED rows:
-- the partial predicate keeps resolved errors out of the index, so a resolved error that
-- recurs inserts a fresh row instead of folding into the old one — exactly the semantics the
-- check-then-insert H2 branch has (see ErrorStoreTest.resolvedErrorsGetNewRowsOnRecurrence).
--
-- H2 has no partial/filtered unique index and a different upsert syntax, which is why this is
-- Postgres-only and the prod path is proven by ErrorStorePostgresIT, not the H2 unit suite.
-- IF NOT EXISTS keeps it idempotent, matching the other framework migrations.
CREATE UNIQUE INDEX IF NOT EXISTS ops_errors_unresolved_dedupe
    ON ops_errors (error_type, route)
    WHERE resolved_at IS NULL;

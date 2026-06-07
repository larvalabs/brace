-- WebSocket broadcast spill table for the Postgres MessageBus (B2)
-- (docs/2026-06-06-brace-0.1.7-multiserver-plan.md, B2).
--
-- Cross-instance WebSocket fan-out uses LISTEN/NOTIFY on channel 'brace_ws'. A NOTIFY payload is
-- capped at 8000 bytes, so a broadcast larger than that is written here and the NOTIFY carries
-- only the row id; every instance's listener fetches the payload by id and delivers to its local
-- room members. Rows are read by ALL instances (not deleted on read) and reaped by age.
--
-- Postgres-only: this table is touched solely by PostgresMessageBus, which is selected only on
-- Postgres (H2/single-process apps use InProcessMessageBus and never create or read it). Lives in
-- the migration_pg tier like V6/V8; shares flyway_brace_history, latest base version is V9, so
-- this is V10.
--
-- IF NOT EXISTS for the same upgrade-safety reason as the other framework migrations.
CREATE TABLE IF NOT EXISTS brace_ws_messages (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    payload     TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_brace_ws_messages_created ON brace_ws_messages (created_at);

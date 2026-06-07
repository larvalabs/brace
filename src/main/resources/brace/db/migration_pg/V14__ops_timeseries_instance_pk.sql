-- Widen the ops_timeseries primary key to include instance_id (P3, Postgres only)
-- (docs/2026-06-06-brace-0.1.7-multiserver-plan.md).
--
-- V2 created PRIMARY KEY (ts, metric); behind a load balancer two instances flushing the same metric
-- at the same instant would collide on that key. Now that each instance writes its own instance-tagged
-- rows (V13 added the column), the key must include instance_id so those rows coexist.
--
-- Postgres-only: the collision only matters for a multi-instance deploy, which is Postgres. H2 (the
-- single-process test path) keeps the (ts, metric) key from V2 — never contended with one instance.
-- DROP PRIMARY KEY syntax differs across engines, which is another reason this lives in migration_pg.
-- Base latest is V13, so this is V14.
--
-- Backfill any pre-upgrade rows (written before instance tagging) so instance_id can be NOT NULL.
UPDATE ops_timeseries SET instance_id = 'legacy' WHERE instance_id IS NULL;
ALTER TABLE ops_timeseries ALTER COLUMN instance_id SET NOT NULL;
ALTER TABLE ops_timeseries DROP CONSTRAINT ops_timeseries_pkey;
ALTER TABLE ops_timeseries ADD PRIMARY KEY (ts, metric, instance_id);

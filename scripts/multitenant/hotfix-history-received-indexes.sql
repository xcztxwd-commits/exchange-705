-- Controlled additive production repair for received-time history pagination.
-- Run only through the separately qualified operator with stopped writers,
-- a current all-field backup/independent restore, and before/after equality.
-- Existing migrations, tenant constraints, prices, history and audit stay intact.
ALTER TABLE market_source_event ADD INDEX mt_source_event_received (tenant_id,symbol_id,received_at,event_sequence,source_time);
ALTER TABLE market_source_tick ADD INDEX mt_source_tick_received (tenant_id,symbol_id,received_at,source_time);

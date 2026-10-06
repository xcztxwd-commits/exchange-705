-- Additive and application-rollback compatible. Keep writer/tenant fences unchanged.
ALTER TABLE market_control_flow
 ADD COLUMN history_pending_until BIGINT NULL,
 ADD COLUMN history_retry_at BIGINT NULL,
 ADD COLUMN history_error VARCHAR(64) NULL;

-- New inactive receipt only; additive columns retain compatibility with the 0603 application.
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026100702,UTC_TIMESTAMP(6),2026100603,0);

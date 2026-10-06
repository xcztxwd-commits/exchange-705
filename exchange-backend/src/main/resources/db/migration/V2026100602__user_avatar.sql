-- Apply to REAL and DEMO schemas before starting the updated backend (ddl-auto=validate).
-- Additive metadata only: no account, password, order, or balance changes.
ALTER TABLE user_account ADD COLUMN avatar_url VARCHAR(300) NULL;
-- Bind the new entity column to the reviewed application epoch. Activation needs fresh proof.
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026100602,UTC_TIMESTAMP(6),2026100602,0);

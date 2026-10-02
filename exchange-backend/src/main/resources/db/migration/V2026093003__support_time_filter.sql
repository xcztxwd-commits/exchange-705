-- A tenant-leading index supports stable bounded support supervision ranges.
CREATE INDEX ix_support_tenant_created_id ON support_conversation(tenant_id,created_at,id);
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026093003,UTC_TIMESTAMP(6),2026093003,0);

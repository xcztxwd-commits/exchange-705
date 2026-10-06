-- Additive, operator-controlled migration. No guessed .net bindings and no automatic activation.
ALTER TABLE tenant
 ADD COLUMN entry_host VARCHAR(253) NULL,
 ADD COLUMN entry_enabled BIT NOT NULL DEFAULT b'0',
 ADD COLUMN entry_verified BIT NOT NULL DEFAULT b'0',
 ADD COLUMN domain_version BIGINT NOT NULL DEFAULT 0,
 ADD UNIQUE KEY uq_tenant_entry_host(entry_host);
ALTER TABLE tenant_domain_binding
 ADD COLUMN domain_role VARCHAR(16) NOT NULL DEFAULT 'FRONTEND',
 ADD COLUMN active_role VARCHAR(16) GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN domain_role ELSE NULL END) STORED,
 ADD COLUMN candidate_role VARCHAR(16) GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING','VERIFIED') THEN domain_role ELSE NULL END) STORED,
 ADD UNIQUE KEY uq_domain_active_role(tenant_id,active_role),
 ADD UNIQUE KEY uq_domain_candidate_role(tenant_id,candidate_role);
ALTER TABLE tenant_domain_history ADD COLUMN domain_role VARCHAR(16) NOT NULL DEFAULT 'FRONTEND';
-- Existing frontend_host/domain_verified/status/sessions/readiness and ownership remain unchanged.
-- The global hostname primary key still covers retired and released records.
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026100603,UTC_TIMESTAMP(6),2026100603,0);

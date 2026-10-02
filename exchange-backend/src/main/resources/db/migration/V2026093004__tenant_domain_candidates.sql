-- A single hostname namespace covers active, pending, retired and explicitly released domains.
CREATE TABLE tenant_domain_binding (
 hostname VARCHAR(253) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 tenant_id BIGINT NOT NULL,status VARCHAR(16) NOT NULL,challenge VARCHAR(32),
 expires_at DATETIME(6),verified_at DATETIME(6),version BIGINT NOT NULL DEFAULT 0,
 KEY ix_domain_candidate(tenant_id,status),
 CONSTRAINT mt_domain_binding_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO tenant_domain_binding(hostname,tenant_id,status,verified_at)
 SELECT frontend_host,id,'ACTIVE',IF(domain_verified,UTC_TIMESTAMP(6),NULL) FROM tenant WHERE frontend_host IS NOT NULL;
-- Existing active/retired conflicts fail the primary key check; never infer or discard an owner.
INSERT INTO tenant_domain_binding(hostname,tenant_id,status) SELECT hostname,tenant_id,'RETIRED' FROM tenant_domain_history;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026093004,UTC_TIMESTAMP(6),2026093004,0);

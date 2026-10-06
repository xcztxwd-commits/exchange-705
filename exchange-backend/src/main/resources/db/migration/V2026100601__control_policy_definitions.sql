-- Additive CONTROL catalog only; existing tenant values and historical data are untouched by this DDL.
CREATE TABLE control_policy_definition (
 policy_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 policy_name VARCHAR(128) NOT NULL,
 options_json LONGTEXT NOT NULL,
 default_value TEXT NOT NULL,
 version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- Stable catalog/tenant-initialization lock anchor. Registration stays closed until an operator edits it.
INSERT INTO control_policy_definition(policy_key,policy_name,options_json,default_value,version)
 VALUES('feature.registration','用户注册','["false","true"]','false',0);
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026100601,UTC_TIMESTAMP(6),2026100601,0);

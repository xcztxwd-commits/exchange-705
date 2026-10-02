-- Forward-only independent identity preferences; existing tenant preferences are untouched.
CREATE TABLE control_table_preference (
 id VARCHAR(160) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
 actor_id BIGINT NOT NULL, table_key VARCHAR(100) COLLATE utf8mb4_bin NOT NULL,
 columns_json LONGTEXT NOT NULL,
 UNIQUE KEY uk_control_preference(actor_id,table_key),
 CONSTRAINT mt_control_preference_actor FOREIGN KEY(actor_id) REFERENCES control_admin(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026093002,UTC_TIMESTAMP(6),2026093002,0);

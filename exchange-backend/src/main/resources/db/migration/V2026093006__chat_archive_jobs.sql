-- Metadata only. Private conversation/message/image bytes stay in the restricted archive root.
-- Additive/restart-safe on existing and newly migrated MySQL5.7+ databases. Never enables deletion.
CREATE TABLE IF NOT EXISTS control_chat_archive_job (
 id VARCHAR(36) NOT NULL PRIMARY KEY, tenant_id BIGINT NOT NULL, conversation_id BIGINT NOT NULL,
 actor_id BIGINT NULL, owner_key VARCHAR(64) NOT NULL, request_key VARCHAR(36) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, reason VARCHAR(512) NOT NULL, state VARCHAR(16) NOT NULL,
 end_id BIGINT NOT NULL, expected_messages BIGINT NOT NULL, processed_messages BIGINT NOT NULL DEFAULT 0,
 cursor_id BIGINT NOT NULL DEFAULT 0, chunk_sequence INT NOT NULL DEFAULT 0,
 attachment_bytes BIGINT NOT NULL DEFAULT 0, chain_hash VARCHAR(64) NOT NULL DEFAULT '',
 snapshot_sha256 VARCHAR(64) NOT NULL, expected_last_hash VARCHAR(64) NOT NULL,
 final_manifest_sha256 VARCHAR(64) NULL, failure_type VARCHAR(128) NULL,
 created_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 UNIQUE KEY archive_request(tenant_id,conversation_id,owner_key,request_key),
 KEY archive_pending(state,updated_at,id), KEY archive_owner(tenant_id,actor_id,conversation_id,created_at),
 CONSTRAINT archive_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id),
 CONSTRAINT archive_actor FOREIGN KEY(actor_id) REFERENCES control_admin(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026093006,UTC_TIMESTAMP(6),2026093006,0) ON DUPLICATE KEY UPDATE version=version;

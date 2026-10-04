-- Additive MySQL 5.7/8.0 migration. Apply only through the project's reviewed migration process.
-- No production execution by this work order. Existing trading, funds and inbox tables stay unchanged.
CREATE TABLE calendar_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 environment VARCHAR(4) NOT NULL, event_id VARCHAR(36) NOT NULL,
 metric VARCHAR(40) NOT NULL, release_date DATE NULL, release_at DATETIME(6) NULL,
 status VARCHAR(24) NOT NULL, published BIT NOT NULL, manual_lock BIT NOT NULL,
 data_json LONGTEXT NOT NULL, upstream_hash VARCHAR(1024) NULL, updated_at DATETIME(6) NOT NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_calendar_event(tenant_id,environment,event_id),
 KEY calendar_window(tenant_id,environment,release_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE calendar_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 environment VARCHAR(4) NOT NULL, event_id VARCHAR(36) NOT NULL, actor VARCHAR(100) NOT NULL,
 action VARCHAR(40) NOT NULL, reason VARCHAR(1000) NOT NULL, before_json LONGTEXT NULL,
 after_json LONGTEXT NULL, created_at DATETIME(6) NOT NULL,
 KEY calendar_audit_event(tenant_id,environment,event_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE calendar_reminder (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 environment VARCHAR(4) NOT NULL, user_id BIGINT NOT NULL, event_id VARCHAR(36) NOT NULL,
 lead_minutes INT NOT NULL, timezone VARCHAR(64) NOT NULL, enabled BIT NOT NULL,
 delivered_at DATETIME(6) NULL, delivered_release_at DATETIME(6) NULL, letter_id BIGINT NULL,
 updated_at DATETIME(6) NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_calendar_reminder(tenant_id,environment,user_id,event_id,lead_minutes),
 KEY calendar_reminder_pending(tenant_id,environment,enabled,event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- These two source tables contain only official public responses, never tenant/user content.
CREATE TABLE calendar_source (
 id VARCHAR(48) NOT NULL PRIMARY KEY, environment VARCHAR(4) NOT NULL, source_id VARCHAR(24) NOT NULL,
 status VARCHAR(24) NOT NULL, last_attempt DATETIME(6) NULL, last_success DATETIME(6) NULL,
 next_attempt DATETIME(6) NULL, lease_until DATETIME(6) NULL, budget_date DATE NULL,
 requests_today INT NOT NULL DEFAULT 0, failures INT NOT NULL DEFAULT 0, http_status INT NULL,
 last_error VARCHAR(500) NULL, etag VARCHAR(300) NULL, last_modified VARCHAR(300) NULL,
 payload LONGTEXT NULL, parsed_json LONGTEXT NULL, row_version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE calendar_source_update (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, environment VARCHAR(4) NOT NULL,
 source_id VARCHAR(24) NOT NULL, status VARCHAR(24) NOT NULL, http_status INT NULL,
 response_hash VARCHAR(64) NULL, error VARCHAR(500) NULL, captured_at DATETIME(6) NOT NULL,
 KEY calendar_source_history(environment,source_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- App bootstrap creates six source states and menu/actions idempotently, not role grants.
-- Rollback: stop calendar jobs and remove the route/permission additions using saved originals.
-- Keep these tables (including audit and delivery receipts) for recovery; never DROP user data.

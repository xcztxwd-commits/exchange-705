-- Additive metadata-only migration for MySQL 5.7/8.0. Reviewed/manual deployment only.
-- No article bodies, images or API keys. Keep existing business and announcement tables unchanged.
CREATE TABLE news_article (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 environment VARCHAR(4) NOT NULL, article_id VARCHAR(36) NOT NULL, source_id VARCHAR(24) NOT NULL,
 category VARCHAR(16) NOT NULL, language VARCHAR(16) NOT NULL,
 published_at DATETIME(6) NULL, discovered_at DATETIME(6) NOT NULL, updated_at DATETIME(6) NOT NULL,
 hidden BIT NOT NULL, sort_order INT NOT NULL DEFAULT 0,
 data_json LONGTEXT NOT NULL, upstream_hash VARCHAR(64) NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_news_article(tenant_id,environment,article_id),
 KEY news_window(tenant_id,environment,source_id,hidden,published_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE news_source_setting (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 environment VARCHAR(4) NOT NULL, source_id VARCHAR(24) NOT NULL,
 enabled BIT NOT NULL, license_reviewed BIT NOT NULL, license_evidence VARCHAR(1000) NULL,
 row_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_news_source_setting(tenant_id,environment,source_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Shared official metadata cache; never tenant-managed content or secrets.
CREATE TABLE news_feed (
 id VARCHAR(40) NOT NULL PRIMARY KEY, environment VARCHAR(4) NOT NULL, source_id VARCHAR(24) NOT NULL,
 status VARCHAR(24) NOT NULL, last_attempt DATETIME(6) NULL, last_success DATETIME(6) NULL,
 content_as_of DATETIME(6) NULL, next_attempt DATETIME(6) NULL, lease_until DATETIME(6) NULL,
 budget_date DATE NULL, requests_today INT NOT NULL DEFAULT 0, failures INT NOT NULL DEFAULT 0,
 item_count INT NOT NULL DEFAULT 0, skipped_count INT NOT NULL DEFAULT 0, http_status INT NULL,
 last_error VARCHAR(200) NULL, etag VARCHAR(300) NULL, last_modified VARCHAR(300) NULL,
 response_hash VARCHAR(64) NULL, parsed_json LONGTEXT NULL, row_version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE news_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 environment VARCHAR(4) NOT NULL, article_id VARCHAR(36) NULL, source_id VARCHAR(24) NOT NULL,
 actor VARCHAR(100) NOT NULL, action VARCHAR(24) NOT NULL, reason VARCHAR(1000) NOT NULL,
 before_json LONGTEXT NULL, after_json LONGTEXT NULL, captured_at DATETIME(6) NOT NULL,
 KEY news_audit_scope(tenant_id,environment,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Bootstrap creates fixed source rows and menu/actions, not arbitrary role grants.
-- Rollback: disable the news job and routes; preserve these four tables for recovery. Do not DROP data.

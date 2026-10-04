-- Additive MySQL 5.7/8.0 metadata migration, after V2026100301/02. No user/order/funds linkage.
CREATE TABLE trader_profile (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 environment VARCHAR(4) NOT NULL, trader_id VARCHAR(36) NOT NULL,
 source_type VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
 name VARCHAR(80) NOT NULL, avatar_url VARCHAR(300) NULL, currency VARCHAR(12) NOT NULL,
 recommended BIT NOT NULL DEFAULT 0, sort_order INT NOT NULL DEFAULT 0,
 updated_at DATETIME(6) NOT NULL, data_json LONGTEXT NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_trader_profile(tenant_id,environment,trader_id),
 KEY trader_public(tenant_id,environment,status,recommended,sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE trader_equity (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 environment VARCHAR(4) NOT NULL, trader_id VARCHAR(36) NOT NULL, point_id VARCHAR(36) NOT NULL,
 point_at DATETIME(6) NOT NULL, net_asset DECIMAL(38,18) NOT NULL, cash_flow DECIMAL(38,18) NULL,
 currency VARCHAR(12) NOT NULL, source_note VARCHAR(1000) NULL,
 UNIQUE KEY uk_trader_point_time(tenant_id,environment,trader_id,point_at),
 UNIQUE KEY uk_trader_point_id(tenant_id,environment,point_id),
 CONSTRAINT fk_trader_equity FOREIGN KEY(tenant_id,environment,trader_id) REFERENCES trader_profile(tenant_id,environment,trader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE trader_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 environment VARCHAR(4) NOT NULL, trader_id VARCHAR(36) NOT NULL, record_id VARCHAR(36) NOT NULL,
 record_key VARCHAR(80) NOT NULL, closed_at DATETIME(6) NOT NULL,
 symbol VARCHAR(40) NOT NULL, direction VARCHAR(8) NOT NULL,
 leverage DECIMAL(38,18) NULL, quantity DECIMAL(38,18) NULL, quantity_unit VARCHAR(16) NULL,
 pnl DECIMAL(38,18) NULL, pnl_basis VARCHAR(16) NOT NULL, fees DECIMAL(38,18) NULL,
 currency VARCHAR(12) NOT NULL, source_note VARCHAR(1000) NULL, evidence_note VARCHAR(1000) NULL,
 UNIQUE KEY uk_trader_record_id(tenant_id,environment,trader_id,record_id),
 UNIQUE KEY uk_trader_record_key(tenant_id,environment,trader_id,record_key),
 KEY trader_closed(tenant_id,environment,trader_id,closed_at),
 CONSTRAINT fk_trader_history FOREIGN KEY(tenant_id,environment,trader_id) REFERENCES trader_profile(tenant_id,environment,trader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE trader_audit (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 environment VARCHAR(4) NOT NULL, trader_id VARCHAR(36) NOT NULL, object_id VARCHAR(36) NULL,
 actor VARCHAR(100) NOT NULL, action VARCHAR(32) NOT NULL, reason VARCHAR(1000) NOT NULL,
 before_json LONGTEXT NULL, after_json LONGTEXT NULL, captured_at DATETIME(6) NOT NULL,
 KEY trader_audit_scope(tenant_id,environment,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- No seeds, external credentials, broad role grants, private users/orders or asset-history reads.
-- Non-destructive rollback: remove routes/grants and code increment; retain four tables and audit for recovery.

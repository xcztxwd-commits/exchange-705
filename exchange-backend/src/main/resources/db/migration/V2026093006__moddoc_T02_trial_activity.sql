-- T02 additive upgrade; MySQL 5.7-compatible repeated execution.
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE activity_campaign ADD COLUMN allow_repeat_claim BOOLEAN NOT NULL DEFAULT FALSE', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_campaign' AND COLUMN_NAME='allow_repeat_claim');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE activity_campaign ADD COLUMN claim_validity_days INT NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_campaign' AND COLUMN_NAME='claim_validity_days');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE activity_campaign ADD COLUMN positions VARCHAR(500) NOT NULL DEFAULT ''["AUTH_HOME"]''', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_campaign' AND COLUMN_NAME='positions');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE activity_campaign ADD COLUMN trigger_conditions VARCHAR(500) NOT NULL DEFAULT ''[]''', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_campaign' AND COLUMN_NAME='trigger_conditions');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE trial_account ADD COLUMN expired DECIMAL(32,16) NOT NULL DEFAULT 0', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trial_account' AND COLUMN_NAME='expired');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE trial_account ADD COLUMN uncovered_loss DECIMAL(32,16) NOT NULL DEFAULT 0', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trial_account' AND COLUMN_NAME='uncovered_loss');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE trial_account ADD COLUMN trial_eligible BOOLEAN NOT NULL DEFAULT FALSE', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trial_account' AND COLUMN_NAME='trial_eligible');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE contract_order ADD COLUMN funding_source VARCHAR(16) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contract_order' AND COLUMN_NAME='funding_source');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE contract_order ADD COLUMN trial_allocations VARCHAR(4000) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contract_order' AND COLUMN_NAME='trial_allocations');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE option_order ADD COLUMN funding_source VARCHAR(16) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='option_order' AND COLUMN_NAME='funding_source');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE option_order ADD COLUMN trial_allocations VARCHAR(4000) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='option_order' AND COLUMN_NAME='trial_allocations');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
CREATE TABLE IF NOT EXISTS trial_grant (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
 user_id BIGINT NOT NULL, campaign_id BIGINT NULL, delivery_id BIGINT NULL,
 request_key VARCHAR(80) NOT NULL, claimed_at DATETIME NOT NULL, expires_at DATETIME NULL,
 active BOOLEAN NOT NULL DEFAULT TRUE,
 available DECIMAL(32,16) NOT NULL DEFAULT 0, frozen DECIMAL(32,16) NOT NULL DEFAULT 0,
 consumed DECIMAL(32,16) NOT NULL DEFAULT 0, expired DECIMAL(32,16) NOT NULL DEFAULT 0,
 UNIQUE KEY uk_trial_grant_request(tenant_id,user_id,request_key), KEY ix_trial_grant_user(tenant_id,user_id,id),
 KEY ix_trial_grant_campaign(tenant_id,campaign_id,user_id)
) ENGINE=InnoDB;
INSERT IGNORE INTO trial_grant(tenant_id,row_version,user_id,campaign_id,delivery_id,request_key,claimed_at,expires_at,active,available,frozen,consumed,expired)
 SELECT tenant_id,0,user_id,NULL,NULL,'LEGACY',CURRENT_TIMESTAMP,NULL,TRUE,available,frozen,consumed,0
 FROM trial_account WHERE granted>0 OR available>0 OR frozen>0;
UPDATE trial_account a SET trial_eligible=TRUE WHERE EXISTS (SELECT 1 FROM trial_grant g WHERE g.tenant_id=a.tenant_id AND g.user_id=a.user_id AND g.request_key='LEGACY' AND g.active=TRUE AND (g.available>0 OR g.frozen>0));
CREATE TABLE IF NOT EXISTS activity_selection (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, row_version BIGINT NOT NULL DEFAULT 0,
 campaign_id BIGINT NOT NULL, operation_id VARCHAR(64) NOT NULL, filter_hash VARCHAR(64) NOT NULL, send_operation_id VARCHAR(64) NULL,
 created_at DATETIME NOT NULL, selected_count BIGINT NOT NULL DEFAULT 0, sent_count BIGINT NOT NULL DEFAULT 0,
 duplicate_count BIGINT NOT NULL DEFAULT 0, ineligible_count BIGINT NOT NULL DEFAULT 0,
 cursor_id BIGINT NOT NULL DEFAULT 0, done BOOLEAN NOT NULL DEFAULT FALSE,
 UNIQUE KEY uk_activity_selection_operation(tenant_id,campaign_id,operation_id)
) ENGINE=InnoDB;
SET @t02_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE activity_selection ADD COLUMN send_operation_id VARCHAR(64) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_selection' AND COLUMN_NAME='send_operation_id');
PREPARE t02_stmt FROM @t02_ddl; EXECUTE t02_stmt; DEALLOCATE PREPARE t02_stmt;
CREATE TABLE IF NOT EXISTS activity_selection_member (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, selection_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 UNIQUE KEY uk_activity_selection_member(tenant_id,selection_id,user_id), KEY ix_activity_selection_cursor(tenant_id,selection_id,id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS activity_send_receipt (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, campaign_id BIGINT NOT NULL,
 operation_id VARCHAR(64) NOT NULL, payload_hash VARCHAR(64) NOT NULL, result_json VARCHAR(1000) NOT NULL,
 UNIQUE KEY uk_activity_send_operation(tenant_id,campaign_id,operation_id)
) ENGINE=InnoDB;

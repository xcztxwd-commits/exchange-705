-- Current workspace additive schema, applied after V2026100101. MySQL 5.7.
-- Existing authoring migrations retain their files; this unique version integrates their missing DDL.
-- No tenant fallback, foreign-key disabling, user/password copying or activation approval.

-- Apply after the existing manual-order and multitenant migrations. MySQL 5.7.
-- Back up first: DDL commits independently. No historical rows or funds are changed.
DELIMITER $$
ALTER TABLE contract_order MODIFY user_id BIGINT NULL$$
ALTER TABLE manual_order_record MODIFY user_id BIGINT NULL$$
CREATE TABLE IF NOT EXISTS manual_order_binding (
 tenant_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 operator_id BIGINT NOT NULL,
 idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 created_at BIGINT NOT NULL,
 evidence LONGTEXT NOT NULL,
 PRIMARY KEY(tenant_id,idempotency_key),
 UNIQUE KEY uk_manual_binding_order(tenant_id,order_id),
 CONSTRAINT fk_manual_binding_order FOREIGN KEY(tenant_id,order_id) REFERENCES contract_order(tenant_id,id),
 CONSTRAINT fk_manual_binding_user FOREIGN KEY(tenant_id,user_id) REFERENCES user_account(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4$$
DROP TRIGGER IF EXISTS manual_unbound_insert$$
CREATE TRIGGER manual_unbound_insert BEFORE INSERT ON contract_order FOR EACH ROW
BEGIN
 IF NEW.user_id IS NULL AND NOT (NEW.order_source='MANUAL_TEST' AND NEW.status='CLOSED' AND NEW.manual_wallet_enabled=0 AND NEW.manual_equity_enabled=0) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Only closed manual simulations may be unbound without funds';
 END IF;
END$$
DROP TRIGGER IF EXISTS manual_unbound_update$$
CREATE TRIGGER manual_unbound_update BEFORE UPDATE ON contract_order FOR EACH ROW
BEGIN
 IF (OLD.user_id IS NOT NULL AND NEW.user_id IS NULL) OR (NEW.user_id IS NULL AND NOT (NEW.order_source='MANUAL_TEST' AND NEW.status='CLOSED' AND NEW.manual_wallet_enabled=0 AND NEW.manual_equity_enabled=0)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid unbound simulation transition';
 END IF;
END$$
DROP TRIGGER IF EXISTS mt_immutable_manual_order_binding$$
CREATE TRIGGER mt_immutable_manual_order_binding BEFORE UPDATE ON manual_order_binding FOR EACH ROW
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF;
END$$
DELIMITER ;

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

SET @stage1_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE user_account ADD COLUMN annual_income DECIMAL(14,2) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_account' AND COLUMN_NAME='annual_income');
PREPARE stage1_stmt FROM @stage1_ddl; EXECUTE stage1_stmt; DEALLOCATE PREPARE stage1_stmt;
SET @stage1_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE user_account ADD COLUMN annual_income_currency VARCHAR(3) NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='user_account' AND COLUMN_NAME='annual_income_currency');
PREPARE stage1_stmt FROM @stage1_ddl; EXECUTE stage1_stmt; DEALLOCATE PREPARE stage1_stmt;
SET @stage1_ddl=(SELECT IF(COUNT(*)=0,'ALTER TABLE announcement ADD COLUMN display_at DATETIME NULL', 'SELECT 1') FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='announcement' AND COLUMN_NAME='display_at');
PREPARE stage1_stmt FROM @stage1_ddl; EXECUTE stage1_stmt; DEALLOCATE PREPARE stage1_stmt;
CREATE TABLE IF NOT EXISTS announcement_receipt (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL, announcement_id BIGINT NOT NULL, read_at DATETIME NOT NULL,
 UNIQUE KEY uk_announcement_receipt(tenant_id,user_id,announcement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE activity_selection ADD UNIQUE KEY mt_tenant_identity(tenant_id,id), ADD CONSTRAINT mt_t_activity_selection FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE activity_selection_member ADD UNIQUE KEY mt_tenant_identity(tenant_id,id), ADD CONSTRAINT mt_t_activity_selection_member FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE activity_send_receipt ADD UNIQUE KEY mt_tenant_identity(tenant_id,id), ADD CONSTRAINT mt_t_activity_send_receipt FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE trial_grant ADD UNIQUE KEY mt_tenant_identity(tenant_id,id), ADD CONSTRAINT mt_t_trial_grant FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE announcement_receipt ADD UNIQUE KEY mt_tenant_identity(tenant_id,id), ADD CONSTRAINT mt_t_announcement_receipt FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE manual_order_binding ADD CONSTRAINT mt_t_manual_order_binding FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE activity_selection ADD CONSTRAINT mt_fk_bbb5482525c1996ff492 FOREIGN KEY(tenant_id,campaign_id) REFERENCES activity_campaign(tenant_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE activity_selection_member ADD CONSTRAINT mt_fk_174160d80e3d9f005d5c FOREIGN KEY(tenant_id,selection_id) REFERENCES activity_selection(tenant_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE activity_selection_member ADD CONSTRAINT mt_fk_5c28fad9c98f685c73db FOREIGN KEY(tenant_id,user_id) REFERENCES user_account(tenant_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE activity_send_receipt ADD CONSTRAINT mt_fk_5ed26ac6d6ccbc4afa73 FOREIGN KEY(tenant_id,campaign_id) REFERENCES activity_campaign(tenant_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE trial_grant ADD CONSTRAINT mt_fk_e6fe59f66115ae0daa18 FOREIGN KEY(tenant_id,user_id) REFERENCES user_account(tenant_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE trial_grant ADD CONSTRAINT mt_fk_061664ca8a3fb6de6312 FOREIGN KEY(tenant_id,campaign_id) REFERENCES activity_campaign(tenant_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE trial_grant ADD CONSTRAINT mt_fk_3e04cc6256c23dedad69 FOREIGN KEY(tenant_id,delivery_id) REFERENCES activity_delivery(tenant_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE announcement_receipt ADD CONSTRAINT mt_fk_48e02e6c67145c4b33fe FOREIGN KEY(tenant_id,user_id) REFERENCES user_account(tenant_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT;
ALTER TABLE announcement_receipt ADD CONSTRAINT mt_fk_dc84e06f52118a640e96 FOREIGN KEY(tenant_id,announcement_id) REFERENCES announcement(tenant_id,id) ON UPDATE RESTRICT ON DELETE RESTRICT;
DELIMITER $$
CREATE TRIGGER mt_immutable_activity_selection BEFORE UPDATE ON activity_selection FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END$$
CREATE TRIGGER mt_immutable_activity_selection_member BEFORE UPDATE ON activity_selection_member FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END$$
CREATE TRIGGER mt_immutable_activity_send_receipt BEFORE UPDATE ON activity_send_receipt FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END$$
CREATE TRIGGER mt_immutable_trial_grant BEFORE UPDATE ON trial_grant FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END$$
CREATE TRIGGER mt_immutable_announcement_receipt BEFORE UPDATE ON announcement_receipt FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END$$
DELIMITER ;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready) VALUES(2026100102,UTC_TIMESTAMP(6),2026100102,0);

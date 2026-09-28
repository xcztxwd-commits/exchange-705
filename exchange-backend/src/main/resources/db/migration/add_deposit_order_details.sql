-- MySQL 5.7; explicitly select the backed-up target database before execution.
-- DDL implicitly commits. Never automatically run against the business database.
SELECT DATABASE() AS target_database, VERSION() AS mysql_version;
SELECT COUNT(*) AS old_orders, SUM(CASE WHEN status='COMPLETED' THEN amount ELSE 0 END) AS old_completed_usd FROM deposit_record;
SELECT d.id FROM deposit_record d LEFT JOIN user_account u ON u.id=d.user_id WHERE u.id IS NULL OR d.amount<=0;
-- STOP and resolve any preflight rows above before production rollout.
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='order_no')=0,'ALTER TABLE deposit_record ADD COLUMN order_no VARCHAR(64) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='source')=0,'ALTER TABLE deposit_record ADD COLUMN source VARCHAR(32) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='account_type')=0,'ALTER TABLE deposit_record ADD COLUMN account_type VARCHAR(16) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='manual_purpose')=0,'ALTER TABLE deposit_record ADD COLUMN manual_purpose VARCHAR(24) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='idempotency_key')=0,'ALTER TABLE deposit_record ADD COLUMN idempotency_key VARCHAR(64) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='request_hash')=0,'ALTER TABLE deposit_record ADD COLUMN request_hash VARCHAR(64) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='review_remark')=0,'ALTER TABLE deposit_record ADD COLUMN review_remark VARCHAR(500) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='created_by_type')=0,'ALTER TABLE deposit_record ADD COLUMN created_by_type VARCHAR(24) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='created_by_id')=0,'ALTER TABLE deposit_record ADD COLUMN created_by_id BIGINT NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='created_by_name')=0,'ALTER TABLE deposit_record ADD COLUMN created_by_name VARCHAR(128) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='reviewed_by_type')=0,'ALTER TABLE deposit_record ADD COLUMN reviewed_by_type VARCHAR(24) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='reviewed_by_id')=0,'ALTER TABLE deposit_record ADD COLUMN reviewed_by_id BIGINT NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='reviewed_by_name')=0,'ALTER TABLE deposit_record ADD COLUMN reviewed_by_name VARCHAR(128) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='fee_rate')=0,'ALTER TABLE deposit_record ADD COLUMN fee_rate DECIMAL(32,16) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='fee_amount')=0,'ALTER TABLE deposit_record ADD COLUMN fee_amount DECIMAL(32,16) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='reviewed_at')=0,'ALTER TABLE deposit_record ADD COLUMN reviewed_at DATETIME(6) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='credited_at')=0,'ALTER TABLE deposit_record ADD COLUMN credited_at DATETIME(6) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
UPDATE deposit_record SET source='LEGACY_UNKNOWN' WHERE source IS NULL;
UPDATE deposit_record SET order_no=CONCAT('LEGACY-DEP-',id) WHERE order_no IS NULL;
UPDATE deposit_record SET account_type='FUND' WHERE account_type IS NULL;
-- No historical audit, fees, rates, timestamps or balance snapshots are guessed.
CREATE TABLE IF NOT EXISTS deposit_credit_record (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 deposit_record_id BIGINT NOT NULL, user_id BIGINT NOT NULL, account_type VARCHAR(16) NOT NULL,
 amount_usd DECIMAL(32,16) NOT NULL, balance_before DECIMAL(32,16) NOT NULL, balance_after DECIMAL(32,16) NOT NULL,
 operator_type VARCHAR(24) NOT NULL, operator_id BIGINT NOT NULL, operator_name VARCHAR(128), credited_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_deposit_credit(deposit_record_id),
 CONSTRAINT fk_deposit_credit_order FOREIGN KEY(deposit_record_id) REFERENCES deposit_record(id) ON DELETE RESTRICT,
 CONSTRAINT fk_deposit_credit_user FOREIGN KEY(user_id) REFERENCES user_account(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='uk_deposit_order_no')=0,'ALTER TABLE deposit_record ADD UNIQUE INDEX uk_deposit_order_no(order_no)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='uk_deposit_request')=0,'ALTER TABLE deposit_record ADD UNIQUE INDEX uk_deposit_request(created_by_type,created_by_id,idempotency_key)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='ix_deposit_created')=0,'ALTER TABLE deposit_record ADD INDEX ix_deposit_created(created_at,id)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='ix_deposit_user')=0,'ALTER TABLE deposit_record ADD INDEX ix_deposit_user(user_id,created_at,id)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='ix_deposit_status')=0,'ALTER TABLE deposit_record ADD INDEX ix_deposit_status(status,created_at,id)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='ix_deposit_source')=0,'ALTER TABLE deposit_record ADD INDEX ix_deposit_source(source,status,credited_at)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='ix_deposit_reviewed')=0,'ALTER TABLE deposit_record ADD INDEX ix_deposit_reviewed(reviewed_at,id)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
INSERT INTO admin_menu(parent_id,menu_name,menu_code,menu_type,path,sort_order,status,created_at,updated_at)
SELECT 0,'充值详情','deposit_orders','menu','/deposit-orders',35,'active',NOW(),NOW() FROM DUAL
WHERE NOT EXISTS(SELECT 1 FROM admin_menu WHERE menu_code='deposit_orders');
SET @deposit_menu=(SELECT id FROM admin_menu WHERE menu_code='deposit_orders');
INSERT INTO admin_menu(parent_id,menu_name,menu_code,menu_type,sort_order,status,created_at,updated_at) SELECT @deposit_menu,'查看充值详情','view_deposit_orders','button',0,'active',NOW(),NOW() FROM DUAL WHERE NOT EXISTS(SELECT 1 FROM admin_menu WHERE menu_code='view_deposit_orders');
INSERT INTO menu_action(menu_id,action_code,action_name,sort_order,created_at,updated_at) SELECT @deposit_menu,'view_deposit_orders','查看充值详情',0,NOW(),NOW() FROM DUAL WHERE NOT EXISTS(SELECT 1 FROM menu_action WHERE menu_id=@deposit_menu AND action_code='view_deposit_orders');
INSERT INTO admin_menu(parent_id,menu_name,menu_code,menu_type,sort_order,status,created_at,updated_at) SELECT @deposit_menu,'手动充值','manual_deposit','button',0,'active',NOW(),NOW() FROM DUAL WHERE NOT EXISTS(SELECT 1 FROM admin_menu WHERE menu_code='manual_deposit');
INSERT INTO menu_action(menu_id,action_code,action_name,sort_order,created_at,updated_at) SELECT @deposit_menu,'manual_deposit','手动充值',0,NOW(),NOW() FROM DUAL WHERE NOT EXISTS(SELECT 1 FROM menu_action WHERE menu_id=@deposit_menu AND action_code='manual_deposit');
INSERT INTO admin_menu(parent_id,menu_name,menu_code,menu_type,sort_order,status,created_at,updated_at) SELECT @deposit_menu,'导出充值详情','export_deposit_orders','button',0,'active',NOW(),NOW() FROM DUAL WHERE NOT EXISTS(SELECT 1 FROM admin_menu WHERE menu_code='export_deposit_orders');
INSERT INTO menu_action(menu_id,action_code,action_name,sort_order,created_at,updated_at) SELECT @deposit_menu,'export_deposit_orders','导出充值详情',0,NOW(),NOW() FROM DUAL WHERE NOT EXISTS(SELECT 1 FROM menu_action WHERE menu_id=@deposit_menu AND action_code='export_deposit_orders');
-- Deliberately no admin_role_menu, user_menu or user_action grants. Existing review authorization is unchanged.

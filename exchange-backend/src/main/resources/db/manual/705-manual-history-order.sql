-- MySQL 5.7 additive migration. Back up first. DDL commits independently of business transactions.
SET @manual_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='order_source')=0, 'ALTER TABLE contract_order ADD COLUMN order_source VARCHAR(24) NOT NULL DEFAULT ''USER''', 'SELECT 1');
PREPARE manual_stmt FROM @manual_ddl;
EXECUTE manual_stmt;
DEALLOCATE PREPARE manual_stmt;
SET @manual_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='manual_wallet_enabled')=0, 'ALTER TABLE contract_order ADD COLUMN manual_wallet_enabled BOOLEAN NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE manual_stmt FROM @manual_ddl;
EXECUTE manual_stmt;
DEALLOCATE PREPARE manual_stmt;
SET @manual_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='manual_equity_enabled')=0, 'ALTER TABLE contract_order ADD COLUMN manual_equity_enabled BOOLEAN NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE manual_stmt FROM @manual_ddl;
EXECUTE manual_stmt;
DEALLOCATE PREPARE manual_stmt;
SET @manual_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='asset_history_1m' AND column_name='manual_adjustment')=0, 'ALTER TABLE asset_history_1m ADD COLUMN manual_adjustment DECIMAL(32,16) NOT NULL DEFAULT 0', 'SELECT 1');
PREPARE manual_stmt FROM @manual_ddl;
EXECUTE manual_stmt;
DEALLOCATE PREPARE manual_stmt;
SET @manual_ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='asset_history_1m' AND column_name='effective_at')=0, 'ALTER TABLE asset_history_1m ADD COLUMN effective_at BIGINT NULL', 'SELECT 1');
PREPARE manual_stmt FROM @manual_ddl;
EXECUTE manual_stmt;
DEALLOCATE PREPARE manual_stmt;
ALTER TABLE asset_history_1m MODIFY observed_at BIGINT NULL, MODIFY quote_batch_id VARCHAR(36) NULL;
CREATE TABLE IF NOT EXISTS manual_order_record (
 idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 order_id BIGINT NOT NULL, operator_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 created_at BIGINT NOT NULL, timezone VARCHAR(64) NOT NULL,
 evidence LONGTEXT NOT NULL, UNIQUE KEY uk_manual_order(order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO asset_history_migration(migration_id,applied_at,basis_version)
VALUES('705-manual-history-order',UNIX_TIMESTAMP()*1000,'net_equity_v1')
ON DUPLICATE KEY UPDATE migration_id=VALUES(migration_id);

-- Additive MySQL 5.7 DDL. Each ALTER implicitly commits; never claim transactional DDL rollback.
DELIMITER $$
DROP PROCEDURE IF EXISTS cq_schema_20260929$$
CREATE PROCEDURE cq_schema_20260929()
BEGIN
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trading_symbol' AND COLUMN_NAME='quantity_unit_type') THEN
  ALTER TABLE trading_symbol ADD COLUMN quantity_unit_type VARCHAR(16) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trading_symbol' AND COLUMN_NAME='spec_version') THEN
  ALTER TABLE trading_symbol ADD COLUMN spec_version BIGINT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trading_symbol' AND COLUMN_NAME='min_order_quantity') THEN
  ALTER TABLE trading_symbol ADD COLUMN min_order_quantity DECIMAL(32,16) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trading_symbol' AND COLUMN_NAME='quantity_step') THEN
  ALTER TABLE trading_symbol ADD COLUMN quantity_step DECIMAL(32,16) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='trading_symbol' AND COLUMN_NAME='min_order_notional') THEN
  ALTER TABLE trading_symbol ADD COLUMN min_order_notional DECIMAL(32,16) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contract_order' AND COLUMN_NAME='quantity_unit_type') THEN
  ALTER TABLE contract_order ADD COLUMN quantity_unit_type VARCHAR(16) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contract_order' AND COLUMN_NAME='spec_version') THEN
  ALTER TABLE contract_order ADD COLUMN spec_version BIGINT NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contract_order' AND COLUMN_NAME='min_order_quantity') THEN
  ALTER TABLE contract_order ADD COLUMN min_order_quantity DECIMAL(32,16) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contract_order' AND COLUMN_NAME='quantity_step') THEN
  ALTER TABLE contract_order ADD COLUMN quantity_step DECIMAL(32,16) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contract_order' AND COLUMN_NAME='min_order_notional') THEN
  ALTER TABLE contract_order ADD COLUMN min_order_notional DECIMAL(32,16) NULL;
 END IF;
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='contract_order' AND COLUMN_NAME='quantity_asset') THEN
  ALTER TABLE contract_order ADD COLUMN quantity_asset VARCHAR(16) NULL;
 END IF;
END$$
CALL cq_schema_20260929()$$
DROP PROCEDURE cq_schema_20260929$$
DELIMITER ;

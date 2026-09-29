-- Apply with trading stopped, after a backup. Does not modify any order or balance.
-- Preserve this one-time backup; rerunning must never overwrite the original fee schedule.
CREATE TABLE IF NOT EXISTS trading_symbol_fx_backup_20260928 LIKE trading_symbol;
INSERT IGNORE INTO trading_symbol_fx_backup_20260928 SELECT * FROM trading_symbol WHERE source_category='Forex';
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='fx_base_currency')=0,
  'ALTER TABLE contract_order ADD COLUMN fx_base_currency VARCHAR(3) NULL', 'SELECT 1');
PREPARE migration_statement FROM @ddl;
EXECUTE migration_statement;
DEALLOCATE PREPARE migration_statement;
START TRANSACTION;
UPDATE trading_symbol SET lot_size=100000,fee_multiplier=7.00,min_trade_amount=0.01,volume_precision=2,
  row_version=row_version+1,updated_at=UTC_TIMESTAMP()
WHERE source_category='Forex' AND lot_size=1000 AND fee_multiplier=30;
COMMIT;
SELECT symbol,lot_size,fee_multiplier,min_trade_amount,volume_precision FROM trading_symbol WHERE source_category='Forex';

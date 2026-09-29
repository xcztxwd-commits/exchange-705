-- Run schema.sql first on restored isolated database. Explicit opt-in and expected database required.
-- SET @cq_apply=1; SET @cq_database='verified_database';
-- First deploy the compatible reader. Never execute against a legacy running writer.
CREATE TABLE IF NOT EXISTS trading_symbol_cq_backup_20260929 LIKE trading_symbol;
DELIMITER $$
DROP PROCEDURE IF EXISTS cq_migrate_20260929$$
CREATE PROCEDURE cq_migrate_20260929()
BEGIN
 DECLARE n INT DEFAULT 0;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 IF COALESCE(@cq_apply,0)<>1 OR NOT(@cq_database <=> DATABASE()) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Explicit verified database opt-in required'; END IF;
 START TRANSACTION;
 SELECT COUNT(*) INTO n FROM trading_symbol WHERE ((id=72 AND symbol='BTCUSDT' AND base_currency='BTC') OR (id=73 AND symbol='ETHUSDT' AND base_currency='ETH') OR (id=74 AND symbol='SOLUSDT' AND base_currency='SOL')) AND market_source='binance' AND source_category='Crypto' AND quote_currency='USDT' FOR UPDATE;
 IF n<>3 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Whitelist identity mismatch'; END IF;
 SELECT COUNT(*) INTO n FROM trading_symbol WHERE ((id=72 AND symbol='BTCUSDT' AND base_currency='BTC') OR (id=73 AND symbol='ETHUSDT' AND base_currency='ETH') OR (id=74 AND symbol='SOLUSDT' AND base_currency='SOL')) AND market_source='binance' AND source_category='Crypto' AND quote_currency='USDT' AND NOT (
 (quantity_unit_type IS NULL AND spec_version IS NULL AND lot_size>0 AND fee_multiplier>=0
 AND CAST(CAST(fee_multiplier AS DECIMAL(65,30))/lot_size AS DECIMAL(32,16))*lot_size=fee_multiplier)
 OR (quantity_unit_type='BASE_ASSET' AND spec_version=1 AND lot_size=1 AND EXISTS(SELECT 1 FROM trading_symbol_cq_backup_20260929 b WHERE b.id=trading_symbol.id)));
 IF n<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid, inexact or unexpected prior specification'; END IF;
 INSERT INTO trading_symbol_cq_backup_20260929 SELECT * FROM trading_symbol WHERE ((id=72 AND symbol='BTCUSDT' AND base_currency='BTC') OR (id=73 AND symbol='ETHUSDT' AND base_currency='ETH') OR (id=74 AND symbol='SOLUSDT' AND base_currency='SOL')) AND market_source='binance' AND source_category='Crypto' AND quote_currency='USDT' AND quantity_unit_type IS NULL
 AND NOT EXISTS(SELECT 1 FROM trading_symbol_cq_backup_20260929 b WHERE b.id=trading_symbol.id);
 UPDATE trading_symbol SET fee_multiplier=CAST(CAST(fee_multiplier AS DECIMAL(65,30))/lot_size AS DECIMAL(32,16)),lot_size=1,
 quantity_unit_type='BASE_ASSET',spec_version=1,min_order_quantity=CASE WHEN id=74 THEN 0.01 ELSE 0.001 END,
 quantity_step=CASE WHEN id=74 THEN 0.01 ELSE 0.001 END,min_order_notional=10,row_version=row_version+1
 WHERE ((id=72 AND symbol='BTCUSDT' AND base_currency='BTC') OR (id=73 AND symbol='ETHUSDT' AND base_currency='ETH') OR (id=74 AND symbol='SOLUSDT' AND base_currency='SOL')) AND market_source='binance' AND source_category='Crypto' AND quote_currency='USDT' AND quantity_unit_type IS NULL AND spec_version IS NULL;
 COMMIT;
END$$
CALL cq_migrate_20260929()$$
DROP PROCEDURE cq_migrate_20260929$$
DELIMITER ;

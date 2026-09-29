-- Keep additive columns. Do not restore a whole business database over later activity.
DELIMITER $$
DROP PROCEDURE IF EXISTS cq_rollback_20260929$$
CREATE PROCEDURE cq_rollback_20260929()
BEGIN
 DECLARE n INT;
 DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
 IF COALESCE(@cq_apply,0)<>1 OR NOT(@cq_database <=> DATABASE()) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Explicit verified database opt-in required'; END IF;
 START TRANSACTION;
 SELECT COUNT(*) INTO n FROM trading_symbol WHERE ((id=72 AND symbol='BTCUSDT' AND base_currency='BTC') OR (id=73 AND symbol='ETHUSDT' AND base_currency='ETH') OR (id=74 AND symbol='SOLUSDT' AND base_currency='SOL')) AND market_source='binance' AND source_category='Crypto' AND quote_currency='USDT' FOR UPDATE;
 IF n<>3 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Whitelist identity mismatch'; END IF;
 SELECT COUNT(*) INTO n FROM contract_order o JOIN trading_symbol_cq_backup_20260929 b ON b.symbol=o.symbol WHERE o.spec_version IS NOT NULL;
 IF n>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='New specification orders exist: keep compatible code and fix forward'; END IF;
 UPDATE trading_symbol s JOIN trading_symbol_cq_backup_20260929 b ON b.id=s.id
 SET s.lot_size=b.lot_size,s.fee_multiplier=b.fee_multiplier,s.quantity_unit_type=b.quantity_unit_type,s.spec_version=b.spec_version,
 s.min_order_quantity=b.min_order_quantity,s.quantity_step=b.quantity_step,s.min_order_notional=b.min_order_notional,s.row_version=s.row_version+1
 WHERE s.quantity_unit_type='BASE_ASSET' AND s.spec_version=1;
 COMMIT;
END$$
CALL cq_rollback_20260929()$$
DROP PROCEDURE cq_rollback_20260929$$
DELIMITER ;

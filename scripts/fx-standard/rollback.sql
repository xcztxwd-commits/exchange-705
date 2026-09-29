-- Stop new order entry first. Restore only migrated symbol settings; NEVER rewrite orders/funds.
-- Keep the new backend for any FX_STANDARD orders already created (fx_base_currency IS NOT NULL).
START TRANSACTION;
UPDATE trading_symbol s JOIN trading_symbol_fx_backup_20260928 b ON s.id=b.id
SET s.lot_size=b.lot_size,s.fee_multiplier=b.fee_multiplier,s.min_trade_amount=b.min_trade_amount,
  s.volume_precision=b.volume_precision,s.row_version=s.row_version+1,s.updated_at=UTC_TIMESTAMP()
WHERE s.source_category='Forex' AND s.lot_size=100000 AND s.fee_multiplier=7.00;
COMMIT;
-- New standard FX entries will reject the old lot size until the new policy is restored.
-- Do not DROP fx_base_currency or clear snapshots. Old binaries cannot safely match new pending FX orders.

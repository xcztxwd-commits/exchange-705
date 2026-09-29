-- Read only, includes disabled instruments. This whitelist was observed on local exchange-705 / 1090.
SELECT id,symbol,base_currency,market_source,source_category,is_enabled,lot_size AS old_lot_size,fee_multiplier AS old_fee_multiplier,
 CAST(fee_multiplier/NULLIF(lot_size,0) AS DECIMAL(32,16)) AS proposed_fee_multiplier,1 AS proposed_lot_size,
 CASE WHEN id=74 THEN 0.01 ELSE 0.001 END AS proposed_minimum_and_step,10 AS proposed_minimum_usd
 FROM trading_symbol WHERE ((id=72 AND symbol='BTCUSDT' AND base_currency='BTC') OR (id=73 AND symbol='ETHUSDT' AND base_currency='ETH') OR (id=74 AND symbol='SOLUSDT' AND base_currency='SOL')) AND market_source='binance' AND source_category='Crypto' AND quote_currency='USDT';
SELECT status,COUNT(*) FROM contract_order GROUP BY status;

-- Apply only missing columns, with backend writers stopped. Hibernate ddl-auto=update also adds these columns.
-- Do not backfill contract_order.lot_size: NULL preserves historical settlement rules.
ALTER TABLE contract_order ADD COLUMN lot_size DECIMAL(32,16) NULL;
-- NULL defaults to a 100x cap in the application; historical leverage settings remain intact.
ALTER TABLE trading_symbol ADD COLUMN max_leverage DECIMAL(10,2) NULL;

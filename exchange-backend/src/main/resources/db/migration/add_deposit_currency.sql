-- For deployments using ddl-auto=validate/none. Existing amounts remain USD.
ALTER TABLE deposit_record
    ADD COLUMN currency VARCHAR(3) NULL,
    ADD COLUMN original_amount DECIMAL(32,16) NULL,
    ADD COLUMN exchange_rate DECIMAL(32,16) NULL;

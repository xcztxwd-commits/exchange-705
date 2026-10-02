-- Only for deployments with automatic schema updates disabled.
ALTER TABLE withdraw_record
    ADD COLUMN currency VARCHAR(3) NULL,
    ADD COLUMN original_amount DECIMAL(32,16) NULL,
    ADD COLUMN exchange_rate DECIMAL(32,16) NULL;

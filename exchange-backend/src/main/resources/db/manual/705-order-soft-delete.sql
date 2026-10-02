-- Run once before deploying; nullable columns preserve all existing records.
ALTER TABLE contract_order ADD COLUMN deleted_at DATETIME NULL, ADD COLUMN deleted_by VARCHAR(64) NULL;
ALTER TABLE option_order ADD COLUMN deleted_at DATETIME NULL, ADD COLUMN deleted_by VARCHAR(64) NULL;

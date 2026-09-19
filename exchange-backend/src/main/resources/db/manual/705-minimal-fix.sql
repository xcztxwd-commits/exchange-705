-- Manual migration only. Stop writers and back up first. Do not run against the original database during QA.
-- Preflight: must return zero rows. Resolve duplicates manually; do not merge historical balances automatically.
SELECT user_id, coin, COUNT(*) AS duplicate_count FROM asset_account GROUP BY user_id, coin HAVING COUNT(*) > 1;
ALTER TABLE asset_account ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE contract_order ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE option_order ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE deposit_record ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE withdraw_record ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE loan_record ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE financial_order ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE financial_yield_record ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE asset_account ADD CONSTRAINT uk_asset_user_coin UNIQUE (user_id, coin);
ALTER TABLE transfer_record ADD COLUMN request_id VARCHAR(64) NULL;
ALTER TABLE transfer_record ADD CONSTRAINT uk_transfer_request UNIQUE (user_id, request_id);
ALTER TABLE admin_user ADD COLUMN current_token VARCHAR(128) NULL;
ALTER TABLE user_account ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE admin_user ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE verify_code ADD COLUMN failed_attempts INT NOT NULL DEFAULT 0;

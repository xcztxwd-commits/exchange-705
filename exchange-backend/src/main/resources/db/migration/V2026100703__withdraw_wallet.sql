-- Run before deploying the wallet-aware withdrawal backend. Existing withdrawals used FUND.
ALTER TABLE withdraw_record
    ADD COLUMN account_type VARCHAR(20) NOT NULL DEFAULT 'FUND';

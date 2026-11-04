-- Additive administrator switch. Existing balances and frozen reservations remain intact.
-- Apply once with the reviewed deployment helper after a verified table backup.
ALTER TABLE trial_account ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1;

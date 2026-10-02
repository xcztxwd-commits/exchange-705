-- T01: apply once after backup. Existing country_code/phone stay unchanged.
ALTER TABLE user_account
  ADD COLUMN annual_income DECIMAL(14,2) NULL,
  ADD COLUMN annual_income_currency VARCHAR(3) NULL;
-- Rollback after verifying no needed profiles: ALTER TABLE user_account DROP COLUMN annual_income, DROP COLUMN annual_income_currency;

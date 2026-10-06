-- Apply to REAL and DEMO schemas before starting the updated backend (ddl-auto=validate).
-- Additive metadata only: no account, password, order, or balance changes.
ALTER TABLE user_account ADD COLUMN avatar_url VARCHAR(300) NULL;

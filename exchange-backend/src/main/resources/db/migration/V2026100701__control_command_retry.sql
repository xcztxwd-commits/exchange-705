-- Additive rollout: retain these columns when rolling back the application image.
ALTER TABLE market_control_command
 ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
 ADD COLUMN retry_at BIGINT NOT NULL DEFAULT 0;

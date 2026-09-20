-- Apply only missing columns while backend writers are stopped.
-- Hibernate ddl-auto=update also adds these fields. Existing offsets are preserved.
ALTER TABLE trading_symbol ADD COLUMN control_start_price DECIMAL(32,16) NULL;
ALTER TABLE trading_symbol ADD COLUMN control_target_price DECIMAL(32,16) NULL;
ALTER TABLE trading_symbol ADD COLUMN control_started_at BIGINT NULL;
ALTER TABLE trading_symbol ADD COLUMN control_duration_seconds INT NULL;
ALTER TABLE trading_symbol ADD COLUMN control_intensity INT NULL;
ALTER TABLE trading_symbol ADD COLUMN control_random_oscillation BIT NULL;
ALTER TABLE trading_symbol ADD COLUMN control_completed_at BIGINT NULL;
ALTER TABLE trading_symbol ADD COLUMN control_restoring BIT NULL;
ALTER TABLE trading_symbol ADD COLUMN row_version BIGINT NOT NULL DEFAULT 0;

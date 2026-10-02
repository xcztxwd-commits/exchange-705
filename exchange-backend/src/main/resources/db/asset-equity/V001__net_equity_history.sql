-- Incremental, MySQL 5.7. Legacy asset_snapshot is wallet_balance_v1, never relabelled/copied.
CREATE TABLE IF NOT EXISTS asset_history_migration (
 migration_id VARCHAR(64) PRIMARY KEY, applied_at BIGINT NOT NULL, basis_version VARCHAR(32) NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS asset_history_quote_batch (
 batch_id VARCHAR(36) PRIMARY KEY, prepared_at BIGINT NOT NULL, evidence LONGTEXT NOT NULL
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS asset_history_1m (
 user_id BIGINT NOT NULL, basis_version VARCHAR(32) NOT NULL, bucket_start BIGINT NOT NULL,
 observed_at BIGINT NOT NULL,
 wallet_balance DECIMAL(32,16) NULL,
 contract_unrealized_pnl DECIMAL(32,16) NULL,
 option_unrealized_pnl DECIMAL(32,16) NULL,
 receivables DECIMAL(32,16) NULL,
 loan_principal DECIMAL(32,16) NULL,
 accrued_interest DECIMAL(32,16) NULL,
 overdue_fees DECIMAL(32,16) NULL,
 accrued_trading_fees DECIMAL(32,16) NULL,
 other_liabilities DECIMAL(32,16) NULL,
 liabilities_total DECIMAL(32,16) NULL,
 net_equity DECIMAL(32,16) NULL,
 valuation_status VARCHAR(24) NOT NULL, reason_code VARCHAR(1024) NOT NULL,
 quote_batch_id VARCHAR(36) NOT NULL, valuation_evidence LONGTEXT NOT NULL,
 origin VARCHAR(24) NOT NULL DEFAULT 'OBSERVED', created_at BIGINT NOT NULL,
 PRIMARY KEY(user_id,basis_version,bucket_start),
 KEY ix_equity_minute_batch(basis_version,bucket_start,user_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS asset_history_1h (
 user_id BIGINT NOT NULL, basis_version VARCHAR(32) NOT NULL,
 bucket_start BIGINT NOT NULL, bucket_end BIGINT NOT NULL,
 open_value DECIMAL(32,16) NULL, high_value DECIMAL(32,16) NULL,
 low_value DECIMAL(32,16) NULL, close_value DECIMAL(32,16) NULL,
 open_at BIGINT NULL, high_at BIGINT NULL, low_at BIGINT NULL, close_at BIGINT NULL,
 source_count BIGINT NOT NULL, valid_sample_count BIGINT NOT NULL,
 invalid_sample_count BIGINT NOT NULL, expected_sample_count BIGINT NOT NULL,
 finalized BOOLEAN NOT NULL, quality VARCHAR(24) NOT NULL, source_through BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 PRIMARY KEY(user_id,basis_version,bucket_start),
 KEY ix_equity_1h_batch(basis_version,bucket_start,user_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS asset_history_4h (
 user_id BIGINT NOT NULL, basis_version VARCHAR(32) NOT NULL,
 bucket_start BIGINT NOT NULL, bucket_end BIGINT NOT NULL,
 open_value DECIMAL(32,16) NULL, high_value DECIMAL(32,16) NULL,
 low_value DECIMAL(32,16) NULL, close_value DECIMAL(32,16) NULL,
 open_at BIGINT NULL, high_at BIGINT NULL, low_at BIGINT NULL, close_at BIGINT NULL,
 source_count BIGINT NOT NULL, valid_sample_count BIGINT NOT NULL,
 invalid_sample_count BIGINT NOT NULL, expected_sample_count BIGINT NOT NULL,
 finalized BOOLEAN NOT NULL, quality VARCHAR(24) NOT NULL, source_through BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 PRIMARY KEY(user_id,basis_version,bucket_start),
 KEY ix_equity_4h_batch(basis_version,bucket_start,user_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS asset_history_1d (
 user_id BIGINT NOT NULL, basis_version VARCHAR(32) NOT NULL,
 bucket_start BIGINT NOT NULL, bucket_end BIGINT NOT NULL,
 open_value DECIMAL(32,16) NULL, high_value DECIMAL(32,16) NULL,
 low_value DECIMAL(32,16) NULL, close_value DECIMAL(32,16) NULL,
 open_at BIGINT NULL, high_at BIGINT NULL, low_at BIGINT NULL, close_at BIGINT NULL,
 source_count BIGINT NOT NULL, valid_sample_count BIGINT NOT NULL,
 invalid_sample_count BIGINT NOT NULL, expected_sample_count BIGINT NOT NULL,
 finalized BOOLEAN NOT NULL, quality VARCHAR(24) NOT NULL, source_through BIGINT NOT NULL, updated_at BIGINT NOT NULL,
 PRIMARY KEY(user_id,basis_version,bucket_start),
 KEY ix_equity_1d_batch(basis_version,bucket_start,user_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS asset_history_baseline (
 user_id BIGINT NOT NULL, basis_version VARCHAR(32) NOT NULL, capture_from BIGINT NOT NULL,
 first_positive DECIMAL(32,16) NULL, first_positive_at BIGINT NULL,
 PRIMARY KEY(user_id,basis_version)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS asset_history_job_state (
 task_name VARCHAR(32) NOT NULL, basis_version VARCHAR(32) NOT NULL,
 watermark BIGINT NOT NULL, user_cursor BIGINT NOT NULL, success_at BIGINT NULL, error VARCHAR(512) NULL,
 PRIMARY KEY(task_name,basis_version)
) ENGINE=InnoDB;
INSERT INTO asset_history_migration(migration_id,applied_at,basis_version)
VALUES('V001__net_equity_history',UNIX_TIMESTAMP()*1000,'net_equity_v1')
ON DUPLICATE KEY UPDATE migration_id=VALUES(migration_id);

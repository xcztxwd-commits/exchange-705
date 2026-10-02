-- Additive MySQL 5.7+/8 migration. No real balance or existing order is changed.
CREATE TABLE IF NOT EXISTS demo_account (
  user_id BIGINT NOT NULL PRIMARY KEY,
  cash DECIMAL(32,8) NOT NULL,
  generation INT NOT NULL DEFAULT 1,
  last_reset_at DATETIME(6) NULL,
  last_reset_key VARCHAR(255) NULL,
  version BIGINT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS demo_order (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  request_key VARCHAR(36) NOT NULL,
  generation INT NOT NULL,
  symbol VARCHAR(32) NOT NULL,
  market_code VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL,
  amount DECIMAL(32,8) NOT NULL,
  quantity DECIMAL(32,16) NOT NULL,
  open_price DECIMAL(32,16) NOT NULL,
  close_price DECIMAL(32,16) NULL,
  open_fee DECIMAL(32,8) NOT NULL,
  close_fee DECIMAL(32,8) NULL,
  realized_pnl DECIMAL(32,8) NULL,
  created_at DATETIME(6) NOT NULL,
  closed_at DATETIME(6) NULL,
  UNIQUE KEY uk_demo_order_request (user_id,request_key),
  KEY idx_demo_order_owner_status (user_id,status,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS demo_ledger (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  generation INT NOT NULL,
  type VARCHAR(16) NOT NULL,
  order_id VARCHAR(36) NULL,
  delta DECIMAL(32,8) NOT NULL,
  balance_after DECIMAL(32,8) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  KEY idx_demo_ledger_owner_time (user_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

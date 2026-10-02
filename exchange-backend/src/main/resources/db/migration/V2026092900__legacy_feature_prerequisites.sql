-- Versioned current-source prerequisites. No legacy financial values or evidence are rewritten.
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

-- Additive MySQL 5.7+ migration. Back up first. JPA ddl-auto=update creates the same schema in development.
CREATE TABLE IF NOT EXISTS activity_campaign (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, row_version BIGINT NOT NULL DEFAULT 0,
 name VARCHAR(120) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', template BIT NOT NULL DEFAULT 0,
 auto_popup BIT NOT NULL DEFAULT 1, animation VARCHAR(16) NOT NULL DEFAULT 'GIFT', default_locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
 translations LONGTEXT NOT NULL, amount DECIMAL(32,16) NOT NULL, recent_login_days INT NOT NULL DEFAULT 3,
 max_claims INT NOT NULL DEFAULT 1000, claim_count INT NOT NULL DEFAULT 0, budget DECIMAL(32,16) NOT NULL,
 granted DECIMAL(32,16) NOT NULL DEFAULT 0, starts_at DATETIME(6), ends_at DATETIME(6), created_at DATETIME(6), updated_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS activity_delivery (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, row_version BIGINT NOT NULL DEFAULT 0, campaign_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 sent_at DATETIME(6), received_at DATETIME(6), opened_at DATETIME(6), closed_at DATETIME(6), claimed_at DATETIME(6),
 open_count INT NOT NULL DEFAULT 0, close_count INT NOT NULL DEFAULT 0, sent_by VARCHAR(120),
 UNIQUE KEY uk_activity_recipient(campaign_id,user_id), KEY ix_activity_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS trial_account (
 user_id BIGINT NOT NULL PRIMARY KEY, row_version BIGINT NOT NULL DEFAULT 0,
 available DECIMAL(32,16) NOT NULL DEFAULT 0, frozen DECIMAL(32,16) NOT NULL DEFAULT 0,
 granted DECIMAL(32,16) NOT NULL DEFAULT 0, consumed DECIMAL(32,16) NOT NULL DEFAULT 0, profits DECIMAL(32,16) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS trial_ledger (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, reason VARCHAR(100) NOT NULL,
 available DECIMAL(32,16) NOT NULL, frozen DECIMAL(32,16) NOT NULL, delta DECIMAL(32,16) NOT NULL, created_at DATETIME(6),
 KEY ix_trial_ledger_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @activity_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='trial_reserved')=0,
 'ALTER TABLE contract_order ADD COLUMN trial_reserved DECIMAL(32,16) NULL','SELECT 1');
PREPARE activity_stmt FROM @activity_sql; EXECUTE activity_stmt; DEALLOCATE PREPARE activity_stmt;
SET @activity_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='option_order' AND column_name='trial_reserved')=0,
 'ALTER TABLE option_order ADD COLUMN trial_reserved DECIMAL(32,16) NULL','SELECT 1');
PREPARE activity_stmt FROM @activity_sql; EXECUTE activity_stmt; DEALLOCATE PREPARE activity_stmt;

CREATE TABLE IF NOT EXISTS admin_table_preference (
    id VARCHAR(200) NOT NULL PRIMARY KEY,
    columns_json LONGTEXT NOT NULL
);

-- Additive and restart-safe. All timestamps are epoch milliseconds; source data stays separate.
CREATE TABLE IF NOT EXISTS market_control_task (
 id VARCHAR(36) PRIMARY KEY, symbol_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL,
 algorithm_version INT NOT NULL, kind VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
 start_price DECIMAL(32,16) NOT NULL, target_price DECIMAL(32,16) NOT NULL,
 duration_seconds INT NOT NULL, intensity INT NOT NULL, oscillation BOOLEAN NOT NULL,
 price_precision INT NOT NULL, start_source VARCHAR(32) NOT NULL, source_time BIGINT NOT NULL,
 started_at BIGINT NOT NULL, planned_end BIGINT NOT NULL, ended_at BIGINT NULL,
 sampled_until BIGINT NOT NULL, request_key VARCHAR(64) NULL,
 UNIQUE KEY control_request (symbol_id, request_key), INDEX control_symbol (symbol_id, started_at)
);
-- Future V3 prices are private plan data, never market samples until their logical second arrives.
CREATE TABLE IF NOT EXISTS market_control_plan (
 task_id VARCHAR(36) PRIMARY KEY, seed BIGINT NOT NULL,
 parameters_json TEXT NOT NULL, prices_json MEDIUMTEXT NOT NULL,
 summary_json TEXT NOT NULL, checksum VARCHAR(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS market_control_sample (
 task_id VARCHAR(36) NOT NULL, generated_at BIGINT NOT NULL, price DECIMAL(32,16) NOT NULL,
 PRIMARY KEY (task_id, generated_at)
);
CREATE TABLE IF NOT EXISTS market_mixed_minute (
 symbol_id BIGINT NOT NULL, minute_at BIGINT NOT NULL, body TEXT NOT NULL,
 last_event BIGINT NOT NULL, PRIMARY KEY (symbol_id, minute_at)
);
CREATE TABLE IF NOT EXISTS market_source_candle (
 symbol_id BIGINT NOT NULL, period VARCHAR(8) NOT NULL, candle_at BIGINT NOT NULL,
 body TEXT NOT NULL, received_at BIGINT NOT NULL,
 PRIMARY KEY (symbol_id, period, candle_at)
);
CREATE TABLE IF NOT EXISTS market_source_quote (
 symbol_id BIGINT PRIMARY KEY, price DECIMAL(32,16) NOT NULL, source_time BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS market_source_tick (
 symbol_id BIGINT NOT NULL, source_time BIGINT NOT NULL, received_at BIGINT NOT NULL,
 price DECIMAL(32,16) NOT NULL, PRIMARY KEY (symbol_id, source_time)
);
-- Keep legacy ticks intact; stream events can have distinct prices at the same source time.
CREATE TABLE IF NOT EXISTS market_source_event (
 event_sequence BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_id VARCHAR(64) NOT NULL, symbol_id BIGINT NOT NULL,
 source_time BIGINT NOT NULL, received_at BIGINT NOT NULL, price DECIMAL(32,16) NOT NULL,
 UNIQUE KEY source_event_identity (symbol_id,event_id),
 KEY source_event_time (symbol_id,source_time,received_at)
);
-- An immediate return to a still-fresh cached quote is a display transition, not a new source quote.
CREATE TABLE IF NOT EXISTS market_control_resume (
 task_id VARCHAR(36) PRIMARY KEY, resumed_at BIGINT NOT NULL,
 source_time BIGINT NOT NULL, price DECIMAL(32,16) NOT NULL
);
-- Only newly created target tasks opt into holding; existing completed tasks stay completed.
CREATE TABLE IF NOT EXISTS market_control_hold (
 task_id VARCHAR(36) PRIMARY KEY, reference_price DECIMAL(32,16) NOT NULL,
 reference_time BIGINT NOT NULL, offset_price DECIMAL(32,16) NULL,
 activated_at BIGINT NULL, released_at BIGINT NULL,
 last_price DECIMAL(32,16) NOT NULL, generated_at BIGINT NOT NULL, source_time BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS market_control_publication (
 task_id VARCHAR(36) PRIMARY KEY, published_at BIGINT NOT NULL,
 from_at BIGINT NOT NULL, to_at BIGINT NOT NULL
);

-- Absence of a flow row means legacy semantics, including always-visible history.
CREATE TABLE IF NOT EXISTS market_control_flow (
 task_id VARCHAR(36) PRIMARY KEY, options_json TEXT NOT NULL,
 state VARCHAR(24) NOT NULL, recovery_started_at BIGINT NULL, remaining_millis BIGINT NULL,
 recovery_offset DECIMAL(32,16) NULL, last_price DECIMAL(32,16) NOT NULL,
 last_at BIGINT NOT NULL, finished_at BIGINT NULL
);

CREATE TABLE IF NOT EXISTS market_simulation_source_candle (
 symbol_id BIGINT NOT NULL, session_at BIGINT NOT NULL, period VARCHAR(8) NOT NULL,
 candle_at BIGINT NOT NULL, body TEXT NOT NULL,
 PRIMARY KEY (symbol_id, session_at, period, candle_at)
);

-- Preserve pre-upgrade mixed minute prefixes when modern tasks share their minute.
CREATE TABLE IF NOT EXISTS market_legacy_minute_snapshot (
 symbol_id BIGINT NOT NULL, minute_at BIGINT NOT NULL, body TEXT NOT NULL,
 last_event BIGINT NOT NULL, PRIMARY KEY(symbol_id,minute_at)
);

SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='order_no')=0,'ALTER TABLE deposit_record ADD COLUMN order_no VARCHAR(64) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='source')=0,'ALTER TABLE deposit_record ADD COLUMN source VARCHAR(32) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='account_type')=0,'ALTER TABLE deposit_record ADD COLUMN account_type VARCHAR(16) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='manual_purpose')=0,'ALTER TABLE deposit_record ADD COLUMN manual_purpose VARCHAR(24) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='idempotency_key')=0,'ALTER TABLE deposit_record ADD COLUMN idempotency_key VARCHAR(64) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='request_hash')=0,'ALTER TABLE deposit_record ADD COLUMN request_hash VARCHAR(64) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='review_remark')=0,'ALTER TABLE deposit_record ADD COLUMN review_remark VARCHAR(500) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='created_by_type')=0,'ALTER TABLE deposit_record ADD COLUMN created_by_type VARCHAR(24) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='created_by_id')=0,'ALTER TABLE deposit_record ADD COLUMN created_by_id BIGINT NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='created_by_name')=0,'ALTER TABLE deposit_record ADD COLUMN created_by_name VARCHAR(128) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='reviewed_by_type')=0,'ALTER TABLE deposit_record ADD COLUMN reviewed_by_type VARCHAR(24) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='reviewed_by_id')=0,'ALTER TABLE deposit_record ADD COLUMN reviewed_by_id BIGINT NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='reviewed_by_name')=0,'ALTER TABLE deposit_record ADD COLUMN reviewed_by_name VARCHAR(128) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='fee_rate')=0,'ALTER TABLE deposit_record ADD COLUMN fee_rate DECIMAL(32,16) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='fee_amount')=0,'ALTER TABLE deposit_record ADD COLUMN fee_amount DECIMAL(32,16) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='reviewed_at')=0,'ALTER TABLE deposit_record ADD COLUMN reviewed_at DATETIME(6) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='deposit_record' AND column_name='credited_at')=0,'ALTER TABLE deposit_record ADD COLUMN credited_at DATETIME(6) NULL','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
-- No historical audit, fees, rates, timestamps or balance snapshots are guessed.
CREATE TABLE IF NOT EXISTS deposit_credit_record (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 deposit_record_id BIGINT NOT NULL, user_id BIGINT NOT NULL, account_type VARCHAR(16) NOT NULL,
 amount_usd DECIMAL(32,16) NOT NULL, balance_before DECIMAL(32,16) NOT NULL, balance_after DECIMAL(32,16) NOT NULL,
 operator_type VARCHAR(24) NOT NULL, operator_id BIGINT NOT NULL, operator_name VARCHAR(128), credited_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_deposit_credit(deposit_record_id),
 CONSTRAINT fk_deposit_credit_order FOREIGN KEY(deposit_record_id) REFERENCES deposit_record(id) ON DELETE RESTRICT,
 CONSTRAINT fk_deposit_credit_user FOREIGN KEY(user_id) REFERENCES user_account(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='uk_deposit_order_no')=0,'ALTER TABLE deposit_record ADD UNIQUE INDEX uk_deposit_order_no(order_no)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='uk_deposit_request')=0,'ALTER TABLE deposit_record ADD UNIQUE INDEX uk_deposit_request(created_by_type,created_by_id,idempotency_key)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='ix_deposit_created')=0,'ALTER TABLE deposit_record ADD INDEX ix_deposit_created(created_at,id)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='ix_deposit_user')=0,'ALTER TABLE deposit_record ADD INDEX ix_deposit_user(user_id,created_at,id)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='ix_deposit_status')=0,'ALTER TABLE deposit_record ADD INDEX ix_deposit_status(status,created_at,id)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='ix_deposit_source')=0,'ALTER TABLE deposit_record ADD INDEX ix_deposit_source(source,status,credited_at)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='deposit_record' AND index_name='ix_deposit_reviewed')=0,'ALTER TABLE deposit_record ADD INDEX ix_deposit_reviewed(reviewed_at,id)','SELECT 1');
PREPARE deposit_stmt FROM @ddl; EXECUTE deposit_stmt; DEALLOCATE PREPARE deposit_stmt;

-- Missing feature tables from the current source, before tenant scoping.
CREATE TABLE IF NOT EXISTS support_conversation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, active_user_id BIGINT,
 admin_id BIGINT, status VARCHAR(16) NOT NULL, client_ip VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL, accepted_at DATETIME(6), closed_at DATETIME(6), updated_at DATETIME(6) NOT NULL,
 user_read_id BIGINT NOT NULL DEFAULT 0, admin_read_id BIGINT NOT NULL DEFAULT 0, last_hash VARCHAR(64),
 UNIQUE KEY uk_active_user(active_user_id), KEY support_queue(status,id),
 KEY support_owner(admin_id,status), KEY support_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS support_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT NOT NULL,
 sender VARCHAR(12) NOT NULL, sender_id BIGINT NOT NULL, sender_name VARCHAR(128) NOT NULL,
 request_id VARCHAR(64) NOT NULL, text VARCHAR(4000) NOT NULL, image BIT NOT NULL,
 image_hash VARCHAR(64) NOT NULL, created_at DATETIME(6) NOT NULL,
 previous_hash VARCHAR(64) NOT NULL, hash VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_support_request(conversation_id,sender,sender_id,request_id), KEY support_message_cursor(conversation_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS support_attachment(message_id BIGINT NOT NULL PRIMARY KEY, content LONGBLOB NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS support_presence(admin_id BIGINT NOT NULL PRIMARY KEY,accepting BIT NOT NULL,heartbeat_at DATETIME(6)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS inbox_letter (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, admin_id BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL, title VARCHAR(120) NOT NULL, content VARCHAR(4000) NOT NULL,
 created_at DATETIME(6) NOT NULL, read_at DATETIME(6),
 UNIQUE KEY uk_inbox_request(admin_id,request_id,user_id), KEY inbox_recipient(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS simulation_seed(user_id BIGINT NOT NULL PRIMARY KEY,amount_per_wallet DECIMAL(32,16) NOT NULL,created_at DATETIME(6) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS asset_history_revision(user_id BIGINT NOT NULL,basis_version VARCHAR(32) NOT NULL,level TINYINT NOT NULL,revision BIGINT NOT NULL,PRIMARY KEY(user_id,basis_version,level)) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS balance_adjustment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,request_hash VARCHAR(64) NOT NULL,
 actor_type VARCHAR(16),actor_id BIGINT,reason VARCHAR(500),changes TEXT,created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_balance_request(request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Nullable current-source additions. NULL preserves legacy order quantity and deletion semantics.
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='deleted_at')=0,'ALTER TABLE contract_order ADD COLUMN deleted_at datetime(6)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='deleted_by')=0,'ALTER TABLE contract_order ADD COLUMN deleted_by varchar(64)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='fx_base_currency')=0,'ALTER TABLE contract_order ADD COLUMN fx_base_currency varchar(3)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='min_order_notional')=0,'ALTER TABLE contract_order ADD COLUMN min_order_notional decimal(32,16)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='min_order_quantity')=0,'ALTER TABLE contract_order ADD COLUMN min_order_quantity decimal(32,16)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='quantity_asset')=0,'ALTER TABLE contract_order ADD COLUMN quantity_asset varchar(16)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='quantity_step')=0,'ALTER TABLE contract_order ADD COLUMN quantity_step decimal(32,16)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='quantity_unit_type')=0,'ALTER TABLE contract_order ADD COLUMN quantity_unit_type varchar(16)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='spec_version')=0,'ALTER TABLE contract_order ADD COLUMN spec_version bigint','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='option_order' AND column_name='deleted_at')=0,'ALTER TABLE option_order ADD COLUMN deleted_at datetime(6)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='option_order' AND column_name='deleted_by')=0,'ALTER TABLE option_order ADD COLUMN deleted_by varchar(64)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='trading_symbol' AND column_name='min_order_notional')=0,'ALTER TABLE trading_symbol ADD COLUMN min_order_notional decimal(32,16)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='trading_symbol' AND column_name='min_order_quantity')=0,'ALTER TABLE trading_symbol ADD COLUMN min_order_quantity decimal(32,16)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='trading_symbol' AND column_name='quantity_step')=0,'ALTER TABLE trading_symbol ADD COLUMN quantity_step decimal(32,16)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='trading_symbol' AND column_name='quantity_unit_type')=0,'ALTER TABLE trading_symbol ADD COLUMN quantity_unit_type varchar(16)','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;
SET @ddl=IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='trading_symbol' AND column_name='spec_version')=0,'ALTER TABLE trading_symbol ADD COLUMN spec_version bigint','SELECT 1');
PREPARE current_stmt FROM @ddl; EXECUTE current_stmt; DEALLOCATE PREPARE current_stmt;

-- Shared permission catalogue uses long module:action codes; expand only, never rewrite values.
SET @ddl=IF((SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='admin_menu' AND column_name='menu_code')<150,'ALTER TABLE admin_menu MODIFY menu_code VARCHAR(150) NOT NULL','SELECT 1');
PREPARE catalogue_stmt FROM @ddl; EXECUTE catalogue_stmt; DEALLOCATE PREPARE catalogue_stmt;

-- Align AssetAccount.coin with the existing 32-character entity limit; retain collation and values.
SET @ddl=(SELECT IF(CHARACTER_MAXIMUM_LENGTH<32,CONCAT('ALTER TABLE asset_account MODIFY coin VARCHAR(32) CHARACTER SET ',CHARACTER_SET_NAME,' COLLATE ',COLLATION_NAME,' NOT NULL'),'SELECT 1') FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='asset_account' AND column_name='coin');
PREPARE coin_stmt FROM @ddl; EXECUTE coin_stmt; DEALLOCATE PREPARE coin_stmt;

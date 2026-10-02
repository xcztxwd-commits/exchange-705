-- Unit fixture only: tenant keys mirror production; production FK behaviour is tested in real MySQL.
CREATE TABLE IF NOT EXISTS trading_symbol(id BIGINT PRIMARY KEY,tenant_id BIGINT NOT NULL);
CREATE TABLE IF NOT EXISTS market_control_task (
 tenant_id BIGINT NOT NULL,
 id VARCHAR(36) PRIMARY KEY, symbol_id BIGINT NOT NULL, symbol VARCHAR(32) NOT NULL,
 algorithm_version INT NOT NULL, kind VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL,
 start_price DECIMAL(32,16) NOT NULL, target_price DECIMAL(32,16) NOT NULL,
 duration_seconds INT NOT NULL, intensity INT NOT NULL, oscillation BOOLEAN NOT NULL,
 price_precision INT NOT NULL, start_source VARCHAR(32) NOT NULL, source_time BIGINT NOT NULL,
 started_at BIGINT NOT NULL, planned_end BIGINT NOT NULL, ended_at BIGINT NULL,
 sampled_until BIGINT NOT NULL, request_key VARCHAR(64) NULL,
 UNIQUE KEY control_request (tenant_id,symbol_id, request_key), INDEX control_symbol (symbol_id, started_at)
);
CREATE TABLE IF NOT EXISTS market_control_plan (
 tenant_id BIGINT NOT NULL,
 task_id VARCHAR(36) NOT NULL, PRIMARY KEY(tenant_id,task_id), seed BIGINT NOT NULL,
 parameters_json TEXT NOT NULL, prices_json MEDIUMTEXT NOT NULL,
 summary_json TEXT NOT NULL, checksum VARCHAR(64) NOT NULL
);
CREATE TABLE IF NOT EXISTS market_control_sample (
 tenant_id BIGINT NOT NULL,
 task_id VARCHAR(36) NOT NULL, generated_at BIGINT NOT NULL, price DECIMAL(32,16) NOT NULL,
 PRIMARY KEY (tenant_id,task_id, generated_at)
);
CREATE TABLE IF NOT EXISTS market_mixed_minute (
 tenant_id BIGINT NOT NULL,
 symbol_id BIGINT NOT NULL, minute_at BIGINT NOT NULL, body TEXT NOT NULL,
 last_event BIGINT NOT NULL, PRIMARY KEY (tenant_id,symbol_id, minute_at)
);
CREATE TABLE IF NOT EXISTS market_source_candle (
 tenant_id BIGINT NOT NULL,
 symbol_id BIGINT NOT NULL, period VARCHAR(8) NOT NULL, candle_at BIGINT NOT NULL,
 body TEXT NOT NULL, received_at BIGINT NOT NULL,
 PRIMARY KEY (tenant_id,symbol_id, period, candle_at)
);
CREATE TABLE IF NOT EXISTS market_source_quote (
 tenant_id BIGINT NOT NULL,
 symbol_id BIGINT NOT NULL, PRIMARY KEY(tenant_id,symbol_id), price DECIMAL(32,16) NOT NULL, source_time BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS market_source_tick (
 tenant_id BIGINT NOT NULL,
 symbol_id BIGINT NOT NULL, source_time BIGINT NOT NULL, received_at BIGINT NOT NULL,
 price DECIMAL(32,16) NOT NULL, PRIMARY KEY (tenant_id,symbol_id, source_time)
);
CREATE TABLE IF NOT EXISTS market_source_event (
 tenant_id BIGINT NOT NULL,
 event_sequence BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 event_id VARCHAR(64) NOT NULL, symbol_id BIGINT NOT NULL,
 source_time BIGINT NOT NULL, received_at BIGINT NOT NULL, price DECIMAL(32,16) NOT NULL,
 UNIQUE KEY source_event_identity (tenant_id,symbol_id,event_id),
 KEY source_event_time (symbol_id,source_time,received_at)
);
CREATE TABLE IF NOT EXISTS market_control_resume (
 tenant_id BIGINT NOT NULL,
 task_id VARCHAR(36) NOT NULL, PRIMARY KEY(tenant_id,task_id), resumed_at BIGINT NOT NULL,
 source_time BIGINT NOT NULL, price DECIMAL(32,16) NOT NULL
);
CREATE TABLE IF NOT EXISTS market_control_hold (
 tenant_id BIGINT NOT NULL,
 task_id VARCHAR(36) NOT NULL, PRIMARY KEY(tenant_id,task_id), reference_price DECIMAL(32,16) NOT NULL,
 reference_time BIGINT NOT NULL, offset_price DECIMAL(32,16) NULL,
 activated_at BIGINT NULL, released_at BIGINT NULL,
 last_price DECIMAL(32,16) NOT NULL, generated_at BIGINT NOT NULL, source_time BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS market_control_publication (
 tenant_id BIGINT NOT NULL,
 task_id VARCHAR(36) NOT NULL, PRIMARY KEY(tenant_id,task_id), published_at BIGINT NOT NULL,
 from_at BIGINT NOT NULL, to_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS market_control_flow (
 tenant_id BIGINT NOT NULL,
 task_id VARCHAR(36) NOT NULL, PRIMARY KEY(tenant_id,task_id), options_json TEXT NOT NULL,
 state VARCHAR(24) NOT NULL, recovery_started_at BIGINT NULL, remaining_millis BIGINT NULL,
 recovery_offset DECIMAL(32,16) NULL, last_price DECIMAL(32,16) NOT NULL,
 last_at BIGINT NOT NULL, finished_at BIGINT NULL
);
CREATE TABLE IF NOT EXISTS market_simulation_source_candle (
 tenant_id BIGINT NOT NULL,
 symbol_id BIGINT NOT NULL, session_at BIGINT NOT NULL, period VARCHAR(8) NOT NULL,
 candle_at BIGINT NOT NULL, body TEXT NOT NULL,
 PRIMARY KEY (tenant_id,symbol_id, session_at, period, candle_at)
);
CREATE TABLE IF NOT EXISTS market_legacy_minute_snapshot (
 tenant_id BIGINT NOT NULL,
 symbol_id BIGINT NOT NULL, minute_at BIGINT NOT NULL, body TEXT NOT NULL,
 last_event BIGINT NOT NULL, PRIMARY KEY(tenant_id,symbol_id,minute_at)
);

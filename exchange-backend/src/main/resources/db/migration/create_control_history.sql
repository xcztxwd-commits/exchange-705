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

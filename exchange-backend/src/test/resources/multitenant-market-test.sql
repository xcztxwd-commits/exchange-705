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
 stop_at BIGINT NULL, sampled_until BIGINT NOT NULL, request_key VARCHAR(64) NULL,
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
 last_at BIGINT NOT NULL, finished_at BIGINT NULL,
 history_pending_until BIGINT NULL,history_retry_at BIGINT NULL,history_error VARCHAR(64) NULL
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

CREATE TABLE IF NOT EXISTS market_engine_runtime (
 tenant_id BIGINT NOT NULL,symbol_id BIGINT NOT NULL,
 writer_generation BIGINT NOT NULL DEFAULT 0,owner_id VARCHAR(36),lease_until BIGINT NOT NULL DEFAULT 0,
 control_revision BIGINT NOT NULL DEFAULT 0,snapshot_version BIGINT NOT NULL DEFAULT 0,
 source_input_revision BIGINT NOT NULL DEFAULT 0,source_dirty_from BIGINT,source_dirty_to BIGINT,
 quote_json MEDIUMTEXT,status_json MEDIUMTEXT,committed_at BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,symbol_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS market_engine_tenant(tenant_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS market_control_command(
 tenant_id BIGINT NOT NULL,id VARCHAR(36) NOT NULL,symbol_id BIGINT NOT NULL,request_key VARCHAR(64) NOT NULL,
 parameter_hash VARCHAR(64) NOT NULL,parameters_json TEXT NOT NULL,state VARCHAR(16) NOT NULL,
 actor_id BIGINT NOT NULL,session_id VARCHAR(64),config_revision BIGINT NOT NULL,control_revision BIGINT NOT NULL,
 writer_generation BIGINT,owner_id VARCHAR(36),seed BIGINT NOT NULL,accepted_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,
 prepared_json MEDIUMTEXT,task_id VARCHAR(36),error_code VARCHAR(64),message VARCHAR(255),
 retry_count INT NOT NULL DEFAULT 0,retry_at BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,id),UNIQUE KEY command_request(tenant_id,symbol_id,request_key),KEY command_queue(tenant_id,state,accepted_at)
) ENGINE=InnoDB;

-- H2 contract fixture only. Formal MySQL migration supplies fences, immutability and tenant FKs.
CREATE TABLE IF NOT EXISTS market_history_ordering (
 tenant_id BIGINT NOT NULL,symbol_id BIGINT NOT NULL,ordering_version INT NOT NULL,
 from_minute BIGINT NOT NULL,source_sequence BIGINT NOT NULL,scope_sha256 CHAR(64) NOT NULL,
 evidence_sha256 CHAR(64) NOT NULL,responses_json MEDIUMTEXT NOT NULL,sealed_at BIGINT NOT NULL,writer_generation BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,symbol_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS market_history_response (
 tenant_id BIGINT NOT NULL,symbol_id BIGINT NOT NULL,request_sha256 CHAR(64) NOT NULL,
 request_json TEXT NOT NULL,response_json MEDIUMTEXT NOT NULL,response_sha256 CHAR(64) NOT NULL,
 artifact_sha256 CHAR(64) NOT NULL,artifact_pointer VARCHAR(255) NOT NULL,scope_sha256 CHAR(64) NOT NULL,
 sealed_at BIGINT NOT NULL,writer_generation BIGINT NOT NULL,PRIMARY KEY(tenant_id,symbol_id,request_sha256)
) ENGINE=InnoDB;

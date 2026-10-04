-- S4-only disposable fixture. Production names/migration number await the S2 integration contract.
CREATE TABLE IF NOT EXISTS s4_history_projection_progress (
 tenant_id BIGINT NOT NULL,
 symbol_id BIGINT NOT NULL,
 generation BIGINT NOT NULL,
 fact_version BIGINT NOT NULL,
 input_revision BIGINT NOT NULL DEFAULT 0,
 initial_watermark BIGINT NOT NULL,
 watermark BIGINT NOT NULL,
 stop_at BIGINT NOT NULL,
 last_hash VARCHAR(64),
 PRIMARY KEY (tenant_id,symbol_id)
);
CREATE TABLE IF NOT EXISTS s4_history_projection_minute (
 tenant_id BIGINT NOT NULL,
 symbol_id BIGINT NOT NULL,
 minute_at BIGINT NOT NULL,
 generation BIGINT NOT NULL,
 fact_version BIGINT NOT NULL,
 body TEXT NOT NULL,
 received_cutoff BIGINT NOT NULL,
 protected_mixed BOOLEAN NOT NULL,
 PRIMARY KEY (tenant_id,symbol_id,minute_at)
);

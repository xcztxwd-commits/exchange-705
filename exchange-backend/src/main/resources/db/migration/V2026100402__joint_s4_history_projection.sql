-- Derived private history only; no quote/funding authority, leases or market generations are allocated here.
CREATE TABLE s4_history_projection_progress (
 tenant_id BIGINT NOT NULL, symbol_id BIGINT NOT NULL, generation BIGINT NOT NULL,
 fact_version BIGINT NOT NULL, initial_watermark BIGINT NOT NULL, watermark BIGINT NOT NULL,
 stop_at BIGINT NOT NULL, last_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 PRIMARY KEY(tenant_id,symbol_id),
 CONSTRAINT mt_t_s4_progress FOREIGN KEY(tenant_id) REFERENCES tenant(id),
 CONSTRAINT mt_s_s4_progress FOREIGN KEY(tenant_id,symbol_id) REFERENCES trading_symbol(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE s4_history_projection_minute (
 tenant_id BIGINT NOT NULL, symbol_id BIGINT NOT NULL, minute_at BIGINT NOT NULL,
 generation BIGINT NOT NULL, fact_version BIGINT NOT NULL, body LONGTEXT NOT NULL,
 received_cutoff BIGINT NOT NULL, protected_mixed BIT(1) NOT NULL,
 PRIMARY KEY(tenant_id,symbol_id,minute_at),
 CONSTRAINT mt_t_s4_minute FOREIGN KEY(tenant_id) REFERENCES tenant(id),
 CONSTRAINT mt_s_s4_minute FOREIGN KEY(tenant_id,symbol_id) REFERENCES trading_symbol(tenant_id,id),
 CONSTRAINT mt_p_s4_minute FOREIGN KEY(tenant_id,symbol_id) REFERENCES s4_history_projection_progress(tenant_id,symbol_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DELIMITER $$
CREATE PROCEDURE joint_s4_fence(IN tenant BIGINT,IN symbol BIGINT,IN generation BIGINT)
BEGIN
 IF @mt705_s4_tenant IS NULL OR @mt705_s4_symbol IS NULL OR @mt705_s4_generation IS NULL OR @mt705_s4_revision IS NULL
 OR @mt705_s4_tenant<>tenant OR @mt705_s4_symbol<>symbol OR @mt705_s4_generation<>generation
 OR NOT EXISTS(SELECT 1 FROM market_engine_runtime WHERE tenant_id=tenant AND symbol_id=symbol
   AND writer_generation=generation AND control_revision=@mt705_s4_revision)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Projection runtime authority fenced'; END IF;
END$$
CREATE TRIGGER joint_s4_progress_insert BEFORE INSERT ON s4_history_projection_progress FOR EACH ROW
BEGIN CALL joint_s4_fence(NEW.tenant_id,NEW.symbol_id,NEW.generation); END$$
CREATE TRIGGER joint_s4_progress_update BEFORE UPDATE ON s4_history_projection_progress FOR EACH ROW
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Projection identity is immutable'; END IF;
 CALL joint_s4_fence(NEW.tenant_id,NEW.symbol_id,NEW.generation);
END$$
CREATE TRIGGER joint_s4_minute_insert BEFORE INSERT ON s4_history_projection_minute FOR EACH ROW
BEGIN CALL joint_s4_fence(NEW.tenant_id,NEW.symbol_id,NEW.generation); END$$
CREATE TRIGGER joint_s4_minute_update BEFORE UPDATE ON s4_history_projection_minute FOR EACH ROW
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id OR NEW.minute_at<>OLD.minute_at THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Projection minute identity is immutable'; END IF;
 CALL joint_s4_fence(NEW.tenant_id,NEW.symbol_id,NEW.generation);
END$$
CREATE TRIGGER joint_s4_progress_delete BEFORE DELETE ON s4_history_projection_progress FOR EACH ROW
BEGIN CALL joint_s4_fence(OLD.tenant_id,OLD.symbol_id,OLD.generation); END$$
CREATE TRIGGER joint_s4_minute_delete BEFORE DELETE ON s4_history_projection_minute FOR EACH ROW
BEGIN CALL joint_s4_fence(OLD.tenant_id,OLD.symbol_id,OLD.generation); END$$
DELIMITER ;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026100402,UTC_TIMESTAMP(6),2026100402,0);

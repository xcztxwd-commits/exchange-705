-- Additive display ledger; no control evidence or source rows are replaced.
ALTER TABLE market_engine_runtime ADD COLUMN history_restore_revision BIGINT NOT NULL DEFAULT 0;
CREATE TABLE market_history_restore_job (
 tenant_id BIGINT NOT NULL,id VARCHAR(36) NOT NULL,symbol_id BIGINT NOT NULL,
 kind VARCHAR(16) NOT NULL,state VARCHAR(16) NOT NULL,request_key VARCHAR(64),
 source_identity VARCHAR(192) NOT NULL,from_at BIGINT NOT NULL,to_at BIGINT NOT NULL,timezone VARCHAR(64) NOT NULL,
 total INT NOT NULL,completed INT NOT NULL DEFAULT 0,actor_id BIGINT NOT NULL,session_id VARCHAR(64),
 created_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,updated_at BIGINT,undo_of VARCHAR(36),error_message VARCHAR(255),
 PRIMARY KEY(tenant_id,id),UNIQUE KEY restore_request(tenant_id,symbol_id,request_key),
 UNIQUE KEY restore_job_symbol(tenant_id,id,symbol_id),KEY restore_queue(tenant_id,state,created_at),
 CONSTRAINT restore_job_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id),
 CONSTRAINT restore_job_symbol FOREIGN KEY(tenant_id,symbol_id) REFERENCES trading_symbol(tenant_id,id),
 CONSTRAINT restore_job_undo FOREIGN KEY(tenant_id,undo_of) REFERENCES market_history_restore_job(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE market_history_restore_minute (
 tenant_id BIGINT NOT NULL,job_id VARCHAR(36) NOT NULL,symbol_id BIGINT NOT NULL,minute_at BIGINT NOT NULL,
 before_json MEDIUMTEXT NOT NULL,source_json MEDIUMTEXT,previous_json MEDIUMTEXT,checksum CHAR(64) NOT NULL,
 effective_version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,job_id,minute_at),KEY restore_visible(tenant_id,symbol_id,minute_at,effective_version),
 CONSTRAINT restore_minute_job FOREIGN KEY(tenant_id,job_id,symbol_id) REFERENCES market_history_restore_job(tenant_id,id,symbol_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
DELIMITER $$
CREATE TRIGGER history_restore_minute_i BEFORE INSERT ON market_history_restore_minute FOR EACH ROW
BEGIN
 IF NEW.effective_version<>0 OR MOD(NEW.minute_at,60000)<>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RESTORE_INVALID_PREVIEW'; END IF;
END$$
CREATE TRIGGER history_restore_minute_u BEFORE UPDATE ON market_history_restore_minute FOR EACH ROW
BEGIN
 DECLARE allowed INT DEFAULT 0;
 IF OLD.effective_version<>0 OR NEW.effective_version<=0 OR NOT (OLD.tenant_id<=>NEW.tenant_id) OR NOT (OLD.job_id<=>NEW.job_id) OR NOT (OLD.symbol_id<=>NEW.symbol_id) OR NOT (OLD.minute_at<=>NEW.minute_at) OR NOT (OLD.before_json<=>NEW.before_json) OR NOT (OLD.source_json<=>NEW.source_json) OR NOT (OLD.previous_json<=>NEW.previous_json) OR NOT (OLD.checksum<=>NEW.checksum) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RESTORE_SNAPSHOT_IMMUTABLE'; END IF;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=NEW.symbol_id AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED)
 AND r.history_restore_revision=NEW.effective_version;
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER history_restore_minute_d BEFORE DELETE ON market_history_restore_minute FOR EACH ROW
BEGIN
 IF OLD.effective_version>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='RESTORE_SNAPSHOT_IMMUTABLE'; END IF;
END$$
DELIMITER ;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026100703,UTC_TIMESTAMP(6),2026100603,0);

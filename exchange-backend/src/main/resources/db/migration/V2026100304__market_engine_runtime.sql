CREATE TABLE market_engine_tenant(tenant_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE market_control_command(
 tenant_id BIGINT NOT NULL,id VARCHAR(36) NOT NULL,symbol_id BIGINT NOT NULL,request_key VARCHAR(64) NOT NULL,
 parameter_hash VARCHAR(64) NOT NULL,parameters_json TEXT NOT NULL,state VARCHAR(16) NOT NULL,
 actor_id BIGINT NOT NULL,session_id VARCHAR(64),config_revision BIGINT NOT NULL,control_revision BIGINT NOT NULL,
 writer_generation BIGINT,owner_id VARCHAR(36),seed BIGINT NOT NULL,accepted_at BIGINT NOT NULL,expires_at BIGINT NOT NULL,
 prepared_json MEDIUMTEXT,task_id VARCHAR(36),error_code VARCHAR(64),message VARCHAR(255),
 PRIMARY KEY(tenant_id,id),UNIQUE KEY command_request(tenant_id,symbol_id,request_key),KEY command_queue(tenant_id,state,accepted_at)
) ENGINE=InnoDB;
CREATE TABLE market_engine_runtime (
 tenant_id BIGINT NOT NULL,symbol_id BIGINT NOT NULL,
 writer_generation BIGINT NOT NULL DEFAULT 0,owner_id VARCHAR(36),lease_until BIGINT NOT NULL DEFAULT 0,
 control_revision BIGINT NOT NULL DEFAULT 0,snapshot_version BIGINT NOT NULL DEFAULT 0,
 quote_json MEDIUMTEXT,status_json MEDIUMTEXT,committed_at BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(tenant_id,symbol_id)
) ENGINE=InnoDB;
ALTER TABLE market_control_task ADD COLUMN stop_at BIGINT NULL;

DELIMITER $$
CREATE TRIGGER s2_control_task_i BEFORE INSERT ON market_control_task FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_task_u BEFORE UPDATE ON market_control_task FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_task_d BEFORE DELETE ON market_control_task FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_quote_i BEFORE INSERT ON market_source_quote FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_quote_u BEFORE UPDATE ON market_source_quote FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_quote_d BEFORE DELETE ON market_source_quote FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_tick_i BEFORE INSERT ON market_source_tick FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_tick_u BEFORE UPDATE ON market_source_tick FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_tick_d BEFORE DELETE ON market_source_tick FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_event_i BEFORE INSERT ON market_source_event FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_event_u BEFORE UPDATE ON market_source_event FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_event_d BEFORE DELETE ON market_source_event FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_candle_i BEFORE INSERT ON market_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_candle_u BEFORE UPDATE ON market_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_source_candle_d BEFORE DELETE ON market_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_mixed_minute_i BEFORE INSERT ON market_mixed_minute FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_mixed_minute_u BEFORE UPDATE ON market_mixed_minute FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_mixed_minute_d BEFORE DELETE ON market_mixed_minute FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_simulation_source_candle_i BEFORE INSERT ON market_simulation_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_simulation_source_candle_u BEFORE UPDATE ON market_simulation_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_simulation_source_candle_d BEFORE DELETE ON market_simulation_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_legacy_minute_snapshot_i BEFORE INSERT ON market_legacy_minute_snapshot FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_legacy_minute_snapshot_u BEFORE UPDATE ON market_legacy_minute_snapshot FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_legacy_minute_snapshot_d BEFORE DELETE ON market_legacy_minute_snapshot FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_plan_i BEFORE INSERT ON market_control_plan FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_plan_u BEFORE UPDATE ON market_control_plan FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_plan_d BEFORE DELETE ON market_control_plan FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_sample_i BEFORE INSERT ON market_control_sample FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_sample_u BEFORE UPDATE ON market_control_sample FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_sample_d BEFORE DELETE ON market_control_sample FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_resume_i BEFORE INSERT ON market_control_resume FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_resume_u BEFORE UPDATE ON market_control_resume FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_resume_d BEFORE DELETE ON market_control_resume FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_hold_i BEFORE INSERT ON market_control_hold FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_hold_u BEFORE UPDATE ON market_control_hold FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_hold_d BEFORE DELETE ON market_control_hold FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_flow_i BEFORE INSERT ON market_control_flow FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_flow_u BEFORE UPDATE ON market_control_flow FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_flow_d BEFORE DELETE ON market_control_flow FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_publication_i BEFORE INSERT ON market_control_publication FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_publication_u BEFORE UPDATE ON market_control_publication FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
CREATE TRIGGER s2_control_publication_d BEFORE DELETE ON market_control_publication FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END$$
DELIMITER ;

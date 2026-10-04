-- Additive only: exact archived response receipts and a future-only per-symbol cutover.
-- No historical row, price, publication, sequence or production activation is changed.
CREATE TABLE market_history_ordering (
 tenant_id BIGINT NOT NULL, symbol_id BIGINT NOT NULL, ordering_version INT NOT NULL,
 from_minute BIGINT NOT NULL, source_sequence BIGINT NOT NULL, responses_json MEDIUMTEXT NOT NULL,
 scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 evidence_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sealed_at BIGINT NOT NULL, writer_generation BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,symbol_id),
 CONSTRAINT mt_t_history_ordering FOREIGN KEY(tenant_id) REFERENCES tenant(id),
 CONSTRAINT mt_s_history_ordering FOREIGN KEY(tenant_id,symbol_id) REFERENCES trading_symbol(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE market_history_response (
 tenant_id BIGINT NOT NULL, symbol_id BIGINT NOT NULL,
 request_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_json TEXT NOT NULL, response_json MEDIUMTEXT NOT NULL,
 response_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 artifact_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 artifact_pointer VARCHAR(255) NOT NULL,
 scope_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 sealed_at BIGINT NOT NULL, writer_generation BIGINT NOT NULL,
 PRIMARY KEY(tenant_id,symbol_id,request_sha256),
 CONSTRAINT mt_t_history_response FOREIGN KEY(tenant_id) REFERENCES tenant(id),
 CONSTRAINT mt_s_history_response FOREIGN KEY(tenant_id,symbol_id) REFERENCES trading_symbol(tenant_id,id),
 CONSTRAINT mt_p_history_response FOREIGN KEY(tenant_id,symbol_id) REFERENCES market_history_ordering(tenant_id,symbol_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
DELIMITER $$
CREATE TRIGGER joint_history_ordering_i BEFORE INSERT ON market_history_ordering FOR EACH ROW
BEGIN
 DECLARE allowed INT DEFAULT 0;
 DECLARE last_sequence BIGINT DEFAULT 0;
 DECLARE last_event BIGINT DEFAULT 0;
 DECLARE last_tick BIGINT DEFAULT 0;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
  WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=NEW.symbol_id AND r.owner_id=@mt705_s2_owner
  AND r.writer_generation=NEW.writer_generation
  AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED)
  AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED) FOR UPDATE;
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History cutover writer fenced'; END IF;
 SELECT COALESCE(MAX(event_sequence),0),COALESCE(MAX(received_at),0) INTO last_sequence,last_event
  FROM market_source_event WHERE tenant_id=NEW.tenant_id AND symbol_id=NEW.symbol_id FOR UPDATE;
 SELECT COALESCE(MAX(received_at),0) INTO last_tick FROM market_source_tick
  WHERE tenant_id=NEW.tenant_id AND symbol_id=NEW.symbol_id FOR UPDATE;
 IF JSON_VALID(NEW.responses_json)<>1 OR JSON_TYPE(NEW.responses_json)<>'OBJECT' OR JSON_LENGTH(NEW.responses_json)>1000
  OR NEW.ordering_version<>2 OR NEW.from_minute<=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
  OR MOD(NEW.from_minute,60000)<>0 OR NEW.source_sequence<>last_sequence OR NEW.source_sequence<0
  OR last_event>=NEW.from_minute OR last_tick>=NEW.from_minute
  OR NEW.sealed_at<=0 OR NEW.sealed_at>=NEW.from_minute
  OR NEW.scope_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
  OR NEW.evidence_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History cutover must be exact and future-only'; END IF;
END$$
CREATE TRIGGER joint_history_ordering_u BEFORE UPDATE ON market_history_ordering FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History cutover is immutable'; END$$
CREATE TRIGGER joint_history_ordering_d BEFORE DELETE ON market_history_ordering FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History cutover cannot be deleted'; END$$
CREATE TRIGGER joint_history_response_i BEFORE INSERT ON market_history_response FOR EACH ROW
BEGIN
 DECLARE allowed INT DEFAULT 0;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r JOIN market_history_ordering p
  ON p.tenant_id=r.tenant_id AND p.symbol_id=r.symbol_id
  WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=NEW.symbol_id AND r.owner_id=@mt705_s2_owner
  AND r.writer_generation=NEW.writer_generation AND p.writer_generation=NEW.writer_generation
  AND p.scope_sha256=NEW.scope_sha256 AND p.sealed_at=NEW.sealed_at
  AND JSON_UNQUOTE(JSON_EXTRACT(p.responses_json,CONCAT('$."',NEW.request_sha256,'"')))=
   SHA2(CONCAT(NEW.request_sha256,':',NEW.response_sha256,':',NEW.artifact_sha256,':',NEW.artifact_pointer),256)
  AND p.from_minute>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
  AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED)
  AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED) FOR UPDATE;
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History response writer or scope fenced'; END IF;
 IF NEW.request_sha256<>SHA2(NEW.request_json,256) OR NEW.response_sha256<>SHA2(NEW.response_json,256)
  OR NEW.artifact_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
  OR LEFT(NEW.artifact_pointer,1)<>'/' OR JSON_VALID(NEW.request_json)<>1 OR JSON_VALID(NEW.response_json)<>1
  OR NOT(JSON_TYPE(JSON_EXTRACT(NEW.request_json,'$.tenant'))<=>'INTEGER')
  OR NOT(JSON_TYPE(JSON_EXTRACT(NEW.request_json,'$.symbol'))<=>'INTEGER')
  OR NOT(CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.request_json,'$.tenant')) AS UNSIGNED)<=>NEW.tenant_id)
  OR NOT(CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.request_json,'$.symbol')) AS UNSIGNED)<=>NEW.symbol_id)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History response bytes or identity invalid'; END IF;
END$$
CREATE TRIGGER joint_history_response_u BEFORE UPDATE ON market_history_response FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History response is immutable'; END$$
CREATE TRIGGER joint_history_response_d BEFORE DELETE ON market_history_response FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History response cannot be deleted'; END$$
CREATE TRIGGER joint_history_event_i AFTER INSERT ON market_source_event FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=NEW.tenant_id AND p.symbol_id=NEW.symbol_id
  AND NEW.received_at>=p.from_minute AND NEW.event_sequence<=p.source_sequence FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Future source sequence precedes sealed cutover'; END IF;
END$$
CREATE TRIGGER joint_history_event_u BEFORE UPDATE ON market_source_event FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=OLD.tenant_id AND p.symbol_id=OLD.symbol_id FOR UPDATE)
  AND (NEW.tenant_id<>OLD.tenant_id OR NEW.event_sequence<>OLD.event_sequence OR BINARY NEW.event_id<>BINARY OLD.event_id OR NEW.symbol_id<>OLD.symbol_id
   OR NEW.source_time<>OLD.source_time OR NEW.received_at<>OLD.received_at OR NEW.price<>OLD.price)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed source facts are immutable'; END IF;
END$$
CREATE TRIGGER joint_history_event_d BEFORE DELETE ON market_source_event FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=OLD.tenant_id AND p.symbol_id=OLD.symbol_id FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed source facts cannot be deleted'; END IF;
END$$
CREATE TRIGGER joint_history_tick_i BEFORE INSERT ON market_source_tick FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=NEW.tenant_id AND p.symbol_id=NEW.symbol_id AND NEW.received_at>=p.from_minute FOR UPDATE)
  AND NOT EXISTS(SELECT 1 FROM market_source_event e JOIN market_history_ordering p ON p.tenant_id=e.tenant_id AND p.symbol_id=e.symbol_id
   WHERE e.tenant_id=NEW.tenant_id AND e.symbol_id=NEW.symbol_id AND e.source_time=NEW.source_time
    AND e.received_at=NEW.received_at AND e.price=NEW.price AND e.event_sequence>p.source_sequence FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Future tick requires exact sequenced source event'; END IF;
END$$
CREATE TRIGGER joint_history_tick_u BEFORE UPDATE ON market_source_tick FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=OLD.tenant_id AND p.symbol_id=OLD.symbol_id FOR UPDATE)
  AND (NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id OR NEW.source_time<>OLD.source_time OR NEW.received_at<>OLD.received_at OR NEW.price<>OLD.price)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed legacy tick is immutable'; END IF;
END$$
CREATE TRIGGER joint_history_tick_d BEFORE DELETE ON market_source_tick FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=OLD.tenant_id AND p.symbol_id=OLD.symbol_id FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed legacy tick cannot be deleted'; END IF;
END$$
CREATE TRIGGER joint_history_publication_i BEFORE INSERT ON market_control_publication FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_control_task t JOIN market_history_ordering p
  ON p.tenant_id=t.tenant_id AND p.symbol_id=t.symbol_id
  WHERE t.tenant_id=NEW.tenant_id AND t.id=NEW.task_id
  AND (t.started_at<p.from_minute OR NEW.from_at<p.from_minute) FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='New publication cannot expose sealed old history'; END IF;
END$$
CREATE TRIGGER joint_history_publication_u BEFORE UPDATE ON market_control_publication FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_control_task t JOIN market_history_ordering p
  ON p.tenant_id=t.tenant_id AND p.symbol_id=t.symbol_id
  WHERE t.tenant_id=OLD.tenant_id AND t.id=OLD.task_id
  AND (t.started_at<p.from_minute OR OLD.from_at<p.from_minute OR NEW.from_at<p.from_minute) FOR UPDATE)
  AND (NEW.tenant_id<>OLD.tenant_id OR BINARY NEW.task_id<>BINARY OLD.task_id
   OR NEW.from_at<>OLD.from_at OR NEW.to_at<>OLD.to_at OR NEW.published_at<>OLD.published_at)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed old publication is immutable'; END IF;
END$$
CREATE TRIGGER joint_history_publication_d BEFORE DELETE ON market_control_publication FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_control_task t JOIN market_history_ordering p
  ON p.tenant_id=t.tenant_id AND p.symbol_id=t.symbol_id
  WHERE t.tenant_id=OLD.tenant_id AND t.id=OLD.task_id
  AND (t.started_at<p.from_minute OR OLD.from_at<p.from_minute) FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed old publication cannot be deleted'; END IF;
END$$
DELIMITER ;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026100404,UTC_TIMESTAMP(6),2026100404,0);

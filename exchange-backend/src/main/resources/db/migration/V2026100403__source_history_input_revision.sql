-- Forward-only 0403 SOURCE input receipts. Apply only under the owned migration ledger.
-- Derived SOURCE revision bookkeeping. No historical body/order/backfill or v2 activation.
ALTER TABLE market_engine_runtime
 ADD COLUMN source_input_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN source_dirty_from BIGINT NULL,
 ADD COLUMN source_dirty_to BIGINT NULL;
ALTER TABLE s4_history_projection_progress ADD COLUMN input_revision BIGINT NOT NULL DEFAULT 0;
DELIMITER $$
CREATE TRIGGER joint_source_dirty_insert BEFORE INSERT ON market_engine_runtime FOR EACH ROW
BEGIN
 IF NEW.source_input_revision<>0 OR NEW.source_dirty_from IS NOT NULL OR NEW.source_dirty_to IS NOT NULL THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source revision must start at zero';
 END IF;
END$$
CREATE TRIGGER joint_source_dirty_update BEFORE UPDATE ON market_engine_runtime FOR EACH ROW
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source revision identity is immutable';
 END IF;
 IF NEW.source_input_revision<>OLD.source_input_revision THEN
  IF NEW.source_input_revision<>OLD.source_input_revision+1
   OR @mt705_s2_owner IS NULL OR @mt705_s2_fences IS NULL
   OR NOT(OLD.owner_id<=>@mt705_s2_owner)
   OR NOT(NEW.owner_id<=>OLD.owner_id) OR NEW.writer_generation<>OLD.writer_generation OR NEW.control_revision<>OLD.control_revision
   OR OLD.lease_until<=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
   OR OLD.writer_generation<>COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',OLD.tenant_id,':',OLD.symbol_id,'"'))) AS UNSIGNED),0)
   OR NEW.source_dirty_from IS NULL OR NEW.source_dirty_to IS NULL OR NEW.source_dirty_from<=0
   OR NEW.source_dirty_from>NEW.source_dirty_to OR MOD(NEW.source_dirty_from,60000)<>0 OR MOD(NEW.source_dirty_to,60000)<>0
   OR (OLD.source_dirty_from IS NOT NULL AND NEW.source_dirty_from>OLD.source_dirty_from)
   OR (OLD.source_dirty_to IS NOT NULL AND NEW.source_dirty_to<OLD.source_dirty_to)
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source revision writer fenced'; END IF;
 ELSEIF NOT(NEW.source_dirty_from<=>OLD.source_dirty_from) OR NOT(NEW.source_dirty_to<=>OLD.source_dirty_to) THEN
  CALL joint_s4_fence(NEW.tenant_id,NEW.symbol_id,NEW.writer_generation);
  IF OLD.source_dirty_from IS NULL
   OR NEW.writer_generation<>OLD.writer_generation OR NEW.control_revision<>OLD.control_revision
   OR NOT(NEW.owner_id<=>OLD.owner_id)
   OR (NEW.source_dirty_from IS NULL AND NEW.source_dirty_to IS NOT NULL)
   OR (NEW.source_dirty_from IS NOT NULL AND (NEW.source_dirty_to IS NULL OR NEW.source_dirty_from<=OLD.source_dirty_from
       OR NEW.source_dirty_from>OLD.source_dirty_to OR MOD(NEW.source_dirty_from,60000)<>0 OR NOT(NEW.source_dirty_to<=>OLD.source_dirty_to)))
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source dirty cursor fenced'; END IF;
 END IF;
END$$
CREATE TRIGGER joint_source_dirty_delete BEFORE DELETE ON market_engine_runtime FOR EACH ROW
BEGIN
 IF OLD.source_input_revision<>0 OR OLD.source_dirty_from IS NOT NULL OR OLD.source_dirty_to IS NOT NULL THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source revision receipt cannot be deleted';
 END IF;
END$$
DELIMITER ;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026100403,UTC_TIMESTAMP(6),2026100403,0);

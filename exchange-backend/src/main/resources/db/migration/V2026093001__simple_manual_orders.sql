-- Apply after the existing manual-order and multitenant migrations. MySQL 5.7.
-- Back up first: DDL commits independently. No historical rows or funds are changed.
DELIMITER $$
ALTER TABLE contract_order MODIFY user_id BIGINT NULL$$
ALTER TABLE manual_order_record MODIFY user_id BIGINT NULL$$
CREATE TABLE IF NOT EXISTS manual_order_binding (
 tenant_id BIGINT NOT NULL,
 order_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 operator_id BIGINT NOT NULL,
 idempotency_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii NOT NULL,
 created_at BIGINT NOT NULL,
 evidence LONGTEXT NOT NULL,
 PRIMARY KEY(tenant_id,idempotency_key),
 UNIQUE KEY uk_manual_binding_order(tenant_id,order_id),
 CONSTRAINT fk_manual_binding_order FOREIGN KEY(tenant_id,order_id) REFERENCES contract_order(tenant_id,id),
 CONSTRAINT fk_manual_binding_user FOREIGN KEY(tenant_id,user_id) REFERENCES user_account(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4$$
DROP TRIGGER IF EXISTS manual_unbound_insert$$
CREATE TRIGGER manual_unbound_insert BEFORE INSERT ON contract_order FOR EACH ROW
BEGIN
 IF NEW.user_id IS NULL AND NOT (NEW.order_source='MANUAL_TEST' AND NEW.status='CLOSED' AND NEW.manual_wallet_enabled=0 AND NEW.manual_equity_enabled=0) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Only closed manual simulations may be unbound without funds';
 END IF;
END$$
DROP TRIGGER IF EXISTS manual_unbound_update$$
CREATE TRIGGER manual_unbound_update BEFORE UPDATE ON contract_order FOR EACH ROW
BEGIN
 IF (OLD.user_id IS NOT NULL AND NEW.user_id IS NULL) OR (NEW.user_id IS NULL AND NOT (NEW.order_source='MANUAL_TEST' AND NEW.status='CLOSED' AND NEW.manual_wallet_enabled=0 AND NEW.manual_equity_enabled=0)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid unbound simulation transition';
 END IF;
END$$
DROP TRIGGER IF EXISTS mt_immutable_manual_order_binding$$
CREATE TRIGGER mt_immutable_manual_order_binding BEFORE UPDATE ON manual_order_binding FOR EACH ROW
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF;
END$$
DELIMITER ;

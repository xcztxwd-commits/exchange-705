-- S2 forward-only compatibility gate. Never reactivate a pre-fencing application writer.
ALTER TABLE market_engine_tenant ADD CONSTRAINT mt_t_market_engine_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id);
ALTER TABLE market_engine_runtime ADD CONSTRAINT mt_t_market_engine_runtime FOREIGN KEY(tenant_id) REFERENCES tenant(id),
 ADD CONSTRAINT mt_s_market_engine_runtime FOREIGN KEY(tenant_id,symbol_id) REFERENCES trading_symbol(tenant_id,id);
ALTER TABLE market_control_command ADD CONSTRAINT mt_t_market_control_command FOREIGN KEY(tenant_id) REFERENCES tenant(id),
 ADD CONSTRAINT mt_s_market_control_command FOREIGN KEY(tenant_id,symbol_id) REFERENCES trading_symbol(tenant_id,id);

DELIMITER $$
CREATE TRIGGER mt_immutable_market_engine_tenant BEFORE UPDATE ON market_engine_tenant FOR EACH ROW
BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END$$
CREATE TRIGGER mt_immutable_market_engine_runtime BEFORE UPDATE ON market_engine_runtime FOR EACH ROW
BEGIN IF NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Runtime identity is immutable'; END IF; END$$
CREATE TRIGGER mt_immutable_market_control_command BEFORE UPDATE ON market_control_command FOR EACH ROW
BEGIN IF NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id OR NEW.request_key<>OLD.request_key OR NEW.parameter_hash<>OLD.parameter_hash THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Command identity is immutable'; END IF; END$$
DELIMITER ;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026100305,UTC_TIMESTAMP(6),2026100305,0);

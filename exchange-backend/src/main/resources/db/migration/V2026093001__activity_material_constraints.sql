-- Forward-only upgrade after V00..V06. Do not regenerate already-applied V02 or V05.
-- Existing invalid tenant owners fail here; no inferred/default tenant or FK disabling.
ALTER TABLE activity_material
 ADD UNIQUE KEY uk_activity_material_owner(tenant_id,id),
 ADD CONSTRAINT mt_activity_material_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id);
DELIMITER $$
CREATE TRIGGER mt_immutable_activity_material BEFORE UPDATE ON activity_material FOR EACH ROW
 BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END$$
DELIMITER ;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026093001,UTC_TIMESTAMP(6),2026093001,0);

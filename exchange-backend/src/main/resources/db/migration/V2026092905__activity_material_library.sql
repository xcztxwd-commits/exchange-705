CREATE TABLE activity_material (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_id BIGINT NOT NULL,
 name VARCHAR(80) NOT NULL,
 nodes_json LONGTEXT NOT NULL,
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 INDEX idx_activity_material_tenant (tenant_id, deleted, id)
);

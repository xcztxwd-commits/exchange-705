-- Additive, single-database material library. Back up before applying.
CREATE TABLE IF NOT EXISTS activity_material (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(80) NOT NULL,
 nodes_json LONGTEXT NOT NULL,
 deleted BOOLEAN NOT NULL DEFAULT FALSE,
 INDEX idx_activity_material_deleted (deleted, id)
);

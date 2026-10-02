-- Stop registration on all backend instances before applying and deploying this release.
-- Hibernate TableGenerator stores the last allocated value (default Hibernate 5.6 setting).
-- Existing user IDs and all foreign keys are unchanged; repeated execution never resets IDs.
CREATE TABLE IF NOT EXISTS user_id_sequence (
 sequence_name VARCHAR(255) NOT NULL PRIMARY KEY,
 next_val BIGINT NOT NULL
) ENGINE=InnoDB;
INSERT INTO user_id_sequence(sequence_name,next_val)
 VALUES('user_account',752910)
 ON DUPLICATE KEY UPDATE next_val=next_val;
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026093005,UTC_TIMESTAMP(6),2026093005,0)
 ON DUPLICATE KEY UPDATE version=version;

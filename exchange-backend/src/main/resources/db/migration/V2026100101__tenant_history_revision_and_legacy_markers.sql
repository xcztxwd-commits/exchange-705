-- Forward-only metadata repair. Full backup, independent restore and stop-write gate required.

-- Original wallet, minute/parent values, audit facts, messages and images are never changed.

-- Known old ahc_* cache callbacks are replaced with tenant-bound revisions. Unknown triggers require separate review.

DROP TRIGGER IF EXISTS ahc_1_insert;

CREATE TRIGGER ahc_1_insert AFTER INSERT ON asset_history_1h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,1,1 ON DUPLICATE KEY UPDATE revision=revision+1;

DROP TRIGGER IF EXISTS ahc_1_update;

CREATE TRIGGER ahc_1_update AFTER UPDATE ON asset_history_1h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,1,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) OR NOT (OLD.bucket_start <=> NEW.bucket_start) OR NOT (OLD.bucket_end <=> NEW.bucket_end) OR NOT (OLD.open_value <=> NEW.open_value) OR NOT (OLD.high_value <=> NEW.high_value) OR NOT (OLD.low_value <=> NEW.low_value) OR NOT (OLD.close_value <=> NEW.close_value) OR NOT (OLD.open_at <=> NEW.open_at) OR NOT (OLD.high_at <=> NEW.high_at) OR NOT (OLD.low_at <=> NEW.low_at) OR NOT (OLD.close_at <=> NEW.close_at) OR NOT (OLD.source_count <=> NEW.source_count) OR NOT (OLD.valid_sample_count <=> NEW.valid_sample_count) OR NOT (OLD.invalid_sample_count <=> NEW.invalid_sample_count) OR NOT (OLD.expected_sample_count <=> NEW.expected_sample_count) OR NOT (OLD.finalized <=> NEW.finalized) OR NOT (OLD.quality <=> NEW.quality) OR NOT (OLD.source_through <=> NEW.source_through) UNION ALL SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,1,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) ON DUPLICATE KEY UPDATE revision=revision+1;

DROP TRIGGER IF EXISTS ahc_1_delete;

CREATE TRIGGER ahc_1_delete AFTER DELETE ON asset_history_1h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,1,1 ON DUPLICATE KEY UPDATE revision=revision+1;

DROP TRIGGER IF EXISTS ahc_2_insert;

CREATE TRIGGER ahc_2_insert AFTER INSERT ON asset_history_4h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,2,1 ON DUPLICATE KEY UPDATE revision=revision+1;

DROP TRIGGER IF EXISTS ahc_2_update;

CREATE TRIGGER ahc_2_update AFTER UPDATE ON asset_history_4h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,2,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) OR NOT (OLD.bucket_start <=> NEW.bucket_start) OR NOT (OLD.bucket_end <=> NEW.bucket_end) OR NOT (OLD.open_value <=> NEW.open_value) OR NOT (OLD.high_value <=> NEW.high_value) OR NOT (OLD.low_value <=> NEW.low_value) OR NOT (OLD.close_value <=> NEW.close_value) OR NOT (OLD.open_at <=> NEW.open_at) OR NOT (OLD.high_at <=> NEW.high_at) OR NOT (OLD.low_at <=> NEW.low_at) OR NOT (OLD.close_at <=> NEW.close_at) OR NOT (OLD.source_count <=> NEW.source_count) OR NOT (OLD.valid_sample_count <=> NEW.valid_sample_count) OR NOT (OLD.invalid_sample_count <=> NEW.invalid_sample_count) OR NOT (OLD.expected_sample_count <=> NEW.expected_sample_count) OR NOT (OLD.finalized <=> NEW.finalized) OR NOT (OLD.quality <=> NEW.quality) OR NOT (OLD.source_through <=> NEW.source_through) UNION ALL SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,2,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) ON DUPLICATE KEY UPDATE revision=revision+1;

DROP TRIGGER IF EXISTS ahc_2_delete;

CREATE TRIGGER ahc_2_delete AFTER DELETE ON asset_history_4h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,2,1 ON DUPLICATE KEY UPDATE revision=revision+1;

DROP TRIGGER IF EXISTS ahc_3_insert;

CREATE TRIGGER ahc_3_insert AFTER INSERT ON asset_history_1d FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,3,1 ON DUPLICATE KEY UPDATE revision=revision+1;

DROP TRIGGER IF EXISTS ahc_3_update;

CREATE TRIGGER ahc_3_update AFTER UPDATE ON asset_history_1d FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,3,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) OR NOT (OLD.bucket_start <=> NEW.bucket_start) OR NOT (OLD.bucket_end <=> NEW.bucket_end) OR NOT (OLD.open_value <=> NEW.open_value) OR NOT (OLD.high_value <=> NEW.high_value) OR NOT (OLD.low_value <=> NEW.low_value) OR NOT (OLD.close_value <=> NEW.close_value) OR NOT (OLD.open_at <=> NEW.open_at) OR NOT (OLD.high_at <=> NEW.high_at) OR NOT (OLD.low_at <=> NEW.low_at) OR NOT (OLD.close_at <=> NEW.close_at) OR NOT (OLD.source_count <=> NEW.source_count) OR NOT (OLD.valid_sample_count <=> NEW.valid_sample_count) OR NOT (OLD.invalid_sample_count <=> NEW.invalid_sample_count) OR NOT (OLD.expected_sample_count <=> NEW.expected_sample_count) OR NOT (OLD.finalized <=> NEW.finalized) OR NOT (OLD.quality <=> NEW.quality) OR NOT (OLD.source_through <=> NEW.source_through) UNION ALL SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,3,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) ON DUPLICATE KEY UPDATE revision=revision+1;

DROP TRIGGER IF EXISTS ahc_3_delete;

CREATE TRIGGER ahc_3_delete AFTER DELETE ON asset_history_1d FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,3,1 ON DUPLICATE KEY UPDATE revision=revision+1;

-- Advance derived cache revisions once so pre-repair revision-zero keys cannot remain current.

INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT tenant_id,user_id,basis_version,1,1 FROM asset_history_1h GROUP BY tenant_id,user_id,basis_version ON DUPLICATE KEY UPDATE revision=revision+1;

INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT tenant_id,user_id,basis_version,2,1 FROM asset_history_4h GROUP BY tenant_id,user_id,basis_version ON DUPLICATE KEY UPDATE revision=revision+1;

INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT tenant_id,user_id,basis_version,3,1 FROM asset_history_1d GROUP BY tenant_id,user_id,basis_version ON DUPLICATE KEY UPDATE revision=revision+1;

-- Restore original ledger prerequisite markers only; never invent monetary receipts or actor/timestamp/fee/rate evidence.

UPDATE deposit_record SET source='LEGACY_UNKNOWN' WHERE source IS NULL;

UPDATE deposit_record SET order_no=CONCAT('LEGACY-DEP-',id) WHERE order_no IS NULL;

UPDATE deposit_record SET account_type='FUND' WHERE account_type IS NULL;

INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready) VALUES(2026100101,UTC_TIMESTAMP(6),2026100101,0);

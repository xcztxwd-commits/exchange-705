-- Apply once to the intended tenant-enabled application schema after a database backup.
-- Additive only. Does not rewrite created_at, countdown_seconds, recipients or money.
SET @ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='announcement' AND column_name='display_at'), 'SELECT 1', 'ALTER TABLE announcement ADD COLUMN display_at DATETIME(6) NULL');
PREPARE t04_stmt FROM @ddl;
EXECUTE t04_stmt;
DEALLOCATE PREPARE t04_stmt;
CREATE TABLE IF NOT EXISTS announcement_receipt (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    announcement_id BIGINT NOT NULL,
    read_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_announcement_receipt (tenant_id,user_id,announcement_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Rollback: restore application code first. Preserve this table and display_at for
-- reversibility; remove only after separately exporting receipts and configured dates.

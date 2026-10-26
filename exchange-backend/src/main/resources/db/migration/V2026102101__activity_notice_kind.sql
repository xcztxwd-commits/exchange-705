-- Additive MySQL 5.7+ migration. Existing campaigns remain reward campaigns.
-- Apply to each tenant database before deploying the corresponding backend.
SET @activity_notice_sql = IF(
 (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='activity_campaign' AND column_name='campaign_kind')=0,
 'ALTER TABLE activity_campaign ADD COLUMN campaign_kind VARCHAR(16) NOT NULL DEFAULT ''TRIAL''',
 'SELECT 1');
PREPARE activity_notice_stmt FROM @activity_notice_sql;
EXECUTE activity_notice_stmt;
DEALLOCATE PREPARE activity_notice_stmt;

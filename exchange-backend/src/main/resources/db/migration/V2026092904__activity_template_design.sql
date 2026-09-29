-- Additive release migration. Run after backup, before starting the new application.
SET SESSION lock_wait_timeout=10;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_campaign' AND COLUMN_NAME='layout_json'),'SELECT 1','ALTER TABLE activity_campaign ADD COLUMN layout_json LONGTEXT NULL');
PREPARE activity_migration FROM @ddl; EXECUTE activity_migration; DEALLOCATE PREPARE activity_migration;

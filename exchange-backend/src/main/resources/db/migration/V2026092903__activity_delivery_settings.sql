-- Additive release migration. Run after backup, before starting the new application.
SET SESSION lock_wait_timeout=10;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_campaign' AND COLUMN_NAME='auto_send_enabled'),'SELECT 1','ALTER TABLE activity_campaign ADD COLUMN auto_send_enabled BIT NOT NULL DEFAULT 0');
PREPARE activity_migration FROM @ddl; EXECUTE activity_migration; DEALLOCATE PREPARE activity_migration;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_campaign' AND COLUMN_NAME='repeat_unread'),'SELECT 1','ALTER TABLE activity_campaign ADD COLUMN repeat_unread BIT NOT NULL DEFAULT 0');
PREPARE activity_migration FROM @ddl; EXECUTE activity_migration; DEALLOCATE PREPARE activity_migration;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_campaign' AND COLUMN_NAME='deleted'),'SELECT 1','ALTER TABLE activity_campaign ADD COLUMN deleted BIT NOT NULL DEFAULT 0');
PREPARE activity_migration FROM @ddl; EXECUTE activity_migration; DEALLOCATE PREPARE activity_migration;

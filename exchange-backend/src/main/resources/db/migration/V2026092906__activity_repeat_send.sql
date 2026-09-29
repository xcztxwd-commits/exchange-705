-- Additive and repeatable. Existing campaigns keep repeat send disabled.
SET SESSION lock_wait_timeout=10;
SET @ddl=IF(EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='activity_campaign' AND COLUMN_NAME='allow_repeat_send'),'SELECT 1','ALTER TABLE activity_campaign ADD COLUMN allow_repeat_send BOOLEAN NOT NULL DEFAULT FALSE');
PREPARE activity_migration FROM @ddl; EXECUTE activity_migration; DEALLOCATE PREPARE activity_migration;

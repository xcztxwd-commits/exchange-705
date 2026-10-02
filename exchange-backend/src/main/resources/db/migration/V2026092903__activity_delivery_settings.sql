-- Preserve existing delivery behavior: automatic sending and unread retries default off.
ALTER TABLE activity_campaign
 ADD COLUMN auto_send_enabled BIT NOT NULL DEFAULT 0,
 ADD COLUMN repeat_unread BIT NOT NULL DEFAULT 0,
 ADD COLUMN deleted BIT NOT NULL DEFAULT 0;

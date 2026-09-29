-- Run against the selected application database after backing up the five tables.
-- MySQL DDL commits implicitly. Existing conversations/messages are retained.
-- Fixes latin1 tables created by Hibernate inheriting the legacy database default.
SET SESSION lock_wait_timeout = 5;
ALTER TABLE support_conversation CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE support_message CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE support_attachment CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE support_presence CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE inbox_letter CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Regression check: temporary clones use the real column definitions, not user data.
-- Expect both checks to return 1. No test messages are written to application tables.
SET NAMES utf8mb4;
SET @unicode_probe = CONVERT(0xE682A8E5A5BDE38081E38193E38293E381ABE381A1E381AFF09F9880 USING utf8mb4);
CREATE TEMPORARY TABLE support_charset_probe AS SELECT text, sender_name FROM support_message WHERE 1=0;
INSERT INTO support_charset_probe (text, sender_name) VALUES (@unicode_probe, @unicode_probe);
SELECT HEX(text) = HEX(@unicode_probe) AND HEX(sender_name) = HEX(@unicode_probe) AS support_unicode_ok
FROM support_charset_probe;
DROP TEMPORARY TABLE support_charset_probe;
CREATE TEMPORARY TABLE inbox_charset_probe AS SELECT title, content FROM inbox_letter WHERE 1=0;
INSERT INTO inbox_charset_probe (title, content) VALUES (@unicode_probe, @unicode_probe);
SELECT HEX(title) = HEX(@unicode_probe) AND HEX(content) = HEX(@unicode_probe) AS inbox_unicode_ok
FROM inbox_charset_probe;
DROP TEMPORARY TABLE inbox_charset_probe;

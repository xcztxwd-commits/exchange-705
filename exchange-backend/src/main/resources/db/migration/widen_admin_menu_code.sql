-- Release prerequisite: run against the selected database BEFORE backend startup.
-- Additive only. No tenant conversion, seed changes, table charset conversion or down migration.
SET @previous_lock_wait_timeout = @@SESSION.lock_wait_timeout;
SET SESSION lock_wait_timeout = 10;
DROP PROCEDURE IF EXISTS release_widen_admin_menu_code;
DELIMITER $$
CREATE PROCEDURE release_widen_admin_menu_code()
BEGIN
    DECLARE width BIGINT;
    DECLARE kind, nullable_value, charset_value, collation_value VARCHAR(64);
    DECLARE default_value, extra_value, comment_value TEXT;
    IF EXISTS (SELECT 1 FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='admin_menu') THEN
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='admin_menu' AND COLUMN_NAME='menu_code') THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='admin_menu.menu_code missing: inspect schema before release';
        END IF;
        SELECT CHARACTER_MAXIMUM_LENGTH, DATA_TYPE, IS_NULLABLE, CHARACTER_SET_NAME, COLLATION_NAME, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT
          INTO width, kind, nullable_value, charset_value, collation_value, default_value, extra_value, comment_value
          FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='admin_menu' AND COLUMN_NAME='menu_code';
        IF kind <> 'varchar' OR nullable_value <> 'NO' OR default_value IS NOT NULL OR extra_value <> '' THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Unexpected menu_code definition: manual review required';
        END IF;
        IF width < 150 THEN
            -- Preserve column charset/collation/comment and existing unique indexes.
            SET @widen_menu_sql = CONCAT('ALTER TABLE admin_menu MODIFY COLUMN menu_code VARCHAR(150) CHARACTER SET ', charset_value,
                ' COLLATE ', collation_value, ' NOT NULL COMMENT ', QUOTE(comment_value));
            PREPARE widen_menu_statement FROM @widen_menu_sql;
            EXECUTE widen_menu_statement;
            DEALLOCATE PREPARE widen_menu_statement;
        END IF;
    END IF;
END$$
DELIMITER ;
CALL release_widen_admin_menu_code();
DROP PROCEDURE release_widen_admin_menu_code;
SET SESSION lock_wait_timeout = @previous_lock_wait_timeout;

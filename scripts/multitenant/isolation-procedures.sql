-- MySQL 5.7; no business writes may run while nullable tenant columns are backfilled.
-- Missing optional tables are reported by the runner; unknown tables are fail-closed.
DELIMITER $$
CREATE PROCEDURE mt_exec(IN statement_text LONGTEXT)
BEGIN
 SET @mt_statement=statement_text;
 PREPARE mt_statement FROM @mt_statement;
 EXECUTE mt_statement;
 DEALLOCATE PREPARE mt_statement;
END$$
CREATE PROCEDURE mt_scope(IN table_arg VARCHAR(64))
scope_body: BEGIN
 DECLARE finished INT DEFAULT 0;
 DECLARE index_arg VARCHAR(64);
 DECLARE columns_arg TEXT;
 DECLARE primary_columns TEXT;
 DECLARE key_column VARCHAR(64);
 DECLARE table_count INT;
 DECLARE unchanged_timestamps TEXT;
 DECLARE unique_indexes CURSOR FOR
  SELECT INDEX_NAME,GROUP_CONCAT(CONCAT('`',COLUMN_NAME,'`',IF(SUB_PART IS NULL,'',CONCAT('(',SUB_PART,')'))) ORDER BY SEQ_IN_INDEX SEPARATOR ',')
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_arg
   AND NON_UNIQUE=0 AND INDEX_NAME<>'PRIMARY'
  GROUP BY INDEX_NAME HAVING SUM(COLUMN_NAME='tenant_id')=0;
 DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished=1;
 SELECT COUNT(*) INTO table_count FROM information_schema.TABLES
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_arg;
 IF table_count=0 THEN LEAVE scope_body; END IF;
 IF EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_arg AND COLUMN_NAME='tenant_id')
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Partial migration or already scoped table: restore verified backup before retry'; END IF;
 CALL mt_exec(CONCAT('ALTER TABLE `',table_arg,'` ADD COLUMN tenant_id BIGINT NULL'));
 SELECT GROUP_CONCAT(CONCAT(',`',COLUMN_NAME,'`=`',COLUMN_NAME,'`') SEPARATOR '') INTO unchanged_timestamps
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_arg AND EXTRA LIKE '%on update%';
 CALL mt_exec(CONCAT('UPDATE `',table_arg,'` SET tenant_id=1',IFNULL(unchanged_timestamps,'')));
 CALL mt_exec(CONCAT('ALTER TABLE `',table_arg,'` MODIFY tenant_id BIGINT NOT NULL, ADD CONSTRAINT `mt_t_',table_arg,'` FOREIGN KEY(tenant_id) REFERENCES tenant(id)'));
 SELECT GROUP_CONCAT(CONCAT('`',COLUMN_NAME,'`') ORDER BY SEQ_IN_INDEX SEPARATOR ',') INTO primary_columns
  FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_arg AND INDEX_NAME='PRIMARY';
 -- Preserve all historical IDs. Natural primary keys need tenant scope, including task watermarks.
 IF primary_columns NOT IN ('`id`','`user_id`','`admin_id`','`message_id`','`event_sequence`') THEN
  CALL mt_exec(CONCAT('ALTER TABLE `',table_arg,'` DROP PRIMARY KEY, ADD PRIMARY KEY(tenant_id,',primary_columns,')'));
 ELSE
  CALL mt_exec(CONCAT('ALTER TABLE `',table_arg,'` ADD UNIQUE KEY mt_tenant_identity(tenant_id,',primary_columns,')'));
 END IF;
 OPEN unique_indexes;
 index_loop: LOOP
  FETCH unique_indexes INTO index_arg,columns_arg;
  IF finished=1 THEN LEAVE index_loop; END IF;
  -- External deposit order identity remains globally unique. Backend account namespace is global.
  IF NOT (table_arg='deposit_record' AND columns_arg='`order_no`')
   AND NOT (table_arg='admin_user' AND columns_arg='`account`') THEN
   -- Existing single-column FKs may use this unique index. Retain a nonunique lookup
   -- before changing uniqueness; composite tenant FKs are added below as the boundary.
   CALL mt_exec(CONCAT('ALTER TABLE `',table_arg,'` ADD INDEX `mt_old_',LEFT(SHA2(index_arg,256),16),'`(',columns_arg,')'));
   IF table_arg='user_account' AND columns_arg IN ('`email`','`phone`') THEN
    CALL mt_exec(CONCAT('ALTER TABLE `',table_arg,'` DROP INDEX `',index_arg,'`'));
   ELSE
    CALL mt_exec(CONCAT('ALTER TABLE `',table_arg,'` DROP INDEX `',index_arg,'`, ADD UNIQUE KEY `',index_arg,'`(tenant_id,',columns_arg,')'));
   END IF;
  END IF;
 END LOOP;
 CLOSE unique_indexes;
 IF EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_arg AND COLUMN_NAME='created_at') THEN
  CALL mt_exec(CONCAT('ALTER TABLE `',table_arg,'` ADD INDEX mt_tenant_created(tenant_id,created_at)'));
 END IF;
 IF EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=table_arg AND COLUMN_NAME='status') THEN
  CALL mt_exec(CONCAT('ALTER TABLE `',table_arg,'` ADD INDEX mt_tenant_status(tenant_id,status)'));
 END IF;
END$$
CREATE PROCEDURE mt_link(IN child_arg VARCHAR(64),IN field_arg VARCHAR(64),IN parent_arg VARCHAR(64),IN parent_field VARCHAR(64))
link_body: BEGIN
 DECLARE invalid_rows BIGINT;
 DECLARE key_name VARCHAR(64);
 DECLARE parent_charset VARCHAR(64);
 DECLARE parent_collation VARCHAR(64);
 DECLARE child_collation VARCHAR(64);
 DECLARE field_type VARCHAR(128);
 DECLARE nullable_field VARCHAR(3);
 IF NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=child_arg AND COLUMN_NAME=field_arg)
  OR NOT EXISTS(SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=parent_arg AND COLUMN_NAME=parent_field)
 THEN LEAVE link_body; END IF;
 SELECT CHARACTER_SET_NAME,COLLATION_NAME,COLUMN_TYPE INTO parent_charset,parent_collation,field_type
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=parent_arg AND COLUMN_NAME=parent_field;
 SELECT COLLATION_NAME,IS_NULLABLE INTO child_collation,nullable_field
  FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=child_arg AND COLUMN_NAME=field_arg;
 IF parent_collation IS NOT NULL AND NOT (parent_collation <=> child_collation) THEN
  -- Legacy native tables are latin1 while newly-created tables may be utf8mb4.
  -- Only ASCII identity columns can be harmonized without changing historical bytes.
  CALL mt_exec(CONCAT('SELECT COUNT(*) INTO @mt_nonascii FROM `',child_arg,'` WHERE HEX(`',field_arg,'`)<>HEX(CONVERT(`',field_arg,'` USING ascii))'));
  IF @mt_nonascii>0 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Non-ASCII FK identity requires reviewed collation migration'; END IF;
  CALL mt_exec(CONCAT('ALTER TABLE `',child_arg,'` MODIFY `',field_arg,'` ',field_type,' CHARACTER SET ',parent_charset,' COLLATE ',parent_collation,IF(nullable_field='YES',' NULL',' NOT NULL')));
 END IF;
 SET key_name=CONCAT('mt_fk_',LEFT(SHA2(CONCAT(child_arg,':',field_arg,':',parent_arg),256),20));
 -- FK creation itself rejects any orphan and any tenant mismatch; never disable FK checks.
 CALL mt_exec(CONCAT('ALTER TABLE `',child_arg,'` ADD CONSTRAINT `',key_name,'` FOREIGN KEY(tenant_id,`',field_arg,'`) REFERENCES `',parent_arg,'`(tenant_id,`',parent_field,'`) ON UPDATE RESTRICT ON DELETE RESTRICT'));
END$$
DELIMITER ;

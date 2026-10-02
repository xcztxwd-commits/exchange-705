-- 更新用户ID从7000000开始
-- 注意：此脚本会修改现有用户的ID，请在执行前备份数据库

-- 1. 临时禁用外键检查（如果有外键约束）
SET FOREIGN_KEY_CHECKS = 0;

-- 2. 创建普通表存储ID映射关系（不使用临时表，避免重复引用问题）
DROP TABLE IF EXISTS user_id_mapping_temp;
CREATE TABLE user_id_mapping_temp (
    old_id BIGINT NOT NULL,
    new_id BIGINT NOT NULL,
    PRIMARY KEY (old_id),
    KEY idx_new_id (new_id)
) ENGINE=InnoDB;

-- 3. 生成ID映射（将现有用户ID映射到7000001开始）
SET @new_id = 7000000;
INSERT INTO user_id_mapping_temp (old_id, new_id)
SELECT id, (@new_id := @new_id + 1) 
FROM user_account 
ORDER BY id;

-- 4. 更新asset_account表的user_id外键（如果表存在）
UPDATE asset_account aa
INNER JOIN user_id_mapping_temp m ON aa.user_id = m.old_id
SET aa.user_id = m.new_id
WHERE EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'asset_account');

-- 5. 更新deposit_record表的user_id外键（如果表存在）
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'deposit_record');
SET @sql = IF(@table_exists > 0, 'UPDATE deposit_record dr INNER JOIN user_id_mapping_temp m ON dr.user_id = m.old_id SET dr.user_id = m.new_id', 'SELECT "deposit_record表不存在，跳过"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. 更新withdraw_record表的user_id外键（如果表存在）
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'withdraw_record');
SET @sql = IF(@table_exists > 0, 'UPDATE withdraw_record wr INNER JOIN user_id_mapping_temp m ON wr.user_id = m.old_id SET wr.user_id = m.new_id', 'SELECT "withdraw_record表不存在，跳过"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 7. 更新loan表的user_id外键（如果表存在）
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'loan');
SET @sql = IF(@table_exists > 0, 'UPDATE loan l INNER JOIN user_id_mapping_temp m ON l.user_id = m.old_id SET l.user_id = m.new_id', 'SELECT "loan表不存在，跳过"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 8. 更新loan_personal_info表的user_id外键（如果表存在）
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'loan_personal_info');
SET @sql = IF(@table_exists > 0, 'UPDATE loan_personal_info lpi INNER JOIN user_id_mapping_temp m ON lpi.user_id = m.old_id SET lpi.user_id = m.new_id', 'SELECT "loan_personal_info表不存在，跳过"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 9. 更新financial_order表的user_id外键（如果表存在）
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'financial_order');
SET @sql = IF(@table_exists > 0, 'UPDATE financial_order fo INNER JOIN user_id_mapping_temp m ON fo.user_id = m.old_id SET fo.user_id = m.new_id', 'SELECT "financial_order表不存在，跳过"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 10. 更新kyc表的user_id外键（如果表存在）
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'kyc');
SET @sql = IF(@table_exists > 0, 'UPDATE kyc k INNER JOIN user_id_mapping_temp m ON k.user_id = m.old_id SET k.user_id = m.new_id', 'SELECT "kyc表不存在，跳过"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 11. 更新user_bank_card表的user_id外键（如果表存在）
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_bank_card');
SET @sql = IF(@table_exists > 0, 'UPDATE user_bank_card ubc INNER JOIN user_id_mapping_temp m ON ubc.user_id = m.old_id SET ubc.user_id = m.new_id', 'SELECT "user_bank_card表不存在，跳过"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 12. 更新user_digital_address表的user_id外键（如果表存在）
SET @table_exists = (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'user_digital_address');
SET @sql = IF(@table_exists > 0, 'UPDATE user_digital_address uda INNER JOIN user_id_mapping_temp m ON uda.user_id = m.old_id SET uda.user_id = m.new_id', 'SELECT "user_digital_address表不存在，跳过"');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 13. 更新user_account表的parent_user_id字段
UPDATE user_account ua
INNER JOIN user_id_mapping_temp m ON ua.parent_user_id = m.old_id
SET ua.parent_user_id = m.new_id
WHERE ua.parent_user_id IS NOT NULL;

-- 14. 更新user_account表的id字段
UPDATE user_account ua
INNER JOIN user_id_mapping_temp m ON ua.id = m.old_id
SET ua.id = m.new_id;

-- 15. 设置AUTO_INCREMENT起始值
ALTER TABLE user_account AUTO_INCREMENT = 7000001;

-- 16. 恢复外键检查
SET FOREIGN_KEY_CHECKS = 1;

-- 17. 清理临时表
DROP TABLE IF EXISTS user_id_mapping_temp;


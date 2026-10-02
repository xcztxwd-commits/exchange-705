-- 简化版：只为新数据库设置用户ID从7000000开始
-- 适用于：没有现有用户数据或愿意清空现有数据的情况

-- 1. 查看当前user_account表的AUTO_INCREMENT值
SELECT AUTO_INCREMENT 
FROM information_schema.tables 
WHERE table_schema = DATABASE() 
AND table_name = 'user_account';

-- 2. 设置AUTO_INCREMENT起始值为7000001
ALTER TABLE user_account AUTO_INCREMENT = 7000001;

-- 3. 验证设置
SELECT AUTO_INCREMENT 
FROM information_schema.tables 
WHERE table_schema = DATABASE() 
AND table_name = 'user_account';

-- 注意：
-- - 如果表中已有数据且ID小于7000000，新数据的ID仍会从7000001开始
-- - 如果表中已有数据且ID大于7000000，新数据的ID会从(最大ID + 1)开始
-- - 如果需要将现有用户ID也改为7000000+，请使用 update_user_id_start_from_7000000.sql


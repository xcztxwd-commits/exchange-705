-- 添加代理管理功能

-- 1. 在admin_menu表中添加"代理管理"菜单
INSERT INTO admin_menu (parent_id, menu_name, menu_code, menu_type, path, icon, sort_order, status)
VALUES (0, '代理管理', 'agents', 'menu', '/agents', 'Avatar', 50, 'active');

-- 获取刚插入的菜单ID（超级管理员需要有这个权限）
SET @agent_menu_id = LAST_INSERT_ID();

-- 2. 为超级管理员添加代理管理权限
INSERT INTO admin_role_menu (role_id, menu_id)
SELECT 1, @agent_menu_id
WHERE NOT EXISTS (
    SELECT 1 FROM admin_role_menu WHERE role_id = 1 AND menu_id = @agent_menu_id
);

-- 3. 在user_account表中添加user_type字段
ALTER TABLE user_account 
ADD COLUMN user_type VARCHAR(20) DEFAULT 'normal' COMMENT '用户类型：normal-普通用户, agent-代理' AFTER status;

-- 添加索引
ALTER TABLE user_account ADD INDEX idx_user_type (user_type);

-- 4. 更新现有用户为普通用户
UPDATE user_account SET user_type = 'normal' WHERE user_type IS NULL;

SELECT '✅ 代理管理功能已添加！' as message;
SELECT '✅ user_type字段已添加到user_account表' as message;
SELECT '✅ 代理管理菜单已创建' as message;


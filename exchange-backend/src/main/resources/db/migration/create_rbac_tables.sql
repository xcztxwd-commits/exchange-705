-- 创建RBAC（基于角色的访问控制）相关表

-- 1. 角色表
CREATE TABLE IF NOT EXISTS admin_role (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_name VARCHAR(50) NOT NULL UNIQUE COMMENT '角色名称',
    role_code VARCHAR(50) NOT NULL UNIQUE COMMENT '角色代码',
    description VARCHAR(200) COMMENT '角色描述',
    status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active, disabled',
    is_super TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否超级管理员',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_role_code (role_code),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员角色表';

-- 2. 菜单表
CREATE TABLE IF NOT EXISTS admin_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    parent_id BIGINT DEFAULT 0 COMMENT '父菜单ID，0表示顶级菜单',
    menu_name VARCHAR(50) NOT NULL COMMENT '菜单名称',
    menu_code VARCHAR(50) NOT NULL UNIQUE COMMENT '菜单代码',
    menu_type VARCHAR(20) NOT NULL COMMENT '菜单类型：menu, button',
    path VARCHAR(200) COMMENT '前端路由路径',
    icon VARCHAR(50) COMMENT '菜单图标',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    status VARCHAR(20) NOT NULL DEFAULT 'active' COMMENT '状态：active, disabled',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_parent_id (parent_id),
    INDEX idx_menu_code (menu_code),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员菜单表';

-- 3. 角色菜单关联表
CREATE TABLE IF NOT EXISTS admin_role_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    role_id BIGINT NOT NULL COMMENT '角色ID',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    INDEX idx_role_id (role_id),
    INDEX idx_menu_id (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色菜单关联表';

-- 4. 修改admin_user表，添加role_id字段
ALTER TABLE admin_user 
ADD COLUMN role_id BIGINT COMMENT '角色ID',
ADD INDEX idx_role_id (role_id);

-- 5. 插入默认超级管理员角色
INSERT INTO admin_role (role_name, role_code, description, is_super, status) 
VALUES ('超级管理员', 'super_admin', '拥有所有权限的超级管理员', 1, 'active');

-- 6. 插入默认普通管理员角色
INSERT INTO admin_role (role_name, role_code, description, is_super, status) 
VALUES ('普通管理员', 'admin', '普通管理员，权限受限', 0, 'active');

-- 7. 插入默认菜单（根据您的实际菜单结构调整）
INSERT INTO admin_menu (menu_name, menu_code, menu_type, path, icon, sort_order, parent_id) VALUES
-- 一级菜单
('控制台', 'dashboard', 'menu', '/dashboard', 'dashboard', 1, 0),
('用户管理', 'users', 'menu', '/users', 'user', 2, 0),
('币种管理', 'symbols', 'menu', '/symbols', 'coin', 3, 0),
('充值设置', 'deposit-settings', 'menu', '/deposit-settings', 'deposit', 4, 0),
('充值审核', 'deposit-review', 'menu', '/deposit-review', 'review', 5, 0),
('提现审核', 'withdraw-review', 'menu', '/withdraw-review', 'withdraw', 6, 0),
('KYC审核', 'kyc-review', 'menu', '/kyc-review', 'kyc', 7, 0),
('理财产品', 'financial-products', 'menu', '/financial-products', 'financial', 8, 0),
('贷款审核', 'loan-review', 'menu', '/loan-review', 'loan', 9, 0),
('贷款设置', 'loan-settings', 'menu', '/loan-settings', 'settings', 10, 0),
('公告管理', 'announcements', 'menu', '/announcements', 'notice', 11, 0),
('角色管理', 'roles', 'menu', '/roles', 'role', 12, 0),
('系统设置', 'system-config', 'menu', '/system-config', 'config', 13, 0);

-- 8. 为超级管理员角色分配所有菜单权限
INSERT INTO admin_role_menu (role_id, menu_id)
SELECT 1, id FROM admin_menu;

-- 9. 更新现有admin用户为超级管理员角色（假设第一个用户是admin）
UPDATE admin_user SET role_id = 1 WHERE id = (SELECT MIN(id) FROM (SELECT id FROM admin_user) AS temp);

-- 10. 为普通管理员角色分配部分菜单权限（示例）
INSERT INTO admin_role_menu (role_id, menu_id)
SELECT 2, id FROM admin_menu WHERE menu_code IN ('dashboard', 'users', 'symbols', 'deposit-review', 'kyc-review');


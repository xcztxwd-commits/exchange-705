-- 创建用户菜单关联表，用于代理用户分配菜单权限
CREATE TABLE IF NOT EXISTS user_menu (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID（user_account表的id）',
    menu_id BIGINT NOT NULL COMMENT '菜单ID（admin_menu表的id）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_menu (user_id, menu_id),
    INDEX idx_user_id (user_id),
    INDEX idx_menu_id (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户菜单关联表（代理用户权限）';


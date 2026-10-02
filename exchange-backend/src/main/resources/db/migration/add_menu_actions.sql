-- 创建菜单操作权限表

-- 1. 菜单操作映射表（定义每个菜单下有哪些操作）
CREATE TABLE IF NOT EXISTS menu_action (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    action_code VARCHAR(100) NOT NULL COMMENT '操作代码（如：reset_password, freeze_user等）',
    action_name VARCHAR(100) NOT NULL COMMENT '操作名称（如：重置密码、冻结用户等）',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '排序',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_menu_action (menu_id, action_code),
    INDEX idx_menu_id (menu_id),
    FOREIGN KEY (menu_id) REFERENCES admin_menu(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单操作映射表';

-- 2. 用户操作权限表（存储代理的操作权限）
CREATE TABLE IF NOT EXISTS user_action (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL COMMENT '用户ID（代理ID）',
    menu_id BIGINT NOT NULL COMMENT '菜单ID',
    action_code VARCHAR(100) NOT NULL COMMENT '操作代码',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_menu_action (user_id, menu_id, action_code),
    INDEX idx_user_id (user_id),
    INDEX idx_menu_id (menu_id),
    FOREIGN KEY (user_id) REFERENCES user_account(id) ON DELETE CASCADE,
    FOREIGN KEY (menu_id) REFERENCES admin_menu(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户操作权限表';





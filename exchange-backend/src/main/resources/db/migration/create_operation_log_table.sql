-- 创建操作日志表
CREATE TABLE IF NOT EXISTS operation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    admin_id BIGINT NOT NULL COMMENT '管理员ID',
    admin_email VARCHAR(100) COMMENT '管理员邮箱',
    operation_type VARCHAR(50) NOT NULL COMMENT '操作类型（如：用户管理、订单管理、充值审核等）',
    operation_action VARCHAR(100) NOT NULL COMMENT '操作动作（如：重置密码、审核通过、删除等）',
    target_type VARCHAR(50) COMMENT '目标类型（如：用户、订单、充值记录等）',
    target_id BIGINT COMMENT '目标ID',
    target_info VARCHAR(500) COMMENT '目标信息（如：用户邮箱、订单号等）',
    request_method VARCHAR(10) COMMENT '请求方法（GET、POST、PUT、DELETE）',
    request_url VARCHAR(500) COMMENT '请求URL',
    request_params TEXT COMMENT '请求参数（JSON格式）',
    ip_address VARCHAR(50) COMMENT 'IP地址',
    user_agent VARCHAR(500) COMMENT '用户代理',
    status VARCHAR(20) DEFAULT 'SUCCESS' COMMENT '操作状态（SUCCESS、FAILED）',
    error_message TEXT COMMENT '错误信息',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_admin_id (admin_id),
    INDEX idx_operation_type (operation_type),
    INDEX idx_created_at (created_at),
    INDEX idx_target_type_id (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';





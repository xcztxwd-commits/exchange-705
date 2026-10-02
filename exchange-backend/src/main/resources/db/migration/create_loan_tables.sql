-- 创建贷款设置表
CREATE TABLE IF NOT EXISTS `loan_setting` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `days` INT NOT NULL COMMENT '贷款期限（天数）',
  `daily_rate` DECIMAL(8,6) NOT NULL COMMENT '日利率（百分比）',
  `free_days` INT NOT NULL DEFAULT 0 COMMENT '免息天数',
  `overdue_rate` DECIMAL(8,6) COMMENT '逾期费率（百分比）',
  `min_amount` DECIMAL(32,16) COMMENT '最小金额',
  `max_amount` DECIMAL(32,16) COMMENT '最大金额',
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  INDEX `idx_enabled_days` (`enabled`, `days`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='贷款设置表';

-- 创建贷款记录表
CREATE TABLE IF NOT EXISTS `loan_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `real_name` VARCHAR(100) COMMENT '真实姓名',
  `id_number` VARCHAR(50) COMMENT '身份证号',
  `phone` VARCHAR(32) COMMENT '电话',
  `address` VARCHAR(500) COMMENT '家庭住址',
  `amount` DECIMAL(32,16) NOT NULL COMMENT '贷款金额',
  `days` INT NOT NULL COMMENT '贷款期限（天数）',
  `daily_rate` DECIMAL(8,6) NOT NULL COMMENT '日利率',
  `free_days` INT NOT NULL DEFAULT 0 COMMENT '免息天数',
  `total_interest` DECIMAL(32,16) NOT NULL COMMENT '总利息',
  `overdue_rate` DECIMAL(8,6) COMMENT '逾期费率',
  `overdue_fee` DECIMAL(32,16) DEFAULT 0 COMMENT '违约金/逾期费',
  `repayment_amount` DECIMAL(32,16) NOT NULL COMMENT '需还款金额（本金+利息）',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING待审核, APPROVED已批准, SIGNED已签约, COMPLETED已完成, OVERDUE已逾期, REJECTED已拒绝',
  `contract_signed` BOOLEAN NOT NULL DEFAULT FALSE COMMENT '合同是否已签署',
  `signature_image` VARCHAR(500) COMMENT '签名图片URL',
  `approved_at` DATETIME COMMENT '批准时间',
  `repayment_date` DATETIME COMMENT '还款日期（到期日）',
  `actual_repayment_at` DATETIME COMMENT '实际还款时间',
  `remark` VARCHAR(500) COMMENT '备注',
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_status` (`status`),
  INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='贷款记录表';




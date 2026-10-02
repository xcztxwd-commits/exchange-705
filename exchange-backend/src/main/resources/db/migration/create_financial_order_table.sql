-- 创建理财订单表
CREATE TABLE IF NOT EXISTS `financial_order` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `product_id` BIGINT NOT NULL COMMENT '产品ID',
  `product_name` VARCHAR(100) NOT NULL COMMENT '产品名称',
  `purchase_amount` DECIMAL(32, 16) NOT NULL COMMENT '申购数量（金额）',
  `currency` VARCHAR(10) NOT NULL DEFAULT 'USD' COMMENT '货币类型',
  `daily_yield_rate` DECIMAL(8, 6) NOT NULL COMMENT '日产率',
  `daily_yield` DECIMAL(32, 16) NOT NULL COMMENT '预计日产（金额）',
  `total_yield` DECIMAL(32, 16) NOT NULL COMMENT '预计总收益',
  `term_days` INT NOT NULL COMMENT '理财期限（天数）',
  `penalty_rate` DECIMAL(8, 6) DEFAULT 0.3 COMMENT '违约赎回费率',
  `penalty_amount` DECIMAL(32, 16) DEFAULT 0 COMMENT '违约金金额',
  `status` VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT '状态：IN_PROGRESS进行中, COMPLETED已结束, REDEEMED已赎回',
  `purchase_time` DATETIME NOT NULL COMMENT '申购时间',
  `end_time` DATETIME COMMENT '结束时间',
  `redeem_time` DATETIME COMMENT '赎回时间',
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_product_id` (`product_id`),
  INDEX `idx_status` (`status`),
  INDEX `idx_purchase_time` (`purchase_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='理财订单表';




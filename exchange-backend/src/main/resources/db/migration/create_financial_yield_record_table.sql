-- 创建理财收益记录表
CREATE TABLE IF NOT EXISTS `financial_yield_record` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `order_id` BIGINT NOT NULL COMMENT '订单ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `product_id` BIGINT NOT NULL COMMENT '产品ID',
  `product_name` VARCHAR(100) NOT NULL COMMENT '产品名称',
  `yield_date` DATE NOT NULL COMMENT '收益日期',
  `daily_yield` DECIMAL(32, 16) NOT NULL COMMENT '当日收益',
  `cumulative_yield` DECIMAL(32, 16) NOT NULL COMMENT '累计收益',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING待发放, PAID已发放',
  `paid_at` DATETIME COMMENT '发放时间',
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  INDEX `idx_order_id` (`order_id`),
  INDEX `idx_user_id` (`user_id`),
  INDEX `idx_yield_date` (`yield_date`),
  INDEX `idx_status` (`status`),
  UNIQUE KEY `uk_order_date` (`order_id`, `yield_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='理财收益记录表';




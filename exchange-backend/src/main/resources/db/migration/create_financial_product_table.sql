-- 创建理财产品表
CREATE TABLE IF NOT EXISTS `financial_product` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(100) NOT NULL COMMENT '产品名称（如180MH/S）',
  `image_url` VARCHAR(500) COMMENT '产品图片',
  `currency` VARCHAR(10) NOT NULL DEFAULT 'USD' COMMENT '货币类型',
  `daily_yield_rate` DECIMAL(8, 6) NOT NULL COMMENT '预计日产率（百分比，如0.3表示0.3%）',
  `rental_fee` DECIMAL(32, 16) NOT NULL COMMENT '矿机租金',
  `min_purchase` DECIMAL(32, 16) NOT NULL COMMENT '最小申购金额',
  `max_purchase` DECIMAL(32, 16) NOT NULL COMMENT '最大申购金额',
  `term_days` INT NOT NULL COMMENT '理财期限（天数）',
  `penalty_rate` DECIMAL(8, 6) DEFAULT 0.3 COMMENT '违约赎回费率（百分比，如30表示30%）',
  `description` TEXT COMMENT '产品介绍',
  `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用',
  `sort_order` INT DEFAULT 0 COMMENT '排序',
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  INDEX `idx_enabled` (`enabled`),
  INDEX `idx_sort_order` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='理财产品表';




-- 创建贷款个人信息表
CREATE TABLE IF NOT EXISTS `loan_personal_info` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `real_name` VARCHAR(100) NOT NULL COMMENT '真实姓名',
  `id_number` VARCHAR(50) NOT NULL COMMENT '身份证号',
  `phone` VARCHAR(32) NOT NULL COMMENT '电话',
  `address` VARCHAR(500) NOT NULL COMMENT '家庭住址',
  `id_front_image` VARCHAR(500) COMMENT '身份证正面图片',
  `id_back_image` VARCHAR(500) COMMENT '身份证反面图片',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING待审核, APPROVED已通过, REJECTED已拒绝',
  `review_remark` VARCHAR(500) COMMENT '审核备注',
  `reviewed_by` BIGINT COMMENT '审核人ID',
  `reviewed_at` DATETIME COMMENT '审核时间',
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  INDEX `idx_status` (`status`),
  INDEX `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='贷款个人信息表';


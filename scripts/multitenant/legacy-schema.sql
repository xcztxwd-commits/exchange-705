
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `admin_menu` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `parent_id` bigint(20) DEFAULT '0',
  `menu_name` varchar(50) NOT NULL,
  `menu_code` varchar(50) NOT NULL,
  `menu_type` varchar(20) NOT NULL,
  `path` varchar(200) DEFAULT NULL,
  `icon` varchar(50) DEFAULT NULL,
  `sort_order` int(11) NOT NULL DEFAULT '0',
  `status` varchar(20) NOT NULL DEFAULT 'active',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `menu_code` (`menu_code`),
  KEY `idx_menu_code` (`menu_code`)
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `admin_role` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `role_name` varchar(50) NOT NULL,
  `role_code` varchar(50) NOT NULL,
  `description` varchar(200) DEFAULT NULL,
  `status` varchar(20) NOT NULL DEFAULT 'active',
  `is_super` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `role_name` (`role_name`),
  UNIQUE KEY `role_code` (`role_code`),
  KEY `idx_role_code` (`role_code`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `admin_role_menu` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `role_id` bigint(20) NOT NULL,
  `menu_id` bigint(20) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`,`menu_id`)
) ENGINE=InnoDB AUTO_INCREMENT=53 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `admin_user` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `account` varchar(64) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `email` varchar(128) NOT NULL,
  `enabled` bit(1) NOT NULL,
  `password_hash` varchar(128) NOT NULL,
  `role` varchar(32) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `role_id` bigint(20) DEFAULT NULL COMMENT '角色ID',
  `current_token` varchar(128) DEFAULT NULL,
  `row_version` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_lhgw84v0nlofhbm3frk55dg23` (`account`),
  UNIQUE KEY `UK_6etwowal6qxvr7xuvqcqmnnk7` (`email`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `announcement` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL COMMENT '公告标题',
  `content` text NOT NULL COMMENT '公告内容',
  `status` varchar(20) NOT NULL DEFAULT 'PUBLISHED' COMMENT '状态: PUBLISHED-已发布, DRAFT-草稿, HIDDEN-隐藏',
  `priority` int(11) NOT NULL DEFAULT '0' COMMENT '优先级，数字越大越优先显示',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `language` varchar(10) NOT NULL,
  `countdown_seconds` int(11) NOT NULL DEFAULT '2',
  PRIMARY KEY (`id`),
  KEY `idx_status_priority` (`status`,`priority`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_language_status` (`language`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=22 DEFAULT CHARSET=utf8mb4 COMMENT='公告表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_account` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `coin` varchar(16) NOT NULL,
  `available` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `frozen` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `row_version` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_coin` (`user_id`,`coin`),
  UNIQUE KEY `uk_asset_user_coin` (`user_id`,`coin`)
) ENGINE=InnoDB AUTO_INCREMENT=199 DEFAULT CHARSET=utf8mb4 COMMENT='用户资产账户';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_1d` (
  `user_id` bigint(20) NOT NULL,
  `basis_version` varchar(32) NOT NULL,
  `bucket_start` bigint(20) NOT NULL,
  `bucket_end` bigint(20) NOT NULL,
  `open_value` decimal(32,16) DEFAULT NULL,
  `high_value` decimal(32,16) DEFAULT NULL,
  `low_value` decimal(32,16) DEFAULT NULL,
  `close_value` decimal(32,16) DEFAULT NULL,
  `open_at` bigint(20) DEFAULT NULL,
  `high_at` bigint(20) DEFAULT NULL,
  `low_at` bigint(20) DEFAULT NULL,
  `close_at` bigint(20) DEFAULT NULL,
  `source_count` bigint(20) NOT NULL,
  `valid_sample_count` bigint(20) NOT NULL,
  `invalid_sample_count` bigint(20) NOT NULL,
  `expected_sample_count` bigint(20) NOT NULL,
  `finalized` tinyint(1) NOT NULL,
  `quality` varchar(24) NOT NULL,
  `source_through` bigint(20) NOT NULL,
  `updated_at` bigint(20) NOT NULL,
  PRIMARY KEY (`user_id`,`basis_version`,`bucket_start`),
  KEY `ix_equity_1d_batch` (`basis_version`,`bucket_start`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_1h` (
  `user_id` bigint(20) NOT NULL,
  `basis_version` varchar(32) NOT NULL,
  `bucket_start` bigint(20) NOT NULL,
  `bucket_end` bigint(20) NOT NULL,
  `open_value` decimal(32,16) DEFAULT NULL,
  `high_value` decimal(32,16) DEFAULT NULL,
  `low_value` decimal(32,16) DEFAULT NULL,
  `close_value` decimal(32,16) DEFAULT NULL,
  `open_at` bigint(20) DEFAULT NULL,
  `high_at` bigint(20) DEFAULT NULL,
  `low_at` bigint(20) DEFAULT NULL,
  `close_at` bigint(20) DEFAULT NULL,
  `source_count` bigint(20) NOT NULL,
  `valid_sample_count` bigint(20) NOT NULL,
  `invalid_sample_count` bigint(20) NOT NULL,
  `expected_sample_count` bigint(20) NOT NULL,
  `finalized` tinyint(1) NOT NULL,
  `quality` varchar(24) NOT NULL,
  `source_through` bigint(20) NOT NULL,
  `updated_at` bigint(20) NOT NULL,
  PRIMARY KEY (`user_id`,`basis_version`,`bucket_start`),
  KEY `ix_equity_1h_batch` (`basis_version`,`bucket_start`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_1m` (
  `user_id` bigint(20) NOT NULL,
  `basis_version` varchar(32) NOT NULL,
  `bucket_start` bigint(20) NOT NULL,
  `observed_at` bigint(20) DEFAULT NULL,
  `wallet_balance` decimal(32,16) DEFAULT NULL,
  `contract_unrealized_pnl` decimal(32,16) DEFAULT NULL,
  `option_unrealized_pnl` decimal(32,16) DEFAULT NULL,
  `receivables` decimal(32,16) DEFAULT NULL,
  `loan_principal` decimal(32,16) DEFAULT NULL,
  `accrued_interest` decimal(32,16) DEFAULT NULL,
  `overdue_fees` decimal(32,16) DEFAULT NULL,
  `accrued_trading_fees` decimal(32,16) DEFAULT NULL,
  `other_liabilities` decimal(32,16) DEFAULT NULL,
  `liabilities_total` decimal(32,16) DEFAULT NULL,
  `net_equity` decimal(32,16) DEFAULT NULL,
  `valuation_status` varchar(24) NOT NULL,
  `reason_code` varchar(1024) NOT NULL,
  `quote_batch_id` varchar(36) DEFAULT NULL,
  `valuation_evidence` longtext NOT NULL,
  `origin` varchar(24) NOT NULL DEFAULT 'OBSERVED',
  `created_at` bigint(20) NOT NULL,
  `manual_adjustment` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `effective_at` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`user_id`,`basis_version`,`bucket_start`),
  KEY `ix_equity_minute_batch` (`basis_version`,`bucket_start`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_4h` (
  `user_id` bigint(20) NOT NULL,
  `basis_version` varchar(32) NOT NULL,
  `bucket_start` bigint(20) NOT NULL,
  `bucket_end` bigint(20) NOT NULL,
  `open_value` decimal(32,16) DEFAULT NULL,
  `high_value` decimal(32,16) DEFAULT NULL,
  `low_value` decimal(32,16) DEFAULT NULL,
  `close_value` decimal(32,16) DEFAULT NULL,
  `open_at` bigint(20) DEFAULT NULL,
  `high_at` bigint(20) DEFAULT NULL,
  `low_at` bigint(20) DEFAULT NULL,
  `close_at` bigint(20) DEFAULT NULL,
  `source_count` bigint(20) NOT NULL,
  `valid_sample_count` bigint(20) NOT NULL,
  `invalid_sample_count` bigint(20) NOT NULL,
  `expected_sample_count` bigint(20) NOT NULL,
  `finalized` tinyint(1) NOT NULL,
  `quality` varchar(24) NOT NULL,
  `source_through` bigint(20) NOT NULL,
  `updated_at` bigint(20) NOT NULL,
  PRIMARY KEY (`user_id`,`basis_version`,`bucket_start`),
  KEY `ix_equity_4h_batch` (`basis_version`,`bucket_start`,`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_baseline` (
  `user_id` bigint(20) NOT NULL,
  `basis_version` varchar(32) NOT NULL,
  `capture_from` bigint(20) NOT NULL,
  `first_positive` decimal(32,16) DEFAULT NULL,
  `first_positive_at` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`user_id`,`basis_version`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_job_state` (
  `task_name` varchar(32) NOT NULL,
  `basis_version` varchar(32) NOT NULL,
  `watermark` bigint(20) NOT NULL,
  `user_cursor` bigint(20) NOT NULL,
  `success_at` bigint(20) DEFAULT NULL,
  `error` varchar(512) DEFAULT NULL,
  PRIMARY KEY (`task_name`,`basis_version`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_migration` (
  `migration_id` varchar(64) NOT NULL,
  `applied_at` bigint(20) NOT NULL,
  `basis_version` varchar(32) NOT NULL,
  PRIMARY KEY (`migration_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_quote_batch` (
  `batch_id` varchar(36) NOT NULL,
  `prepared_at` bigint(20) NOT NULL,
  `evidence` longtext NOT NULL,
  PRIMARY KEY (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_snapshot` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `captured_at` bigint(20) NOT NULL,
  `total` decimal(32,16) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_snapshot_user_time` (`user_id`,`captured_at`)
) ENGINE=InnoDB AUTO_INCREMENT=42797 DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `contract_order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `close_time` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `current_price` decimal(32,16) DEFAULT NULL,
  `fee` decimal(32,16) DEFAULT NULL,
  `margin` decimal(32,16) DEFAULT NULL,
  `open_price` decimal(32,16) DEFAULT NULL,
  `open_time` datetime(6) DEFAULT NULL,
  `price` decimal(32,16) DEFAULT NULL,
  `profit` decimal(32,16) DEFAULT NULL,
  `quantity` decimal(32,16) NOT NULL,
  `side` varchar(10) NOT NULL,
  `status` varchar(20) NOT NULL,
  `stop_loss` decimal(32,16) DEFAULT NULL,
  `symbol` varchar(32) NOT NULL,
  `take_profit` decimal(32,16) DEFAULT NULL,
  `type` varchar(10) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `close_price` decimal(32,16) DEFAULT NULL,
  `leverage` decimal(10,2) DEFAULT NULL,
  `limit_match_enabled` bit(1) NOT NULL,
  `lot_size` decimal(32,16) DEFAULT NULL,
  `row_version` bigint(20) NOT NULL,
  `margin_conversion_rate` decimal(32,16) DEFAULT NULL,
  `quote_currency` varchar(16) DEFAULT NULL,
  `quote_source` varchar(16) DEFAULT NULL,
  `settlement_conversion_rate` decimal(32,16) DEFAULT NULL,
  `order_source` varchar(24) NOT NULL DEFAULT 'USER',
  `manual_wallet_enabled` tinyint(1) NOT NULL DEFAULT '0',
  `manual_equity_enabled` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=87 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `deposit_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `address` varchar(200) NOT NULL,
  `amount` decimal(32,16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `network` varchar(50) NOT NULL,
  `proof_image` varchar(500) DEFAULT NULL,
  `remark` varchar(500) DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `type` varchar(20) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `row_version` bigint(20) NOT NULL,
  `currency` varchar(3) DEFAULT NULL,
  `exchange_rate` decimal(32,16) DEFAULT NULL,
  `original_amount` decimal(32,16) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `deposit_setting` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `type` varchar(20) NOT NULL DEFAULT 'digital' COMMENT '类型: digital 数字货币, bank 银行卡',
  `network` varchar(50) DEFAULT NULL COMMENT '网络/币种，如 USDC-ERC20, USDT-TRC20（银行卡时可为空）',
  `address` varchar(200) DEFAULT NULL COMMENT '充值地址（银行卡时可为空）',
  `bank_name` varchar(100) DEFAULT NULL COMMENT '开户银行',
  `bank_account` varchar(50) DEFAULT NULL COMMENT '银行卡号',
  `account_name` varchar(100) DEFAULT NULL COMMENT '户名',
  `qr_code` varchar(500) DEFAULT NULL COMMENT '二维码图片URL',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_network_type` (`network`,`type`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COMMENT='充值设置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `financial_order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `product_id` bigint(20) NOT NULL COMMENT '产品ID',
  `product_name` varchar(100) NOT NULL COMMENT '产品名称',
  `purchase_amount` decimal(32,16) NOT NULL COMMENT '申购数量（金额）',
  `currency` varchar(10) NOT NULL DEFAULT 'USD' COMMENT '货币类型',
  `daily_yield_rate` decimal(8,6) NOT NULL COMMENT '日产率',
  `daily_yield` decimal(32,16) NOT NULL COMMENT '预计日产（金额）',
  `total_yield` decimal(32,16) NOT NULL COMMENT '预计总收益',
  `term_days` int(11) NOT NULL COMMENT '理财期限（天数）',
  `penalty_rate` decimal(8,6) DEFAULT '0.300000' COMMENT '违约赎回费率',
  `penalty_amount` decimal(32,16) DEFAULT '0.0000000000000000' COMMENT '违约金金额',
  `status` varchar(20) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT '状态：IN_PROGRESS进行中, COMPLETED已结束, REDEEMED已赎回',
  `purchase_time` datetime NOT NULL COMMENT '申购时间',
  `end_time` datetime DEFAULT NULL COMMENT '结束时间',
  `redeem_time` datetime DEFAULT NULL COMMENT '赎回时间',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `row_version` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_product_id` (`product_id`),
  KEY `idx_status` (`status`),
  KEY `idx_purchase_time` (`purchase_time`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COMMENT='理财订单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `financial_product` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `name` varchar(100) NOT NULL COMMENT '产品名称（如180MH/S）',
  `image_url` varchar(500) DEFAULT NULL COMMENT '产品图片',
  `currency` varchar(10) NOT NULL DEFAULT 'USD' COMMENT '货币类型',
  `daily_yield_rate` decimal(8,6) NOT NULL COMMENT '预计日产率（百分比，如0.3表示0.3%）',
  `rental_fee` decimal(32,16) NOT NULL COMMENT '矿机租金',
  `min_purchase` decimal(32,16) NOT NULL COMMENT '最小申购金额',
  `max_purchase` decimal(32,16) NOT NULL COMMENT '最大申购金额',
  `term_days` int(11) NOT NULL COMMENT '理财期限（天数）',
  `penalty_rate` decimal(8,6) DEFAULT '0.300000' COMMENT '违约赎回费率（百分比，如30表示30%）',
  `description` text COMMENT '产品介绍',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `sort_order` int(11) DEFAULT '0' COMMENT '排序',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_enabled` (`enabled`),
  KEY `idx_sort_order` (`sort_order`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COMMENT='理财产品表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `financial_yield_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `cumulative_yield` decimal(32,16) NOT NULL,
  `daily_yield` decimal(32,16) NOT NULL,
  `order_id` bigint(20) NOT NULL,
  `paid_at` datetime(6) DEFAULT NULL,
  `product_id` bigint(20) NOT NULL,
  `product_name` varchar(100) NOT NULL,
  `status` varchar(20) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `yield_date` date NOT NULL,
  `row_version` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK9dll9ic2smexlc46rjowsrvdy` (`order_id`,`yield_date`)
) ENGINE=InnoDB AUTO_INCREMENT=24 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `kyc_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `real_name` varchar(100) NOT NULL COMMENT '真实姓名',
  `id_number` varchar(50) NOT NULL COMMENT '证件号',
  `id_front_image` varchar(500) DEFAULT NULL COMMENT '证件正面图片',
  `id_back_image` varchar(500) DEFAULT NULL COMMENT '证件反面图片',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING-待审核, APPROVED-已通过, REJECTED-已拒绝',
  `review_remark` varchar(500) DEFAULT NULL COMMENT '审核备注',
  `reviewed_by` bigint(20) DEFAULT NULL COMMENT '审核人ID',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `updated_at` datetime NOT NULL COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COMMENT='实名认证记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `loan_personal_info` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `real_name` varchar(100) NOT NULL COMMENT '真实姓名',
  `id_number` varchar(50) NOT NULL COMMENT '身份证号',
  `phone` varchar(32) NOT NULL COMMENT '电话',
  `address` varchar(500) NOT NULL COMMENT '家庭住址',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING待审核, APPROVED已通过, REJECTED已拒绝',
  `review_remark` varchar(500) DEFAULT NULL COMMENT '审核备注',
  `reviewed_by` bigint(20) DEFAULT NULL COMMENT '审核人ID',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `id_back_image` varchar(500) DEFAULT NULL,
  `handheld_image` varchar(500) DEFAULT NULL COMMENT '手持身份证图片',
  `id_front_image` varchar(500) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COMMENT='贷款个人信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `loan_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `actual_repayment_at` datetime(6) DEFAULT NULL,
  `amount` decimal(32,16) NOT NULL,
  `approved_at` datetime(6) DEFAULT NULL,
  `contract_signed` bit(1) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `daily_rate` decimal(8,6) NOT NULL,
  `days` int(11) NOT NULL,
  `free_days` int(11) NOT NULL,
  `overdue_fee` decimal(32,16) DEFAULT NULL,
  `overdue_rate` decimal(8,6) DEFAULT NULL,
  `remark` varchar(500) DEFAULT NULL,
  `repayment_amount` decimal(32,16) NOT NULL,
  `repayment_date` datetime(6) DEFAULT NULL,
  `signature_image` varchar(500) DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `total_interest` decimal(32,16) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `address` varchar(500) DEFAULT NULL,
  `id_number` varchar(50) DEFAULT NULL,
  `phone` varchar(32) DEFAULT NULL,
  `real_name` varchar(100) DEFAULT NULL,
  `row_version` bigint(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `loan_setting` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) NOT NULL,
  `daily_rate` decimal(8,6) NOT NULL,
  `days` int(11) NOT NULL,
  `enabled` bit(1) NOT NULL,
  `free_days` int(11) NOT NULL,
  `max_amount` decimal(32,16) DEFAULT NULL,
  `min_amount` decimal(32,16) DEFAULT NULL,
  `overdue_rate` decimal(8,6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `manual_order_record` (
  `idempotency_key` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `request_hash` char(64) CHARACTER SET ascii NOT NULL,
  `order_id` bigint(20) NOT NULL,
  `operator_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `created_at` bigint(20) NOT NULL,
  `timezone` varchar(64) NOT NULL,
  `evidence` longtext NOT NULL,
  PRIMARY KEY (`idempotency_key`),
  UNIQUE KEY `uk_manual_order` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_flow` (
  `task_id` varchar(36) NOT NULL,
  `options_json` text NOT NULL,
  `state` varchar(24) NOT NULL,
  `recovery_started_at` bigint(20) DEFAULT NULL,
  `remaining_millis` bigint(20) DEFAULT NULL,
  `recovery_offset` decimal(32,16) DEFAULT NULL,
  `last_price` decimal(32,16) NOT NULL,
  `last_at` bigint(20) NOT NULL,
  `finished_at` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_hold` (
  `task_id` varchar(36) NOT NULL,
  `reference_price` decimal(32,16) NOT NULL,
  `reference_time` bigint(20) NOT NULL,
  `offset_price` decimal(32,16) DEFAULT NULL,
  `activated_at` bigint(20) DEFAULT NULL,
  `released_at` bigint(20) DEFAULT NULL,
  `last_price` decimal(32,16) NOT NULL,
  `generated_at` bigint(20) NOT NULL,
  `source_time` bigint(20) NOT NULL,
  PRIMARY KEY (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_publication` (
  `task_id` varchar(36) NOT NULL,
  `published_at` bigint(20) NOT NULL,
  `from_at` bigint(20) NOT NULL,
  `to_at` bigint(20) NOT NULL,
  PRIMARY KEY (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_resume` (
  `task_id` varchar(36) NOT NULL,
  `resumed_at` bigint(20) NOT NULL,
  `source_time` bigint(20) NOT NULL,
  `price` decimal(32,16) NOT NULL,
  PRIMARY KEY (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_sample` (
  `task_id` varchar(36) NOT NULL,
  `generated_at` bigint(20) NOT NULL,
  `price` decimal(32,16) NOT NULL,
  PRIMARY KEY (`task_id`,`generated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_task` (
  `id` varchar(36) NOT NULL,
  `symbol_id` bigint(20) NOT NULL,
  `symbol` varchar(32) NOT NULL,
  `algorithm_version` int(11) NOT NULL,
  `kind` varchar(16) NOT NULL,
  `status` varchar(16) NOT NULL,
  `start_price` decimal(32,16) NOT NULL,
  `target_price` decimal(32,16) NOT NULL,
  `duration_seconds` int(11) NOT NULL,
  `intensity` int(11) NOT NULL,
  `oscillation` tinyint(1) NOT NULL,
  `price_precision` int(11) NOT NULL,
  `start_source` varchar(32) NOT NULL,
  `source_time` bigint(20) NOT NULL,
  `started_at` bigint(20) NOT NULL,
  `planned_end` bigint(20) NOT NULL,
  `ended_at` bigint(20) DEFAULT NULL,
  `sampled_until` bigint(20) NOT NULL,
  `request_key` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `control_request` (`symbol_id`,`request_key`),
  KEY `control_symbol` (`symbol_id`,`started_at`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_legacy_minute_snapshot` (
  `symbol_id` bigint(20) NOT NULL,
  `minute_at` bigint(20) NOT NULL,
  `body` text NOT NULL,
  `last_event` bigint(20) NOT NULL,
  PRIMARY KEY (`symbol_id`,`minute_at`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_mixed_minute` (
  `symbol_id` bigint(20) NOT NULL,
  `minute_at` bigint(20) NOT NULL,
  `body` text NOT NULL,
  `last_event` bigint(20) NOT NULL,
  PRIMARY KEY (`symbol_id`,`minute_at`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_simulation_source_candle` (
  `symbol_id` bigint(20) NOT NULL,
  `session_at` bigint(20) NOT NULL,
  `period` varchar(8) NOT NULL,
  `candle_at` bigint(20) NOT NULL,
  `body` text NOT NULL,
  PRIMARY KEY (`symbol_id`,`session_at`,`period`,`candle_at`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_source_candle` (
  `symbol_id` bigint(20) NOT NULL,
  `period` varchar(8) NOT NULL,
  `candle_at` bigint(20) NOT NULL,
  `body` text NOT NULL,
  `received_at` bigint(20) NOT NULL,
  PRIMARY KEY (`symbol_id`,`period`,`candle_at`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_source_event` (
  `event_sequence` bigint(20) NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) NOT NULL,
  `symbol_id` bigint(20) NOT NULL,
  `source_time` bigint(20) NOT NULL,
  `received_at` bigint(20) NOT NULL,
  `price` decimal(32,16) NOT NULL,
  PRIMARY KEY (`event_sequence`),
  UNIQUE KEY `source_event_identity` (`symbol_id`,`event_id`),
  KEY `source_event_time` (`symbol_id`,`source_time`,`received_at`)
) ENGINE=InnoDB AUTO_INCREMENT=1055249 DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_source_quote` (
  `symbol_id` bigint(20) NOT NULL,
  `price` decimal(32,16) NOT NULL,
  `source_time` bigint(20) NOT NULL,
  PRIMARY KEY (`symbol_id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_source_tick` (
  `symbol_id` bigint(20) NOT NULL,
  `source_time` bigint(20) NOT NULL,
  `received_at` bigint(20) NOT NULL,
  `price` decimal(32,16) NOT NULL,
  PRIMARY KEY (`symbol_id`,`source_time`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `menu_action` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID',
  `action_code` varchar(100) NOT NULL COMMENT '操作代码（如：reset_password, freeze_user等）',
  `action_name` varchar(100) NOT NULL COMMENT '操作名称（如：重置密码、冻结用户等）',
  `sort_order` int(11) NOT NULL DEFAULT '0' COMMENT '排序',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_menu_action` (`menu_id`,`action_code`),
  KEY `idx_menu_id` (`menu_id`),
  CONSTRAINT `menu_action_ibfk_1` FOREIGN KEY (`menu_id`) REFERENCES `admin_menu` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单操作映射表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `operation_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `admin_id` bigint(20) NOT NULL COMMENT '管理员ID',
  `admin_email` varchar(100) DEFAULT NULL COMMENT '管理员邮箱',
  `operation_type` varchar(50) NOT NULL COMMENT '操作类型（如：用户管理、订单管理、充值审核等）',
  `operation_action` varchar(100) NOT NULL COMMENT '操作动作（如：重置密码、审核通过、删除等）',
  `target_type` varchar(50) DEFAULT NULL COMMENT '目标类型（如：用户、订单、充值记录等）',
  `target_id` bigint(20) DEFAULT NULL COMMENT '目标ID',
  `target_info` varchar(500) DEFAULT NULL COMMENT '目标信息（如：用户邮箱、订单号等）',
  `request_method` varchar(10) DEFAULT NULL COMMENT '请求方法（GET、POST、PUT、DELETE）',
  `request_url` varchar(500) DEFAULT NULL COMMENT '请求URL',
  `request_params` text COMMENT '请求参数（JSON格式）',
  `ip_address` varchar(50) DEFAULT NULL COMMENT 'IP地址',
  `user_agent` varchar(500) DEFAULT NULL COMMENT '用户代理',
  `status` varchar(20) DEFAULT 'SUCCESS' COMMENT '操作状态（SUCCESS、FAILED）',
  `error_message` text COMMENT '错误信息',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_admin_id` (`admin_id`),
  KEY `idx_operation_type` (`operation_type`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_target_type_id` (`target_type`,`target_id`)
) ENGINE=InnoDB AUTO_INCREMENT=573 DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `option_duration` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
  `duration` int(11) NOT NULL COMMENT '时长（秒）',
  `label` varchar(20) NOT NULL COMMENT '显示标签，如 30s, 60s',
  `sort_order` int(11) NOT NULL DEFAULT '0' COMMENT '排序顺序',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `profit_rate` decimal(5,4) NOT NULL DEFAULT '0.8000' COMMENT '盈亏比例（如 0.8 表示 80%）',
  `loss_rate` decimal(5,4) NOT NULL DEFAULT '1.0000' COMMENT '亏损比例（如 1.0 表示 100%，全部亏损）',
  `max_amount` decimal(18,2) DEFAULT NULL,
  `min_amount` decimal(18,2) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_duration` (`duration`),
  KEY `idx_enabled` (`enabled`),
  KEY `idx_sort_order` (`sort_order`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COMMENT='期限设置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `option_order` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `amount` decimal(32,16) NOT NULL,
  `close_price` decimal(32,16) DEFAULT NULL,
  `close_time` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `direction` varchar(10) NOT NULL,
  `duration` int(11) DEFAULT NULL,
  `profit_rate` decimal(5,4) DEFAULT NULL COMMENT '下单时的盈利比例',
  `loss_rate` decimal(5,4) DEFAULT NULL COMMENT '下单时的亏损比例',
  `open_price` decimal(32,16) DEFAULT NULL,
  `open_time` datetime(6) DEFAULT NULL,
  `profit` decimal(32,16) DEFAULT NULL,
  `status` varchar(20) NOT NULL,
  `symbol` varchar(32) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `preset_profit_type` varchar(10) DEFAULT NULL,
  `row_version` bigint(20) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `symbol_duration` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `symbol` varchar(32) NOT NULL COMMENT '交易对',
  `duration` int(11) NOT NULL COMMENT '时长（秒）',
  `label` varchar(20) NOT NULL COMMENT '显示标签',
  `profit_rate` decimal(5,4) NOT NULL DEFAULT '0.8000' COMMENT '盈利比例',
  `loss_rate` decimal(5,4) NOT NULL DEFAULT '1.0000' COMMENT '亏损比例',
  `min_amount` decimal(18,2) DEFAULT '1.00' COMMENT '最低购买金额',
  `max_amount` decimal(18,2) DEFAULT '10000.00' COMMENT '最大购买金额',
  `sort_order` int(11) NOT NULL DEFAULT '0' COMMENT '排序顺序',
  `enabled` tinyint(1) NOT NULL DEFAULT '1' COMMENT '是否启用',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_symbol_duration` (`symbol`,`duration`),
  KEY `idx_symbol` (`symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品期限配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `system_config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `config_key` varchar(100) NOT NULL,
  `config_value` text,
  `created_at` datetime(6) NOT NULL,
  `description` varchar(200) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_npsxm1erd0lbetjn5d3ayrsof` (`config_key`)
) ENGINE=InnoDB AUTO_INCREMENT=31 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `t_ai_model` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '模型ID',
  `model_name` varchar(100) DEFAULT '' COMMENT '模型名称',
  `base_url` varchar(500) DEFAULT '' COMMENT 'API基础URL',
  `model` varchar(100) DEFAULT '' COMMENT '模型标识',
  `api_key` varchar(200) DEFAULT '' COMMENT 'API密钥',
  `flag` int(1) DEFAULT '0' COMMENT '当前选中标记 0-未选中 1-已选中',
  `status` int(1) DEFAULT '1' COMMENT '状态 0-禁用 1-启用',
  `sort_order` int(4) DEFAULT '0' COMMENT '排序',
  `create_by` varchar(64) DEFAULT '' COMMENT '创建者',
  `create_time` datetime DEFAULT NULL COMMENT '创建时间',
  `update_by` varchar(64) DEFAULT '' COMMENT '更新者',
  `update_time` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COMMENT='AI模型配置';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `trading_symbol` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `symbol` varchar(32) NOT NULL COMMENT '交易对符号，如 BTCUSD, XAUUSD',
  `base_currency` varchar(16) NOT NULL COMMENT '基础货币，如 BTC, XAU',
  `quote_currency` varchar(16) NOT NULL DEFAULT 'USD' COMMENT '计价货币，如 USD',
  `name` varchar(64) NOT NULL COMMENT '显示名称，如 比特币/美元',
  `name_en` varchar(64) DEFAULT NULL COMMENT '英文名称',
  `category` varchar(32) NOT NULL DEFAULT 'US' COMMENT '分类：US, Crypto, Metal, Forex, CFD',
  `icon_url` varchar(255) DEFAULT NULL COMMENT '图标URL',
  `flag_url` varchar(255) DEFAULT NULL COMMENT '国旗图标URL',
  `is_hot` tinyint(4) NOT NULL DEFAULT '0' COMMENT '是否热门：0否 1是',
  `is_enabled` tinyint(4) NOT NULL DEFAULT '1' COMMENT '是否启用：0否 1是',
  `sort_order` int(11) NOT NULL DEFAULT '0' COMMENT '排序权重，数字越大越靠前',
  `price_precision` int(11) NOT NULL DEFAULT '2' COMMENT '价格精度（小数位数）',
  `volume_precision` int(11) NOT NULL DEFAULT '2' COMMENT '数量精度（小数位数）',
  `min_trade_amount` decimal(32,16) DEFAULT '0.0000000000000000' COMMENT '最小交易数量',
  `alltick_symbol` varchar(64) DEFAULT NULL COMMENT 'Alltick API中的symbol，用于订阅行情',
  `current_price` decimal(32,16) DEFAULT '0.0000000000000000' COMMENT '当前价格（缓存）',
  `price_change_24h` decimal(32,16) DEFAULT '0.0000000000000000' COMMENT '24小时涨跌额',
  `price_change_pct_24h` decimal(10,4) DEFAULT '0.0000' COMMENT '24小时涨跌幅（百分比）',
  `sparkline_data` text COMMENT 'K线数据（JSON数组，用于展示小图）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `fee_multiplier` decimal(32,16) DEFAULT NULL,
  `lot_size` decimal(32,16) DEFAULT NULL,
  `control_enabled` bit(1) NOT NULL,
  `control_price_offset` decimal(32,16) DEFAULT NULL,
  `leverage` decimal(10,2) DEFAULT NULL,
  `control_completed_at` bigint(20) DEFAULT NULL,
  `control_duration_seconds` int(11) DEFAULT NULL,
  `control_intensity` int(11) DEFAULT NULL,
  `control_random_oscillation` bit(1) DEFAULT NULL,
  `control_restoring` bit(1) DEFAULT NULL,
  `control_start_price` decimal(32,16) DEFAULT NULL,
  `control_started_at` bigint(20) DEFAULT NULL,
  `control_target_price` decimal(32,16) DEFAULT NULL,
  `max_leverage` decimal(10,2) DEFAULT NULL,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  `random_market_base_price` decimal(32,16) DEFAULT NULL,
  `random_market_enabled` bit(1) DEFAULT NULL,
  `random_market_started_at` bigint(20) DEFAULT NULL,
  `random_market_controls` longtext,
  `market_instrument_key` varchar(128) DEFAULT NULL,
  `market_source` varchar(16) NOT NULL,
  `source_category` varchar(32) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_symbol` (`symbol`),
  UNIQUE KEY `UK_s7aam1xh6nnv6ghru10f6vnuh` (`market_instrument_key`),
  KEY `idx_category` (`category`),
  KEY `idx_is_hot` (`is_hot`),
  KEY `idx_is_enabled` (`is_enabled`),
  KEY `idx_sort_order` (`sort_order`)
) ENGINE=InnoDB AUTO_INCREMENT=75 DEFAULT CHARSET=utf8mb4 COMMENT='交易对/币种表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `transfer_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `from_account` varchar(32) NOT NULL COMMENT '转出账户: FUND-资金账户, CONTRACT-合约账户, OPTION-期权账户',
  `to_account` varchar(32) NOT NULL COMMENT '转入账户: FUND-资金账户, CONTRACT-合约账户, OPTION-期权账户',
  `amount` decimal(32,16) NOT NULL COMMENT '划转金额',
  `created_at` datetime NOT NULL COMMENT '创建时间',
  `request_id` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_transfer_request` (`user_id`,`request_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=62 DEFAULT CHARSET=utf8mb4 COMMENT='划转记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_account` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `email` varchar(128) NOT NULL,
  `country_code` varchar(32) DEFAULT NULL,
  `phone` varchar(32) DEFAULT NULL,
  `password_hash` varchar(128) NOT NULL,
  `nickname` varchar(50) DEFAULT NULL,
  `invite_code` varchar(32) DEFAULT NULL,
  `my_invite_code` varchar(32) DEFAULT NULL COMMENT '用户自己的邀请码（用于邀请别人）',
  `parent_user_id` bigint(20) DEFAULT NULL COMMENT '上级用户ID',
  `status` varchar(20) NOT NULL DEFAULT 'normal',
  `user_type` varchar(20) DEFAULT 'normal' COMMENT '用户类型：normal-普通用户, agent-代理',
  `kyc_level` int(11) DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `kyc_status` varchar(20) DEFAULT NULL,
  `last_login_at` datetime(6) DEFAULT NULL,
  `last_login_ip` varchar(64) DEFAULT NULL,
  `last_login_region` varchar(128) DEFAULT NULL,
  `last_login_domain` varchar(255) DEFAULT NULL,
  `current_token` varchar(128) DEFAULT NULL COMMENT '当前有效的登录token标识（用于单设备登录）',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注（用于代理管理）',
  `last_activity_at` datetime(6) DEFAULT NULL,
  `row_version` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `email` (`email`),
  UNIQUE KEY `phone` (`phone`),
  UNIQUE KEY `my_invite_code` (`my_invite_code`),
  KEY `idx_parent_user_id` (`parent_user_id`),
  KEY `idx_user_type` (`user_type`)
) ENGINE=InnoDB AUTO_INCREMENT=7000021 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_action` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID（代理ID）',
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID',
  `action_code` varchar(100) NOT NULL COMMENT '操作代码',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_menu_action` (`user_id`,`menu_id`,`action_code`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_menu_id` (`menu_id`),
  CONSTRAINT `user_action_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`) ON DELETE CASCADE,
  CONSTRAINT `user_action_ibfk_2` FOREIGN KEY (`menu_id`) REFERENCES `admin_menu` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户操作权限表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_bank_card` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `currency` varchar(10) NOT NULL COMMENT '货币代码，如 USD, EUR, GBP',
  `bank_name` varchar(100) NOT NULL COMMENT '银行名称',
  `bank_address` varchar(200) DEFAULT NULL COMMENT '银行地址',
  `swift` varchar(50) DEFAULT NULL COMMENT 'SWIFT代码',
  `recipient_name` varchar(100) NOT NULL COMMENT '收款人姓名',
  `recipient_account` varchar(100) NOT NULL COMMENT '收款人账户',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COMMENT='用户银行卡绑定表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_digital_address` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `currency` varchar(20) NOT NULL COMMENT '货币代码，如 BTC, ETH, USDT',
  `network` varchar(50) NOT NULL COMMENT '网络，如 BTC, ETH, ERC20, TRC20',
  `address` varchar(200) NOT NULL COMMENT '钱包地址',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_currency_network` (`currency`,`network`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb4 COMMENT='用户数字货币地址绑定表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_menu` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID（user_account表的id）',
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID（admin_menu表的id）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_menu` (`user_id`,`menu_id`),
  UNIQUE KEY `UKr3a3ma3v059ubr7pchn83hcl5` (`user_id`,`menu_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_menu_id` (`menu_id`)
) ENGINE=InnoDB AUTO_INCREMENT=309 DEFAULT CHARSET=utf8mb4 COMMENT='用户菜单关联表（代理用户权限）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `verify_code` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `email` varchar(128) NOT NULL,
  `scene` varchar(32) NOT NULL COMMENT 'register/login/forget_password',
  `code` varchar(16) NOT NULL,
  `expire_at` datetime NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `failed_attempts` int(11) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_email_scene` (`email`,`scene`)
) ENGINE=InnoDB AUTO_INCREMENT=44 DEFAULT CHARSET=utf8mb4 COMMENT='邮箱验证码记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `withdraw_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID',
  `type` varchar(20) NOT NULL COMMENT '类型: digital 数字货币, bank 银行卡',
  `network` varchar(50) NOT NULL COMMENT '网络/币种 (如 USDT-TRC20, USD)',
  `amount` decimal(32,16) NOT NULL COMMENT '提现金额',
  `actual_amount` decimal(32,16) DEFAULT NULL COMMENT '实际到账金额（扣除手续费后）',
  `fee` decimal(32,16) DEFAULT '0.0000000000000000' COMMENT '手续费',
  `address` varchar(200) NOT NULL COMMENT '提币地址/收款账户',
  `remark` varchar(500) DEFAULT NULL COMMENT '备注',
  `country` varchar(100) DEFAULT NULL COMMENT '国家',
  `full_name` varchar(100) DEFAULT NULL COMMENT '姓名',
  `id_number` varchar(100) DEFAULT NULL COMMENT '证件号码',
  `bank_card_number` varchar(50) DEFAULT NULL COMMENT '银行卡号',
  `bank_name` varchar(100) DEFAULT NULL COMMENT '银行名称',
  `swift_code` varchar(50) DEFAULT NULL COMMENT 'SWIFT代码',
  `status` varchar(20) NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING 待审核, APPROVED 已通过, REJECTED 已驳回, COMPLETED 已完成',
  `review_remark` varchar(500) DEFAULT NULL COMMENT '审核备注',
  `reviewed_at` datetime DEFAULT NULL COMMENT '审核时间',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  `row_version` bigint(20) NOT NULL,
  `currency` varchar(3) DEFAULT NULL,
  `exchange_rate` decimal(32,16) DEFAULT NULL,
  `original_amount` decimal(32,16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_status_type` (`status`,`type`),
  KEY `idx_bank_card_number` (`bank_card_number`),
  KEY `idx_country` (`country`)
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COMMENT='提现记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


-- Exchange 705 structure-only snapshot 2026-10-07; schema epoch 2026100702.
-- No business data, migration receipts or activation approval.


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `activity_campaign` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  `name` varchar(120) NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'DRAFT',
  `template` bit(1) NOT NULL DEFAULT b'0',
  `auto_popup` bit(1) NOT NULL DEFAULT b'1',
  `animation` varchar(16) NOT NULL DEFAULT 'GIFT',
  `default_locale` varchar(16) NOT NULL DEFAULT 'zh-CN',
  `translations` longtext NOT NULL,
  `amount` decimal(32,16) NOT NULL,
  `recent_login_days` int(11) NOT NULL DEFAULT '3',
  `max_claims` int(11) NOT NULL DEFAULT '1000',
  `claim_count` int(11) NOT NULL DEFAULT '0',
  `budget` decimal(32,16) NOT NULL,
  `granted` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `starts_at` datetime(6) DEFAULT NULL,
  `ends_at` datetime(6) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  `auto_send_enabled` bit(1) NOT NULL DEFAULT b'0',
  `repeat_unread` bit(1) NOT NULL DEFAULT b'0',
  `deleted` bit(1) NOT NULL DEFAULT b'0',
  `layout_json` longtext,
  `allow_repeat_send` tinyint(1) NOT NULL DEFAULT '0',
  `allow_repeat_claim` tinyint(1) NOT NULL DEFAULT '0',
  `claim_validity_days` int(11) DEFAULT NULL,
  `positions` varchar(500) NOT NULL DEFAULT '["AUTH_HOME"]',
  `trigger_conditions` varchar(500) NOT NULL DEFAULT '[]',
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `mt_t_activity_campaign` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_activity_campaign` BEFORE UPDATE ON `activity_campaign` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `activity_delivery` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  `campaign_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `received_at` datetime(6) DEFAULT NULL,
  `opened_at` datetime(6) DEFAULT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `claimed_at` datetime(6) DEFAULT NULL,
  `open_count` int(11) NOT NULL DEFAULT '0',
  `close_count` int(11) NOT NULL DEFAULT '0',
  `sent_by` varchar(120) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_activity_recipient` (`tenant_id`,`campaign_id`,`user_id`),
  KEY `ix_activity_user` (`user_id`,`id`),
  KEY `mt_old_403fd7ee66e0b7b6` (`campaign_id`,`user_id`),
  KEY `mt_fk_f9e675144f6bbfb5e8d7` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_b8d81c4d7a659753b377` FOREIGN KEY (`tenant_id`, `campaign_id`) REFERENCES `activity_campaign` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_f9e675144f6bbfb5e8d7` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_activity_delivery` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_activity_delivery` BEFORE UPDATE ON `activity_delivery` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `activity_material` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `name` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `nodes_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_material_owner` (`tenant_id`,`id`),
  KEY `idx_activity_material_tenant` (`tenant_id`,`deleted`,`id`),
  CONSTRAINT `mt_activity_material_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_immutable_activity_material BEFORE UPDATE ON activity_material FOR EACH ROW
 BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `activity_selection` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  `campaign_id` bigint(20) NOT NULL,
  `operation_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `filter_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `send_operation_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `selected_count` bigint(20) NOT NULL DEFAULT '0',
  `sent_count` bigint(20) NOT NULL DEFAULT '0',
  `duplicate_count` bigint(20) NOT NULL DEFAULT '0',
  `ineligible_count` bigint(20) NOT NULL DEFAULT '0',
  `cursor_id` bigint(20) NOT NULL DEFAULT '0',
  `done` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_selection_operation` (`tenant_id`,`campaign_id`,`operation_id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  CONSTRAINT `mt_fk_bbb5482525c1996ff492` FOREIGN KEY (`tenant_id`, `campaign_id`) REFERENCES `activity_campaign` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_activity_selection` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_immutable_activity_selection BEFORE UPDATE ON activity_selection FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `activity_selection_member` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `selection_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_selection_member` (`tenant_id`,`selection_id`,`user_id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `ix_activity_selection_cursor` (`tenant_id`,`selection_id`,`id`),
  KEY `mt_fk_5c28fad9c98f685c73db` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_174160d80e3d9f005d5c` FOREIGN KEY (`tenant_id`, `selection_id`) REFERENCES `activity_selection` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_5c28fad9c98f685c73db` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_activity_selection_member` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_immutable_activity_selection_member BEFORE UPDATE ON activity_selection_member FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `activity_send_receipt` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `campaign_id` bigint(20) NOT NULL,
  `operation_id` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `result_json` varchar(1000) COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_activity_send_operation` (`tenant_id`,`campaign_id`,`operation_id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  CONSTRAINT `mt_fk_5ed26ac6d6ccbc4afa73` FOREIGN KEY (`tenant_id`, `campaign_id`) REFERENCES `activity_campaign` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_activity_send_receipt` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_immutable_activity_send_receipt BEFORE UPDATE ON activity_send_receipt FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `admin_menu` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `parent_id` bigint(20) DEFAULT '0',
  `menu_name` varchar(50) NOT NULL,
  `menu_code` varchar(150) NOT NULL,
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `role_code` (`tenant_id`,`role_code`),
  UNIQUE KEY `role_name` (`tenant_id`,`role_name`),
  KEY `idx_role_code` (`role_code`),
  KEY `mt_old_29df60163fb74096` (`role_code`),
  KEY `mt_old_2fb3eb1546582849` (`role_name`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `mt_t_admin_role` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_admin_role` BEFORE UPDATE ON `admin_role` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `admin_role_menu` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `role_id` bigint(20) NOT NULL,
  `menu_id` bigint(20) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_role_menu` (`tenant_id`,`role_id`,`menu_id`),
  KEY `mt_old_e65f3be18f0bdc05` (`role_id`,`menu_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_fk_9c50ba4eb3e07fd0c1e0` FOREIGN KEY (`tenant_id`, `role_id`) REFERENCES `admin_role` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_admin_role_menu` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_admin_role_menu` BEFORE UPDATE ON `admin_role_menu` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `admin_table_preference` (
  `id` varchar(200) COLLATE utf8mb4_unicode_ci NOT NULL,
  `columns_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  CONSTRAINT `mt_t_admin_table_preference` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_admin_table_preference` BEFORE UPDATE ON `admin_table_preference` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  `must_change_password` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_lhgw84v0nlofhbm3frk55dg23` (`account`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `UK_6etwowal6qxvr7xuvqcqmnnk7` (`tenant_id`,`email`),
  KEY `idx_role_id` (`role_id`),
  KEY `mt_old_38909fe62d26ff3b` (`email`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_fk_e95a17001bc7ce96ff03` (`tenant_id`,`role_id`),
  CONSTRAINT `mt_fk_e95a17001bc7ce96ff03` FOREIGN KEY (`tenant_id`, `role_id`) REFERENCES `admin_role` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_admin_user` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_admin_user` BEFORE UPDATE ON `admin_user` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  `display_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `idx_status_priority` (`status`,`priority`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_language_status` (`language`,`status`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `mt_t_announcement` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公告表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_announcement` BEFORE UPDATE ON `announcement` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `announcement_receipt` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `announcement_id` bigint(20) NOT NULL,
  `read_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_announcement_receipt` (`tenant_id`,`user_id`,`announcement_id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `mt_fk_dc84e06f52118a640e96` (`tenant_id`,`announcement_id`),
  CONSTRAINT `mt_fk_48e02e6c67145c4b33fe` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_dc84e06f52118a640e96` FOREIGN KEY (`tenant_id`, `announcement_id`) REFERENCES `announcement` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_announcement_receipt` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_immutable_announcement_receipt BEFORE UPDATE ON announcement_receipt FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_account` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `coin` varchar(32) NOT NULL,
  `available` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `frozen` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `row_version` bigint(20) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_asset_user_coin` (`tenant_id`,`user_id`,`coin`),
  UNIQUE KEY `uk_user_coin` (`tenant_id`,`user_id`,`coin`),
  KEY `mt_old_b80a8c17543f2ff8` (`user_id`,`coin`),
  KEY `mt_old_1f918f4c60f0b206` (`user_id`,`coin`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_fk_baa188f189f51ddbaf02` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_asset_account` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户资产账户';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_asset_account` BEFORE UPDATE ON `asset_account` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`user_id`,`basis_version`,`bucket_start`),
  KEY `ix_equity_1d_batch` (`basis_version`,`bucket_start`,`user_id`),
  CONSTRAINT `mt_fk_6a23f3c6789c7e434d2e` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_asset_history_1d` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER ahc_3_insert AFTER INSERT ON asset_history_1d FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,3,1 ON DUPLICATE KEY UPDATE revision=revision+1 */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_asset_history_1d` BEFORE UPDATE ON `asset_history_1d` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER ahc_3_update AFTER UPDATE ON asset_history_1d FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,3,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) OR NOT (OLD.bucket_start <=> NEW.bucket_start) OR NOT (OLD.bucket_end <=> NEW.bucket_end) OR NOT (OLD.open_value <=> NEW.open_value) OR NOT (OLD.high_value <=> NEW.high_value) OR NOT (OLD.low_value <=> NEW.low_value) OR NOT (OLD.close_value <=> NEW.close_value) OR NOT (OLD.open_at <=> NEW.open_at) OR NOT (OLD.high_at <=> NEW.high_at) OR NOT (OLD.low_at <=> NEW.low_at) OR NOT (OLD.close_at <=> NEW.close_at) OR NOT (OLD.source_count <=> NEW.source_count) OR NOT (OLD.valid_sample_count <=> NEW.valid_sample_count) OR NOT (OLD.invalid_sample_count <=> NEW.invalid_sample_count) OR NOT (OLD.expected_sample_count <=> NEW.expected_sample_count) OR NOT (OLD.finalized <=> NEW.finalized) OR NOT (OLD.quality <=> NEW.quality) OR NOT (OLD.source_through <=> NEW.source_through) UNION ALL SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,3,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) ON DUPLICATE KEY UPDATE revision=revision+1 */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER ahc_3_delete AFTER DELETE ON asset_history_1d FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,3,1 ON DUPLICATE KEY UPDATE revision=revision+1 */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`user_id`,`basis_version`,`bucket_start`),
  KEY `ix_equity_1h_batch` (`basis_version`,`bucket_start`,`user_id`),
  CONSTRAINT `mt_fk_d62ee382bbba2a44e83d` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_asset_history_1h` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER ahc_1_insert AFTER INSERT ON asset_history_1h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,1,1 ON DUPLICATE KEY UPDATE revision=revision+1 */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_asset_history_1h` BEFORE UPDATE ON `asset_history_1h` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER ahc_1_update AFTER UPDATE ON asset_history_1h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,1,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) OR NOT (OLD.bucket_start <=> NEW.bucket_start) OR NOT (OLD.bucket_end <=> NEW.bucket_end) OR NOT (OLD.open_value <=> NEW.open_value) OR NOT (OLD.high_value <=> NEW.high_value) OR NOT (OLD.low_value <=> NEW.low_value) OR NOT (OLD.close_value <=> NEW.close_value) OR NOT (OLD.open_at <=> NEW.open_at) OR NOT (OLD.high_at <=> NEW.high_at) OR NOT (OLD.low_at <=> NEW.low_at) OR NOT (OLD.close_at <=> NEW.close_at) OR NOT (OLD.source_count <=> NEW.source_count) OR NOT (OLD.valid_sample_count <=> NEW.valid_sample_count) OR NOT (OLD.invalid_sample_count <=> NEW.invalid_sample_count) OR NOT (OLD.expected_sample_count <=> NEW.expected_sample_count) OR NOT (OLD.finalized <=> NEW.finalized) OR NOT (OLD.quality <=> NEW.quality) OR NOT (OLD.source_through <=> NEW.source_through) UNION ALL SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,1,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) ON DUPLICATE KEY UPDATE revision=revision+1 */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER ahc_1_delete AFTER DELETE ON asset_history_1h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,1,1 ON DUPLICATE KEY UPDATE revision=revision+1 */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`user_id`,`basis_version`,`bucket_start`),
  KEY `ix_equity_minute_batch` (`basis_version`,`bucket_start`,`user_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_fk_9d49204dd673c933d8a7` (`tenant_id`,`quote_batch_id`),
  CONSTRAINT `mt_fk_9d49204dd673c933d8a7` FOREIGN KEY (`tenant_id`, `quote_batch_id`) REFERENCES `asset_history_quote_batch` (`tenant_id`, `batch_id`),
  CONSTRAINT `mt_fk_a94d6335a0ddc467ef44` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_asset_history_1m` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_asset_history_1m` BEFORE UPDATE ON `asset_history_1m` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`user_id`,`basis_version`,`bucket_start`),
  KEY `ix_equity_4h_batch` (`basis_version`,`bucket_start`,`user_id`),
  CONSTRAINT `mt_fk_52ac99d8fc3b5c2dd844` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_asset_history_4h` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER ahc_2_insert AFTER INSERT ON asset_history_4h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,2,1 ON DUPLICATE KEY UPDATE revision=revision+1 */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_asset_history_4h` BEFORE UPDATE ON `asset_history_4h` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER ahc_2_update AFTER UPDATE ON asset_history_4h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT NEW.tenant_id,NEW.user_id,NEW.basis_version,2,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) OR NOT (OLD.bucket_start <=> NEW.bucket_start) OR NOT (OLD.bucket_end <=> NEW.bucket_end) OR NOT (OLD.open_value <=> NEW.open_value) OR NOT (OLD.high_value <=> NEW.high_value) OR NOT (OLD.low_value <=> NEW.low_value) OR NOT (OLD.close_value <=> NEW.close_value) OR NOT (OLD.open_at <=> NEW.open_at) OR NOT (OLD.high_at <=> NEW.high_at) OR NOT (OLD.low_at <=> NEW.low_at) OR NOT (OLD.close_at <=> NEW.close_at) OR NOT (OLD.source_count <=> NEW.source_count) OR NOT (OLD.valid_sample_count <=> NEW.valid_sample_count) OR NOT (OLD.invalid_sample_count <=> NEW.invalid_sample_count) OR NOT (OLD.expected_sample_count <=> NEW.expected_sample_count) OR NOT (OLD.finalized <=> NEW.finalized) OR NOT (OLD.quality <=> NEW.quality) OR NOT (OLD.source_through <=> NEW.source_through) UNION ALL SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,2,1 FROM DUAL WHERE NOT (OLD.user_id <=> NEW.user_id) OR NOT (OLD.basis_version <=> NEW.basis_version) ON DUPLICATE KEY UPDATE revision=revision+1 */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER ahc_2_delete AFTER DELETE ON asset_history_4h FOR EACH ROW INSERT INTO asset_history_revision(tenant_id,user_id,basis_version,level,revision) SELECT OLD.tenant_id,OLD.user_id,OLD.basis_version,2,1 ON DUPLICATE KEY UPDATE revision=revision+1 */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_baseline` (
  `user_id` bigint(20) NOT NULL,
  `basis_version` varchar(32) NOT NULL,
  `capture_from` bigint(20) NOT NULL,
  `first_positive` decimal(32,16) DEFAULT NULL,
  `first_positive_at` bigint(20) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`user_id`,`basis_version`),
  CONSTRAINT `mt_fk_459539abd470acd7c35d` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_asset_history_baseline` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_asset_history_baseline` BEFORE UPDATE ON `asset_history_baseline` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_job_state` (
  `task_name` varchar(32) NOT NULL,
  `basis_version` varchar(32) NOT NULL,
  `watermark` bigint(20) NOT NULL,
  `user_cursor` bigint(20) NOT NULL,
  `success_at` bigint(20) DEFAULT NULL,
  `error` varchar(512) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`task_name`,`basis_version`),
  CONSTRAINT `mt_t_asset_history_job_state` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_asset_history_job_state` BEFORE UPDATE ON `asset_history_job_state` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`batch_id`),
  CONSTRAINT `mt_t_asset_history_quote_batch` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_asset_history_quote_batch` BEFORE UPDATE ON `asset_history_quote_batch` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_history_revision` (
  `user_id` bigint(20) NOT NULL,
  `basis_version` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL,
  `level` tinyint(4) NOT NULL,
  `revision` bigint(20) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`user_id`,`basis_version`,`level`),
  CONSTRAINT `mt_fk_7a92759d9a1c73cd2cf4` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_asset_history_revision` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_asset_history_revision` BEFORE UPDATE ON `asset_history_revision` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `asset_snapshot` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `captured_at` bigint(20) NOT NULL,
  `total` decimal(32,16) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `idx_snapshot_user_time` (`user_id`,`captured_at`),
  KEY `mt_fk_6f9daa439f60d4bcb1c5` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_6f9daa439f60d4bcb1c5` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_asset_snapshot` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_asset_snapshot` BEFORE UPDATE ON `asset_snapshot` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `backend_login` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `normalized_account` varchar(128) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  `subject_type` varchar(16) NOT NULL,
  `admin_user_id` bigint(20) DEFAULT NULL,
  `user_id` bigint(20) DEFAULT NULL,
  `enabled` bit(1) NOT NULL DEFAULT b'1',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_backend_account` (`normalized_account`),
  UNIQUE KEY `uk_backend_admin` (`tenant_id`,`admin_user_id`),
  UNIQUE KEY `uk_backend_agent` (`tenant_id`,`user_id`),
  CONSTRAINT `fk_backend_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `mt_fk_9e9acbde318b55fc3331` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_e695c4f7a0fb2561aa76` FOREIGN KEY (`tenant_id`, `admin_user_id`) REFERENCES `admin_user` (`tenant_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_backend_subject_insert BEFORE INSERT ON backend_login FOR EACH ROW
 BEGIN
 IF NOT ((NEW.subject_type='ADMIN' AND NEW.admin_user_id IS NOT NULL AND NEW.user_id IS NULL)
      OR (NEW.subject_type='AGENT' AND NEW.user_id IS NOT NULL AND NEW.admin_user_id IS NULL))
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid backend principal'; END IF;
 IF BINARY NEW.normalized_account <> BINARY LOWER(TRIM(NEW.normalized_account)) OR NEW.normalized_account=''
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Backend account must be normalized'; END IF;
 END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_backend_subject_update BEFORE UPDATE ON backend_login FOR EACH ROW
 BEGIN
 IF NOT ((NEW.subject_type='ADMIN' AND NEW.admin_user_id IS NOT NULL AND NEW.user_id IS NULL)
      OR (NEW.subject_type='AGENT' AND NEW.user_id IS NOT NULL AND NEW.admin_user_id IS NULL))
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid backend principal'; END IF;
 IF BINARY NEW.normalized_account <> BINARY LOWER(TRIM(NEW.normalized_account)) OR NEW.normalized_account=''
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Backend account must be normalized'; END IF;
 IF NEW.tenant_id <> OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF;
 END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `balance_adjustment` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `request_key` varchar(64) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `actor_type` varchar(16) DEFAULT NULL,
  `actor_id` bigint(20) DEFAULT NULL,
  `reason` varchar(500) DEFAULT NULL,
  `changes` text,
  `created_at` datetime(6) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_balance_request` (`tenant_id`,`request_key`),
  KEY `mt_old_70804a4177ee6bb0` (`request_key`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_fk_90600745af18a146ba7b` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_90600745af18a146ba7b` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_balance_adjustment` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_balance_adjustment` BEFORE UPDATE ON `balance_adjustment` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `calendar_audit` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `event_id` varchar(36) NOT NULL,
  `actor` varchar(100) NOT NULL,
  `action` varchar(40) NOT NULL,
  `reason` varchar(1000) NOT NULL,
  `before_json` longtext,
  `after_json` longtext,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `calendar_audit_event` (`tenant_id`,`environment`,`event_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `calendar_event` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `event_id` varchar(36) NOT NULL,
  `metric` varchar(40) NOT NULL,
  `release_date` date DEFAULT NULL,
  `release_at` datetime(6) DEFAULT NULL,
  `status` varchar(24) NOT NULL,
  `published` bit(1) NOT NULL,
  `manual_lock` bit(1) NOT NULL,
  `data_json` longtext NOT NULL,
  `upstream_hash` varchar(1024) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_calendar_event` (`tenant_id`,`environment`,`event_id`),
  KEY `calendar_window` (`tenant_id`,`environment`,`release_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `calendar_reminder` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `event_id` varchar(36) NOT NULL,
  `lead_minutes` int(11) NOT NULL,
  `timezone` varchar(64) NOT NULL,
  `enabled` bit(1) NOT NULL,
  `delivered_at` datetime(6) DEFAULT NULL,
  `delivered_release_at` datetime(6) DEFAULT NULL,
  `letter_id` bigint(20) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_calendar_reminder` (`tenant_id`,`environment`,`user_id`,`event_id`,`lead_minutes`),
  KEY `calendar_reminder_pending` (`tenant_id`,`environment`,`enabled`,`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `calendar_source` (
  `id` varchar(48) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `source_id` varchar(24) NOT NULL,
  `status` varchar(24) NOT NULL,
  `last_attempt` datetime(6) DEFAULT NULL,
  `last_success` datetime(6) DEFAULT NULL,
  `next_attempt` datetime(6) DEFAULT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  `budget_date` date DEFAULT NULL,
  `requests_today` int(11) NOT NULL DEFAULT '0',
  `failures` int(11) NOT NULL DEFAULT '0',
  `http_status` int(11) DEFAULT NULL,
  `last_error` varchar(500) DEFAULT NULL,
  `etag` varchar(300) DEFAULT NULL,
  `last_modified` varchar(300) DEFAULT NULL,
  `payload` longtext,
  `parsed_json` longtext,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `calendar_source_update` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `environment` varchar(4) NOT NULL,
  `source_id` varchar(24) NOT NULL,
  `status` varchar(24) NOT NULL,
  `http_status` int(11) DEFAULT NULL,
  `response_hash` varchar(64) DEFAULT NULL,
  `error` varchar(500) DEFAULT NULL,
  `captured_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `calendar_source_history` (`environment`,`source_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
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
  `user_id` bigint(20) DEFAULT NULL,
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
  `trial_reserved` decimal(32,16) DEFAULT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `deleted_by` varchar(64) DEFAULT NULL,
  `fx_base_currency` varchar(3) DEFAULT NULL,
  `min_order_notional` decimal(32,16) DEFAULT NULL,
  `min_order_quantity` decimal(32,16) DEFAULT NULL,
  `quantity_asset` varchar(16) DEFAULT NULL,
  `quantity_step` decimal(32,16) DEFAULT NULL,
  `quantity_unit_type` varchar(16) DEFAULT NULL,
  `spec_version` bigint(20) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  `funding_source` varchar(16) DEFAULT NULL,
  `trial_allocations` varchar(4000) DEFAULT NULL,
  `request_key` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `request_hash` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `promotion_pending` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_contract_order_request` (`tenant_id`,`user_id`,`request_key`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  KEY `ix_contract_promotion` (`tenant_id`,`promotion_pending`,`id`),
  CONSTRAINT `mt_fk_d47d5ad36fd99730d76f` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_contract_order` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER manual_unbound_insert BEFORE INSERT ON contract_order FOR EACH ROW
BEGIN
 IF NEW.user_id IS NULL AND NOT (NEW.order_source='MANUAL_TEST' AND NEW.status='CLOSED' AND NEW.manual_wallet_enabled=0 AND NEW.manual_equity_enabled=0) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Only closed manual simulations may be unbound without funds';
 END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_contract_order` BEFORE UPDATE ON `contract_order` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER manual_unbound_update BEFORE UPDATE ON contract_order FOR EACH ROW
BEGIN
 IF (OLD.user_id IS NOT NULL AND NEW.user_id IS NULL) OR (NEW.user_id IS NULL AND NOT (NEW.order_source='MANUAL_TEST' AND NEW.status='CLOSED' AND NEW.manual_wallet_enabled=0 AND NEW.manual_equity_enabled=0)) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid unbound simulation transition';
 END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `control_access_session` (
  `id` varchar(64) NOT NULL,
  `actor_id` bigint(20) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  `ticket_hash` varchar(64) NOT NULL,
  `browser_binding_hash` varchar(64) NOT NULL,
  `actor_version` bigint(20) NOT NULL,
  `expires_at` datetime(6) NOT NULL,
  `ticket_expires_at` datetime(6) NOT NULL,
  `last_activity_at` datetime(6) NOT NULL,
  `consumed` bit(1) NOT NULL DEFAULT b'0',
  `revoked` bit(1) NOT NULL DEFAULT b'0',
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_control_ticket` (`ticket_hash`),
  KEY `ix_control_session_tenant` (`tenant_id`,`expires_at`),
  KEY `fk_control_session_actor` (`actor_id`),
  CONSTRAINT `fk_control_session_actor` FOREIGN KEY (`actor_id`) REFERENCES `control_admin` (`id`),
  CONSTRAINT `fk_control_session_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `control_admin` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `account` varchar(64) NOT NULL,
  `password_hash` varchar(128) NOT NULL,
  `enabled` bit(1) NOT NULL DEFAULT b'1',
  `mfa_secret` text,
  `mfa_enabled` bit(1) NOT NULL DEFAULT b'0',
  `session_version` bigint(20) NOT NULL DEFAULT '0',
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_control_account` (`account`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `control_audit_log` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `actor_id` bigint(20) DEFAULT NULL,
  `tenant_id` bigint(20) DEFAULT NULL,
  `access_session_id` varchar(64) DEFAULT NULL,
  `request_id` varchar(64) DEFAULT NULL,
  `action` varchar(128) NOT NULL,
  `object_ref` varchar(255) DEFAULT NULL,
  `outcome` varchar(32) NOT NULL,
  `detail` text,
  `reason` varchar(512) DEFAULT NULL,
  `remote_address` varchar(64) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `ix_control_audit_tenant` (`tenant_id`,`created_at`,`id`),
  KEY `ix_control_audit_actor` (`actor_id`,`created_at`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_control_audit_no_update BEFORE UPDATE ON control_audit_log FOR EACH ROW
 BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Control audit is append-only'; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_control_audit_no_delete BEFORE DELETE ON control_audit_log FOR EACH ROW
 BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Control audit is append-only'; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `control_chat_archive_job` (
  `id` varchar(36) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  `conversation_id` bigint(20) NOT NULL,
  `actor_id` bigint(20) DEFAULT NULL,
  `owner_key` varchar(64) NOT NULL,
  `request_key` varchar(36) NOT NULL,
  `request_hash` varchar(64) NOT NULL,
  `reason` varchar(512) NOT NULL,
  `state` varchar(16) NOT NULL,
  `end_id` bigint(20) NOT NULL,
  `expected_messages` bigint(20) NOT NULL,
  `processed_messages` bigint(20) NOT NULL DEFAULT '0',
  `cursor_id` bigint(20) NOT NULL DEFAULT '0',
  `chunk_sequence` int(11) NOT NULL DEFAULT '0',
  `attachment_bytes` bigint(20) NOT NULL DEFAULT '0',
  `chain_hash` varchar(64) NOT NULL DEFAULT '',
  `snapshot_sha256` varchar(64) NOT NULL,
  `expected_last_hash` varchar(64) NOT NULL,
  `final_manifest_sha256` varchar(64) DEFAULT NULL,
  `failure_type` varchar(128) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `archive_request` (`tenant_id`,`conversation_id`,`owner_key`,`request_key`),
  KEY `archive_pending` (`state`,`updated_at`,`id`),
  KEY `archive_owner` (`tenant_id`,`actor_id`,`conversation_id`,`created_at`),
  KEY `archive_actor` (`actor_id`),
  CONSTRAINT `archive_actor` FOREIGN KEY (`actor_id`) REFERENCES `control_admin` (`id`),
  CONSTRAINT `archive_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `control_policy_definition` (
  `policy_key` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `policy_name` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `options_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `default_value` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`policy_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `control_table_preference` (
  `id` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `actor_id` bigint(20) NOT NULL,
  `table_key` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `columns_json` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_control_preference` (`actor_id`,`table_key`),
  CONSTRAINT `mt_control_preference_actor` FOREIGN KEY (`actor_id`) REFERENCES `control_admin` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `demo_account` (
  `user_id` bigint(20) NOT NULL,
  `cash` decimal(32,8) NOT NULL,
  `generation` int(11) NOT NULL DEFAULT '1',
  `last_reset_at` datetime(6) DEFAULT NULL,
  `last_reset_key` varchar(255) DEFAULT NULL,
  `version` bigint(20) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_e6544798bb4730d7b306` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_demo_account` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_demo_account` BEFORE UPDATE ON `demo_account` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `demo_ledger` (
  `id` varchar(36) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `generation` int(11) NOT NULL,
  `type` varchar(16) NOT NULL,
  `order_id` varchar(36) DEFAULT NULL,
  `delta` decimal(32,8) NOT NULL,
  `balance_after` decimal(32,8) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `idx_demo_ledger_owner_time` (`user_id`,`created_at`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_fk_21b8187c084d5be03533` (`tenant_id`,`user_id`),
  KEY `mt_fk_f81e633478046eee721c` (`tenant_id`,`order_id`),
  CONSTRAINT `mt_fk_21b8187c084d5be03533` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_f81e633478046eee721c` FOREIGN KEY (`tenant_id`, `order_id`) REFERENCES `demo_order` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_demo_ledger` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_demo_ledger` BEFORE UPDATE ON `demo_ledger` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `demo_order` (
  `id` varchar(36) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `request_key` varchar(36) NOT NULL,
  `generation` int(11) NOT NULL,
  `symbol` varchar(32) NOT NULL,
  `market_code` varchar(64) NOT NULL,
  `status` varchar(16) NOT NULL,
  `amount` decimal(32,8) NOT NULL,
  `quantity` decimal(32,16) NOT NULL,
  `open_price` decimal(32,16) NOT NULL,
  `close_price` decimal(32,16) DEFAULT NULL,
  `open_fee` decimal(32,8) NOT NULL,
  `close_fee` decimal(32,8) DEFAULT NULL,
  `realized_pnl` decimal(32,8) DEFAULT NULL,
  `created_at` datetime(6) NOT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_demo_order_request` (`tenant_id`,`user_id`,`request_key`),
  KEY `idx_demo_order_owner_status` (`user_id`,`status`,`created_at`),
  KEY `mt_old_1ff2d1f57f38b42a` (`user_id`,`request_key`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `mt_fk_949ab0b45abb4cf3ecf6` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_demo_order` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_demo_order` BEFORE UPDATE ON `demo_order` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `deposit_credit_record` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `deposit_record_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `account_type` varchar(16) NOT NULL,
  `amount_usd` decimal(32,16) NOT NULL,
  `balance_before` decimal(32,16) NOT NULL,
  `balance_after` decimal(32,16) NOT NULL,
  `operator_type` varchar(24) NOT NULL,
  `operator_id` bigint(20) NOT NULL,
  `operator_name` varchar(128) DEFAULT NULL,
  `credited_at` datetime(6) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_deposit_credit` (`tenant_id`,`deposit_record_id`),
  KEY `fk_deposit_credit_user` (`user_id`),
  KEY `mt_old_83a546e1d74c9f4b` (`deposit_record_id`),
  KEY `mt_fk_ffab7bdc031c083df005` (`tenant_id`,`user_id`),
  CONSTRAINT `fk_deposit_credit_order` FOREIGN KEY (`deposit_record_id`) REFERENCES `deposit_record` (`id`),
  CONSTRAINT `fk_deposit_credit_user` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`),
  CONSTRAINT `mt_fk_26642a321d900586598e` FOREIGN KEY (`tenant_id`, `deposit_record_id`) REFERENCES `deposit_record` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_ffab7bdc031c083df005` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_deposit_credit_record` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_deposit_credit_record` BEFORE UPDATE ON `deposit_credit_record` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `order_no` varchar(64) DEFAULT NULL,
  `source` varchar(32) DEFAULT NULL,
  `account_type` varchar(16) DEFAULT NULL,
  `manual_purpose` varchar(24) DEFAULT NULL,
  `idempotency_key` varchar(64) DEFAULT NULL,
  `request_hash` varchar(64) DEFAULT NULL,
  `review_remark` varchar(500) DEFAULT NULL,
  `created_by_type` varchar(24) DEFAULT NULL,
  `created_by_id` bigint(20) DEFAULT NULL,
  `created_by_name` varchar(128) DEFAULT NULL,
  `reviewed_by_type` varchar(24) DEFAULT NULL,
  `reviewed_by_id` bigint(20) DEFAULT NULL,
  `reviewed_by_name` varchar(128) DEFAULT NULL,
  `fee_rate` decimal(32,16) DEFAULT NULL,
  `fee_amount` decimal(32,16) DEFAULT NULL,
  `reviewed_at` datetime(6) DEFAULT NULL,
  `credited_at` datetime(6) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_deposit_order_no` (`order_no`),
  UNIQUE KEY `uk_deposit_request` (`tenant_id`,`created_by_type`,`created_by_id`,`idempotency_key`),
  KEY `ix_deposit_created` (`created_at`,`id`),
  KEY `ix_deposit_user` (`user_id`,`created_at`,`id`),
  KEY `ix_deposit_status` (`status`,`created_at`,`id`),
  KEY `ix_deposit_source` (`source`,`status`,`credited_at`),
  KEY `ix_deposit_reviewed` (`reviewed_at`,`id`),
  KEY `mt_old_40a023208e9a4af1` (`created_by_type`,`created_by_id`,`idempotency_key`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  KEY `mt_fk_1b89baafca4b889ed818` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_1b89baafca4b889ed818` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_deposit_record` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_deposit_record` BEFORE UPDATE ON `deposit_record` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_network_type` (`tenant_id`,`network`,`type`),
  KEY `mt_old_21f857c7ae3e9b26` (`network`,`type`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_t_deposit_setting` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='充值设置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_deposit_setting` BEFORE UPDATE ON `deposit_setting` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  `request_key` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `request_hash` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `last_accrued_date` date DEFAULT NULL,
  `accrued_yield` decimal(32,16) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_financial_order_request` (`tenant_id`,`user_id`,`request_key`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_product_id` (`product_id`),
  KEY `idx_status` (`status`),
  KEY `idx_purchase_time` (`purchase_time`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  KEY `mt_fk_285bfb9b3be5af402cc8` (`tenant_id`,`product_id`),
  CONSTRAINT `mt_fk_285bfb9b3be5af402cc8` FOREIGN KEY (`tenant_id`, `product_id`) REFERENCES `financial_product` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_e9329799dd0d7b84068e` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_financial_order` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='理财订单表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_financial_order` BEFORE UPDATE ON `financial_order` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `idx_enabled` (`enabled`),
  KEY `idx_sort_order` (`sort_order`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_t_financial_product` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='理财产品表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_financial_product` BEFORE UPDATE ON `financial_product` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `UK9dll9ic2smexlc46rjowsrvdy` (`tenant_id`,`order_id`,`yield_date`),
  KEY `mt_old_f051d563ecfeb18a` (`order_id`,`yield_date`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  KEY `mt_fk_203e06ffb45190de8b0b` (`tenant_id`,`user_id`),
  KEY `mt_fk_2f2bf34b7500721b0302` (`tenant_id`,`product_id`),
  CONSTRAINT `mt_fk_203e06ffb45190de8b0b` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_2f2bf34b7500721b0302` FOREIGN KEY (`tenant_id`, `product_id`) REFERENCES `financial_product` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_98080e1d022123a73687` FOREIGN KEY (`tenant_id`, `order_id`) REFERENCES `financial_order` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_financial_yield_record` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_financial_yield_record` BEFORE UPDATE ON `financial_yield_record` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `inbox_letter` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `admin_id` bigint(20) DEFAULT NULL,
  `request_id` varchar(64) NOT NULL,
  `title` varchar(120) NOT NULL,
  `content` varchar(4000) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `read_at` datetime(6) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  `control_actor_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_inbox_request` (`tenant_id`,`admin_id`,`request_id`,`user_id`),
  UNIQUE KEY `uk_inbox_control_request` (`tenant_id`,`control_actor_id`,`request_id`,`user_id`),
  KEY `inbox_recipient` (`user_id`,`id`),
  KEY `mt_old_841d0982f7b3d514` (`admin_id`,`request_id`,`user_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_fk_b3e30a8534726e1a3b7e` (`tenant_id`,`user_id`),
  KEY `mt_inbox_control_actor` (`control_actor_id`),
  CONSTRAINT `mt_fk_b3e30a8534726e1a3b7e` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_cd78d780af9736dd2323` FOREIGN KEY (`tenant_id`, `admin_id`) REFERENCES `admin_user` (`tenant_id`, `id`),
  CONSTRAINT `mt_inbox_control_actor` FOREIGN KEY (`control_actor_id`) REFERENCES `control_admin` (`id`),
  CONSTRAINT `mt_t_inbox_letter` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_inbox_letter` BEFORE UPDATE ON `inbox_letter` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  KEY `mt_fk_c989412821d3c66f68a3` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_c989412821d3c66f68a3` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_kyc_record` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实名认证记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_kyc_record` BEFORE UPDATE ON `kyc_record` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_user_id` (`tenant_id`,`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_created_at` (`created_at`),
  KEY `mt_old_360bbc5a40864d35` (`user_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `mt_fk_6900f465a9d936413d13` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_loan_personal_info` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='贷款个人信息表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_loan_personal_info` BEFORE UPDATE ON `loan_personal_info` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  `request_key` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `request_hash` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_loan_record_request` (`tenant_id`,`user_id`,`request_key`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `mt_fk_7ad261b8f0c18e96fda3` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_loan_record` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_loan_record` BEFORE UPDATE ON `loan_record` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_t_loan_setting` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_loan_setting` BEFORE UPDATE ON `loan_setting` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `manual_order_binding` (
  `tenant_id` bigint(20) NOT NULL,
  `order_id` bigint(20) NOT NULL,
  `user_id` bigint(20) NOT NULL,
  `operator_id` bigint(20) NOT NULL,
  `idempotency_key` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `request_hash` char(64) CHARACTER SET ascii NOT NULL,
  `created_at` bigint(20) NOT NULL,
  `evidence` longtext NOT NULL,
  PRIMARY KEY (`tenant_id`,`idempotency_key`),
  UNIQUE KEY `uk_manual_binding_order` (`tenant_id`,`order_id`),
  KEY `fk_manual_binding_user` (`tenant_id`,`user_id`),
  CONSTRAINT `fk_manual_binding_order` FOREIGN KEY (`tenant_id`, `order_id`) REFERENCES `contract_order` (`tenant_id`, `id`),
  CONSTRAINT `fk_manual_binding_user` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_manual_order_binding` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_immutable_manual_order_binding BEFORE UPDATE ON manual_order_binding FOR EACH ROW
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `manual_order_record` (
  `idempotency_key` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `request_hash` char(64) CHARACTER SET ascii NOT NULL,
  `order_id` bigint(20) NOT NULL,
  `operator_id` bigint(20) NOT NULL,
  `user_id` bigint(20) DEFAULT NULL,
  `created_at` bigint(20) NOT NULL,
  `timezone` varchar(64) NOT NULL,
  `evidence` longtext NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`idempotency_key`),
  UNIQUE KEY `uk_manual_order` (`tenant_id`,`order_id`),
  KEY `mt_old_19f3dec96fa122ce` (`order_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_fk_bcb7c2341e13bdd9a1be` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_6ad40e17ee136a6c60bf` FOREIGN KEY (`tenant_id`, `order_id`) REFERENCES `contract_order` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_bcb7c2341e13bdd9a1be` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_manual_order_record` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_manual_order_record` BEFORE UPDATE ON `manual_order_record` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_command` (
  `tenant_id` bigint(20) NOT NULL,
  `id` varchar(36) COLLATE utf8mb4_unicode_ci NOT NULL,
  `symbol_id` bigint(20) NOT NULL,
  `request_key` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `parameter_hash` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `parameters_json` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `state` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `actor_id` bigint(20) NOT NULL,
  `session_id` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `config_revision` bigint(20) NOT NULL,
  `control_revision` bigint(20) NOT NULL,
  `writer_generation` bigint(20) DEFAULT NULL,
  `owner_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `seed` bigint(20) NOT NULL,
  `accepted_at` bigint(20) NOT NULL,
  `expires_at` bigint(20) NOT NULL,
  `prepared_json` mediumtext COLLATE utf8mb4_unicode_ci,
  `task_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `error_code` varchar(64) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `message` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `retry_count` int(11) NOT NULL DEFAULT '0',
  `retry_at` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`tenant_id`,`id`),
  UNIQUE KEY `command_request` (`tenant_id`,`symbol_id`,`request_key`),
  KEY `command_queue` (`tenant_id`,`state`,`accepted_at`),
  CONSTRAINT `mt_s_market_control_command` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_control_command` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_immutable_market_control_command BEFORE UPDATE ON market_control_command FOR EACH ROW
BEGIN IF NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id OR NEW.request_key<>OLD.request_key OR NEW.parameter_hash<>OLD.parameter_hash THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Command identity is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  `history_pending_until` bigint(20) DEFAULT NULL,
  `history_retry_at` bigint(20) DEFAULT NULL,
  `history_error` varchar(64) DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`task_id`),
  CONSTRAINT `mt_fk_6d66d213c682628f995f` FOREIGN KEY (`tenant_id`, `task_id`) REFERENCES `market_control_task` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_control_flow` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_flow_i BEFORE INSERT ON market_control_flow FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_control_flow` BEFORE UPDATE ON `market_control_flow` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_flow_u BEFORE UPDATE ON market_control_flow FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_flow_d BEFORE DELETE ON market_control_flow FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`task_id`),
  CONSTRAINT `mt_fk_2401347050c1b068748c` FOREIGN KEY (`tenant_id`, `task_id`) REFERENCES `market_control_task` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_control_hold` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_hold_i BEFORE INSERT ON market_control_hold FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_control_hold` BEFORE UPDATE ON `market_control_hold` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_hold_u BEFORE UPDATE ON market_control_hold FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_hold_d BEFORE DELETE ON market_control_hold FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_plan` (
  `task_id` varchar(36) CHARACTER SET latin1 NOT NULL,
  `seed` bigint(20) NOT NULL,
  `parameters_json` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `prices_json` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `summary_json` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `checksum` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`task_id`),
  CONSTRAINT `mt_fk_2ea06a736cb068d29d6d` FOREIGN KEY (`tenant_id`, `task_id`) REFERENCES `market_control_task` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_control_plan` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_plan_i BEFORE INSERT ON market_control_plan FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_control_plan` BEFORE UPDATE ON `market_control_plan` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_plan_u BEFORE UPDATE ON market_control_plan FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_plan_d BEFORE DELETE ON market_control_plan FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_publication` (
  `task_id` varchar(36) NOT NULL,
  `published_at` bigint(20) NOT NULL,
  `from_at` bigint(20) NOT NULL,
  `to_at` bigint(20) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`task_id`),
  CONSTRAINT `mt_fk_bd18bc0098cb40c0a811` FOREIGN KEY (`tenant_id`, `task_id`) REFERENCES `market_control_task` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_control_publication` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_publication_i BEFORE INSERT ON market_control_publication FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_publication_i BEFORE INSERT ON market_control_publication FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_control_task t JOIN market_history_ordering p
  ON p.tenant_id=t.tenant_id AND p.symbol_id=t.symbol_id
  WHERE t.tenant_id=NEW.tenant_id AND t.id=NEW.task_id
  AND (t.started_at<p.from_minute OR NEW.from_at<p.from_minute) FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='New publication cannot expose sealed old history'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_control_publication` BEFORE UPDATE ON `market_control_publication` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_publication_u BEFORE UPDATE ON market_control_publication FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_publication_u BEFORE UPDATE ON market_control_publication FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_control_task t JOIN market_history_ordering p
  ON p.tenant_id=t.tenant_id AND p.symbol_id=t.symbol_id
  WHERE t.tenant_id=OLD.tenant_id AND t.id=OLD.task_id
  AND (t.started_at<p.from_minute OR OLD.from_at<p.from_minute OR NEW.from_at<p.from_minute) FOR UPDATE)
  AND (NEW.tenant_id<>OLD.tenant_id OR BINARY NEW.task_id<>BINARY OLD.task_id
   OR NEW.from_at<>OLD.from_at OR NEW.to_at<>OLD.to_at OR NEW.published_at<>OLD.published_at)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed old publication is immutable'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_publication_d BEFORE DELETE ON market_control_publication FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_publication_d BEFORE DELETE ON market_control_publication FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_control_task t JOIN market_history_ordering p
  ON p.tenant_id=t.tenant_id AND p.symbol_id=t.symbol_id
  WHERE t.tenant_id=OLD.tenant_id AND t.id=OLD.task_id
  AND (t.started_at<p.from_minute OR OLD.from_at<p.from_minute) FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed old publication cannot be deleted'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_resume` (
  `task_id` varchar(36) NOT NULL,
  `resumed_at` bigint(20) NOT NULL,
  `source_time` bigint(20) NOT NULL,
  `price` decimal(32,16) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`task_id`),
  CONSTRAINT `mt_fk_f5e0891528d0e3b11189` FOREIGN KEY (`tenant_id`, `task_id`) REFERENCES `market_control_task` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_control_resume` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_resume_i BEFORE INSERT ON market_control_resume FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_control_resume` BEFORE UPDATE ON `market_control_resume` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_resume_u BEFORE UPDATE ON market_control_resume FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_resume_d BEFORE DELETE ON market_control_resume FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_control_sample` (
  `task_id` varchar(36) NOT NULL,
  `generated_at` bigint(20) NOT NULL,
  `price` decimal(32,16) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`task_id`,`generated_at`),
  CONSTRAINT `mt_fk_0dcdcafc9872e551354e` FOREIGN KEY (`tenant_id`, `task_id`) REFERENCES `market_control_task` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_control_sample` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_sample_i BEFORE INSERT ON market_control_sample FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_control_sample` BEFORE UPDATE ON `market_control_sample` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_sample_u BEFORE UPDATE ON market_control_sample FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=NEW.tenant_id AND id=NEW.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_sample_d BEFORE DELETE ON market_control_sample FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=(SELECT symbol_id FROM market_control_task WHERE tenant_id=OLD.tenant_id AND id=OLD.task_id);
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  `stop_at` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `control_request` (`tenant_id`,`symbol_id`,`request_key`),
  KEY `control_symbol` (`symbol_id`,`started_at`),
  KEY `mt_old_277bcf538fda8ec7` (`symbol_id`,`request_key`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `mt_fk_1d99c3a5c8212fbf6475` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_control_task` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_task_i BEFORE INSERT ON market_control_task FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_control_task` BEFORE UPDATE ON `market_control_task` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_task_u BEFORE UPDATE ON market_control_task FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_control_task_d BEFORE DELETE ON market_control_task FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_engine_runtime` (
  `tenant_id` bigint(20) NOT NULL,
  `symbol_id` bigint(20) NOT NULL,
  `writer_generation` bigint(20) NOT NULL DEFAULT '0',
  `owner_id` varchar(36) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `lease_until` bigint(20) NOT NULL DEFAULT '0',
  `control_revision` bigint(20) NOT NULL DEFAULT '0',
  `snapshot_version` bigint(20) NOT NULL DEFAULT '0',
  `quote_json` mediumtext COLLATE utf8mb4_unicode_ci,
  `status_json` mediumtext COLLATE utf8mb4_unicode_ci,
  `committed_at` bigint(20) NOT NULL DEFAULT '0',
  `source_input_revision` bigint(20) NOT NULL DEFAULT '0',
  `source_dirty_from` bigint(20) DEFAULT NULL,
  `source_dirty_to` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`tenant_id`,`symbol_id`),
  CONSTRAINT `mt_s_market_engine_runtime` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_engine_runtime` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_source_dirty_insert BEFORE INSERT ON market_engine_runtime FOR EACH ROW
BEGIN
 IF NEW.source_input_revision<>0 OR NEW.source_dirty_from IS NOT NULL OR NEW.source_dirty_to IS NOT NULL THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source revision must start at zero';
 END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_immutable_market_engine_runtime BEFORE UPDATE ON market_engine_runtime FOR EACH ROW
BEGIN IF NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Runtime identity is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_source_dirty_update BEFORE UPDATE ON market_engine_runtime FOR EACH ROW
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source revision identity is immutable';
 END IF;
 IF NEW.source_input_revision<>OLD.source_input_revision THEN
  IF NEW.source_input_revision<>OLD.source_input_revision+1
   OR @mt705_s2_owner IS NULL OR @mt705_s2_fences IS NULL
   OR NOT(OLD.owner_id<=>@mt705_s2_owner)
   OR NOT(NEW.owner_id<=>OLD.owner_id) OR NEW.writer_generation<>OLD.writer_generation OR NEW.control_revision<>OLD.control_revision
   OR OLD.lease_until<=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
   OR OLD.writer_generation<>COALESCE(CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',OLD.tenant_id,':',OLD.symbol_id,'"'))) AS UNSIGNED),0)
   OR NEW.source_dirty_from IS NULL OR NEW.source_dirty_to IS NULL OR NEW.source_dirty_from<=0
   OR NEW.source_dirty_from>NEW.source_dirty_to OR MOD(NEW.source_dirty_from,60000)<>0 OR MOD(NEW.source_dirty_to,60000)<>0
   OR (OLD.source_dirty_from IS NOT NULL AND NEW.source_dirty_from>OLD.source_dirty_from)
   OR (OLD.source_dirty_to IS NOT NULL AND NEW.source_dirty_to<OLD.source_dirty_to)
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source revision writer fenced'; END IF;
 ELSEIF NOT(NEW.source_dirty_from<=>OLD.source_dirty_from) OR NOT(NEW.source_dirty_to<=>OLD.source_dirty_to) THEN
  CALL joint_s4_fence(NEW.tenant_id,NEW.symbol_id,NEW.writer_generation);
  IF OLD.source_dirty_from IS NULL
   OR NEW.writer_generation<>OLD.writer_generation OR NEW.control_revision<>OLD.control_revision
   OR NOT(NEW.owner_id<=>OLD.owner_id)
   OR (NEW.source_dirty_from IS NULL AND NEW.source_dirty_to IS NOT NULL)
   OR (NEW.source_dirty_from IS NOT NULL AND (NEW.source_dirty_to IS NULL OR NEW.source_dirty_from<=OLD.source_dirty_from
       OR NEW.source_dirty_from>OLD.source_dirty_to OR MOD(NEW.source_dirty_from,60000)<>0 OR NOT(NEW.source_dirty_to<=>OLD.source_dirty_to)))
  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source dirty cursor fenced'; END IF;
 END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_source_dirty_delete BEFORE DELETE ON market_engine_runtime FOR EACH ROW
BEGIN
 IF OLD.source_input_revision<>0 OR OLD.source_dirty_from IS NOT NULL OR OLD.source_dirty_to IS NOT NULL THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Source revision receipt cannot be deleted';
 END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_engine_tenant` (
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`),
  CONSTRAINT `mt_t_market_engine_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_immutable_market_engine_tenant BEFORE UPDATE ON market_engine_tenant FOR EACH ROW
BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_history_ordering` (
  `tenant_id` bigint(20) NOT NULL,
  `symbol_id` bigint(20) NOT NULL,
  `ordering_version` int(11) NOT NULL,
  `from_minute` bigint(20) NOT NULL,
  `source_sequence` bigint(20) NOT NULL,
  `responses_json` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `scope_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `evidence_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `sealed_at` bigint(20) NOT NULL,
  `writer_generation` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`symbol_id`),
  CONSTRAINT `mt_s_history_ordering` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_history_ordering` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_ordering_i BEFORE INSERT ON market_history_ordering FOR EACH ROW
BEGIN
 DECLARE allowed INT DEFAULT 0;
 DECLARE last_sequence BIGINT DEFAULT 0;
 DECLARE last_event BIGINT DEFAULT 0;
 DECLARE last_tick BIGINT DEFAULT 0;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
  WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=NEW.symbol_id AND r.owner_id=@mt705_s2_owner
  AND r.writer_generation=NEW.writer_generation
  AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED)
  AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED) FOR UPDATE;
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History cutover writer fenced'; END IF;
 SELECT COALESCE(MAX(event_sequence),0),COALESCE(MAX(received_at),0) INTO last_sequence,last_event
  FROM market_source_event WHERE tenant_id=NEW.tenant_id AND symbol_id=NEW.symbol_id FOR UPDATE;
 SELECT COALESCE(MAX(received_at),0) INTO last_tick FROM market_source_tick
  WHERE tenant_id=NEW.tenant_id AND symbol_id=NEW.symbol_id FOR UPDATE;
 IF JSON_VALID(NEW.responses_json)<>1 OR JSON_TYPE(NEW.responses_json)<>'OBJECT' OR JSON_LENGTH(NEW.responses_json)>1000
  OR NEW.ordering_version<>2 OR NEW.from_minute<=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
  OR MOD(NEW.from_minute,60000)<>0 OR NEW.source_sequence<>last_sequence OR NEW.source_sequence<0
  OR last_event>=NEW.from_minute OR last_tick>=NEW.from_minute
  OR NEW.sealed_at<=0 OR NEW.sealed_at>=NEW.from_minute
  OR NEW.scope_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
  OR NEW.evidence_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History cutover must be exact and future-only'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_ordering_u BEFORE UPDATE ON market_history_ordering FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History cutover is immutable'; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_ordering_d BEFORE DELETE ON market_history_ordering FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History cutover cannot be deleted'; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_history_response` (
  `tenant_id` bigint(20) NOT NULL,
  `symbol_id` bigint(20) NOT NULL,
  `request_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `request_json` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `response_json` mediumtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `response_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `artifact_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `artifact_pointer` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `scope_sha256` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `sealed_at` bigint(20) NOT NULL,
  `writer_generation` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`symbol_id`,`request_sha256`),
  CONSTRAINT `mt_p_history_response` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `market_history_ordering` (`tenant_id`, `symbol_id`),
  CONSTRAINT `mt_s_history_response` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_history_response` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_response_i BEFORE INSERT ON market_history_response FOR EACH ROW
BEGIN
 DECLARE allowed INT DEFAULT 0;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r JOIN market_history_ordering p
  ON p.tenant_id=r.tenant_id AND p.symbol_id=r.symbol_id
  WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=NEW.symbol_id AND r.owner_id=@mt705_s2_owner
  AND r.writer_generation=NEW.writer_generation AND p.writer_generation=NEW.writer_generation
  AND p.scope_sha256=NEW.scope_sha256 AND p.sealed_at=NEW.sealed_at
  AND JSON_UNQUOTE(JSON_EXTRACT(p.responses_json,CONCAT('$."',NEW.request_sha256,'"')))=
   SHA2(CONCAT(NEW.request_sha256,':',NEW.response_sha256,':',NEW.artifact_sha256,':',NEW.artifact_pointer),256)
  AND p.from_minute>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
  AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED)
  AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED) FOR UPDATE;
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History response writer or scope fenced'; END IF;
 IF NEW.request_sha256<>SHA2(NEW.request_json,256) OR NEW.response_sha256<>SHA2(NEW.response_json,256)
  OR NEW.artifact_sha256 NOT REGEXP BINARY '^[0-9a-f]{64}$'
  OR LEFT(NEW.artifact_pointer,1)<>'/' OR JSON_VALID(NEW.request_json)<>1 OR JSON_VALID(NEW.response_json)<>1
  OR NOT(JSON_TYPE(JSON_EXTRACT(NEW.request_json,'$.tenant'))<=>'INTEGER')
  OR NOT(JSON_TYPE(JSON_EXTRACT(NEW.request_json,'$.symbol'))<=>'INTEGER')
  OR NOT(CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.request_json,'$.tenant')) AS UNSIGNED)<=>NEW.tenant_id)
  OR NOT(CAST(JSON_UNQUOTE(JSON_EXTRACT(NEW.request_json,'$.symbol')) AS UNSIGNED)<=>NEW.symbol_id)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History response bytes or identity invalid'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_response_u BEFORE UPDATE ON market_history_response FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History response is immutable'; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_response_d BEFORE DELETE ON market_history_response FOR EACH ROW
BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='History response cannot be deleted'; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_legacy_minute_snapshot` (
  `symbol_id` bigint(20) NOT NULL,
  `minute_at` bigint(20) NOT NULL,
  `body` text NOT NULL,
  `last_event` bigint(20) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`symbol_id`,`minute_at`),
  CONSTRAINT `mt_fk_5eab2d3fcc23fbee8321` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_legacy_minute_snapshot` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_legacy_minute_snapshot_i BEFORE INSERT ON market_legacy_minute_snapshot FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_legacy_minute_snapshot` BEFORE UPDATE ON `market_legacy_minute_snapshot` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_legacy_minute_snapshot_u BEFORE UPDATE ON market_legacy_minute_snapshot FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_legacy_minute_snapshot_d BEFORE DELETE ON market_legacy_minute_snapshot FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_mixed_minute` (
  `symbol_id` bigint(20) NOT NULL,
  `minute_at` bigint(20) NOT NULL,
  `body` text NOT NULL,
  `last_event` bigint(20) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`symbol_id`,`minute_at`),
  CONSTRAINT `mt_fk_23085511787f171f5c55` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_mixed_minute` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_mixed_minute_i BEFORE INSERT ON market_mixed_minute FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_mixed_minute` BEFORE UPDATE ON `market_mixed_minute` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_mixed_minute_u BEFORE UPDATE ON market_mixed_minute FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_mixed_minute_d BEFORE DELETE ON market_mixed_minute FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_simulation_source_candle` (
  `symbol_id` bigint(20) NOT NULL,
  `session_at` bigint(20) NOT NULL,
  `period` varchar(8) NOT NULL,
  `candle_at` bigint(20) NOT NULL,
  `body` text NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`symbol_id`,`session_at`,`period`,`candle_at`),
  CONSTRAINT `mt_fk_3575d0af3817d8d18c96` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_simulation_source_candle` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_simulation_source_candle_i BEFORE INSERT ON market_simulation_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_simulation_source_candle` BEFORE UPDATE ON `market_simulation_source_candle` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_simulation_source_candle_u BEFORE UPDATE ON market_simulation_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_simulation_source_candle_d BEFORE DELETE ON market_simulation_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_source_candle` (
  `symbol_id` bigint(20) NOT NULL,
  `period` varchar(8) NOT NULL,
  `candle_at` bigint(20) NOT NULL,
  `body` text NOT NULL,
  `received_at` bigint(20) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`symbol_id`,`period`,`candle_at`),
  CONSTRAINT `mt_fk_f0220a9e8cc62d740fdf` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_source_candle` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_candle_i BEFORE INSERT ON market_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_source_candle` BEFORE UPDATE ON `market_source_candle` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_candle_u BEFORE UPDATE ON market_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_candle_d BEFORE DELETE ON market_source_candle FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_source_event` (
  `event_sequence` bigint(20) NOT NULL AUTO_INCREMENT,
  `event_id` varchar(64) NOT NULL,
  `symbol_id` bigint(20) NOT NULL,
  `source_time` bigint(20) NOT NULL,
  `received_at` bigint(20) NOT NULL,
  `price` decimal(32,16) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`event_sequence`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`event_sequence`),
  UNIQUE KEY `source_event_identity` (`tenant_id`,`symbol_id`,`event_id`),
  KEY `source_event_time` (`symbol_id`,`source_time`,`received_at`),
  KEY `mt_old_a784a6d8fcc24fc2` (`symbol_id`,`event_id`),
  CONSTRAINT `mt_fk_ece26abe286cbd0ec452` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_source_event` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_event_i BEFORE INSERT ON market_source_event FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_event_i AFTER INSERT ON market_source_event FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=NEW.tenant_id AND p.symbol_id=NEW.symbol_id
  AND NEW.received_at>=p.from_minute AND NEW.event_sequence<=p.source_sequence FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Future source sequence precedes sealed cutover'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_source_event` BEFORE UPDATE ON `market_source_event` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_event_u BEFORE UPDATE ON market_source_event FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_event_u BEFORE UPDATE ON market_source_event FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=OLD.tenant_id AND p.symbol_id=OLD.symbol_id FOR UPDATE)
  AND (NEW.tenant_id<>OLD.tenant_id OR NEW.event_sequence<>OLD.event_sequence OR BINARY NEW.event_id<>BINARY OLD.event_id OR NEW.symbol_id<>OLD.symbol_id
   OR NEW.source_time<>OLD.source_time OR NEW.received_at<>OLD.received_at OR NEW.price<>OLD.price)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed source facts are immutable'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_event_d BEFORE DELETE ON market_source_event FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_event_d BEFORE DELETE ON market_source_event FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=OLD.tenant_id AND p.symbol_id=OLD.symbol_id FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed source facts cannot be deleted'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_source_quote` (
  `symbol_id` bigint(20) NOT NULL,
  `price` decimal(32,16) NOT NULL,
  `source_time` bigint(20) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`symbol_id`),
  CONSTRAINT `mt_fk_2f78669731d93008017a` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_source_quote` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_quote_i BEFORE INSERT ON market_source_quote FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_source_quote` BEFORE UPDATE ON `market_source_quote` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_quote_u BEFORE UPDATE ON market_source_quote FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_quote_d BEFORE DELETE ON market_source_quote FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `market_source_tick` (
  `symbol_id` bigint(20) NOT NULL,
  `source_time` bigint(20) NOT NULL,
  `received_at` bigint(20) NOT NULL,
  `price` decimal(32,16) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`tenant_id`,`symbol_id`,`source_time`),
  CONSTRAINT `mt_fk_e1c6f0d42feb0d070431` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_market_source_tick` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_tick_i BEFORE INSERT ON market_source_tick FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_tick_i BEFORE INSERT ON market_source_tick FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=NEW.tenant_id AND p.symbol_id=NEW.symbol_id AND NEW.received_at>=p.from_minute FOR UPDATE)
  AND NOT EXISTS(SELECT 1 FROM market_source_event e JOIN market_history_ordering p ON p.tenant_id=e.tenant_id AND p.symbol_id=e.symbol_id
   WHERE e.tenant_id=NEW.tenant_id AND e.symbol_id=NEW.symbol_id AND e.source_time=NEW.source_time
    AND e.received_at=NEW.received_at AND e.price=NEW.price AND e.event_sequence>p.source_sequence FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Future tick requires exact sequenced source event'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_market_source_tick` BEFORE UPDATE ON `market_source_tick` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_tick_u BEFORE UPDATE ON market_source_tick FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=NEW.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=NEW.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_tick_u BEFORE UPDATE ON market_source_tick FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=OLD.tenant_id AND p.symbol_id=OLD.symbol_id FOR UPDATE)
  AND (NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id OR NEW.source_time<>OLD.source_time OR NEW.received_at<>OLD.received_at OR NEW.price<>OLD.price)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed legacy tick is immutable'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER s2_source_tick_d BEFORE DELETE ON market_source_tick FOR EACH ROW
BEGIN
 DECLARE symbol BIGINT;
 DECLARE allowed INT DEFAULT 0;
 SET symbol=OLD.symbol_id;
 SELECT COUNT(*) INTO allowed FROM market_engine_runtime r
 WHERE r.tenant_id=OLD.tenant_id AND r.symbol_id=symbol AND r.owner_id=@mt705_s2_owner
 AND r.lease_until>CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)
 AND r.writer_generation=CAST(JSON_UNQUOTE(JSON_EXTRACT(@mt705_s2_fences,CONCAT('$."',r.tenant_id,':',r.symbol_id,'"'))) AS UNSIGNED);
 IF allowed<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ENGINE_FENCED'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_history_tick_d BEFORE DELETE ON market_source_tick FOR EACH ROW
BEGIN
 IF EXISTS(SELECT 1 FROM market_history_ordering p WHERE p.tenant_id=OLD.tenant_id AND p.symbol_id=OLD.symbol_id FOR UPDATE)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Sealed legacy tick cannot be deleted'; END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
CREATE TABLE `news_article` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `article_id` varchar(36) NOT NULL,
  `source_id` varchar(24) NOT NULL,
  `category` varchar(16) NOT NULL,
  `language` varchar(16) NOT NULL,
  `published_at` datetime(6) DEFAULT NULL,
  `discovered_at` datetime(6) NOT NULL,
  `updated_at` datetime(6) NOT NULL,
  `hidden` bit(1) NOT NULL,
  `sort_order` int(11) NOT NULL DEFAULT '0',
  `data_json` longtext NOT NULL,
  `upstream_hash` varchar(64) NOT NULL,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_news_article` (`tenant_id`,`environment`,`article_id`),
  KEY `news_window` (`tenant_id`,`environment`,`source_id`,`hidden`,`published_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `news_audit` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `article_id` varchar(36) DEFAULT NULL,
  `source_id` varchar(24) NOT NULL,
  `actor` varchar(100) NOT NULL,
  `action` varchar(24) NOT NULL,
  `reason` varchar(1000) NOT NULL,
  `before_json` longtext,
  `after_json` longtext,
  `captured_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `news_audit_scope` (`tenant_id`,`environment`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `news_feed` (
  `id` varchar(40) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `source_id` varchar(24) NOT NULL,
  `status` varchar(24) NOT NULL,
  `last_attempt` datetime(6) DEFAULT NULL,
  `last_success` datetime(6) DEFAULT NULL,
  `content_as_of` datetime(6) DEFAULT NULL,
  `next_attempt` datetime(6) DEFAULT NULL,
  `lease_until` datetime(6) DEFAULT NULL,
  `budget_date` date DEFAULT NULL,
  `requests_today` int(11) NOT NULL DEFAULT '0',
  `failures` int(11) NOT NULL DEFAULT '0',
  `item_count` int(11) NOT NULL DEFAULT '0',
  `skipped_count` int(11) NOT NULL DEFAULT '0',
  `http_status` int(11) DEFAULT NULL,
  `last_error` varchar(200) DEFAULT NULL,
  `etag` varchar(300) DEFAULT NULL,
  `last_modified` varchar(300) DEFAULT NULL,
  `response_hash` varchar(64) DEFAULT NULL,
  `parsed_json` longtext,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `news_source_setting` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `source_id` varchar(24) NOT NULL,
  `enabled` bit(1) NOT NULL,
  `license_reviewed` bit(1) NOT NULL,
  `license_evidence` varchar(1000) DEFAULT NULL,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_news_source_setting` (`tenant_id`,`environment`,`source_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `idx_admin_id` (`admin_id`),
  KEY `idx_operation_type` (`operation_type`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_target_type_id` (`target_type`,`target_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `mt_t_operation_log` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_operation_log` BEFORE UPDATE ON `operation_log` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_duration` (`tenant_id`,`duration`),
  KEY `idx_enabled` (`enabled`),
  KEY `idx_sort_order` (`sort_order`),
  KEY `mt_old_420e589a4e3a28c9` (`duration`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_t_option_duration` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='期限设置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_option_duration` BEFORE UPDATE ON `option_duration` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `trial_reserved` decimal(32,16) DEFAULT NULL,
  `deleted_at` datetime(6) DEFAULT NULL,
  `deleted_by` varchar(64) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  `funding_source` varchar(16) DEFAULT NULL,
  `trial_allocations` varchar(4000) DEFAULT NULL,
  `request_key` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `request_hash` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `promotion_pending` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_option_order_request` (`tenant_id`,`user_id`,`request_key`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  KEY `ix_option_promotion` (`tenant_id`,`promotion_pending`,`id`),
  CONSTRAINT `mt_fk_86eaa8ddf560f959a206` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_option_order` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_option_order` BEFORE UPDATE ON `option_order` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `s4_history_projection_minute` (
  `tenant_id` bigint(20) NOT NULL,
  `symbol_id` bigint(20) NOT NULL,
  `minute_at` bigint(20) NOT NULL,
  `generation` bigint(20) NOT NULL,
  `fact_version` bigint(20) NOT NULL,
  `body` longtext COLLATE utf8mb4_unicode_ci NOT NULL,
  `received_cutoff` bigint(20) NOT NULL,
  `protected_mixed` bit(1) NOT NULL,
  PRIMARY KEY (`tenant_id`,`symbol_id`,`minute_at`),
  CONSTRAINT `mt_p_s4_minute` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `s4_history_projection_progress` (`tenant_id`, `symbol_id`),
  CONSTRAINT `mt_s_s4_minute` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_s4_minute` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_s4_minute_insert BEFORE INSERT ON s4_history_projection_minute FOR EACH ROW
BEGIN CALL joint_s4_fence(NEW.tenant_id,NEW.symbol_id,NEW.generation); END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_s4_minute_update BEFORE UPDATE ON s4_history_projection_minute FOR EACH ROW
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id OR NEW.minute_at<>OLD.minute_at THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Projection minute identity is immutable'; END IF;
 CALL joint_s4_fence(NEW.tenant_id,NEW.symbol_id,NEW.generation);
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_s4_minute_delete BEFORE DELETE ON s4_history_projection_minute FOR EACH ROW
BEGIN CALL joint_s4_fence(OLD.tenant_id,OLD.symbol_id,OLD.generation); END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `s4_history_projection_progress` (
  `tenant_id` bigint(20) NOT NULL,
  `symbol_id` bigint(20) NOT NULL,
  `generation` bigint(20) NOT NULL,
  `fact_version` bigint(20) NOT NULL,
  `initial_watermark` bigint(20) NOT NULL,
  `watermark` bigint(20) NOT NULL,
  `stop_at` bigint(20) NOT NULL,
  `last_hash` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `input_revision` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`tenant_id`,`symbol_id`),
  CONSTRAINT `mt_s_s4_progress` FOREIGN KEY (`tenant_id`, `symbol_id`) REFERENCES `trading_symbol` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_s4_progress` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_s4_progress_insert BEFORE INSERT ON s4_history_projection_progress FOR EACH ROW
BEGIN CALL joint_s4_fence(NEW.tenant_id,NEW.symbol_id,NEW.generation); END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_s4_progress_update BEFORE UPDATE ON s4_history_projection_progress FOR EACH ROW
BEGIN
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.symbol_id<>OLD.symbol_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Projection identity is immutable'; END IF;
 CALL joint_s4_fence(NEW.tenant_id,NEW.symbol_id,NEW.generation);
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER joint_s4_progress_delete BEFORE DELETE ON s4_history_projection_progress FOR EACH ROW
BEGIN CALL joint_s4_fence(OLD.tenant_id,OLD.symbol_id,OLD.generation); END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `simulation_seed` (
  `user_id` bigint(20) NOT NULL,
  `amount_per_wallet` decimal(32,16) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`user_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_fk_eac3043ef86b6d837662` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_simulation_seed` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_simulation_seed` BEFORE UPDATE ON `simulation_seed` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `support_attachment` (
  `message_id` bigint(20) NOT NULL,
  `content` longblob NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`message_id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`message_id`),
  CONSTRAINT `mt_fk_3ef9c109b4c93ad77529` FOREIGN KEY (`tenant_id`, `message_id`) REFERENCES `support_message` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_support_attachment` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_support_attachment` BEFORE UPDATE ON `support_attachment` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `support_conversation` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `active_user_id` bigint(20) DEFAULT NULL,
  `admin_id` bigint(20) DEFAULT NULL,
  `status` varchar(16) NOT NULL,
  `client_ip` varchar(64) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `accepted_at` datetime(6) DEFAULT NULL,
  `closed_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL,
  `user_read_id` bigint(20) NOT NULL DEFAULT '0',
  `admin_read_id` bigint(20) NOT NULL DEFAULT '0',
  `last_hash` varchar(64) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  `legal_hold` bit(1) NOT NULL DEFAULT b'0',
  `control_actor_id` bigint(20) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_active_user` (`tenant_id`,`active_user_id`),
  KEY `support_queue` (`status`,`id`),
  KEY `support_owner` (`admin_id`,`status`),
  KEY `support_user` (`user_id`,`id`),
  KEY `mt_old_cb7c2c271cc9df33` (`active_user_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  KEY `mt_fk_6729c78e35f7c7efebec` (`tenant_id`,`user_id`),
  KEY `mt_fk_6505728799905547753a` (`tenant_id`,`admin_id`),
  KEY `mt_support_control_actor` (`control_actor_id`),
  KEY `ix_support_tenant_created_id` (`tenant_id`,`created_at`,`id`),
  CONSTRAINT `mt_fk_6505728799905547753a` FOREIGN KEY (`tenant_id`, `admin_id`) REFERENCES `admin_user` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_6729c78e35f7c7efebec` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_f3849e0bb7927125705f` FOREIGN KEY (`tenant_id`, `active_user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_support_control_actor` FOREIGN KEY (`control_actor_id`) REFERENCES `control_admin` (`id`),
  CONSTRAINT `mt_t_support_conversation` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_support_conversation` BEFORE UPDATE ON `support_conversation` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `support_message` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `conversation_id` bigint(20) NOT NULL,
  `sender` varchar(12) NOT NULL,
  `sender_id` bigint(20) NOT NULL,
  `sender_name` varchar(128) NOT NULL,
  `request_id` varchar(64) NOT NULL,
  `text` varchar(4000) NOT NULL,
  `image` bit(1) NOT NULL,
  `image_hash` varchar(64) NOT NULL,
  `created_at` datetime(6) NOT NULL,
  `previous_hash` varchar(64) NOT NULL,
  `hash` varchar(64) NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_support_request` (`tenant_id`,`conversation_id`,`sender`,`sender_id`,`request_id`),
  KEY `support_message_cursor` (`conversation_id`,`id`),
  KEY `mt_old_d16f92fac6282a01` (`conversation_id`,`sender`,`sender_id`,`request_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_fk_d9db291c0d514059a00e` FOREIGN KEY (`tenant_id`, `conversation_id`) REFERENCES `support_conversation` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_support_message` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_support_message` BEFORE UPDATE ON `support_message` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `support_presence` (
  `admin_id` bigint(20) NOT NULL,
  `accepting` bit(1) NOT NULL,
  `heartbeat_at` datetime(6) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`admin_id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`admin_id`),
  CONSTRAINT `mt_fk_76b90f79bce6a4a09e94` FOREIGN KEY (`tenant_id`, `admin_id`) REFERENCES `admin_user` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_support_presence` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_support_presence` BEFORE UPDATE ON `support_presence` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_symbol_duration` (`tenant_id`,`symbol`,`duration`),
  KEY `idx_symbol` (`symbol`),
  KEY `mt_old_2e400eb8cc1a512a` (`symbol`,`duration`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_t_symbol_duration` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产品期限配置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_symbol_duration` BEFORE UPDATE ON `symbol_duration` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `system_config` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `config_key` varchar(100) NOT NULL,
  `config_value` text,
  `created_at` datetime(6) NOT NULL,
  `description` varchar(200) DEFAULT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `UK_npsxm1erd0lbetjn5d3ayrsof` (`tenant_id`,`config_key`),
  KEY `mt_old_4731e0dd2ba01ae8` (`config_key`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_t_system_config` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_system_config` BEFORE UPDATE ON `system_config` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `mt_t_t_ai_model` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI模型配置';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_t_ai_model` BEFORE UPDATE ON `t_ai_model` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `tenant` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `code` varchar(64) NOT NULL,
  `name` varchar(128) NOT NULL,
  `frontend_host` varchar(253) DEFAULT NULL,
  `status` varchar(32) NOT NULL DEFAULT 'DRAFT',
  `template_version` varchar(32) NOT NULL DEFAULT 'safe-v1',
  `policy_version` bigint(20) NOT NULL DEFAULT '0',
  `session_version` bigint(20) NOT NULL DEFAULT '0',
  `config_ready` bit(1) NOT NULL DEFAULT b'0',
  `domain_verified` bit(1) NOT NULL DEFAULT b'0',
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  `created_at` datetime(6) NOT NULL,
  `entry_host` varchar(253) DEFAULT NULL,
  `entry_enabled` bit(1) NOT NULL DEFAULT b'0',
  `entry_verified` bit(1) NOT NULL DEFAULT b'0',
  `domain_version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_code` (`code`),
  UNIQUE KEY `uk_tenant_host` (`frontend_host`),
  UNIQUE KEY `uq_tenant_entry_host` (`entry_host`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `tenant_domain_binding` (
  `hostname` varchar(253) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `tenant_id` bigint(20) NOT NULL,
  `status` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL,
  `challenge` varchar(32) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `expires_at` datetime(6) DEFAULT NULL,
  `verified_at` datetime(6) DEFAULT NULL,
  `version` bigint(20) NOT NULL DEFAULT '0',
  `domain_role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'FRONTEND',
  `active_role` varchar(16) COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS ((case when (`status` = 'ACTIVE') then `domain_role` else NULL end)) STORED,
  `candidate_role` varchar(16) COLLATE utf8mb4_unicode_ci GENERATED ALWAYS AS ((case when (`status` in ('PENDING','VERIFIED')) then `domain_role` else NULL end)) STORED,
  PRIMARY KEY (`hostname`),
  UNIQUE KEY `uq_domain_active_role` (`tenant_id`,`active_role`),
  UNIQUE KEY `uq_domain_candidate_role` (`tenant_id`,`candidate_role`),
  KEY `ix_domain_candidate` (`tenant_id`,`status`),
  CONSTRAINT `mt_domain_binding_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `tenant_domain_history` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `hostname` varchar(253) NOT NULL,
  `retired_at` datetime(6) DEFAULT NULL,
  `domain_role` varchar(16) NOT NULL DEFAULT 'FRONTEND',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_domain_history` (`hostname`),
  KEY `fk_domain_history_tenant` (`tenant_id`),
  CONSTRAINT `fk_domain_history_tenant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `tenant_policy` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `policy_key` varchar(128) NOT NULL,
  `policy_value` text,
  `locked` bit(1) NOT NULL DEFAULT b'0',
  `version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_policy` (`tenant_id`,`policy_key`),
  CONSTRAINT `fk_tenant_policy` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `tenant_schema_version` (
  `version` bigint(20) NOT NULL,
  `applied_at` datetime(6) NOT NULL,
  `minimum_application_epoch` bigint(20) NOT NULL,
  `business_activation_ready` bit(1) NOT NULL DEFAULT b'0',
  PRIMARY KEY (`version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `trader_audit` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `trader_id` varchar(36) NOT NULL,
  `object_id` varchar(36) DEFAULT NULL,
  `actor` varchar(100) NOT NULL,
  `action` varchar(32) NOT NULL,
  `reason` varchar(1000) NOT NULL,
  `before_json` longtext,
  `after_json` longtext,
  `captured_at` datetime(6) NOT NULL,
  PRIMARY KEY (`id`),
  KEY `trader_audit_scope` (`tenant_id`,`environment`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `trader_equity` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `trader_id` varchar(36) NOT NULL,
  `point_id` varchar(36) NOT NULL,
  `point_at` datetime(6) NOT NULL,
  `net_asset` decimal(38,18) NOT NULL,
  `cash_flow` decimal(38,18) DEFAULT NULL,
  `currency` varchar(12) NOT NULL,
  `source_note` varchar(1000) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trader_point_time` (`tenant_id`,`environment`,`trader_id`,`point_at`),
  UNIQUE KEY `uk_trader_point_id` (`tenant_id`,`environment`,`point_id`),
  CONSTRAINT `fk_trader_equity` FOREIGN KEY (`tenant_id`, `environment`, `trader_id`) REFERENCES `trader_profile` (`tenant_id`, `environment`, `trader_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `trader_history` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `trader_id` varchar(36) NOT NULL,
  `record_id` varchar(36) NOT NULL,
  `record_key` varchar(80) NOT NULL,
  `closed_at` datetime(6) NOT NULL,
  `symbol` varchar(40) NOT NULL,
  `direction` varchar(8) NOT NULL,
  `leverage` decimal(38,18) DEFAULT NULL,
  `quantity` decimal(38,18) DEFAULT NULL,
  `quantity_unit` varchar(16) DEFAULT NULL,
  `pnl` decimal(38,18) DEFAULT NULL,
  `pnl_basis` varchar(16) NOT NULL,
  `fees` decimal(38,18) DEFAULT NULL,
  `currency` varchar(12) NOT NULL,
  `source_note` varchar(1000) DEFAULT NULL,
  `evidence_note` varchar(1000) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trader_record_id` (`tenant_id`,`environment`,`trader_id`,`record_id`),
  UNIQUE KEY `uk_trader_record_key` (`tenant_id`,`environment`,`trader_id`,`record_key`),
  KEY `trader_closed` (`tenant_id`,`environment`,`trader_id`,`closed_at`),
  CONSTRAINT `fk_trader_history` FOREIGN KEY (`tenant_id`, `environment`, `trader_id`) REFERENCES `trader_profile` (`tenant_id`, `environment`, `trader_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `trader_profile` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `environment` varchar(4) NOT NULL,
  `trader_id` varchar(36) NOT NULL,
  `source_type` varchar(16) NOT NULL,
  `status` varchar(16) NOT NULL,
  `name` varchar(80) NOT NULL,
  `avatar_url` varchar(300) DEFAULT NULL,
  `currency` varchar(12) NOT NULL,
  `recommended` bit(1) NOT NULL DEFAULT b'0',
  `sort_order` int(11) NOT NULL DEFAULT '0',
  `updated_at` datetime(6) NOT NULL,
  `data_json` longtext NOT NULL,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trader_profile` (`tenant_id`,`environment`,`trader_id`),
  KEY `trader_public` (`tenant_id`,`environment`,`status`,`recommended`,`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
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
  `min_order_notional` decimal(32,16) DEFAULT NULL,
  `min_order_quantity` decimal(32,16) DEFAULT NULL,
  `quantity_step` decimal(32,16) DEFAULT NULL,
  `quantity_unit_type` varchar(16) DEFAULT NULL,
  `spec_version` bigint(20) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_symbol` (`tenant_id`,`symbol`),
  UNIQUE KEY `UK_s7aam1xh6nnv6ghru10f6vnuh` (`tenant_id`,`market_instrument_key`),
  KEY `idx_category` (`category`),
  KEY `idx_is_hot` (`is_hot`),
  KEY `idx_is_enabled` (`is_enabled`),
  KEY `idx_sort_order` (`sort_order`),
  KEY `mt_old_507e5c3f28785527` (`market_instrument_key`),
  KEY `mt_old_7927b83f824afdab` (`symbol`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_t_trading_symbol` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易对/币种表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_trading_symbol` BEFORE UPDATE ON `trading_symbol` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_transfer_request` (`tenant_id`,`user_id`,`request_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_created_at` (`created_at`),
  KEY `mt_old_625e804fd449f19c` (`user_id`,`request_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_fk_1c701e7a2120e2de41cf` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_transfer_record` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='划转记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_transfer_record` BEFORE UPDATE ON `transfer_record` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `trial_account` (
  `user_id` bigint(20) NOT NULL,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  `available` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `frozen` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `granted` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `consumed` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `profits` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `tenant_id` bigint(20) NOT NULL,
  `expired` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `uncovered_loss` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `trial_eligible` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`user_id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_21882666b7edaf1be239` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_trial_account` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_trial_account` BEFORE UPDATE ON `trial_account` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `trial_grant` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `tenant_id` bigint(20) NOT NULL,
  `row_version` bigint(20) NOT NULL DEFAULT '0',
  `user_id` bigint(20) NOT NULL,
  `campaign_id` bigint(20) DEFAULT NULL,
  `delivery_id` bigint(20) DEFAULT NULL,
  `request_key` varchar(80) COLLATE utf8mb4_unicode_ci NOT NULL,
  `claimed_at` datetime NOT NULL,
  `expires_at` datetime DEFAULT NULL,
  `active` tinyint(1) NOT NULL DEFAULT '1',
  `available` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `frozen` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `consumed` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  `expired` decimal(32,16) NOT NULL DEFAULT '0.0000000000000000',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_trial_grant_request` (`tenant_id`,`user_id`,`request_key`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `ix_trial_grant_user` (`tenant_id`,`user_id`,`id`),
  KEY `ix_trial_grant_campaign` (`tenant_id`,`campaign_id`,`user_id`),
  KEY `mt_fk_3e04cc6256c23dedad69` (`tenant_id`,`delivery_id`),
  CONSTRAINT `mt_fk_061664ca8a3fb6de6312` FOREIGN KEY (`tenant_id`, `campaign_id`) REFERENCES `activity_campaign` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_3e04cc6256c23dedad69` FOREIGN KEY (`tenant_id`, `delivery_id`) REFERENCES `activity_delivery` (`tenant_id`, `id`),
  CONSTRAINT `mt_fk_e6fe59f66115ae0daa18` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_trial_grant` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER mt_immutable_trial_grant BEFORE UPDATE ON trial_grant FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `trial_ledger` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL,
  `reason` varchar(100) NOT NULL,
  `available` decimal(32,16) NOT NULL,
  `frozen` decimal(32,16) NOT NULL,
  `delta` decimal(32,16) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `ix_trial_ledger_user` (`user_id`,`id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_fk_499fc1d44648354ad966` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_499fc1d44648354ad966` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_trial_ledger` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_trial_ledger` BEFORE UPDATE ON `trial_ledger` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  `normalized_email` varchar(128) GENERATED ALWAYS AS (lower(trim(`email`))) STORED,
  `normalized_phone` varchar(32) GENERATED ALWAYS AS (nullif(trim(`phone`),'')) STORED,
  `last_page_code` varchar(32) DEFAULT NULL,
  `last_page_seen_at` datetime(6) DEFAULT NULL,
  `last_page_sequence` bigint(20) DEFAULT NULL,
  `last_device_type` varchar(16) DEFAULT NULL,
  `annual_income` decimal(14,2) DEFAULT NULL,
  `annual_income_currency` varchar(3) DEFAULT NULL,
  `avatar_url` varchar(300) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `my_invite_code` (`tenant_id`,`my_invite_code`),
  UNIQUE KEY `uk_tenant_normalized_email` (`tenant_id`,`normalized_email`),
  UNIQUE KEY `uk_tenant_normalized_phone` (`tenant_id`,`normalized_phone`),
  KEY `idx_parent_user_id` (`parent_user_id`),
  KEY `idx_user_type` (`user_type`),
  KEY `mt_old_82244417f956ac7c` (`email`),
  KEY `mt_old_471b0683539fe0b1` (`my_invite_code`),
  KEY `mt_old_45569da57f4b7bf4` (`phone`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  KEY `mt_fk_72d601353dacb02f6447` (`tenant_id`,`parent_user_id`),
  KEY `ix_tenant_online` (`tenant_id`,`last_activity_at`,`id`),
  CONSTRAINT `mt_fk_72d601353dacb02f6447` FOREIGN KEY (`tenant_id`, `parent_user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_user_account` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=7000001 DEFAULT CHARSET=utf8mb4;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_user_account` BEFORE UPDATE ON `user_account` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_action` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID（代理ID）',
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID',
  `action_code` varchar(100) NOT NULL COMMENT '操作代码',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_user_menu_action` (`tenant_id`,`user_id`,`menu_id`,`action_code`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_menu_id` (`menu_id`),
  KEY `mt_old_5e8199ed62f57e99` (`user_id`,`menu_id`,`action_code`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_fk_e3389fab52e8a4faadae` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_user_action` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`),
  CONSTRAINT `user_action_ibfk_1` FOREIGN KEY (`user_id`) REFERENCES `user_account` (`id`) ON DELETE CASCADE,
  CONSTRAINT `user_action_ibfk_2` FOREIGN KEY (`menu_id`) REFERENCES `admin_menu` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户操作权限表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_user_action` BEFORE UPDATE ON `user_action` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_fk_28c4ef3f1dec09ee4a0a` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_28c4ef3f1dec09ee4a0a` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_user_bank_card` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户银行卡绑定表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_user_bank_card` BEFORE UPDATE ON `user_bank_card` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_currency_network` (`currency`,`network`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_fk_6599d5f7c60256bd9f98` (`tenant_id`,`user_id`),
  CONSTRAINT `mt_fk_6599d5f7c60256bd9f98` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_user_digital_address` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户数字货币地址绑定表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_user_digital_address` BEFORE UPDATE ON `user_digital_address` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_id_sequence` (
  `sequence_name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `next_val` bigint(20) NOT NULL,
  PRIMARY KEY (`sequence_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8 */;
CREATE TABLE `user_menu` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) NOT NULL COMMENT '用户ID（user_account表的id）',
  `menu_id` bigint(20) NOT NULL COMMENT '菜单ID（admin_menu表的id）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `UKr3a3ma3v059ubr7pchn83hcl5` (`tenant_id`,`user_id`,`menu_id`),
  UNIQUE KEY `uk_user_menu` (`tenant_id`,`user_id`,`menu_id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_menu_id` (`menu_id`),
  KEY `mt_old_2b38be039022650d` (`user_id`,`menu_id`),
  KEY `mt_old_d0307b96a663dd24` (`user_id`,`menu_id`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_fk_1e8ef3f7cc86f5982b54` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_user_menu` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户菜单关联表（代理用户权限）';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_user_menu` BEFORE UPDATE ON `user_menu` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  KEY `idx_email_scene` (`email`,`scene`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  CONSTRAINT `mt_t_verify_code` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='邮箱验证码记录';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_verify_code` BEFORE UPDATE ON `verify_code` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
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
  `tenant_id` bigint(20) NOT NULL,
  `request_key` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `request_hash` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `mt_tenant_identity` (`tenant_id`,`id`),
  UNIQUE KEY `uk_withdraw_record_request` (`tenant_id`,`user_id`,`request_key`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_status` (`status`),
  KEY `idx_status_type` (`status`,`type`),
  KEY `idx_bank_card_number` (`bank_card_number`),
  KEY `idx_country` (`country`),
  KEY `mt_tenant_created` (`tenant_id`,`created_at`),
  KEY `mt_tenant_status` (`tenant_id`,`status`),
  CONSTRAINT `mt_fk_b64ca5123f44eefd042f` FOREIGN KEY (`tenant_id`, `user_id`) REFERENCES `user_account` (`tenant_id`, `id`),
  CONSTRAINT `mt_t_withdraw_record` FOREIGN KEY (`tenant_id`) REFERENCES `tenant` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提现记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50017 DEFINER=CURRENT_USER*/ /*!50003 TRIGGER `mt_immutable_withdraw_record` BEFORE UPDATE ON `withdraw_record` FOR EACH ROW BEGIN IF NEW.tenant_id<>OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF; END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_general_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'STRICT_TRANS_TABLES,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
CREATE DEFINER=CURRENT_USER PROCEDURE `joint_s4_fence`(IN tenant BIGINT,IN symbol BIGINT,IN generation BIGINT)
BEGIN
 IF @mt705_s4_tenant IS NULL OR @mt705_s4_symbol IS NULL OR @mt705_s4_generation IS NULL OR @mt705_s4_revision IS NULL
 OR @mt705_s4_tenant<>tenant OR @mt705_s4_symbol<>symbol OR @mt705_s4_generation<>generation
 OR NOT EXISTS(SELECT 1 FROM market_engine_runtime WHERE tenant_id=tenant AND symbol_id=symbol
   AND writer_generation=generation AND control_revision=@mt705_s4_revision)
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Projection runtime authority fenced'; END IF;
END ;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

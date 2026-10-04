-- Forward-only S3 protocol. Preserve all old order/receipt amounts and nullable progress.
-- Preflight the actual tenant-scoped receipts before any additive DDL.
DELIMITER $$
CREATE PROCEDURE joint_s3_require_receipts()
BEGIN
 IF NOT EXISTS (SELECT 1 FROM (SELECT TABLE_NAME,INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS fields
     FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND NON_UNIQUE=0
     AND TABLE_NAME='financial_yield_record' GROUP BY TABLE_NAME,INDEX_NAME) k WHERE k.fields='tenant_id,order_id,yield_date')
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing unique tenant/order/yield-date receipt'; END IF;
 IF NOT EXISTS (SELECT 1 FROM (SELECT TABLE_NAME,INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS fields
     FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND NON_UNIQUE=0
     AND TABLE_NAME='financial_order' GROUP BY TABLE_NAME,INDEX_NAME) k WHERE k.fields='tenant_id,user_id,request_key')
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Missing unique tenant/user/request receipt'; END IF;
END$$
CALL joint_s3_require_receipts()$$
DROP PROCEDURE joint_s3_require_receipts$$
DELIMITER ;
ALTER TABLE financial_order ADD COLUMN last_accrued_date DATE NULL, ADD COLUMN accrued_yield DECIMAL(32,16) NULL;
ALTER TABLE contract_order ADD COLUMN promotion_pending BIT(1) NOT NULL DEFAULT b'0', ADD INDEX ix_contract_promotion(tenant_id,promotion_pending,id);
ALTER TABLE option_order ADD COLUMN promotion_pending BIT(1) NOT NULL DEFAULT b'0', ADD INDEX ix_option_promotion(tenant_id,promotion_pending,id);
-- NULL/NULL historical progress is reconstructed in bounded 31-day units, never MAX(receipt_date).
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026100401,UTC_TIMESTAMP(6),2026100401,0);

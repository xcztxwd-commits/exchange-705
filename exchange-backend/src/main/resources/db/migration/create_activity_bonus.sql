-- Additive MySQL 5.7+ migration. Back up first. JPA ddl-auto=update creates the same schema in development.
CREATE TABLE IF NOT EXISTS activity_campaign (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, row_version BIGINT NOT NULL DEFAULT 0,
 name VARCHAR(120) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'DRAFT', template BIT NOT NULL DEFAULT 0,
 auto_popup BIT NOT NULL DEFAULT 1, animation VARCHAR(16) NOT NULL DEFAULT 'GIFT', default_locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN',
 translations LONGTEXT NOT NULL, amount DECIMAL(32,16) NOT NULL, recent_login_days INT NOT NULL DEFAULT 3,
 max_claims INT NOT NULL DEFAULT 1000, claim_count INT NOT NULL DEFAULT 0, budget DECIMAL(32,16) NOT NULL,
 granted DECIMAL(32,16) NOT NULL DEFAULT 0, starts_at DATETIME(6), ends_at DATETIME(6), created_at DATETIME(6), updated_at DATETIME(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS activity_delivery (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, row_version BIGINT NOT NULL DEFAULT 0, campaign_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
 sent_at DATETIME(6), received_at DATETIME(6), opened_at DATETIME(6), closed_at DATETIME(6), claimed_at DATETIME(6),
 open_count INT NOT NULL DEFAULT 0, close_count INT NOT NULL DEFAULT 0, sent_by VARCHAR(120),
 UNIQUE KEY uk_activity_recipient(campaign_id,user_id), KEY ix_activity_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS trial_account (
 user_id BIGINT NOT NULL PRIMARY KEY, row_version BIGINT NOT NULL DEFAULT 0,
 available DECIMAL(32,16) NOT NULL DEFAULT 0, frozen DECIMAL(32,16) NOT NULL DEFAULT 0,
 granted DECIMAL(32,16) NOT NULL DEFAULT 0, consumed DECIMAL(32,16) NOT NULL DEFAULT 0, profits DECIMAL(32,16) NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS trial_ledger (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, reason VARCHAR(100) NOT NULL,
 available DECIMAL(32,16) NOT NULL, frozen DECIMAL(32,16) NOT NULL, delta DECIMAL(32,16) NOT NULL, created_at DATETIME(6),
 KEY ix_trial_ledger_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
SET @activity_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='contract_order' AND column_name='trial_reserved')=0,
 'ALTER TABLE contract_order ADD COLUMN trial_reserved DECIMAL(32,16) NULL','SELECT 1');
PREPARE activity_stmt FROM @activity_sql; EXECUTE activity_stmt; DEALLOCATE PREPARE activity_stmt;
SET @activity_sql = IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='option_order' AND column_name='trial_reserved')=0,
 'ALTER TABLE option_order ADD COLUMN trial_reserved DECIMAL(32,16) NULL','SELECT 1');
PREPARE activity_stmt FROM @activity_sql; EXECUTE activity_stmt; DEALLOCATE PREPARE activity_stmt;

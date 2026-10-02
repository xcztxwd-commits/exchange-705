-- MySQL 5.7. Apply only after stopped writes, verified backup, preflight and rehearsal.
-- DDL commits implicitly. Do not run Hibernate ddl-auto=update during this migration.
CREATE TABLE IF NOT EXISTS tenant (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 code VARCHAR(64) NOT NULL, name VARCHAR(128) NOT NULL,
 frontend_host VARCHAR(253) NULL, status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
 template_version VARCHAR(32) NOT NULL DEFAULT 'safe-v1', policy_version BIGINT NOT NULL DEFAULT 0,
 session_version BIGINT NOT NULL DEFAULT 0,
 config_ready BIT NOT NULL DEFAULT 0, domain_verified BIT NOT NULL DEFAULT 0,
 row_version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_tenant_code(code), UNIQUE KEY uk_tenant_host(frontend_host)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- No inferred domain, no privileged control account and no enabled business are seeded.
INSERT INTO tenant(id,code,name,status,created_at)
 SELECT 1,'default','Default tenant','MAINTENANCE',UTC_TIMESTAMP(6) FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM tenant WHERE id=1);
CREATE TABLE IF NOT EXISTS tenant_policy (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 policy_key VARCHAR(128) NOT NULL, policy_value TEXT, locked BIT NOT NULL DEFAULT 0,
 version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_tenant_policy(tenant_id,policy_key),
 CONSTRAINT fk_tenant_policy FOREIGN KEY(tenant_id) REFERENCES tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS control_admin (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, account VARCHAR(64) NOT NULL,
 password_hash VARCHAR(128) NOT NULL, enabled BIT NOT NULL DEFAULT 1,
 mfa_secret TEXT, mfa_enabled BIT NOT NULL DEFAULT 0,
 session_version BIGINT NOT NULL DEFAULT 0, row_version BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY uk_control_account(account)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS control_access_session (
 id VARCHAR(64) NOT NULL PRIMARY KEY, actor_id BIGINT NOT NULL, tenant_id BIGINT NOT NULL,
 ticket_hash VARCHAR(64) NOT NULL, browser_binding_hash VARCHAR(64) NOT NULL,
 actor_version BIGINT NOT NULL, expires_at DATETIME(6) NOT NULL,
 ticket_expires_at DATETIME(6) NOT NULL, last_activity_at DATETIME(6) NOT NULL,
 consumed BIT NOT NULL DEFAULT 0, revoked BIT NOT NULL DEFAULT 0,
 row_version BIGINT NOT NULL DEFAULT 0, created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_control_ticket(ticket_hash), KEY ix_control_session_tenant(tenant_id,expires_at),
 CONSTRAINT fk_control_session_actor FOREIGN KEY(actor_id) REFERENCES control_admin(id),
 CONSTRAINT fk_control_session_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS control_audit_log (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, actor_id BIGINT NULL, tenant_id BIGINT NULL,
 access_session_id VARCHAR(64), request_id VARCHAR(64), action VARCHAR(128) NOT NULL,
 object_ref VARCHAR(255), outcome VARCHAR(32) NOT NULL, detail TEXT, reason VARCHAR(512),
 remote_address VARCHAR(64), created_at DATETIME(6) NOT NULL,
 KEY ix_control_audit_tenant(tenant_id,created_at,id), KEY ix_control_audit_actor(actor_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS backend_login (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, normalized_account VARCHAR(128) NOT NULL,
 tenant_id BIGINT NOT NULL, subject_type VARCHAR(16) NOT NULL,
 admin_user_id BIGINT NULL, user_id BIGINT NULL, enabled BIT NOT NULL DEFAULT 1,
 UNIQUE KEY uk_backend_account(normalized_account),
 UNIQUE KEY uk_backend_admin(tenant_id,admin_user_id), UNIQUE KEY uk_backend_agent(tenant_id,user_id),
 CONSTRAINT fk_backend_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tenant_domain_history (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL,
 hostname VARCHAR(253) NOT NULL, retired_at DATETIME(6),
 UNIQUE KEY uk_domain_history(hostname),
 CONSTRAINT fk_domain_history_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS tenant_schema_version (
 version BIGINT NOT NULL PRIMARY KEY, applied_at DATETIME(6) NOT NULL,
 minimum_application_epoch BIGINT NOT NULL, business_activation_ready BIT NOT NULL DEFAULT 0,
 CHECK (business_activation_ready IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- MySQL 5.7 does NOT enforce CHECK; the following triggers enforce actual boundaries.
DELIMITER $$
CREATE TRIGGER mt_control_audit_no_update BEFORE UPDATE ON control_audit_log FOR EACH ROW
 BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Control audit is append-only'; END$$
CREATE TRIGGER mt_control_audit_no_delete BEFORE DELETE ON control_audit_log FOR EACH ROW
 BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Control audit is append-only'; END$$
CREATE TRIGGER mt_backend_subject_insert BEFORE INSERT ON backend_login FOR EACH ROW
 BEGIN
 IF NOT ((NEW.subject_type='ADMIN' AND NEW.admin_user_id IS NOT NULL AND NEW.user_id IS NULL)
      OR (NEW.subject_type='AGENT' AND NEW.user_id IS NOT NULL AND NEW.admin_user_id IS NULL))
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid backend principal'; END IF;
 IF BINARY NEW.normalized_account <> BINARY LOWER(TRIM(NEW.normalized_account)) OR NEW.normalized_account=''
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Backend account must be normalized'; END IF;
 END$$
CREATE TRIGGER mt_backend_subject_update BEFORE UPDATE ON backend_login FOR EACH ROW
 BEGIN
 IF NOT ((NEW.subject_type='ADMIN' AND NEW.admin_user_id IS NOT NULL AND NEW.user_id IS NULL)
      OR (NEW.subject_type='AGENT' AND NEW.user_id IS NOT NULL AND NEW.admin_user_id IS NULL))
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Invalid backend principal'; END IF;
 IF BINARY NEW.normalized_account <> BINARY LOWER(TRIM(NEW.normalized_account)) OR NEW.normalized_account=''
 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Backend account must be normalized'; END IF;
 IF NEW.tenant_id <> OLD.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Tenant is immutable'; END IF;
 END$$
DELIMITER ;

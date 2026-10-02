ALTER TABLE user_account
 ADD COLUMN normalized_email VARCHAR(128) GENERATED ALWAYS AS (LOWER(TRIM(email))) STORED,
 ADD COLUMN normalized_phone VARCHAR(32) GENERATED ALWAYS AS (NULLIF(TRIM(phone),'')) STORED,
 ADD UNIQUE KEY uk_tenant_normalized_email(tenant_id,normalized_email),
 ADD UNIQUE KEY uk_tenant_normalized_phone(tenant_id,normalized_phone),
 ADD INDEX ix_tenant_online(tenant_id,last_activity_at,id);
ALTER TABLE user_account MODIFY id BIGINT NOT NULL AUTO_INCREMENT, AUTO_INCREMENT=7000001;
ALTER TABLE user_account ADD COLUMN last_page_code VARCHAR(32),
 ADD COLUMN last_page_seen_at DATETIME(6), ADD COLUMN last_page_sequence BIGINT,
 ADD COLUMN last_device_type VARCHAR(16);
ALTER TABLE support_conversation ADD COLUMN legal_hold BIT NOT NULL DEFAULT 0;
ALTER TABLE support_conversation ADD COLUMN control_actor_id BIGINT NULL,
 ADD CONSTRAINT mt_support_control_actor FOREIGN KEY(control_actor_id) REFERENCES control_admin(id);
ALTER TABLE inbox_letter MODIFY admin_id BIGINT NULL, ADD COLUMN control_actor_id BIGINT NULL,
 ADD UNIQUE KEY uk_inbox_control_request(tenant_id,control_actor_id,request_id,user_id),
 ADD CONSTRAINT mt_inbox_control_actor FOREIGN KEY(control_actor_id) REFERENCES control_admin(id);
ALTER TABLE admin_user ADD COLUMN must_change_password BIT NOT NULL DEFAULT 0;
INSERT INTO backend_login(normalized_account,tenant_id,subject_type,admin_user_id,enabled)
 SELECT LOWER(TRIM(account)),tenant_id,'ADMIN',id,enabled FROM admin_user;
INSERT INTO backend_login(normalized_account,tenant_id,subject_type,user_id,enabled)
 SELECT LOWER(TRIM(email)),tenant_id,'AGENT',id,IF(status='normal',1,0) FROM user_account WHERE user_type='agent';
-- Revoke pre-tenant sessions. The runner fingerprints history excluding only these known ephemeral fields.
UPDATE admin_user SET current_token=NULL,updated_at=updated_at;
UPDATE user_account SET current_token=NULL,updated_at=updated_at;
INSERT INTO tenant_policy(tenant_id,policy_key,policy_value,locked,version)
 VALUES(1,'retention.auto_delete_enabled','false',1,0),(1,'retention.keep_days','365',1,0);
INSERT INTO tenant_schema_version(version,applied_at,minimum_application_epoch,business_activation_ready)
 VALUES(2026092902,UTC_TIMESTAMP(6),2026092902,0);
DROP PROCEDURE mt_link;
DROP PROCEDURE mt_scope;
DROP PROCEDURE mt_exec;

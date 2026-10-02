-- Missing feature tables from the current source, before tenant scoping.
CREATE TABLE IF NOT EXISTS support_conversation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, active_user_id BIGINT,
 admin_id BIGINT, status VARCHAR(16) NOT NULL, client_ip VARCHAR(64) NOT NULL,
 created_at DATETIME(6) NOT NULL, accepted_at DATETIME(6), closed_at DATETIME(6), updated_at DATETIME(6) NOT NULL,
 user_read_id BIGINT NOT NULL DEFAULT 0, admin_read_id BIGINT NOT NULL DEFAULT 0, last_hash VARCHAR(64),
 UNIQUE KEY uk_active_user(active_user_id), KEY support_queue(status,id),
 KEY support_owner(admin_id,status), KEY support_user(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS support_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, conversation_id BIGINT NOT NULL,
 sender VARCHAR(12) NOT NULL, sender_id BIGINT NOT NULL, sender_name VARCHAR(128) NOT NULL,
 request_id VARCHAR(64) NOT NULL, text VARCHAR(4000) NOT NULL, image BIT NOT NULL,
 image_hash VARCHAR(64) NOT NULL, created_at DATETIME(6) NOT NULL,
 previous_hash VARCHAR(64) NOT NULL, hash VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_support_request(conversation_id,sender,sender_id,request_id), KEY support_message_cursor(conversation_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS support_attachment(message_id BIGINT NOT NULL PRIMARY KEY, content LONGBLOB NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS support_presence(admin_id BIGINT NOT NULL PRIMARY KEY,accepting BIT NOT NULL,heartbeat_at DATETIME(6)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS inbox_letter (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL, admin_id BIGINT NOT NULL,
 request_id VARCHAR(64) NOT NULL, title VARCHAR(120) NOT NULL, content VARCHAR(4000) NOT NULL,
 created_at DATETIME(6) NOT NULL, read_at DATETIME(6),
 UNIQUE KEY uk_inbox_request(admin_id,request_id,user_id), KEY inbox_recipient(user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS simulation_seed(user_id BIGINT NOT NULL PRIMARY KEY,amount_per_wallet DECIMAL(32,16) NOT NULL,created_at DATETIME(6) NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS asset_history_revision(user_id BIGINT NOT NULL,basis_version VARCHAR(32) NOT NULL,level TINYINT NOT NULL,revision BIGINT NOT NULL,PRIMARY KEY(user_id,basis_version,level)) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS balance_adjustment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,user_id BIGINT NOT NULL,
 request_key VARCHAR(64) NOT NULL,request_hash VARCHAR(64) NOT NULL,
 actor_type VARCHAR(16),actor_id BIGINT,reason VARCHAR(500),changes TEXT,created_at DATETIME(6) NOT NULL,
 UNIQUE KEY uk_balance_request(request_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

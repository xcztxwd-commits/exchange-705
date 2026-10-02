-- 添加登录IP相关字段到user_account表

ALTER TABLE user_account 
ADD COLUMN last_login_ip VARCHAR(64) COMMENT '最后登录IP',
ADD COLUMN last_login_region VARCHAR(128) COMMENT '最后登录地区',
ADD COLUMN last_login_at DATETIME COMMENT '最后登录时间';

-- 添加索引以优化查询
ALTER TABLE user_account ADD INDEX idx_last_login_ip (last_login_ip);
ALTER TABLE user_account ADD INDEX idx_last_login_at (last_login_at);


-- 添加登录域名字段到user_account表（简化版本）
-- 如果列已存在，执行会报错，可以忽略

-- 添加last_login_domain字段（如果已存在会报错，可以忽略）
ALTER TABLE user_account 
ADD COLUMN last_login_domain VARCHAR(255) COMMENT '最后登录域名';

-- 添加索引（如果已存在会报错，可以忽略）
ALTER TABLE user_account ADD INDEX idx_last_login_domain (last_login_domain);





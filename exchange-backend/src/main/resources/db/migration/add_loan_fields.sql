-- 为loan_record表添加实名信息字段（如果表已存在但字段不存在）
ALTER TABLE `loan_record` 
ADD COLUMN IF NOT EXISTS `real_name` VARCHAR(100) COMMENT '真实姓名' AFTER `user_id`,
ADD COLUMN IF NOT EXISTS `id_number` VARCHAR(50) COMMENT '身份证号' AFTER `real_name`,
ADD COLUMN IF NOT EXISTS `phone` VARCHAR(32) COMMENT '电话' AFTER `id_number`,
ADD COLUMN IF NOT EXISTS `address` VARCHAR(500) COMMENT '家庭住址' AFTER `phone`;

-- 增加签名图片字段长度（base64可能很长）
ALTER TABLE `loan_record` 
MODIFY COLUMN `signature_image` VARCHAR(2000) COMMENT '签名图片URL或base64';




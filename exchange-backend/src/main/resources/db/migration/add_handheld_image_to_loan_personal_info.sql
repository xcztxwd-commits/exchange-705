-- 添加手持身份证图片字段到贷款个人信息表
ALTER TABLE `loan_personal_info` 
ADD COLUMN `handheld_image` VARCHAR(500) COMMENT '手持身份证图片' AFTER `id_back_image`;




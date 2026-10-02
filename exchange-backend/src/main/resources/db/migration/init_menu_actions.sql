-- 初始化菜单操作映射数据
-- 为每个菜单定义可用的操作

-- 用户管理菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'reset_password', '重置密码', 1 FROM admin_menu WHERE menu_code = 'users'
ON DUPLICATE KEY UPDATE action_name = '重置密码';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'freeze_user', '冻结', 2 FROM admin_menu WHERE menu_code = 'users'
ON DUPLICATE KEY UPDATE action_name = '冻结';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'unfreeze_user', '解冻', 3 FROM admin_menu WHERE menu_code = 'users'
ON DUPLICATE KEY UPDATE action_name = '解冻';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'ban_user', '禁用', 4 FROM admin_menu WHERE menu_code = 'users'
ON DUPLICATE KEY UPDATE action_name = '禁用';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'set_agent', '设为代理', 5 FROM admin_menu WHERE menu_code = 'users'
ON DUPLICATE KEY UPDATE action_name = '设为代理';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'unset_agent', '取消代理', 6 FROM admin_menu WHERE menu_code = 'users'
ON DUPLICATE KEY UPDATE action_name = '取消代理';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'modify_balance', '修改余额', 7 FROM admin_menu WHERE menu_code = 'users'
ON DUPLICATE KEY UPDATE action_name = '修改余额';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'wallet_management', '收款管理', 8 FROM admin_menu WHERE menu_code = 'users'
ON DUPLICATE KEY UPDATE action_name = '收款管理';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'view_subordinates', '下级用户', 9 FROM admin_menu WHERE menu_code = 'users'
ON DUPLICATE KEY UPDATE action_name = '下级用户';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'delete_user', '删除', 10 FROM admin_menu WHERE menu_code = 'users'
ON DUPLICATE KEY UPDATE action_name = '删除';

-- 充值审核菜单的操作（支持两种菜单代码格式）
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'approve_deposit', '审核通过', 1 FROM admin_menu WHERE menu_code = 'deposit-review' OR menu_code = 'deposit_review'
ON DUPLICATE KEY UPDATE action_name = '审核通过';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'reject_deposit', '拒绝', 2 FROM admin_menu WHERE menu_code = 'deposit-review' OR menu_code = 'deposit_review'
ON DUPLICATE KEY UPDATE action_name = '拒绝';

-- 提现审核菜单的操作（支持两种菜单代码格式）
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'approve_withdraw', '审核通过', 1 FROM admin_menu WHERE menu_code = 'withdraw-review' OR menu_code = 'withdraw_review'
ON DUPLICATE KEY UPDATE action_name = '审核通过';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'reject_withdraw', '驳回', 2 FROM admin_menu WHERE menu_code = 'withdraw-review' OR menu_code = 'withdraw_review'
ON DUPLICATE KEY UPDATE action_name = '驳回';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'complete_withdraw', '标记完成', 3 FROM admin_menu WHERE menu_code = 'withdraw-review' OR menu_code = 'withdraw_review'
ON DUPLICATE KEY UPDATE action_name = '标记完成';

-- 订单管理菜单的操作（合约订单）
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'close_order', '平仓', 1 FROM admin_menu WHERE menu_code = 'orders'
ON DUPLICATE KEY UPDATE action_name = '平仓';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'cancel_order', '撤单', 2 FROM admin_menu WHERE menu_code = 'orders'
ON DUPLICATE KEY UPDATE action_name = '撤单';

-- 订单管理菜单的操作（期货订单）
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'set_profit', '设为盈利', 3 FROM admin_menu WHERE menu_code = 'orders'
ON DUPLICATE KEY UPDATE action_name = '设为盈利';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'set_loss', '设为亏损', 4 FROM admin_menu WHERE menu_code = 'orders'
ON DUPLICATE KEY UPDATE action_name = '设为亏损';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'clear_preset', '清除预设', 5 FROM admin_menu WHERE menu_code = 'orders'
ON DUPLICATE KEY UPDATE action_name = '清除预设';

-- 实名审核菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'approve_kyc', '通过', 1 FROM admin_menu WHERE menu_code = 'kyc-review' OR menu_code = 'kyc_review'
ON DUPLICATE KEY UPDATE action_name = '通过';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'reject_kyc', '拒绝', 2 FROM admin_menu WHERE menu_code = 'kyc-review' OR menu_code = 'kyc_review'
ON DUPLICATE KEY UPDATE action_name = '拒绝';

-- 贷款审核菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'view_loan_detail', '查看详情', 1 FROM admin_menu WHERE menu_code = 'loan-review' OR menu_code = 'loan_review'
ON DUPLICATE KEY UPDATE action_name = '查看详情';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'approve_loan', '通过', 2 FROM admin_menu WHERE menu_code = 'loan-review' OR menu_code = 'loan_review'
ON DUPLICATE KEY UPDATE action_name = '通过';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'reject_loan', '拒绝', 3 FROM admin_menu WHERE menu_code = 'loan-review' OR menu_code = 'loan_review'
ON DUPLICATE KEY UPDATE action_name = '拒绝';

-- 个人信息审核菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'approve_personal_info', '通过', 1 FROM admin_menu WHERE menu_code = 'loan-personal-info-review' OR menu_code = 'loan_personal_info_review'
ON DUPLICATE KEY UPDATE action_name = '通过';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'reject_personal_info', '拒绝', 2 FROM admin_menu WHERE menu_code = 'loan-personal-info-review' OR menu_code = 'loan_personal_info_review'
ON DUPLICATE KEY UPDATE action_name = '拒绝';

-- 角色管理菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'edit_role', '编辑', 1 FROM admin_menu WHERE menu_code = 'roles'
ON DUPLICATE KEY UPDATE action_name = '编辑';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'assign_permission', '分配权限', 2 FROM admin_menu WHERE menu_code = 'roles'
ON DUPLICATE KEY UPDATE action_name = '分配权限';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'delete_role', '删除', 3 FROM admin_menu WHERE menu_code = 'roles'
ON DUPLICATE KEY UPDATE action_name = '删除';

-- 币种管理菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'edit_symbol', '编辑', 1 FROM admin_menu WHERE menu_code = 'symbols'
ON DUPLICATE KEY UPDATE action_name = '编辑';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'delete_symbol', '删除', 2 FROM admin_menu WHERE menu_code = 'symbols'
ON DUPLICATE KEY UPDATE action_name = '删除';

-- 期限设置菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'edit_duration', '编辑', 1 FROM admin_menu WHERE menu_code = 'durations'
ON DUPLICATE KEY UPDATE action_name = '编辑';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'delete_duration', '删除', 2 FROM admin_menu WHERE menu_code = 'durations'
ON DUPLICATE KEY UPDATE action_name = '删除';

-- 充值设置菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'edit_deposit_setting', '编辑', 1 FROM admin_menu WHERE menu_code = 'deposit-settings' OR menu_code = 'deposit_settings'
ON DUPLICATE KEY UPDATE action_name = '编辑';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'delete_deposit_setting', '删除', 2 FROM admin_menu WHERE menu_code = 'deposit-settings' OR menu_code = 'deposit_settings'
ON DUPLICATE KEY UPDATE action_name = '删除';

-- 贷款设置菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'edit_loan_setting', '编辑', 1 FROM admin_menu WHERE menu_code = 'loan-settings' OR menu_code = 'loan_settings'
ON DUPLICATE KEY UPDATE action_name = '编辑';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'delete_loan_setting', '删除', 2 FROM admin_menu WHERE menu_code = 'loan-settings' OR menu_code = 'loan_settings'
ON DUPLICATE KEY UPDATE action_name = '删除';

-- 理财产品菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'edit_financial_product', '编辑', 1 FROM admin_menu WHERE menu_code = 'financial-products' OR menu_code = 'financial_products'
ON DUPLICATE KEY UPDATE action_name = '编辑';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'delete_financial_product', '删除', 2 FROM admin_menu WHERE menu_code = 'financial-products' OR menu_code = 'financial_products'
ON DUPLICATE KEY UPDATE action_name = '删除';

-- 理财订单菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'view_financial_order', '查看详情', 1 FROM admin_menu WHERE menu_code = 'financial-orders' OR menu_code = 'financial_orders'
ON DUPLICATE KEY UPDATE action_name = '查看详情';

-- 公告管理菜单的操作
INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'edit_announcement', '编辑', 1 FROM admin_menu WHERE menu_code = 'announcement' OR menu_code = 'announcements'
ON DUPLICATE KEY UPDATE action_name = '编辑';

INSERT INTO menu_action (menu_id, action_code, action_name, sort_order) 
SELECT id, 'delete_announcement', '删除', 2 FROM admin_menu WHERE menu_code = 'announcement' OR menu_code = 'announcements'
ON DUPLICATE KEY UPDATE action_name = '删除';


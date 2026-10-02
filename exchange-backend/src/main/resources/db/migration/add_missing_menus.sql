-- 添加缺失的菜单项（订单管理、期限设置等）

-- 检查并添加"期限设置"菜单
INSERT INTO admin_menu (menu_name, menu_code, menu_type, path, icon, sort_order, parent_id, status)
SELECT '期限设置', 'durations', 'menu', '/durations', 'Timer', 3, 0, 'active'
WHERE NOT EXISTS (
    SELECT 1 FROM admin_menu WHERE menu_code = 'durations'
);

-- 检查并添加"订单管理"菜单
INSERT INTO admin_menu (menu_name, menu_code, menu_type, path, icon, sort_order, parent_id, status)
SELECT '订单管理', 'orders', 'menu', '/orders', 'Document', 4, 0, 'active'
WHERE NOT EXISTS (
    SELECT 1 FROM admin_menu WHERE menu_code = 'orders'
);

-- 检查并添加"个人信息审核"菜单
INSERT INTO admin_menu (menu_name, menu_code, menu_type, path, icon, sort_order, parent_id, status)
SELECT '个人信息审核', 'loan-personal-info-review', 'menu', '/loan-personal-info-review', 'UserFilled', 10, 0, 'active'
WHERE NOT EXISTS (
    SELECT 1 FROM admin_menu WHERE menu_code = 'loan-personal-info-review'
);

-- 检查并添加"理财订单"菜单
INSERT INTO admin_menu (menu_name, menu_code, menu_type, path, icon, sort_order, parent_id, status)
SELECT '理财订单', 'financial-orders', 'menu', '/financial-orders', 'Document', 9, 0, 'active'
WHERE NOT EXISTS (
    SELECT 1 FROM admin_menu WHERE menu_code = 'financial-orders'
);

-- 检查并添加"代理管理"菜单
INSERT INTO admin_menu (menu_name, menu_code, menu_type, path, icon, sort_order, parent_id, status)
SELECT '代理管理', 'agents', 'menu', '/agents', 'Avatar', 14, 0, 'active'
WHERE NOT EXISTS (
    SELECT 1 FROM admin_menu WHERE menu_code = 'agents'
);

-- 为超级管理员角色分配新添加的菜单权限
INSERT INTO admin_role_menu (role_id, menu_id)
SELECT 1, id FROM admin_menu 
WHERE menu_code IN ('durations', 'orders', 'loan-personal-info-review', 'financial-orders', 'agents')
AND id NOT IN (SELECT menu_id FROM admin_role_menu WHERE role_id = 1);

SELECT '✅ 缺失的菜单已添加！' as message;
SELECT '✅ 已为超级管理员分配新菜单权限' as message;





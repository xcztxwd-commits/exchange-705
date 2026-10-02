-- 添加操作日志和数据统计菜单（不加入代理菜单分配）

-- 操作日志菜单
INSERT INTO admin_menu (menu_name, menu_code, menu_type, path, icon, sort_order, parent_id, status) 
VALUES ('操作日志', 'operation_log', 'menu', '/operation-log', 'document', 14, 0, 'active')
ON DUPLICATE KEY UPDATE menu_name = '操作日志';

-- 数据统计菜单
INSERT INTO admin_menu (menu_name, menu_code, menu_type, path, icon, sort_order, parent_id, status) 
VALUES ('数据统计', 'statistics', 'menu', '/statistics', 'data-analysis', 15, 0, 'active')
ON DUPLICATE KEY UPDATE menu_name = '数据统计';





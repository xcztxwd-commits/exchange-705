package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.AdminMenu;
import com.gtcfesk.exchange.repository.AdminMenuRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdminMenuService {

    @Autowired
    private AdminMenuRepository menuRepository;

    /**
     * 获取所有菜单
     */
    public List<AdminMenu> getAllMenus() {
        return menuRepository.findByStatusOrderBySortOrderAsc("active");
    }

    /**
     * 获取菜单树
     */
    public List<AdminMenu> getMenuTree() {
        List<AdminMenu> allMenus = getAllMenus();
        
        // 构建菜单树
        Map<Long, AdminMenu> menuMap = new HashMap<>();
        for (AdminMenu menu : allMenus) {
            menuMap.put(menu.getId(), menu);
            menu.setChildren(new ArrayList<>());
        }
        
        List<AdminMenu> rootMenus = new ArrayList<>();
        for (AdminMenu menu : allMenus) {
            if (menu.getParentId() == 0) {
                rootMenus.add(menu);
            } else {
                AdminMenu parent = menuMap.get(menu.getParentId());
                if (parent != null) {
                    parent.getChildren().add(menu);
                }
            }
        }
        
        return rootMenus;
    }

    /**
     * 获取扁平菜单列表（用于前端树形选择）
     */
    public List<Map<String, Object>> getMenuListForSelection() {
        List<AdminMenu> menus = getAllMenus();
        return menus.stream().map(menu -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", menu.getId());
            map.put("label", menu.getMenuName());
            map.put("parentId", menu.getParentId());
            return map;
        }).collect(Collectors.toList());
    }
}


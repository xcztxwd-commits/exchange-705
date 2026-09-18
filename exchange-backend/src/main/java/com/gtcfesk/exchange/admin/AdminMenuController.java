package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.AdminMenu;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/menus")
public class AdminMenuController {

    @Autowired
    private AdminMenuService menuService;

    /**
     * 获取菜单树
     */
    @GetMapping("/tree")
    public ResponseEntity<?> getMenuTree() {
        List<AdminMenu> menuTree = menuService.getMenuTree();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", menuTree);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有菜单（扁平列表）
     */
    @GetMapping
    public ResponseEntity<?> getAllMenus() {
        List<AdminMenu> menus = menuService.getAllMenus();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", menus);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取菜单列表（用于选择）
     */
    @GetMapping("/selection")
    public ResponseEntity<?> getMenusForSelection() {
        List<Map<String, Object>> menus = menuService.getMenuListForSelection();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", menus);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有菜单（扁平列表，用于前端选择）
     */
    @GetMapping("/list")
    public ResponseEntity<?> getMenuList() {
        List<AdminMenu> menus = menuService.getAllMenus();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", menus);
        return ResponseEntity.ok(result);
    }
}


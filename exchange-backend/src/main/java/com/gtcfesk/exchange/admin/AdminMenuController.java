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

    @Autowired private AdminPermissionService permissions;

    @Autowired private com.gtcfesk.exchange.repository.AdminRoleRepository roles;
    @GetMapping("/roles")
    public Map<String,Object> roleChoices() {
        permissions.require("admin_list", "");
        List<Map<String,Object>> choices = new java.util.ArrayList<>();
        for (com.gtcfesk.exchange.entity.AdminRole role : roles.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId())) {
            if (!"active".equals(role.getStatus()) || (!permissions.isSuper() && Boolean.TRUE.equals(role.getIsSuper()))) continue;
            Map<String,Object> row = new HashMap<>(); row.put("roleCode", role.getRoleCode()); row.put("roleName", role.getRoleName()); choices.add(row);
        }
        if (permissions.isSuper() && choices.stream().noneMatch(r -> "super_admin".equals(r.get("roleCode")))) {
            Map<String,Object> row = new HashMap<>(); row.put("roleCode", "super_admin"); row.put("roleName", "超级管理员"); choices.add(row);
        }
        Map<String,Object> out = new HashMap<>(); out.put("success", true); out.put("list", choices); return out;
    }
    @GetMapping("/current")
    public Map<String,Object> currentPermissions(javax.servlet.http.HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); return permissions.current(); }

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


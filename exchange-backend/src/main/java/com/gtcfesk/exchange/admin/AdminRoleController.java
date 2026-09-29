package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.AdminRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/roles")
public class AdminRoleController {

    @Autowired
    private AdminRoleService roleService;

    /**
     * 获取角色列表
     */
    @GetMapping
    @com.gtcfesk.exchange.config.AdminPermission(menu = "roles", action = "")
    public ResponseEntity<?> getRoles() {
        List<Map<String, Object>> roles = roleService.getRolesWithMenuCount();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", roles);
        return ResponseEntity.ok(result);
    }

    /**
     * 获取角色详情
     */
    @GetMapping("/{id}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "roles", action = "detail")
    public ResponseEntity<?> getRole(@PathVariable Long id) {
        AdminRole role = roleService.getRoleById(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", role);
        return ResponseEntity.ok(result);
    }

    /**
     * 创建角色
     */
    @PostMapping
    @com.gtcfesk.exchange.config.AdminPermission(menu = "roles", action = "create")
    public ResponseEntity<?> createRole(@RequestBody AdminRole role) {
        try {
            AdminRole created = roleService.createRole(role);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "角色创建成功");
            result.put("data", created);
            return ResponseEntity.ok(result);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 更新角色
     */
    @PutMapping("/{id}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "roles", action = "edit")
    public ResponseEntity<?> updateRole(@PathVariable Long id, @RequestBody AdminRole role) {
        try {
            AdminRole updated = roleService.updateRole(id, role);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "角色更新成功");
            result.put("data", updated);
            return ResponseEntity.ok(result);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 删除角色
     */
    @DeleteMapping("/{id}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "roles", action = "delete")
    public ResponseEntity<?> deleteRole(@PathVariable Long id) {
        try {
            roleService.deleteRole(id);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "角色删除成功");
            return ResponseEntity.ok(result);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.ok(result);
        }
    }

    /**
     * 获取角色的菜单权限
     */
    @GetMapping("/{id}/menus")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "roles", action = "assign_permission")
    public ResponseEntity<?> getRoleMenus(@PathVariable Long id) {
        List<Long> menuIds = roleService.getRoleMenuIds(id);
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("menuIds", menuIds);
        return ResponseEntity.ok(result);
    }

    /**
     * 分配角色菜单权限
     */
    @PostMapping("/{id}/menus")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "roles", action = "assign_permission")
    public ResponseEntity<?> assignMenus(@PathVariable Long id, @RequestBody Map<String, Object> request) {
        try {
            List<Long> menuIds = PermissionGrantInput.menuIds(request);
            roleService.assignMenus(id, menuIds);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "权限分配成功");
            return ResponseEntity.ok(result);
        } catch (org.springframework.security.access.AccessDeniedException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false); result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.ok(result);
        }
    }
}


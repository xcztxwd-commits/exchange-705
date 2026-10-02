package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.AdminRole;
import com.gtcfesk.exchange.entity.AdminRoleMenu;
import com.gtcfesk.exchange.repository.AdminRoleRepository;
import com.gtcfesk.exchange.repository.AdminRoleMenuRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AdminRoleService {
    private void auditControl(String action,String object,String detail,String reason){if(com.gtcfesk.exchange.control.ControlIdentity.isAccess())controlAudit.recordCurrent(action,object,detail,reason); }
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService controlAudit;

    @Autowired private AdminPermissionService permissions;
    @Autowired private AdminUserRepository admins;
    @Autowired private javax.persistence.EntityManager entityManager;

    @Autowired
    private AdminRoleRepository roleRepository;

    @Autowired
    private AdminRoleMenuRepository roleMenuRepository;

    /**
     * 获取所有角色列表
     */
    public List<AdminRole> getAllRoles() {
        return roleRepository.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
    }

    /**
     * 根据ID获取角色
     */
    public AdminRole getRoleById(Long id) {
        return roleRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id)
                .orElseThrow(() -> new IllegalArgumentException("角色不存在"));
    }

    /**
     * 创建角色
     */
    @Transactional
    public AdminRole createRole(AdminRole role) {
        role.setId(null);
        role.setIsSuper(false);
        if ("super_admin".equals(role.getRoleCode())) throw new IllegalArgumentException("保留角色代码");
        // 验证角色代码是否重复
        if (roleRepository.existsByTenantIdAndRoleCode(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), role.getRoleCode())) {
            throw new IllegalArgumentException("角色代码已存在");
        }
        AdminRole saved=roleRepository.saveAndFlush(role);auditControl("ROLE_UPSERT",String.valueOf(saved.getId()),"role="+saved.getRoleCode(),null);return saved;
    }

    /**
     * 更新角色
     */
    @Transactional
    public AdminRole updateRole(Long id, AdminRole roleData) {
        AdminRole role = getRoleById(id);
        
        // 超级管理员不允许修改某些字段
        if (role.getIsSuper()) {
            throw new IllegalArgumentException("超级管理员角色不能修改");
        }
        
        permissions.validateManagedGrants(getRoleMenuIds(id));
        role.setRoleName(roleData.getRoleName());
        role.setDescription(roleData.getDescription());
        role.setStatus(roleData.getStatus());
        
        AdminRole saved=roleRepository.saveAndFlush(role);auditControl("ROLE_UPSERT",String.valueOf(saved.getId()),"role="+saved.getRoleCode(),null);return saved;
    }

    /**
     * 删除角色
     */
    @Transactional
    public void deleteRole(Long id) {
        AdminRole role = getRoleById(id);
        
        // 超级管理员不能删除
        if (role.getIsSuper()) {
            throw new IllegalArgumentException("超级管理员角色不能删除");
        }
        
        if (admins.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId()).stream().anyMatch(a -> role.getRoleCode().equals(a.getRole()))) throw new IllegalArgumentException("角色仍有绑定账号，不能删除");
        permissions.validateManagedGrants(getRoleMenuIds(id));
        // 删除角色的菜单权限
        roleMenuRepository.deleteByRoleId(id);
        
        // 删除角色
        roleRepository.deleteByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id);auditControl("ROLE_DELETE",String.valueOf(id),"",null);
    }

    /**
     * 获取角色的菜单权限
     */
    public List<Long> getRoleMenuIds(Long roleId) {
        List<AdminRoleMenu> roleMenus = roleMenuRepository.findByTenantIdAndRoleId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), roleId);
        return roleMenus.stream()
                .map(AdminRoleMenu::getMenuId)
                .collect(Collectors.toList());
    }

    /**
     * 分配角色菜单权限
     */
    @Transactional
    public void assignMenus(Long roleId, List<Long> menuIds) {
        AdminRole role = getRoleById(roleId);
        
        // 超级管理员的权限不能修改
        if (role.getIsSuper()) {
            throw new IllegalArgumentException("超级管理员权限不能修改");
        }
        
        permissions.validateManagedGrants(getRoleMenuIds(roleId));
        if (menuIds == null) throw new IllegalArgumentException("menuIds 不能为空；清空请传空列表");
        permissions.validateGrant(menuIds);
        // 删除旧的权限
        roleMenuRepository.deleteByRoleId(roleId);
        entityManager.flush();
        
        auditControl("ROLE_GRANTS",String.valueOf(roleId),"menuCount="+menuIds.size(),null);
        // 添加新的权限
        if (menuIds != null && !menuIds.isEmpty()) {
            for (Long menuId : new java.util.LinkedHashSet<>(menuIds)) {
                AdminRoleMenu roleMenu = new AdminRoleMenu();
                roleMenu.setRoleId(roleId);
                roleMenu.setMenuId(menuId);
                roleMenuRepository.save(roleMenu);
            }
        }
    }

    /**
     * 获取角色列表（带菜单数量）
     */
    public List<Map<String, Object>> getRolesWithMenuCount() {
        List<AdminRole> roles = roleRepository.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
        List<Map<String, Object>> result = new ArrayList<>();
        
        for (AdminRole role : roles) {
            Map<String, Object> roleMap = new HashMap<>();
            roleMap.put("id", role.getId());
            roleMap.put("roleName", role.getRoleName());
            roleMap.put("roleCode", role.getRoleCode());
            roleMap.put("description", role.getDescription());
            roleMap.put("status", role.getStatus());
            roleMap.put("isSuper", role.getIsSuper());
            roleMap.put("createdAt", role.getCreatedAt());
            
            // 获取菜单数量
            List<AdminRoleMenu> roleMenus = roleMenuRepository.findByTenantIdAndRoleId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), role.getId());
            roleMap.put("menuCount", roleMenus.size());
            
            result.add(roleMap);
        }
        
        return result;
    }
}


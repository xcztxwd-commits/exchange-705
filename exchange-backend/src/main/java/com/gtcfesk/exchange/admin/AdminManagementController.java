package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.BusinessException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/admins")
@RequiredArgsConstructor
public class AdminManagementController {
    private void auditControl(String action,String object,String detail,String reason){if(com.gtcfesk.exchange.control.ControlIdentity.isAccess())controlAudit.recordCurrent(action,object,detail,reason); }
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService controlAudit;
    
    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.gtcfesk.exchange.repository.AdminRoleRepository roleRepository;
    private final com.gtcfesk.exchange.repository.AdminRoleMenuRepository roleMenuRepository;
    private final AdminPermissionService permissions;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.BackendLoginRegistry logins;

    private void validateRole(String code) {
        if ("super_admin".equals(code)) {
            if (!permissions.isSuper()) throw new org.springframework.security.access.AccessDeniedException("不能授予超级管理员");
            return;
        }
        com.gtcfesk.exchange.entity.AdminRole role = roleRepository.findByTenantIdAndRoleCode(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), code)
            .orElseThrow(() -> new BusinessException("角色不存在"));
        if (!"active".equals(role.getStatus())) throw new BusinessException("角色已停用");
        permissions.validateGrant(roleMenuRepository.findByTenantIdAndRoleId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), role.getId()).stream()
            .map(com.gtcfesk.exchange.entity.AdminRoleMenu::getMenuId).collect(java.util.stream.Collectors.toList()));
    }
    private void validateTarget(AdminUser target) {
        if (!permissions.isSuper()) {
            if ("super_admin".equals(target.getRole())) throw new org.springframework.security.access.AccessDeniedException("不能修改超级管理员");
            validateRole(target.getRole());
        }
    }
    
    /**
     * 获取管理员列表（分页）
     */
    @GetMapping
    @com.gtcfesk.exchange.config.AdminPermission(menu = "admin_list", action = "")
    public ResponseEntity<?> getAdminList(
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) Boolean enabled
    ) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        
        Page<AdminUser> adminPage;
        
        // 如果指定了ID，直接根据ID查询
        if (id != null) {
            Optional<AdminUser> adminOpt = adminUserRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), id);
            if (adminOpt.isPresent()) {
                AdminUser admin = adminOpt.get();
                // 进一步过滤（如果有关键词、角色、状态条件）
                boolean matches = true;
                
                if (keyword != null && !keyword.trim().isEmpty()) {
                    String lowerKeyword = keyword.toLowerCase();
                    matches = matches && (
                        (admin.getAccount() != null && admin.getAccount().toLowerCase().contains(lowerKeyword)) ||
                        (admin.getEmail() != null && admin.getEmail().toLowerCase().contains(lowerKeyword))
                    );
                }
                if (role != null && !role.trim().isEmpty()) {
                    matches = matches && role.equals(admin.getRole());
                }
                if (enabled != null) {
                    matches = matches && enabled.equals(admin.getEnabled());
                }
                
                if (matches) {
                    List<AdminUser> singleList = Collections.singletonList(admin);
                    adminPage = new org.springframework.data.domain.PageImpl<>(
                        singleList,
                        pageable,
                        1
                    );
                } else {
                    // 不匹配过滤条件，返回空列表
                    adminPage = new org.springframework.data.domain.PageImpl<>(
                        Collections.emptyList(),
                        pageable,
                        0
                    );
                }
            } else {
                // ID不存在，返回空列表
                adminPage = new org.springframework.data.domain.PageImpl<>(
                    Collections.emptyList(),
                    pageable,
                    0
                );
            }
        } else {
            // 如果没有指定ID，使用原有逻辑
            // 获取所有数据
            List<AdminUser> allAdmins = adminUserRepository.findAllByTenantId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId());
            
            // 应用过滤条件
            if (keyword != null && !keyword.trim().isEmpty()) {
                String lowerKeyword = keyword.toLowerCase();
                allAdmins = allAdmins.stream()
                    .filter(admin -> 
                        (admin.getAccount() != null && admin.getAccount().toLowerCase().contains(lowerKeyword)) ||
                        (admin.getEmail() != null && admin.getEmail().toLowerCase().contains(lowerKeyword))
                    )
                    .collect(java.util.stream.Collectors.toList());
            }
            if (role != null && !role.trim().isEmpty()) {
                allAdmins = allAdmins.stream()
                    .filter(admin -> role.equals(admin.getRole()))
                    .collect(java.util.stream.Collectors.toList());
            }
            if (enabled != null) {
                allAdmins = allAdmins.stream()
                    .filter(admin -> enabled.equals(admin.getEnabled()))
                    .collect(java.util.stream.Collectors.toList());
            }
            
            // 手动分页
            int start = page * size;
            int end = Math.min(start + size, allAdmins.size());
            List<AdminUser> pageContent = start < allAdmins.size() 
                ? allAdmins.subList(start, end) 
                : Collections.emptyList();
            
            adminPage = new org.springframework.data.domain.PageImpl<>(
                pageContent,
                pageable,
                allAdmins.size()
            );
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("list", adminPage.getContent());
        result.put("total", adminPage.getTotalElements());
        result.put("page", adminPage.getNumber());
        result.put("size", adminPage.getSize());
        return ResponseEntity.ok(result);
    }
    
    /**
     * 创建管理员
     */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping
    @com.gtcfesk.exchange.config.AdminPermission(menu = "admin_list", action = "create")
    public ResponseEntity<?> createAdmin(
            Authentication auth,
            @RequestBody CreateAdminRequest req
    ) {
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("未登录");
        }
        
        logins.requireAvailable(req.getAccount());
        // 检查账号是否已存在
        if (adminUserRepository.findByTenantIdAndAccount(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), req.getAccount()).isPresent()) {
            throw new BusinessException("登录账号已存在");
        }
        
        // 检查邮箱是否已存在
        if (adminUserRepository.findByTenantIdAndEmail(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), req.getEmail()).isPresent()) {
            throw new BusinessException("邮箱已存在");
        }
        
        // 验证角色
        if (req.getRole() == null || req.getRole().trim().isEmpty()) {
            req.setRole("admin"); // 默认角色
        }
        
        validateRole(req.getRole());
        
        if (req.getPassword() == null || req.getPassword().length() < 6) throw new BusinessException("新管理员密码至少 6 位，允许纯数字");
        // 创建管理员
        AdminUser admin = new AdminUser();
        admin.setAccount(com.gtcfesk.exchange.control.BackendLoginRegistry.normalize(req.getAccount()));
        admin.setEmail(req.getEmail().trim());
        admin.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        admin.setRole(req.getRole());
        admin.setMustChangePassword(true);
        admin.setCurrentToken(null);
        admin.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);
        
        try { adminUserRepository.saveAndFlush(admin); }
        catch (org.springframework.dao.DataIntegrityViolationException conflict) { throw new BusinessException("账号或邮箱不可用"); }
        logins.register("ADMIN", admin.getId(), admin.getAccount());
        auditControl("ADMIN_UPSERT",String.valueOf(admin.getId()),"account/role/status credentials changed (redacted)",null);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "管理员创建成功");
        result.put("data", admin);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 更新管理员信息
     */
    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/{adminId}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "admin_list", action = "edit")
    public ResponseEntity<?> updateAdmin(
            Authentication auth,
            @PathVariable Long adminId,
            @RequestBody UpdateAdminRequest req
    ) {
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("未登录");
        }
        
        AdminUser admin = adminUserRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        validateTarget(admin);
        
        // Stage the normalized name until all reads/validation finish; no dirty-write autoflush.
        String nextAccount = admin.getAccount();
        if (req.getAccount() != null && !req.getAccount().trim().isEmpty()) {
            nextAccount = com.gtcfesk.exchange.control.BackendLoginRegistry.normalize(req.getAccount());
            if (!admin.getAccount().equals(nextAccount)) {
                logins.requireAvailable(nextAccount);
                if (adminUserRepository.findByTenantIdAndAccount(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), nextAccount).isPresent()) {
                    throw new BusinessException("登录账号已被使用");
                }
            }
        }
        
        // 检查邮箱是否已被其他管理员使用
        if (req.getEmail() != null && !req.getEmail().trim().isEmpty()) {
            if (!admin.getEmail().equals(req.getEmail().trim())) {
                if (adminUserRepository.findByTenantIdAndEmail(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), req.getEmail().trim()).isPresent()) {
                    throw new BusinessException("邮箱已被使用");
                }
                admin.setEmail(req.getEmail().trim());
            }
        }
        
        // 更新角色
        if (req.getRole() != null && !req.getRole().trim().isEmpty()) {
            validateRole(req.getRole());
            admin.setRole(req.getRole());
        }
        
        // 更新启用状态
        if (req.getEnabled() != null) {
            admin.setCurrentToken(null);
        admin.setEnabled(req.getEnabled());
        }
        
        // 更新密码（如果提供了新密码）
        if (req.getPassword() != null && !req.getPassword().trim().isEmpty()) {
            if (req.getPassword().length() < 6) throw new BusinessException("新管理员密码至少 6 位，允许纯数字");
            admin.setMustChangePassword(true);
            admin.setPasswordHash(passwordEncoder.encode(req.getPassword()));
            admin.setCurrentToken(null);
        }
        
        admin.setAccount(nextAccount);
        try { adminUserRepository.saveAndFlush(admin); }
        catch (org.springframework.dao.DataIntegrityViolationException conflict) { throw new BusinessException("账号或邮箱不可用"); }
        logins.register("ADMIN", admin.getId(), admin.getAccount());
        auditControl("ADMIN_UPSERT",String.valueOf(admin.getId()),"account/role/status credentials changed (redacted)",null);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "管理员更新成功");
        return ResponseEntity.ok(result);
    }
    
    /**
     * 删除管理员
     */
    @org.springframework.transaction.annotation.Transactional
    @DeleteMapping("/{adminId}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "admin_list", action = "delete")
    public ResponseEntity<?> deleteAdmin(
            Authentication auth,
            @PathVariable Long adminId
    ) {
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("未登录");
        }
        
        Long currentAdminId = Long.parseLong(auth.getName());
        
        // 不能删除自己
        if (currentAdminId.equals(adminId)) {
            throw new BusinessException("不能删除自己的账号");
        }
        
        AdminUser admin = adminUserRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        validateTarget(admin);
        
        // 不能删除超级管理员（可选限制）
        if ("super_admin".equals(admin.getRole())) {
            throw new BusinessException("不能删除超级管理员");
        }
        
        logins.removeAdmin(adminId);
        adminUserRepository.deleteByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), adminId);
        auditControl("ADMIN_DELETE",String.valueOf(adminId),"",null);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "管理员删除成功");
        return ResponseEntity.ok(result);
    }
    
    /**
     * 启用/禁用管理员
     */
    @org.springframework.transaction.annotation.Transactional
    @PutMapping("/{adminId}/status")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "admin_list", action = "status")
    public ResponseEntity<?> updateAdminStatus(
            Authentication auth,
            @PathVariable Long adminId,
            @RequestBody Map<String, Boolean> request
    ) {
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("未登录");
        }
        
        Long currentAdminId = Long.parseLong(auth.getName());
        
        // 不能禁用自己
        if (currentAdminId.equals(adminId)) {
            throw new BusinessException("不能禁用自己的账号");
        }
        
        AdminUser admin = adminUserRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        validateTarget(admin);
        
        Boolean enabled = request.get("enabled");
        if (enabled != null) {
            admin.setCurrentToken(null);
        admin.setEnabled(enabled);
            adminUserRepository.saveAndFlush(admin);
        logins.register("ADMIN", admin.getId(), admin.getAccount());
        auditControl("ADMIN_UPSERT",String.valueOf(admin.getId()),"account/role/status credentials changed (redacted)",null);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", enabled ? "管理员已启用" : "管理员已禁用");
        return ResponseEntity.ok(result);
    }
    
    /**
     * 获取管理员详情
     */
    @GetMapping("/{adminId}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "admin_list", action = "detail")
    public ResponseEntity<?> getAdminDetail(@PathVariable Long adminId) {
        AdminUser admin = adminUserRepository.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        validateTarget(admin);
        
        // 不返回密码哈希
        Map<String, Object> adminData = new HashMap<>();
        adminData.put("id", admin.getId());
        adminData.put("account", admin.getAccount());
        adminData.put("email", admin.getEmail());
        adminData.put("role", admin.getRole());
        adminData.put("enabled", admin.getEnabled());
        adminData.put("createdAt", admin.getCreatedAt());
        adminData.put("updatedAt", admin.getUpdatedAt());
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", adminData);
        return ResponseEntity.ok(result);
    }
    
    @Data
    public static class CreateAdminRequest {
        private String account;
        private String email;
        private String password;
        private String role = "admin"; // super_admin / admin / ops
        private Boolean enabled = true;
    }
    
    @Data
    public static class UpdateAdminRequest {
        private String account;
        private String email;
        private String password; // 可选，如果提供则更新密码
        private String role;
        private Boolean enabled;
    }
}


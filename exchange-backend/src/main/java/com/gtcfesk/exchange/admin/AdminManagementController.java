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
    
    private final AdminUserRepository adminUserRepository;
    private final PasswordEncoder passwordEncoder;
    
    /**
     * 获取管理员列表（分页）
     */
    @GetMapping
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
            Optional<AdminUser> adminOpt = adminUserRepository.findById(id);
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
            List<AdminUser> allAdmins = adminUserRepository.findAll();
            
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
    @PostMapping
    public ResponseEntity<?> createAdmin(
            Authentication auth,
            @RequestBody CreateAdminRequest req
    ) {
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("未登录");
        }
        
        // 检查账号是否已存在
        if (adminUserRepository.findByAccount(req.getAccount()).isPresent()) {
            throw new BusinessException("登录账号已存在");
        }
        
        // 检查邮箱是否已存在
        if (adminUserRepository.findByEmail(req.getEmail()).isPresent()) {
            throw new BusinessException("邮箱已存在");
        }
        
        // 验证角色
        if (req.getRole() == null || req.getRole().trim().isEmpty()) {
            req.setRole("admin"); // 默认角色
        }
        
        if (!"super_admin".equals(req.getRole()) && 
            !"admin".equals(req.getRole()) && 
            !"ops".equals(req.getRole())) {
            throw new BusinessException("无效的角色类型");
        }
        
        // 创建管理员
        AdminUser admin = new AdminUser();
        admin.setAccount(req.getAccount().trim());
        admin.setEmail(req.getEmail().trim());
        admin.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        admin.setRole(req.getRole());
        admin.setEnabled(req.getEnabled() != null ? req.getEnabled() : true);
        
        adminUserRepository.save(admin);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "管理员创建成功");
        result.put("data", admin);
        return ResponseEntity.ok(result);
    }
    
    /**
     * 更新管理员信息
     */
    @PutMapping("/{adminId}")
    public ResponseEntity<?> updateAdmin(
            Authentication auth,
            @PathVariable Long adminId,
            @RequestBody UpdateAdminRequest req
    ) {
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("未登录");
        }
        
        AdminUser admin = adminUserRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        
        // 检查账号是否已被其他管理员使用
        if (req.getAccount() != null && !req.getAccount().trim().isEmpty()) {
            if (!admin.getAccount().equals(req.getAccount().trim())) {
                if (adminUserRepository.findByAccount(req.getAccount().trim()).isPresent()) {
                    throw new BusinessException("登录账号已被使用");
                }
                admin.setAccount(req.getAccount().trim());
            }
        }
        
        // 检查邮箱是否已被其他管理员使用
        if (req.getEmail() != null && !req.getEmail().trim().isEmpty()) {
            if (!admin.getEmail().equals(req.getEmail().trim())) {
                if (adminUserRepository.findByEmail(req.getEmail().trim()).isPresent()) {
                    throw new BusinessException("邮箱已被使用");
                }
                admin.setEmail(req.getEmail().trim());
            }
        }
        
        // 更新角色
        if (req.getRole() != null && !req.getRole().trim().isEmpty()) {
            if (!"super_admin".equals(req.getRole()) && 
                !"admin".equals(req.getRole()) && 
                !"ops".equals(req.getRole())) {
                throw new BusinessException("无效的角色类型");
            }
            admin.setRole(req.getRole());
        }
        
        // 更新启用状态
        if (req.getEnabled() != null) {
            admin.setEnabled(req.getEnabled());
        }
        
        // 更新密码（如果提供了新密码）
        if (req.getPassword() != null && !req.getPassword().trim().isEmpty()) {
            admin.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        }
        
        adminUserRepository.save(admin);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "管理员更新成功");
        return ResponseEntity.ok(result);
    }
    
    /**
     * 删除管理员
     */
    @DeleteMapping("/{adminId}")
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
        
        AdminUser admin = adminUserRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        
        // 不能删除超级管理员（可选限制）
        if ("super_admin".equals(admin.getRole())) {
            throw new BusinessException("不能删除超级管理员");
        }
        
        adminUserRepository.deleteById(adminId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "管理员删除成功");
        return ResponseEntity.ok(result);
    }
    
    /**
     * 启用/禁用管理员
     */
    @PutMapping("/{adminId}/status")
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
        
        AdminUser admin = adminUserRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        
        Boolean enabled = request.get("enabled");
        if (enabled != null) {
            admin.setEnabled(enabled);
            adminUserRepository.save(admin);
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
    public ResponseEntity<?> getAdminDetail(@PathVariable Long adminId) {
        AdminUser admin = adminUserRepository.findById(adminId)
                .orElseThrow(() -> new BusinessException("管理员不存在"));
        
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


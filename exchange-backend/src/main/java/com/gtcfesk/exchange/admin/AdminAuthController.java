package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.auth.dto.LoginRequest;
import com.gtcfesk.exchange.auth.vo.AuthResponse;
import com.gtcfesk.exchange.common.BusinessException;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Validated @RequestBody LoginRequest req) {
        return ResponseEntity.ok(adminAuthService.login(req));
    }

    /**
     * 更新当前管理员的登录名称
     */
    @PutMapping("/profile/account")
    public ResponseEntity<?> updateAccount(
            Authentication auth,
            @RequestBody UpdateAccountRequest req) {
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("未登录");
        }
        
        Long adminId = Long.parseLong(auth.getName());
        adminAuthService.updateAccount(adminId, req.getAccount());
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "登录名称更新成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 修改当前管理员的密码
     */
    @PutMapping("/profile/password")
    public ResponseEntity<?> changePassword(
            Authentication auth,
            @RequestBody ChangePasswordRequest req) {
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("未登录");
        }
        
        Long adminId = Long.parseLong(auth.getName());
        adminAuthService.changePassword(adminId, req.getOldPassword(), req.getNewPassword());
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "密码修改成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 更新当前代理的邮箱（账户名）
     */
    @PutMapping("/agent/profile/email")
    public ResponseEntity<?> updateAgentEmail(
            Authentication auth,
            @RequestBody UpdateEmailRequest req) {
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("未登录");
        }
        
        // 从JWT中提取代理ID（格式：agent-{agentId}）
        String subject = auth.getName();
        if (!subject.startsWith("agent-")) {
            throw new BusinessException("无权限：此接口仅限代理用户使用");
        }
        
        Long agentId = Long.parseLong(subject.substring(6));
        adminAuthService.updateAgentEmail(agentId, req.getEmail());
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "邮箱更新成功");
        return ResponseEntity.ok(result);
    }

    /**
     * 修改当前代理的密码
     */
    @PutMapping("/agent/profile/password")
    public ResponseEntity<?> changeAgentPassword(
            Authentication auth,
            @RequestBody ChangePasswordRequest req) {
        if (auth == null || auth.getName() == null) {
            throw new BusinessException("未登录");
        }
        
        // 从JWT中提取代理ID（格式：agent-{agentId}）
        String subject = auth.getName();
        if (!subject.startsWith("agent-")) {
            throw new BusinessException("无权限：此接口仅限代理用户使用");
        }
        
        Long agentId = Long.parseLong(subject.substring(6));
        adminAuthService.changeAgentPassword(agentId, req.getOldPassword(), req.getNewPassword());
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "密码修改成功");
        return ResponseEntity.ok(result);
    }

    @Data
    public static class UpdateAccountRequest {
        private String account;
    }

    @Data
    public static class UpdateEmailRequest {
        private String email;
    }

    @Data
    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
    }
}








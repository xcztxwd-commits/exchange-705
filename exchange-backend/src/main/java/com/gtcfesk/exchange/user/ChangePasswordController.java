package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.auth.dto.SendCodeRequest;
import com.gtcfesk.exchange.auth.dto.ResetPasswordRequest;
import com.gtcfesk.exchange.auth.AuthService;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class ChangePasswordController {
    
    private final AuthService authService;
    private final UserAccountRepository userAccountRepository;
    
    /**
     * 发送修改密码验证码
     */
    @PostMapping("/changePassword/sendCode")
    public ResponseEntity<?> sendChangePasswordCode(Authentication auth) {
        if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
            throw new BusinessException("未登录");
        }
        
        Long userId = Long.parseLong(auth.getName());
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        
        SendCodeRequest req = new SendCodeRequest();
        req.setEmail(user.getEmail());
        req.setScene("change_password");
        
        authService.sendEmailCode(req);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "验证码已发送");
        return ResponseEntity.ok(result);
    }
    
    /**
     * 修改密码
     */
    @PostMapping("/changePassword")
    @Transactional
    public ResponseEntity<?> changePassword(
            Authentication auth,
            @RequestBody ChangePasswordRequest req) {
        
        if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
            throw new BusinessException("未登录");
        }
        
        Long userId = Long.parseLong(auth.getName());
        UserAccount user = userAccountRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("用户不存在"));
        
        // 验证密码一致性
        if (!req.getPassword().equals(req.getConfirmPassword())) {
            throw new BusinessException("两次密码不一致");
        }
        
        // 验证验证码
        ResetPasswordRequest resetReq = new ResetPasswordRequest();
        resetReq.setEmail(user.getEmail());
        resetReq.setVerifyCode(req.getVerifyCode());
        resetReq.setPassword(req.getPassword());
        resetReq.setConfirmPassword(req.getConfirmPassword());
        resetReq.setScene("change_password");
        
        // 验证验证码并修改密码
        authService.resetPassword(resetReq);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "密码修改成功");
        return ResponseEntity.ok(result);
    }
    
    @Data
    public static class ChangePasswordRequest {
        private String verifyCode;
        private String password;
        private String confirmPassword;
    }
}


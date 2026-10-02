package com.gtcfesk.exchange.auth;

import com.gtcfesk.exchange.auth.dto.LoginRequest;
import com.gtcfesk.exchange.auth.dto.RegisterRequest;
import com.gtcfesk.exchange.auth.dto.ResetPasswordRequest;
import com.gtcfesk.exchange.auth.dto.SendCodeRequest;
import com.gtcfesk.exchange.auth.vo.AuthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.gtcfesk.exchange.activity.ActivityService activities;
    private final com.gtcfesk.exchange.security.RegistrationSecurity registrationSecurity;

    @lombok.Data
    public static class CaptchaRequest {
        @javax.validation.constraints.NotBlank
        @javax.validation.constraints.Pattern(regexp = "[a-f0-9]{32}")
        private String captchaSession;
    }
    @PostMapping("/captcha")
    public ResponseEntity<Map<String, Object>> captcha(@Validated @RequestBody CaptchaRequest req) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(registrationSecurity.create(req.getCaptchaSession()));
    }


    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Validated @RequestBody LoginRequest req) {
        AuthResponse response=authService.login(req);
        if(activities!=null)activities.trigger(Long.valueOf(((Map<?,?>)response.getUser()).get("id").toString()),"LOGIN","AUTH_HOME");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Validated @RequestBody RegisterRequest req) {
        AuthResponse response=authService.register(req);
        if(activities!=null)activities.trigger(Long.valueOf(((Map<?,?>)response.getUser()).get("id").toString()),"LOGIN","AUTH_HOME");
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sendEmailCode")
    public ResponseEntity<String> sendEmailCode(@Validated @RequestBody SendCodeRequest req) {
        authService.sendEmailCode(req);
        return ResponseEntity.ok("ok");
    }

    @PostMapping("/resetPassword")
    public ResponseEntity<String> resetPassword(@Validated @RequestBody ResetPasswordRequest req) {
        authService.resetPassword(req);
        return ResponseEntity.ok("ok");
    }

    /**
     * 心跳接口：更新用户活动时间（用于实时在线检测）
     * 该接口由JwtFilter自动更新lastActivityAt，这里只需要返回成功即可
     */
    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Object>> heartbeat(Authentication auth) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("message", "ok");
        return ResponseEntity.ok(resp);
    }
}


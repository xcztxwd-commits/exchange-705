package com.gtcfesk.exchange.user;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/user/dashboard")
@RequiredArgsConstructor
public class UserDashboardController {
    
    private final UserDashboardService dashboardService;
    
    /**
     * 获取用户首页统计数据
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getDashboardStats(Authentication auth) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }
            
            Long userId = Long.parseLong(auth.getName());
            Map<String, Object> stats = dashboardService.getUserDashboardStats(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", stats);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
}




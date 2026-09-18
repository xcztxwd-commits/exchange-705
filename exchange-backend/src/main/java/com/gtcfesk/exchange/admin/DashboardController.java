package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.JwtUtil;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {
    
    private final DashboardService dashboardService;
    private final JwtUtil jwtUtil;
    
    /**
     * 从JWT中提取代理ID
     */
    private Long extractAgentId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = authHeader.substring(7);
            Claims claims = jwtUtil.parse(token);
            String subject = claims.getSubject();
            if (subject != null && subject.startsWith("agent-")) {
                return Long.parseLong(subject.substring(6));
            }
        } catch (Exception ignored) {
            // 解析失败，返回null（管理员）
        }
        return null;
    }
    
    /**
     * 获取仪表盘统计数据
     */
    @GetMapping("/stats")
    public ResponseEntity<?> getDashboardStats(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Long agentId = extractAgentId(authHeader);
            Map<String, Object> stats = dashboardService.getDashboardStats(agentId);
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
    
    /**
     * 获取充值和提现的图表数据
     */
    @GetMapping("/chart-data")
    public ResponseEntity<?> getChartData(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Long agentId = extractAgentId(authHeader);
            Map<String, Object> chartData = dashboardService.getDepositWithdrawChartData(agentId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", chartData);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
}




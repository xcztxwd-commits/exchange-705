package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.FinancialYieldRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/financial/yield")
@RequiredArgsConstructor
public class FinancialYieldController {
    
    private final FinancialYieldService yieldService;
    
    /**
     * 获取订单的收益列表
     */
    @GetMapping("/order/{orderId}")
    public ResponseEntity<?> getOrderYields(
            Authentication auth,
            @PathVariable Long orderId) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }
            
            Long userId = Long.parseLong(auth.getName());
            List<FinancialYieldRecord> yields = yieldService.getOrderYields(orderId);
            
            // 验证订单是否属于当前用户
            if (!yields.isEmpty() && !yields.get(0).getUserId().equals(userId)) {
                throw new RuntimeException("无权访问该订单的收益记录");
            }
            
            FinancialYieldService.FinancialYieldStats stats = yieldService.getOrderYieldStats(orderId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", yields);
            resp.put("stats", stats);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 获取我的收益列表
     */
    @GetMapping("/my")
    public ResponseEntity<?> getMyYields(Authentication auth) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }
            
            Long userId = Long.parseLong(auth.getName());
            List<FinancialYieldRecord> yields = yieldService.getUserYields(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", yields);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }
}




package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.FinancialOrder;
import com.gtcfesk.exchange.entity.FinancialProduct;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/financial")
@RequiredArgsConstructor
public class FinancialController {
    
    private final FinancialService financialService;
    
    /**
     * 获取理财产品列表
     */
    @GetMapping("/products")
    public ResponseEntity<?> getProducts() {
        try {
            List<FinancialProduct> products = financialService.getAvailableProducts();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", products);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 获取产品详情
     */
    @GetMapping("/product/{id}")
    public ResponseEntity<?> getProduct(@PathVariable Long id) {
        try {
            FinancialProduct product = financialService.getProduct(id);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", product);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 申购理财产品
     */
    @PostMapping("/purchase")
    public ResponseEntity<?> purchaseProduct(
            Authentication auth,
            @RequestBody Map<String, Object> request) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }
            
            Long userId = Long.parseLong(auth.getName());
            Long productId = Long.parseLong(request.get("productId").toString());
            BigDecimal purchaseAmount = new BigDecimal(request.get("purchaseAmount").toString());
            
            FinancialOrder order = financialService.purchaseProduct(userId, productId, purchaseAmount);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", order);
            resp.put("message", "申购成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "申购失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 违约赎回
     */
    @PostMapping("/redeem/{orderId}")
    public ResponseEntity<?> earlyRedeem(
            Authentication auth,
            @PathVariable Long orderId) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }
            
            Long userId = Long.parseLong(auth.getName());
            FinancialOrder order = financialService.earlyRedeem(userId, orderId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", order);
            resp.put("message", "赎回成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "赎回失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 计算违约金
     */
    @GetMapping("/penalty/{orderId}")
    public ResponseEntity<?> calculatePenalty(
            Authentication auth,
            @PathVariable Long orderId) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }
            
            BigDecimal penalty = financialService.calculatePenalty(Long.valueOf(auth.getName()), orderId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("penaltyAmount", penalty);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "计算失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 获取我的订单列表
     */
    @GetMapping("/orders")
    public ResponseEntity<?> getMyOrders(
            Authentication auth,
            @RequestParam(required = false) String status) {
        try {
            if (auth == null || auth.getName() == null) {
                throw new RuntimeException("未登录");
            }
            
            Long userId = Long.parseLong(auth.getName());
            List<FinancialOrder> orders;
            
            if (status != null && !status.isEmpty()) {
                orders = financialService.getUserOrdersByStatus(userId, status);
            } else {
                orders = financialService.getUserOrders(userId);
            }
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", orders);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
}




package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.FinancialOrder;
import com.gtcfesk.exchange.entity.FinancialProduct;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/financial")
@RequiredArgsConstructor
public class AdminFinancialController {
    
    private final AdminFinancialService adminFinancialService;
    private final UserAccountRepository userAccountRepository;
    
    /**
     * 获取所有理财产品
     */
    @GetMapping("/products")
    public ResponseEntity<?> getProducts() {
        try {
            List<FinancialProduct> products = adminFinancialService.getAllProducts();
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
     * 创建理财产品
     */
    @PostMapping("/products")
    public ResponseEntity<?> createProduct(@RequestBody FinancialProduct product) {
        try {
            FinancialProduct created = adminFinancialService.createProduct(product);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", created);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "创建失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 更新理财产品
     */
    @PutMapping("/products/{id}")
    public ResponseEntity<?> updateProduct(@PathVariable Long id, @RequestBody FinancialProduct product) {
        try {
            product.setId(id);
            FinancialProduct updated = adminFinancialService.updateProduct(product);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", updated);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "更新失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 删除理财产品
     */
    @DeleteMapping("/products/{id}")
    public ResponseEntity<?> deleteProduct(@PathVariable Long id) {
        try {
            adminFinancialService.deleteProduct(id);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "删除失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 获取所有理财订单
     */
    @GetMapping("/orders")
    public ResponseEntity<?> getOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userEmail) {
        try {
            List<FinancialOrder> orders = adminFinancialService.getOrders(status, userId, userEmail);
            
            // 为每条订单添加用户备注
            List<Map<String, Object>> orderList = orders.stream().map(order -> {
                Map<String, Object> orderMap = new HashMap<>();
                orderMap.put("id", order.getId());
                orderMap.put("userId", order.getUserId());
                orderMap.put("productId", order.getProductId());
                orderMap.put("productName", order.getProductName());
                orderMap.put("purchaseAmount", order.getPurchaseAmount());
                orderMap.put("currency", order.getCurrency());
                orderMap.put("dailyYield", order.getDailyYield());
                orderMap.put("totalYield", order.getTotalYield());
                orderMap.put("termDays", order.getTermDays());
                orderMap.put("status", order.getStatus());
                orderMap.put("purchaseTime", order.getPurchaseTime());
                orderMap.put("endTime", order.getEndTime());
                orderMap.put("redeemTime", order.getRedeemTime());
                
                // 添加用户备注
                if (order.getUserId() != null) {
                    UserAccount user = userAccountRepository.findById(order.getUserId()).orElse(null);
                    if (user != null) {
                        orderMap.put("userRemark", user.getRemark());
                    }
                }
                
                return orderMap;
            }).collect(Collectors.toList());
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", orderList);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
}




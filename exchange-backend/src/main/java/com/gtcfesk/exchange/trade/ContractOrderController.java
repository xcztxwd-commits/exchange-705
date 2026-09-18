package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.ContractOrder;
import com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trade/contract")
@RequiredArgsConstructor
public class ContractOrderController {

    private final ContractOrderService contractOrderService;

    /**
     * 创建合约订单
     */
    @PostMapping("/order")
    public ResponseEntity<?> createOrder(
            Authentication auth,
            @RequestBody CreateContractOrderRequest req) {
        try {
            // 从 SecurityContext 获取认证信息（如果参数为 null）
            if (auth == null) {
                auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            }
            
            // 检查认证信息
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录，请先登录");
                return ResponseEntity.status(401).body(resp);
            }
            
            // 检查请求参数
            if (req == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "请求参数不能为空");
                return ResponseEntity.badRequest().body(resp);
            }
            
            // 检查必要字段
            if (req.getSymbol() == null || req.getSymbol().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "交易对不能为空");
                return ResponseEntity.badRequest().body(resp);
            }
            
            if (req.getQuantity() == null || req.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "交易数量必须大于0");
                return ResponseEntity.badRequest().body(resp);
            }
            
            Long userId = Long.parseLong(auth.getName());
            ContractOrder order = contractOrderService.createOrder(userId, req);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("orderId", order.getId());
            resp.put("message", "订单创建成功");
            
            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "订单创建失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            e.printStackTrace(); // 打印堆栈以便调试
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 获取用户的合约订单列表
     */
    @GetMapping("/orders")
    public ResponseEntity<?> getUserOrders(
            Authentication auth,
            @RequestParam(required = false) String status) {
        try {
            Long userId = Long.parseLong(auth.getName());
            List<ContractOrder> orders = contractOrderService.getUserOrders(userId, status);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("list", orders);
            resp.put("total", orders.size());
            
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取订单失败: " + e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 获取合约资产余额
     */
    @GetMapping("/balance")
    public ResponseEntity<?> getContractBalance(Authentication auth) {
        try {
            // 从 SecurityContext 获取认证信息（如果参数为 null）
            if (auth == null) {
                auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            }
            
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录，请先登录");
                return ResponseEntity.status(401).body(resp);
            }
            
            Long userId;
            try {
                userId = Long.parseLong(auth.getName());
            } catch (NumberFormatException e) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "获取余额失败: 用户ID格式错误 - " + auth.getName());
                return ResponseEntity.badRequest().body(resp);
            }
            
            BigDecimal balance = contractOrderService.getContractBalance(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("balance", balance);
            resp.put("available", balance);
            
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取余额失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            e.printStackTrace(); // 打印堆栈以便调试
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 平仓订单
     */
    @PostMapping("/order/{orderId}/close")
    public ResponseEntity<?> closeOrder(
            Authentication auth,
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> req) {
        try {
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录");
                return ResponseEntity.status(401).body(resp);
            }

            Long userId = Long.parseLong(auth.getName());
            BigDecimal closePrice = req.get("closePrice") != null 
                    ? new BigDecimal(req.get("closePrice").toString()) 
                    : null;

            if (closePrice == null) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "平仓价格不能为空");
                return ResponseEntity.badRequest().body(resp);
            }

            ContractOrder order = contractOrderService.closeOrder(userId, orderId, closePrice);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "平仓成功");
            resp.put("order", order);

            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "平仓失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            e.printStackTrace();
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 撤单
     */
    @PostMapping("/order/{orderId}/cancel")
    public ResponseEntity<?> cancelOrder(
            Authentication auth,
            @PathVariable Long orderId) {
        try {
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录");
                return ResponseEntity.status(401).body(resp);
            }

            Long userId = Long.parseLong(auth.getName());
            ContractOrder order = contractOrderService.cancelOrder(userId, orderId);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "撤单成功");
            resp.put("order", order);

            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "撤单失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            e.printStackTrace();
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 更新订单的止盈止损
     */
    @PostMapping("/order/{orderId}/update-tp-sl")
    public ResponseEntity<?> updateStopLossTakeProfit(
            Authentication auth,
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> req) {
        try {
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录");
                return ResponseEntity.status(401).body(resp);
            }

            Long userId = Long.parseLong(auth.getName());
            BigDecimal stopLoss = req.get("stopLoss") != null && !req.get("stopLoss").toString().isEmpty()
                    ? new BigDecimal(req.get("stopLoss").toString()) 
                    : null;
            BigDecimal takeProfit = req.get("takeProfit") != null && !req.get("takeProfit").toString().isEmpty()
                    ? new BigDecimal(req.get("takeProfit").toString()) 
                    : null;

            ContractOrder order = contractOrderService.updateStopLossTakeProfit(userId, orderId, stopLoss, takeProfit);

            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "修改成功");
            resp.put("order", order);

            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "修改失败: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            e.printStackTrace();
            return ResponseEntity.badRequest().body(resp);
        }
    }
}


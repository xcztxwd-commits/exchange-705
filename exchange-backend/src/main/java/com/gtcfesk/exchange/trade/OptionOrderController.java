package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.OptionOrder;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.trade.dto.CreateOptionOrderRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trade/option")
@RequiredArgsConstructor
public class OptionOrderController {

    private final OptionOrderService optionOrderService;
    private final TradingSymbolRepository tradingSymbolRepository;

    /**
     * 获取交易对列表（用于期权交易）
     */
    @GetMapping("/symbols")
    public ResponseEntity<?> getSymbols(@RequestParam(required = false) String category) {
        try {
            List<TradingSymbol> symbols;
            if (category != null && !category.isEmpty()) {
                symbols = tradingSymbolRepository.findByCategoryAndIsEnabledTrueOrderBySortOrderDesc(category);
            } else {
                symbols = tradingSymbolRepository.findByIsEnabledTrueOrderBySortOrderDesc();
            }
            Map<String, Object> resp = new HashMap<>();
            resp.put("list", symbols);
            resp.put("total", symbols.size());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取交易对失败: " + (com.gtcfesk.exchange.common.SafeErrors.message(e) != null ? com.gtcfesk.exchange.common.SafeErrors.message(e) : "未知错误"));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 创建期货订单
     */
    @PostMapping("/order")
    public ResponseEntity<?> createOrder(
            Authentication auth,
            @RequestBody CreateOptionOrderRequest req) {
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
            
            Long userId = Long.parseLong(auth.getName());
            OptionOrder order = optionOrderService.createOrder(userId, req);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("orderId", order.getId());
            resp.put("id", order.getId());
            resp.put("data", order); // 返回完整订单数据
            resp.put("message", "订单创建成功");
            
            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "订单创建失败: " + (com.gtcfesk.exchange.common.SafeErrors.message(e) != null ? com.gtcfesk.exchange.common.SafeErrors.message(e) : "未知错误"));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 获取用户的期货订单列表
     */
    @GetMapping("/orders")
    public ResponseEntity<?> getUserOrders(
            Authentication auth,
            @RequestParam(required = false) String status) {
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
            
            Long userId = Long.parseLong(auth.getName());
            List<OptionOrder> orders = optionOrderService.getUserOrders(userId, status);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("list", orders);
            resp.put("total", orders.size());
            
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取订单失败: " + (com.gtcfesk.exchange.common.SafeErrors.message(e) != null ? com.gtcfesk.exchange.common.SafeErrors.message(e) : "未知错误"));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 获取期权资产余额
     */
    @GetMapping("/balance")
    public ResponseEntity<?> getOptionBalance(Authentication auth) {
        try {
            Long userId = Long.parseLong(auth.getName());
            BigDecimal balance = optionOrderService.getOptionBalance(userId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("balance", balance);
            resp.put("available", balance);
            
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取余额失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }

    /**
     * 平仓（到期自动平仓或手动平仓）
     */
    @PostMapping("/order/{orderId}/close")
    public ResponseEntity<?> closeOrder(
            Authentication auth,
            @PathVariable Long orderId,
            @RequestBody Map<String, Object> req) {
        try {
            if (auth == null) {
                auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            }
            
            if (auth == null || auth.getName() == null || auth.getName().isEmpty()) {
                Map<String, Object> resp = new HashMap<>();
                resp.put("success", false);
                resp.put("message", "用户未登录，请先登录");
                return ResponseEntity.status(401).body(resp);
            }
            
            Long userId = Long.parseLong(auth.getName());
            BigDecimal closePrice = req.get("closePrice") != null 
                ? new BigDecimal(req.get("closePrice").toString()) 
                : null;
            
            OptionOrder order = optionOrderService.closeOrder(userId, orderId, closePrice);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("data", order);
            resp.put("profit", order.getProfit());
            resp.put("message", "平仓成功");
            
            return ResponseEntity.ok(resp);
        } catch (BusinessException e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "平仓失败: " + (com.gtcfesk.exchange.common.SafeErrors.message(e) != null ? com.gtcfesk.exchange.common.SafeErrors.message(e) : "未知错误"));
            return ResponseEntity.badRequest().body(resp);
        }
    }
}


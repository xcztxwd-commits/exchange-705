package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.FinancialYieldRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/financial/yield")
@RequiredArgsConstructor
public class AdminFinancialYieldController {
    
    private final AdminFinancialYieldService yieldService;
    private final com.gtcfesk.exchange.repository.FinancialYieldRecordRepository yieldRecords;
    private final com.gtcfesk.exchange.config.BackendAccess access;
    
    /**
     * 获取订单的收益列表
     */
    @GetMapping("/order/{orderId}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "financial_orders", action = "detail")
    public ResponseEntity<?> getOrderYields(@PathVariable Long orderId) {
        try {
            List<FinancialYieldRecord> yields = yieldService.getOrderYields(orderId);
            AdminFinancialYieldService.FinancialYieldStats stats = yieldService.getOrderYieldStats(orderId);
            
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("list", yields);
            resp.put("stats", stats);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 手动触发收益计算
     */
    @PostMapping("/calculate")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "financial_orders", action = "calculate")
    public ResponseEntity<?> calculateYield() {
        try {
            yieldService.calculateDailyYield();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "收益计算完成");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "计算失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 发放收益
     */
    @PostMapping("/payout/{yieldRecordId}")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "financial_orders", action = "payout")
    public ResponseEntity<?> payoutYield(@PathVariable Long yieldRecordId) {
        if (com.gtcfesk.exchange.config.BackendAccess.agentId() != null)
            access.checkUser(yieldRecords.findByTenantIdAndId(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), yieldRecordId).map(FinancialYieldRecord::getUserId).orElse(null));
        try {
            yieldService.payoutYield(yieldRecordId);
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "收益发放成功");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "发放失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
    
    /**
     * 批量发放所有待发放的收益
     */
    @PostMapping("/payout-all")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "financial_orders", action = "payout_all")
    public ResponseEntity<?> payoutAllPending() {
        try {
            yieldService.payoutAllPendingYields();
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", true);
            resp.put("message", "批量发放完成");
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            Map<String, Object> resp = new HashMap<>();
            resp.put("success", false);
            resp.put("message", "发放失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(resp);
        }
    }
}




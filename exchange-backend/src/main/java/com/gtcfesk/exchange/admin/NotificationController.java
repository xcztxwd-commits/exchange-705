package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.ContractOrderRepository;
import com.gtcfesk.exchange.repository.DepositRecordRepository;
import com.gtcfesk.exchange.repository.KycRecordRepository;
import com.gtcfesk.exchange.repository.OptionOrderRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.repository.WithdrawRecordRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/notification")
@RequiredArgsConstructor
public class NotificationController {
    
    private final DepositRecordRepository depositRecordRepository;
    private final WithdrawRecordRepository withdrawRecordRepository;
    private final KycRecordRepository kycRecordRepository;
    private final ContractOrderRepository contractOrderRepository;
    private final OptionOrderRepository optionOrderRepository;
    private final UserAccountRepository userAccountRepository;
    private final JwtUtil jwtUtil;
    private final com.gtcfesk.exchange.config.BackendAccess access;
    private final SystemConfigService systemConfigService;

    @GetMapping("/sounds")
    public ResponseEntity<?> getNotificationSounds() {
        List<Map<String, String>> result = new java.util.ArrayList<>();
        for (String type : java.util.Arrays.asList("withdraw", "deposit", "kyc", "order")) {
            String key = "notification.sound." + type;
            Map<String, String> item = new HashMap<>();
            item.put("configKey", key);
            item.put("configValue", systemConfigService.getConfigValue(key));
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }
    
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
     * 获取待处理消息数量
     */
    @GetMapping("/pending-counts")
    public ResponseEntity<?> getPendingCounts(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            Long agentId = extractAgentId(authHeader);
            
            Map<String, Long> counts = new HashMap<>();
            
            if (agentId != null) {
                // 代理：只统计下级用户的待处理消息
                List<UserAccount> subordinates = userAccountRepository.findByParentUserId(agentId);
                Set<Long> subordinateUserIds = subordinates.stream()
                        .map(UserAccount::getId)
                        .collect(Collectors.toSet());
                
                if (subordinateUserIds.isEmpty()) {
                    counts.put("deposit", 0L);
                    counts.put("withdraw", 0L);
                    counts.put("kyc", 0L);
                    counts.put("order", 0L);
                } else {
                    // 充值待审核数量
                    long depositCount = depositRecordRepository.findAll().stream()
                            .filter(record -> "PENDING".equals(record.getStatus()))
                            .filter(record -> subordinateUserIds.contains(record.getUserId()))
                            .count();
                    counts.put("deposit", depositCount);
                    
                    // 提现待审核数量
                    long withdrawCount = withdrawRecordRepository.findAll().stream()
                            .filter(record -> "PENDING".equals(record.getStatus()))
                            .filter(record -> subordinateUserIds.contains(record.getUserId()))
                            .count();
                    counts.put("withdraw", withdrawCount);
                    
                    // 实名待审核数量
                    long kycCount = kycRecordRepository.findAll().stream()
                            .filter(record -> "PENDING".equals(record.getStatus()))
                            .filter(record -> subordinateUserIds.contains(record.getUserId()))
                            .count();
                    counts.put("kyc", kycCount);
                    
                    // 订单数量（合约订单 + 期货订单）- 只统计未平仓的订单
                    // 合约订单：OPEN（持仓中）或 PENDING（挂单中）为未平仓
                    long contractOrderCount = contractOrderRepository.findAll().stream()
                            .filter(order -> subordinateUserIds.contains(order.getUserId()))
                            .filter(order -> "OPEN".equals(order.getStatus()) || "PENDING".equals(order.getStatus()))
                            .count();
                    // 期货订单：TRADING（交易中）为未平仓
                    long optionOrderCount = optionOrderRepository.findAll().stream()
                            .filter(order -> subordinateUserIds.contains(order.getUserId()))
                            .filter(order -> "TRADING".equals(order.getStatus()))
                            .count();
                    counts.put("order", contractOrderCount + optionOrderCount);
                }
            } else {
                // 管理员：统计所有待处理消息
                // 充值待审核数量
                long depositCount = depositRecordRepository.findAll().stream()
                        .filter(record -> "PENDING".equals(record.getStatus()))
                        .count();
                counts.put("deposit", depositCount);
                
                // 提现待审核数量
                long withdrawCount = withdrawRecordRepository.findAll().stream()
                        .filter(record -> "PENDING".equals(record.getStatus()))
                        .count();
                counts.put("withdraw", withdrawCount);
                
                // 实名待审核数量
                long kycCount = kycRecordRepository.findAll().stream()
                        .filter(record -> "PENDING".equals(record.getStatus()))
                        .count();
                counts.put("kyc", kycCount);
                
                // 订单数量（合约订单 + 期货订单）- 只统计未平仓的订单
                // 合约订单：OPEN（持仓中）或 PENDING（挂单中）为未平仓
                long contractOrderCount = contractOrderRepository.findAll().stream()
                        .filter(order -> "OPEN".equals(order.getStatus()) || "PENDING".equals(order.getStatus()))
                        .count();
                // 期货订单：TRADING（交易中）为未平仓
                long optionOrderCount = optionOrderRepository.findAll().stream()
                        .filter(order -> "TRADING".equals(order.getStatus()))
                        .count();
                counts.put("order", contractOrderCount + optionOrderCount);
            }
            
            if (!access.canReadMenu("deposit_review")) counts.put("deposit", 0L);
            if (!access.canReadMenu("withdraw_review")) counts.put("withdraw", 0L);
            if (!access.canReadMenu("kyc_review")) counts.put("kyc", 0L);
            if (!access.canReadMenu("orders")) counts.put("order", 0L);
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("data", counts);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "获取失败: " + com.gtcfesk.exchange.common.SafeErrors.message(e));
            return ResponseEntity.badRequest().body(result);
        }
    }
}


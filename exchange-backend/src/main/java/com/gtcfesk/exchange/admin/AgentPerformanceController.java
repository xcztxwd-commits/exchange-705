package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/admin/agents")
public class AgentPerformanceController {

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private DepositRecordRepository depositRecordRepository;

    @Autowired
    private WithdrawRecordRepository withdrawRecordRepository;

    @Autowired
    private ContractOrderRepository contractOrderRepository;

    /**
     * 获取代理业绩数据
     */
    @GetMapping("/{agentId}/performance")
    @com.gtcfesk.exchange.config.AdminPermission(menu = "agents", action = "performance")
    public ResponseEntity<?> getAgentPerformance(@PathVariable Long agentId) {
        // 验证代理是否存在
        userAccountRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("代理不存在"));

        // 获取所有下级用户ID（包括直接和间接下级）
        Set<Long> subordinateUserIds = getAllSubordinateUserIds(agentId);
        int subordinateCount = subordinateUserIds.size();

        // 统计累计充值（状态为COMPLETED的充值记录）
        BigDecimal totalDeposit = depositRecordRepository.findAll().stream().filter(d -> !"ADMIN_MANUAL".equals(d.getSource()))
                .filter(deposit -> subordinateUserIds.contains(deposit.getUserId()))
                .filter(deposit -> "COMPLETED".equals(deposit.getStatus()))
                .map(deposit -> deposit.getAmount() != null ? deposit.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 统计累计提现（状态为COMPLETED或APPROVED的提现记录）
        BigDecimal totalWithdraw = withdrawRecordRepository.findAll().stream()
                .filter(withdraw -> subordinateUserIds.contains(withdraw.getUserId()))
                .filter(withdraw -> "COMPLETED".equals(withdraw.getStatus()) || "APPROVED".equals(withdraw.getStatus()))
                .map(withdraw -> withdraw.getAmount() != null ? withdraw.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 统计累计交易量（已平仓的合约订单的交易量）
        BigDecimal totalTrade = contractOrderRepository.findAll().stream()
                .filter(order -> subordinateUserIds.contains(order.getUserId()))
                .filter(order -> "CLOSED".equals(order.getStatus()))
                .map(order -> {
                    if (order.getQuantity() != null && order.getOpenPrice() != null) {
                        return order.getQuantity().multiply(order.getOpenPrice());
                    }
                    return BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> performance = new HashMap<>();
        performance.put("subordinateCount", subordinateCount);
        Map<String,BigDecimal> depositGroups=new LinkedHashMap<>();
        for(com.gtcfesk.exchange.entity.DepositRecord d:depositRecordRepository.findAll()) {
            if(!subordinateUserIds.contains(d.getUserId()) || !"COMPLETED".equals(d.getStatus()))continue;
            String source=d.getSource()==null?"LEGACY_UNKNOWN":d.getSource();
            if("ADMIN_MANUAL".equals(source))source+="_"+d.getManualPurpose();
            depositGroups.merge(source,d.getAmount(),BigDecimal::add);
        }
        performance.put("depositGroupsUsd",depositGroups);
        performance.put("depositBasis","累计充值含用户提交与历史未知，手动用途独立分组；旧报表保持创建时间口径");
        performance.put("subordinateTotalDeposit", totalDeposit); // 下级用户累计充值
        performance.put("subordinateTotalWithdraw", totalWithdraw); // 下级用户累计提现
        performance.put("totalTrade", totalTrade);

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", performance);
        return ResponseEntity.ok(result);
    }

    /**
     * 递归获取所有下级用户ID（包括直接和间接下级）
     */
    private Set<Long> getAllSubordinateUserIds(Long agentId) {
        Set<Long> allSubordinateIds = new HashSet<>();
        Queue<Long> queue = new LinkedList<>();
        queue.offer(agentId);

        while (!queue.isEmpty()) {
            Long currentUserId = queue.poll();
            List<UserAccount> directSubordinates = userAccountRepository.findByParentUserId(currentUserId);
            
            for (UserAccount subordinate : directSubordinates) {
                if (!allSubordinateIds.contains(subordinate.getId())) {
                    allSubordinateIds.add(subordinate.getId());
                    queue.offer(subordinate.getId());
                }
            }
        }

        return allSubordinateIds;
    }
}


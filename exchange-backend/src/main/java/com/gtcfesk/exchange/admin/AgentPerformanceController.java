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
    public ResponseEntity<?> getAgentPerformance(@PathVariable Long agentId) {
        // 验证代理是否存在
        userAccountRepository.findById(agentId)
                .orElseThrow(() -> new IllegalArgumentException("代理不存在"));

        // 获取所有下级用户ID（包括直接和间接下级）
        Set<Long> subordinateUserIds = getAllSubordinateUserIds(agentId);
        int subordinateCount = subordinateUserIds.size();

        // 统计累计充值（状态为COMPLETED的充值记录）
        BigDecimal totalDeposit = depositRecordRepository.findAll().stream()
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


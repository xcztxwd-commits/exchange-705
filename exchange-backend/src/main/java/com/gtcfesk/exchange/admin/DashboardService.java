package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.FinancialOrder;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.ContractOrderRepository;
import com.gtcfesk.exchange.repository.DepositRecordRepository;
import com.gtcfesk.exchange.repository.FinancialOrderRepository;
import com.gtcfesk.exchange.repository.OptionOrderRepository;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.repository.WithdrawRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DashboardService {
    
    private final UserAccountRepository userAccountRepository;
    private final FinancialOrderRepository financialOrderRepository;
    private final ContractOrderRepository contractOrderRepository;
    private final OptionOrderRepository optionOrderRepository;
    private final DepositRecordRepository depositRecordRepository;
    private final WithdrawRecordRepository withdrawRecordRepository;
    
    /**
     * 获取仪表盘统计数据
     * @param agentId 代理ID，如果为null则返回管理员统计数据
     */
    public Map<String, Object> getDashboardStats(Long agentId) {
        Map<String, Object> stats = new HashMap<>();
        
        if (agentId != null) {
            // 代理统计：下级用户、下级用户交易订单、下级用户交易金额
            List<UserAccount> subordinates = userAccountRepository.findByParentUserId(agentId);
            long subordinateCount = subordinates.size();
            stats.put("subordinateCount", subordinateCount);
            
            // 获取所有下级用户ID
            Set<Long> subordinateUserIds = subordinates.stream()
                    .map(UserAccount::getId)
                    .collect(Collectors.toSet());
            
            if (!subordinateUserIds.isEmpty()) {
                // 统计下级用户的交易订单数（合约订单 + 期货订单）
                long contractOrderCount = contractOrderRepository.findAll().stream()
                        .filter(order -> subordinateUserIds.contains(order.getUserId()))
                        .count();
                long optionOrderCount = optionOrderRepository.findAll().stream()
                        .filter(order -> subordinateUserIds.contains(order.getUserId()))
                        .count();
                long totalOrderCount = contractOrderCount + optionOrderCount;
                stats.put("subordinateOrderCount", totalOrderCount);
                
                // 统计下级用户的交易金额（合约订单的保证金 + 期货订单的金额）
                BigDecimal contractAmount = contractOrderRepository.findAll().stream()
                        .filter(order -> subordinateUserIds.contains(order.getUserId()))
                        .map(order -> order.getMargin() != null ? order.getMargin() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal optionAmount = optionOrderRepository.findAll().stream()
                        .filter(order -> subordinateUserIds.contains(order.getUserId()))
                        .map(order -> order.getAmount() != null ? order.getAmount() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal totalAmount = contractAmount.add(optionAmount);
                stats.put("subordinateOrderAmount", totalAmount);
            } else {
                stats.put("subordinateOrderCount", 0L);
                stats.put("subordinateOrderAmount", BigDecimal.ZERO);
            }
        } else {
            // 管理员统计：用户总数、今日交易、交易金额、活跃用户
            long totalUsers = userAccountRepository.count();
            stats.put("totalUsers", totalUsers);
            
            // 今日交易（今日创建的订单数）
            LocalDateTime todayStart = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
            LocalDateTime todayEnd = LocalDateTime.of(LocalDate.now(), LocalTime.MAX);
            long todayTransactions = financialOrderRepository.countByPurchaseTimeBetween(todayStart, todayEnd);
            stats.put("todayTransactions", todayTransactions);
            
            // 今日交易金额（今日订单的总金额）
            BigDecimal todayAmount = financialOrderRepository
                    .findByPurchaseTimeBetween(todayStart, todayEnd)
                    .stream()
                    .map(FinancialOrder::getPurchaseAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            stats.put("todayAmount", todayAmount);
            
            // 活跃用户（最近7天内有交易的用户数）
            LocalDateTime sevenDaysAgo = LocalDateTime.now().minusDays(7);
            long activeUsers = financialOrderRepository
                    .findDistinctUserIdByPurchaseTimeAfter(sevenDaysAgo)
                    .size();
            stats.put("activeUsers", activeUsers);
        }
        
        return stats;
    }
    
    /**
     * 获取充值和提现的图表数据（最近7天）
     * @param agentId 代理ID，如果为null则返回管理员统计数据
     */
    public Map<String, Object> getDepositWithdrawChartData(Long agentId) {
        Map<String, Object> result = new HashMap<>();
        
        // 获取最近7天的日期列表
        List<String> dates = new ArrayList<>();
        List<BigDecimal> depositAmounts = new ArrayList<>();
        List<BigDecimal> withdrawAmounts = new ArrayList<>();
        
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            dates.add(date.toString());
            
            LocalDateTime startOfDay = LocalDateTime.of(date, LocalTime.MIN);
            LocalDateTime endOfDay = LocalDateTime.of(date, LocalTime.MAX);
            
            // 获取该日期的充值金额
            BigDecimal depositAmount = BigDecimal.ZERO;
            // 获取该日期的提现金额
            BigDecimal withdrawAmount = BigDecimal.ZERO;
            
            if (agentId != null) {
                // 代理：只统计下级用户的数据
                List<UserAccount> subordinates = userAccountRepository.findByParentUserId(agentId);
                Set<Long> subordinateUserIds = subordinates.stream()
                        .map(UserAccount::getId)
                        .collect(Collectors.toSet());
                
                if (!subordinateUserIds.isEmpty()) {
                    // 统计下级用户的充值金额（已审核通过的）
                    depositAmount = depositRecordRepository.findAll().stream()
                            .filter(record -> subordinateUserIds.contains(record.getUserId()))
                            .filter(record -> record.getCreatedAt() != null 
                                    && !record.getCreatedAt().isBefore(startOfDay)
                                    && !record.getCreatedAt().isAfter(endOfDay))
                            .filter(record -> "COMPLETED".equals(record.getStatus()))
                            .map(record -> record.getAmount() != null ? record.getAmount() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    
                    // 统计下级用户的提现金额（已审核通过的，包括APPROVED和COMPLETED）
                    withdrawAmount = withdrawRecordRepository.findAll().stream()
                            .filter(record -> subordinateUserIds.contains(record.getUserId()))
                            .filter(record -> record.getCreatedAt() != null 
                                    && !record.getCreatedAt().isBefore(startOfDay)
                                    && !record.getCreatedAt().isAfter(endOfDay))
                            .filter(record -> "APPROVED".equals(record.getStatus()) || "COMPLETED".equals(record.getStatus()))
                            .map(record -> record.getAmount() != null ? record.getAmount() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                }
            } else {
                // 管理员：统计所有用户的数据
                depositAmount = depositRecordRepository.findAll().stream()
                        .filter(record -> record.getCreatedAt() != null 
                                && !record.getCreatedAt().isBefore(startOfDay)
                                && !record.getCreatedAt().isAfter(endOfDay))
                        .filter(record -> "COMPLETED".equals(record.getStatus()))
                        .map(record -> record.getAmount() != null ? record.getAmount() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                withdrawAmount = withdrawRecordRepository.findAll().stream()
                        .filter(record -> record.getCreatedAt() != null 
                                && !record.getCreatedAt().isBefore(startOfDay)
                                && !record.getCreatedAt().isAfter(endOfDay))
                        .filter(record -> "APPROVED".equals(record.getStatus()) || "COMPLETED".equals(record.getStatus()))
                        .map(record -> record.getAmount() != null ? record.getAmount() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            
            depositAmounts.add(depositAmount);
            withdrawAmounts.add(withdrawAmount);
        }
        
        result.put("dates", dates);
        result.put("depositAmounts", depositAmounts.stream().map(BigDecimal::doubleValue).collect(Collectors.toList()));
        result.put("withdrawAmounts", withdrawAmounts.stream().map(BigDecimal::doubleValue).collect(Collectors.toList()));
        
        return result;
    }
    
    /**
     * 获取统计数据（总用户数、充值总额、提现总额、交易总额）
     * @param startDate 开始日期
     * @param endDate 结束日期
     */
    public Map<String, Object> getStatisticsData(LocalDate startDate, LocalDate endDate) {
        Map<String, Object> stats = new HashMap<>();
        Long agent = com.gtcfesk.exchange.config.BackendAccess.agentId();
        if (agent != null) {
            java.util.Set<Long> allowed = userAccountRepository.findByParentUserId(agent).stream().map(UserAccount::getId).collect(java.util.stream.Collectors.toSet());
            java.util.function.Predicate<LocalDateTime> inRange = time -> time != null && !time.toLocalDate().isBefore(startDate) && !time.toLocalDate().isAfter(endDate);
            stats.put("totalUsers", (long) allowed.size());
            stats.put("totalDeposit", depositRecordRepository.findAll().stream().filter(d -> allowed.contains(d.getUserId()) && "COMPLETED".equals(d.getStatus()) && inRange.test(d.getCreatedAt())).map(d -> d.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add));
            stats.put("totalWithdraw", withdrawRecordRepository.findAll().stream().filter(d -> allowed.contains(d.getUserId()) && ("APPROVED".equals(d.getStatus()) || "COMPLETED".equals(d.getStatus())) && inRange.test(d.getCreatedAt())).map(d -> d.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add));
            BigDecimal contract = contractOrderRepository.findAll().stream().filter(d -> allowed.contains(d.getUserId()) && inRange.test(d.getCreatedAt())).map(d -> d.getMargin()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal option = optionOrderRepository.findAll().stream().filter(d -> allowed.contains(d.getUserId()) && inRange.test(d.getCreatedAt())).map(d -> d.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
            stats.put("totalTrade", contract.add(option));
            return stats;
        }
        LocalDateTime start = LocalDateTime.of(startDate, LocalTime.MIN);
        LocalDateTime end = LocalDateTime.of(endDate, LocalTime.MAX);
        
        System.out.println("[DashboardService] 开始计算统计数据，日期范围: " + start + " 至 " + end);
        
        try {
            // 1. 总用户数（所有用户）
            long totalUsers = userAccountRepository.count();
            stats.put("totalUsers", totalUsers);
            System.out.println("[DashboardService] 总用户数: " + totalUsers);
            
            // 2. 充值总额（指定日期范围内已完成的充值）- 使用数据库查询优化性能
            System.out.println("[DashboardService] 开始计算充值总额");
            BigDecimal totalDeposit = depositRecordRepository.sumAmountByStatusAndCreatedAtBetween("COMPLETED", start, end);
            if (totalDeposit == null) {
                totalDeposit = BigDecimal.ZERO;
            }
            stats.put("totalDeposit", totalDeposit);
            System.out.println("[DashboardService] 充值总额: " + totalDeposit);
            
            // 3. 提现总额（指定日期范围内已审核通过的提现）- 使用数据库查询优化性能
            System.out.println("[DashboardService] 开始计算提现总额");
            BigDecimal totalWithdraw = withdrawRecordRepository.sumAmountByApprovedStatusAndCreatedAtBetween(start, end);
            if (totalWithdraw == null) {
                totalWithdraw = BigDecimal.ZERO;
            }
            stats.put("totalWithdraw", totalWithdraw);
            System.out.println("[DashboardService] 提现总额: " + totalWithdraw);
            
            // 4. 交易总额（合约订单保证金 + 期货订单金额）- 使用数据库查询优化性能
            System.out.println("[DashboardService] 开始计算交易总额");
            BigDecimal contractAmount = contractOrderRepository.sumMarginByCreatedAtBetween(start, end);
            if (contractAmount == null) {
                contractAmount = BigDecimal.ZERO;
            }
            System.out.println("[DashboardService] 合约订单总额: " + contractAmount);
            
            BigDecimal optionAmount = optionOrderRepository.sumAmountByCreatedAtBetween(start, end);
            if (optionAmount == null) {
                optionAmount = BigDecimal.ZERO;
            }
            System.out.println("[DashboardService] 期货订单总额: " + optionAmount);
            
            BigDecimal totalTrade = contractAmount.add(optionAmount);
            stats.put("totalTrade", totalTrade);
            System.out.println("[DashboardService] 交易总额: " + totalTrade);
            
        } catch (Exception e) {
            System.err.println("[DashboardService] 计算统计数据时出错: " + e.getMessage());
            e.printStackTrace();
            // 即使出错也返回默认值
            stats.put("totalUsers", 0L);
            stats.put("totalDeposit", BigDecimal.ZERO);
            stats.put("totalWithdraw", BigDecimal.ZERO);
            stats.put("totalTrade", BigDecimal.ZERO);
        }
        
        return stats;
    }
    
    /**
     * 根据日期范围获取充值和提现的图表数据
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @param agentId 代理ID，如果为null则返回管理员统计数据
     */
    public Map<String, Object> getDepositWithdrawChartDataByDateRange(LocalDate startDate, LocalDate endDate, Long agentId) {
        Map<String, Object> result = new HashMap<>();
        
        // 获取日期范围内的所有日期
        List<String> dates = new ArrayList<>();
        List<BigDecimal> depositAmounts = new ArrayList<>();
        List<BigDecimal> withdrawAmounts = new ArrayList<>();
        
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            dates.add(current.toString());
            
            LocalDateTime startOfDay = LocalDateTime.of(current, LocalTime.MIN);
            LocalDateTime endOfDay = LocalDateTime.of(current, LocalTime.MAX);
            
            // 获取该日期的充值金额
            BigDecimal depositAmount = BigDecimal.ZERO;
            // 获取该日期的提现金额
            BigDecimal withdrawAmount = BigDecimal.ZERO;
            
            if (agentId != null) {
                // 代理：只统计下级用户的数据
                List<UserAccount> subordinates = userAccountRepository.findByParentUserId(agentId);
                Set<Long> subordinateUserIds = subordinates.stream()
                        .map(UserAccount::getId)
                        .collect(Collectors.toSet());
                
                if (!subordinateUserIds.isEmpty()) {
                    // 统计下级用户的充值金额（已完成的）
                    depositAmount = depositRecordRepository.findAll().stream()
                            .filter(record -> subordinateUserIds.contains(record.getUserId()))
                            .filter(record -> record.getCreatedAt() != null 
                                    && !record.getCreatedAt().isBefore(startOfDay)
                                    && !record.getCreatedAt().isAfter(endOfDay))
                            .filter(record -> "COMPLETED".equals(record.getStatus()))
                            .map(record -> record.getAmount() != null ? record.getAmount() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    
                    // 统计下级用户的提现金额（已审核通过的，包括APPROVED和COMPLETED）
                    withdrawAmount = withdrawRecordRepository.findAll().stream()
                            .filter(record -> subordinateUserIds.contains(record.getUserId()))
                            .filter(record -> record.getCreatedAt() != null 
                                    && !record.getCreatedAt().isBefore(startOfDay)
                                    && !record.getCreatedAt().isAfter(endOfDay))
                            .filter(record -> "APPROVED".equals(record.getStatus()) || "COMPLETED".equals(record.getStatus()))
                            .map(record -> record.getAmount() != null ? record.getAmount() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                }
            } else {
                // 管理员：统计所有用户的数据
                depositAmount = depositRecordRepository.findAll().stream()
                        .filter(record -> record.getCreatedAt() != null 
                                && !record.getCreatedAt().isBefore(startOfDay)
                                && !record.getCreatedAt().isAfter(endOfDay))
                        .filter(record -> "COMPLETED".equals(record.getStatus()))
                        .map(record -> record.getAmount() != null ? record.getAmount() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                withdrawAmount = withdrawRecordRepository.findAll().stream()
                        .filter(record -> record.getCreatedAt() != null 
                                && !record.getCreatedAt().isBefore(startOfDay)
                                && !record.getCreatedAt().isAfter(endOfDay))
                        .filter(record -> "APPROVED".equals(record.getStatus()) || "COMPLETED".equals(record.getStatus()))
                        .map(record -> record.getAmount() != null ? record.getAmount() : BigDecimal.ZERO)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
            }
            
            depositAmounts.add(depositAmount);
            withdrawAmounts.add(withdrawAmount);
            
            current = current.plusDays(1);
        }
        
        result.put("dates", dates);
        result.put("depositAmounts", depositAmounts.stream().map(BigDecimal::doubleValue).collect(Collectors.toList()));
        result.put("withdrawAmounts", withdrawAmounts.stream().map(BigDecimal::doubleValue).collect(Collectors.toList()));
        
        return result;
    }
}


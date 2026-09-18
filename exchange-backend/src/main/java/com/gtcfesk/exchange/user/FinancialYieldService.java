package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.FinancialOrder;
import com.gtcfesk.exchange.entity.FinancialYieldRecord;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.FinancialOrderRepository;
import com.gtcfesk.exchange.repository.FinancialYieldRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinancialYieldService {
    
    private final FinancialOrderRepository orderRepository;
    private final FinancialYieldRecordRepository yieldRecordRepository;
    private final AssetAccountRepository assetAccountRepository;
    
    /**
     * 每天自动计算收益（定时任务，每天凌晨1点执行）
     */
    @Scheduled(cron = "0 0 1 * * ?")
    @Transactional
    public void calculateDailyYield() {
        log.info("开始执行每日收益计算任务");
        LocalDate today = LocalDate.now();
        
        // 获取所有进行中的订单
        List<FinancialOrder> activeOrders = orderRepository.findByStatusOrderByPurchaseTimeDesc("IN_PROGRESS");
        
        for (FinancialOrder order : activeOrders) {
            try {
                // 检查订单是否已到期
                if (order.getEndTime() != null && order.getEndTime().isBefore(LocalDateTime.now())) {
                    // 订单已到期，返还本金到资金账户
                    AssetAccount fundAccount = assetAccountRepository
                            .findByUserIdAndCoin(order.getUserId(), "FUND")
                            .orElseGet(() -> {
                                AssetAccount account = new AssetAccount();
                                account.setUserId(order.getUserId());
                                account.setCoin("FUND");
                                account.setAvailable(BigDecimal.ZERO);
                                account.setFrozen(BigDecimal.ZERO);
                                return assetAccountRepository.save(account);
                            });
                    
                    // 解冻本金
                    BigDecimal frozen = fundAccount.getFrozen() != null ? fundAccount.getFrozen() : BigDecimal.ZERO;
                    if (frozen.compareTo(order.getPurchaseAmount()) >= 0) {
                        fundAccount.setFrozen(frozen.subtract(order.getPurchaseAmount()));
                        BigDecimal available = fundAccount.getAvailable() != null ? fundAccount.getAvailable() : BigDecimal.ZERO;
                        fundAccount.setAvailable(available.add(order.getPurchaseAmount()));
                        assetAccountRepository.save(fundAccount);
                        log.info("订单 {} 到期，本金已返还到资金账户: {}", order.getId(), order.getPurchaseAmount());
                    }
                    
                    // 订单已到期，更新状态为已完成
                    order.setStatus("COMPLETED");
                    orderRepository.save(order);
                    continue;
                }
                
                // 检查今天是否已经计算过收益
                FinancialYieldRecord existing = yieldRecordRepository.findByOrderIdAndYieldDate(order.getId(), today);
                if (existing != null) {
                    log.info("订单 {} 今日收益已计算", order.getId());
                    continue;
                }
                
                // 计算今日收益
                BigDecimal dailyYield = order.getDailyYield();
                
                // 计算累计收益
                List<FinancialYieldRecord> previousRecords = yieldRecordRepository.findByOrderIdOrderByYieldDateDesc(order.getId());
                BigDecimal cumulativeYield = dailyYield;
                if (!previousRecords.isEmpty()) {
                    cumulativeYield = previousRecords.get(0).getCumulativeYield().add(dailyYield);
                }
                
                // 创建收益记录
                FinancialYieldRecord record = new FinancialYieldRecord();
                record.setOrderId(order.getId());
                record.setUserId(order.getUserId());
                record.setProductId(order.getProductId());
                record.setProductName(order.getProductName());
                record.setYieldDate(today);
                record.setDailyYield(dailyYield);
                record.setCumulativeYield(cumulativeYield);
                record.setStatus("PENDING");
                
                yieldRecordRepository.save(record);
                log.info("订单 {} 今日收益计算完成: {}", order.getId(), dailyYield);
                
            } catch (Exception e) {
                log.error("计算订单 {} 收益失败: {}", order.getId(), e.getMessage(), e);
            }
        }
        
        log.info("每日收益计算任务完成");
    }
    
    /**
     * 发放收益（将收益添加到用户合约账户）
     */
    @Transactional
    public void payoutYield(Long yieldRecordId) {
        FinancialYieldRecord record = yieldRecordRepository.findById(yieldRecordId)
                .orElseThrow(() -> new RuntimeException("收益记录不存在"));
        
        if ("PAID".equals(record.getStatus())) {
            throw new RuntimeException("该收益已发放");
        }
        
        // 获取用户资金账户
        AssetAccount fundAccount = assetAccountRepository
                .findByUserIdAndCoin(record.getUserId(), "FUND")
                .orElseGet(() -> {
                    AssetAccount account = new AssetAccount();
                    account.setUserId(record.getUserId());
                    account.setCoin("FUND");
                    account.setAvailable(BigDecimal.ZERO);
                    account.setFrozen(BigDecimal.ZERO);
                    return assetAccountRepository.save(account);
                });
        
        // 增加可用余额
        BigDecimal currentAvailable = fundAccount.getAvailable() != null 
                ? fundAccount.getAvailable() 
                : BigDecimal.ZERO;
        fundAccount.setAvailable(currentAvailable.add(record.getDailyYield()));
        assetAccountRepository.save(fundAccount);
        
        // 更新收益记录状态
        record.setStatus("PAID");
        record.setPaidAt(LocalDateTime.now());
        yieldRecordRepository.save(record);
    }
    
    /**
     * 批量发放收益（发放所有待发放的收益）
     */
    @Transactional
    public void payoutAllPendingYields() {
        List<FinancialYieldRecord> pendingRecords = yieldRecordRepository.findByStatusOrderByYieldDateAsc("PENDING");
        
        for (FinancialYieldRecord record : pendingRecords) {
            try {
                payoutYield(record.getId());
            } catch (Exception e) {
                log.error("发放收益记录 {} 失败: {}", record.getId(), e.getMessage(), e);
            }
        }
    }
    
    /**
     * 获取订单的收益列表
     */
    public List<FinancialYieldRecord> getOrderYields(Long orderId) {
        return yieldRecordRepository.findByOrderIdOrderByYieldDateDesc(orderId);
    }
    
    /**
     * 获取用户的收益列表
     */
    public List<FinancialYieldRecord> getUserYields(Long userId) {
        return yieldRecordRepository.findByUserIdOrderByYieldDateDesc(userId);
    }
    
    /**
     * 获取订单的收益统计
     */
    public FinancialYieldStats getOrderYieldStats(Long orderId) {
        List<FinancialYieldRecord> records = yieldRecordRepository.findByOrderIdOrderByYieldDateDesc(orderId);
        
        BigDecimal totalYield = BigDecimal.ZERO;
        BigDecimal paidYield = BigDecimal.ZERO;
        BigDecimal pendingYield = BigDecimal.ZERO;
        
        for (FinancialYieldRecord record : records) {
            totalYield = totalYield.add(record.getDailyYield());
            if ("PAID".equals(record.getStatus())) {
                paidYield = paidYield.add(record.getDailyYield());
            } else {
                pendingYield = pendingYield.add(record.getDailyYield());
            }
        }
        
        FinancialYieldStats stats = new FinancialYieldStats();
        stats.setTotalYield(totalYield);
        stats.setPaidYield(paidYield);
        stats.setPendingYield(pendingYield);
        stats.setRecordCount(records.size());
        
        return stats;
    }
    
    public static class FinancialYieldStats {
        private BigDecimal totalYield = BigDecimal.ZERO;
        private BigDecimal paidYield = BigDecimal.ZERO;
        private BigDecimal pendingYield = BigDecimal.ZERO;
        private Integer recordCount = 0;
        
        // Getters and Setters
        public BigDecimal getTotalYield() { return totalYield; }
        public void setTotalYield(BigDecimal totalYield) { this.totalYield = totalYield; }
        public BigDecimal getPaidYield() { return paidYield; }
        public void setPaidYield(BigDecimal paidYield) { this.paidYield = paidYield; }
        public BigDecimal getPendingYield() { return pendingYield; }
        public void setPendingYield(BigDecimal pendingYield) { this.pendingYield = pendingYield; }
        public Integer getRecordCount() { return recordCount; }
        public void setRecordCount(Integer recordCount) { this.recordCount = recordCount; }
    }
}


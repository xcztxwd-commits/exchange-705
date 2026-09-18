package com.gtcfesk.exchange.admin;

import com.gtcfesk.exchange.entity.FinancialYieldRecord;
import com.gtcfesk.exchange.repository.FinancialYieldRecordRepository;
import com.gtcfesk.exchange.user.FinancialYieldService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AdminFinancialYieldService {
    
    private final FinancialYieldRecordRepository yieldRecordRepository;
    private final FinancialYieldService yieldService;
    
    /**
     * 获取订单的收益列表
     */
    public List<FinancialYieldRecord> getOrderYields(Long orderId) {
        return yieldRecordRepository.findByOrderIdOrderByYieldDateDesc(orderId);
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
    
    /**
     * 手动触发收益计算
     */
    public void calculateDailyYield() {
        yieldService.calculateDailyYield();
    }
    
    /**
     * 发放收益
     */
    public void payoutYield(Long yieldRecordId) {
        yieldService.payoutYield(yieldRecordId);
    }
    
    /**
     * 批量发放收益
     */
    public void payoutAllPendingYields() {
        yieldService.payoutAllPendingYields();
    }
    
    public static class FinancialYieldStats {
        private BigDecimal totalYield = BigDecimal.ZERO;
        private BigDecimal paidYield = BigDecimal.ZERO;
        private BigDecimal pendingYield = BigDecimal.ZERO;
        private Integer recordCount = 0;
        
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




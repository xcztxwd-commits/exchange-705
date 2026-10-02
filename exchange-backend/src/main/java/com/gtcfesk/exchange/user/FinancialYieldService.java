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
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService audit;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.repository.UserAccountRepository users;
    
    private final FinancialOrderRepository orderRepository;
    private final FinancialYieldRecordRepository yieldRecordRepository;
    private final AssetAccountRepository assetAccountRepository;
    
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.tenant.TenantJobRunner tenantJobs;
    /**
     * 每天自动计算收益（定时任务，每天凌晨1点执行）
     */
    @Scheduled(cron = "0 0 1 * * ?")
    public void scheduledYield() { tenantJobs.each("financial-yield",tenant -> calculateDailyYield()); }
    // Boot/periodic recovery uses the same idempotent engine; the daily schedule remains unchanged.
    @Scheduled(fixedDelayString="${financial.yield.recovery-ms:60000}", initialDelayString="${financial.yield.initial-delay-ms:1000}")
    public void recoverYield() { tenantJobs.each("financial-yield-recovery",tenant -> calculateDailyYield()); }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void calculateDailyYield() {
        if(com.gtcfesk.exchange.config.BackendAccess.agentId()!=null)throw new org.springframework.security.access.AccessDeniedException("代理不能执行全租户收益计算");
        LocalDate today=LocalDate.now();
        // IDs only: locking queries must not reuse stale entities loaded by the initial scan.
        for(Long id:orderRepository.unsettledIds()) {
            FinancialOrder order=orderRepository.lockById(id).orElseThrow(()->new com.gtcfesk.exchange.common.BusinessException("订单不存在"));
            if(!"IN_PROGRESS".equals(order.getStatus()))continue;
            users.lockById(order.getUserId()).orElseThrow(()->new com.gtcfesk.exchange.common.BusinessException("用户不存在"));
            BigDecimal daily=order.getDailyYield();
            if(daily==null||daily.signum()<0||order.getPurchaseTime()==null||order.getTermDays()==null||order.getTermDays()<=0)
                throw new com.gtcfesk.exchange.common.BusinessException("理财收益配置异常");
            // Preserve the existing purchase-calendar-day convention, bounded by the saved term.
            // Accrue every missed date before maturity; never read a subsequently edited product.
            LocalDate first=order.getPurchaseTime().toLocalDate();
            LocalDate last=first.plusDays(order.getTermDays()-1L);
            if(last.isAfter(today))last=today;
            if(order.getEndTime()!=null&&last.isAfter(order.getEndTime().toLocalDate()))last=order.getEndTime().toLocalDate();
            List<FinancialYieldRecord> previous=new java.util.ArrayList<>(yieldRecordRepository.lockByOrder(id));
            java.util.Set<LocalDate> recorded=new java.util.HashSet<>();
            for(FinancialYieldRecord r:previous)recorded.add(r.getYieldDate());
            for(LocalDate date=first;!date.isAfter(last);date=date.plusDays(1)) {
                if(recorded.contains(date))continue;
                BigDecimal cumulative=daily;
                for(FinancialYieldRecord r:previous)if(!r.getYieldDate().isAfter(date))cumulative=cumulative.add(r.getDailyYield());
                FinancialYieldRecord record=new FinancialYieldRecord();record.setOrderId(id);record.setUserId(order.getUserId());record.setProductId(order.getProductId());record.setProductName(order.getProductName());record.setYieldDate(date);record.setDailyYield(daily);record.setCumulativeYield(cumulative);record.setStatus("PENDING");
                yieldRecordRepository.save(record);previous.add(record);recorded.add(date);
                audit.recordCurrent("FINANCIAL_YIELD_CALCULATE",record.getId().toString(),"orderId="+id+"; userId="+record.getUserId()+"; yieldDate="+date+"; amount="+daily,null);
            }
            if(order.getEndTime()!=null&&!order.getEndTime().isAfter(LocalDateTime.now())) {
                AssetAccount fund=assetAccountRepository.lockByUserId(order.getUserId()).stream().filter(a->"FUND".equals(a.getCoin())).findFirst().orElseThrow(()->new com.gtcfesk.exchange.common.BusinessException("资金账户不存在"));
                BigDecimal before=fund.getAvailable(),frozen=fund.getFrozen();
                com.gtcfesk.exchange.common.TradeValidation.positive(order.getPurchaseAmount(),"申购本金");
                if(frozen==null||frozen.compareTo(order.getPurchaseAmount())<0)throw new com.gtcfesk.exchange.common.BusinessException("冻结资金异常");
                fund.setFrozen(frozen.subtract(order.getPurchaseAmount()));fund.setAvailable(before.add(order.getPurchaseAmount()));
                assetAccountRepository.save(fund);order.setStatus("COMPLETED");orderRepository.save(order);
                audit.recordCurrent("FINANCIAL_MATURE",id.toString(),"userId="+order.getUserId()+"; principal="+order.getPurchaseAmount()+"; availableBefore="+before+"; availableAfter="+fund.getAvailable()+"; frozenBefore="+frozen+"; frozenAfter="+fund.getFrozen(),null);
            }
        }
    }

    @Transactional
    public void payoutYield(Long yieldRecordId) {
        FinancialYieldRecord record=yieldRecordRepository.lockById(yieldRecordId).orElseThrow(()->new com.gtcfesk.exchange.common.BusinessException("收益记录不存在"));
        users.lockById(record.getUserId()).orElseThrow(()->new com.gtcfesk.exchange.common.BusinessException("用户不存在"));
        if("PAID".equals(record.getStatus()))return;
        if(!"PENDING".equals(record.getStatus())||record.getDailyYield()==null||record.getDailyYield().signum()<0)throw new com.gtcfesk.exchange.common.BusinessException("收益记录状态或金额异常");
        AssetAccount fund=assetAccountRepository.lockByUserId(record.getUserId()).stream().filter(a->"FUND".equals(a.getCoin())).findFirst().orElseThrow(()->new com.gtcfesk.exchange.common.BusinessException("资金账户不存在"));
        BigDecimal before=fund.getAvailable();fund.setAvailable(before.add(record.getDailyYield()));assetAccountRepository.save(fund);
        record.setStatus("PAID");record.setPaidAt(LocalDateTime.now());yieldRecordRepository.save(record);
        audit.recordCurrent("FINANCIAL_YIELD_PAY",yieldRecordId.toString(),"userId="+record.getUserId()+"; amount="+record.getDailyYield()+"; availableBefore="+before+"; availableAfter="+fund.getAvailable(),null);
    }

    @Transactional
    public void payoutAllPendingYields() {
        if(com.gtcfesk.exchange.config.BackendAccess.agentId()!=null)throw new org.springframework.security.access.AccessDeniedException("代理不能执行全租户收益发放");
        // Batch is atomic; never swallow an audit or balance failure and commit earlier mutations.
        for(Long id:yieldRecordRepository.unsettledIds())payoutYield(id);
    }

    /**
     * 获取订单的收益列表
     */
    public List<FinancialYieldRecord> getOrderYields(Long orderId) {
        return yieldRecordRepository.findByTenantIdAndOrderIdOrderByYieldDateDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), orderId);
    }
    
    /**
     * 获取用户的收益列表
     */
    public List<FinancialYieldRecord> getUserYields(Long userId) {
        return yieldRecordRepository.findByTenantIdAndUserIdOrderByYieldDateDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), userId);
    }
    
    /**
     * 获取订单的收益统计
     */
    public FinancialYieldStats getOrderYieldStats(Long orderId) {
        List<FinancialYieldRecord> records = yieldRecordRepository.findByTenantIdAndOrderIdOrderByYieldDateDesc(com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), orderId);
        
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


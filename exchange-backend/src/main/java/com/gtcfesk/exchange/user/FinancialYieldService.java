package com.gtcfesk.exchange.user;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.common.TradeValidation;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.entity.AssetAccount;
import com.gtcfesk.exchange.entity.FinancialOrder;
import com.gtcfesk.exchange.entity.FinancialYieldRecord;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.AssetAccountRepository;
import com.gtcfesk.exchange.repository.FinancialOrderRepository;
import com.gtcfesk.exchange.repository.FinancialYieldRecordRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinancialYieldService {
    private static final int ACCRUAL_DAYS_PER_TRANSACTION = 31;
    private static final int ORDER_SCAN_PAGE_SIZE = 100;
    private static final int TRANSIENT_RETRIES = 3;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.control.ControlAuditService audit;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.repository.UserAccountRepository users;
    @org.springframework.beans.factory.annotation.Autowired private PlatformTransactionManager transactionManager;
    @javax.persistence.PersistenceContext private javax.persistence.EntityManager entityManager;
    @org.springframework.beans.factory.annotation.Autowired private com.gtcfesk.exchange.tenant.TenantJobRunner tenantJobs;

    private final FinancialOrderRepository orderRepository;
    private final FinancialYieldRecordRepository yieldRecordRepository;
    private final AssetAccountRepository assetAccountRepository;

    @Scheduled(cron = "0 0 1 * * ?")
    public void scheduledYield() { tenantJobs.eachContext("financial-yield", tenant -> calculateDailyYield()); }

    @Scheduled(fixedDelayString="${financial.yield.recovery-ms:60000}", initialDelayString="${financial.yield.initial-delay-ms:1000}")
    public void recoverYield() { tenantJobs.eachContext("financial-yield-recovery", tenant -> calculateDailyYield()); }

    /** Scan scalar IDs without a funding transaction; one bounded committed date segment per order per run. */
    public void calculateDailyYield() {
        if (BackendAccess.agentId()!=null)
            throw new org.springframework.security.access.AccessDeniedException("代理不能执行全租户收益计算");
        TenantContext.requireTenantId();
        LocalDateTime now = LocalDateTime.now();
        Long after = 0L;
        List<Long> ids;
        do {
            ids = orderRepository.unsettledIdsAfter(after, PageRequest.of(0, ORDER_SCAN_PAGE_SIZE));
            for (Long id : ids) {
                Long owner = orderRepository.ownerId(id).orElse(null);
                if (owner != null) accrueWithRetry(id, owner, now);
            }
            if (!ids.isEmpty()) after = ids.get(ids.size()-1);
        } while (ids.size() == ORDER_SCAN_PAGE_SIZE);
    }

    private void accrueWithRetry(Long id, Long owner, LocalDateTime now) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        // Explicit legacy outer transactions retain their atomic contract, without borrowing a nested connection.
        // Never retry a failed joined transaction; its caller must roll back and retry the whole unit.
        int attempts = TransactionSynchronizationManager.isActualTransactionActive() ? 1 : TRANSIENT_RETRIES;
        for (int attempt=1; attempt<=attempts; attempt++) {
            try {
                transaction.execute(status -> { accrueLocked(id, owner, now); return null; });
                return;
            } catch (TransientDataAccessException failure) {
                if (attempt == attempts) throw failure;
                log.warn("Financial accrual retry: order={}, attempt={}, type={}", id, attempt, failure.getClass().getSimpleName());
            }
        }
    }

    private void accrueLocked(Long id, Long ownerId, LocalDateTime now) {
        lockUser(ownerId);
        List<AssetAccount> accounts = lockAssets(ownerId);
        FinancialCurrentLocks.order(entityManager,id);
        FinancialOrder order = orderRepository.lockById(id).orElseThrow(() -> new BusinessException("订单不存在"));
        TenantContext.require(order.getTenantId());
        if (!ownerId.equals(order.getUserId())) throw new BusinessException("理财订单归属已变更");
        if (!"IN_PROGRESS".equals(order.getStatus())) return;
        TradeValidation.nonNegative(order.getDailyYield(), "理财日收益");
        if (order.getPurchaseTime()==null || order.getTermDays()==null || order.getTermDays()<=0)
            throw new BusinessException("理财收益配置异常");

        // Keep the saved purchase-calendar-day convention; an edited product is never reread for accrued money.
        LocalDate first = order.getPurchaseTime().toLocalDate();
        LocalDate termLast = first.plusDays(order.getTermDays()-1L);
        LocalDate last = termLast.isAfter(now.toLocalDate()) ? now.toLocalDate() : termLast;
        if (order.getEndTime()!=null && last.isAfter(order.getEndTime().toLocalDate()))
            last = order.getEndTime().toLocalDate();
        LocalDate progress = order.getLastAccruedDate();
        BigDecimal cumulative = order.getAccruedYield();
        boolean changed = false;
        if (progress==null && cumulative==null) {
            // Legacy sparse receipts are revisited only once, in the same bounded segments as new dates.
            progress = first.minusDays(1);
            cumulative = BigDecimal.ZERO;
            changed = true;
        }
        if (progress==null || cumulative==null || progress.isBefore(first.minusDays(1)) || progress.isAfter(termLast))
            throw new BusinessException("理财计提进度异常");
        TradeValidation.nonNegative(cumulative, "理财累计收益");
        LocalDate next = progress.plusDays(1);
        if (!next.isAfter(last)) {
            LocalDate chunkLast = next.plusDays(ACCRUAL_DAYS_PER_TRANSACTION-1L);
            if (chunkLast.isAfter(last)) chunkLast = last;
            Map<LocalDate, FinancialYieldRecord> existing = new HashMap<>();
            FinancialCurrentLocks.dates(entityManager,id,next,chunkLast);
            for (FinancialYieldRecord receipt : yieldRecordRepository.lockByOrderAndDateRange(id, next, chunkLast)) {
                TenantContext.require(receipt.getTenantId());
                if (!ownerId.equals(receipt.getUserId()) || !order.getProductId().equals(receipt.getProductId())
                        || (!"PENDING".equals(receipt.getStatus()) && !"PAID".equals(receipt.getStatus()))
                        || existing.put(receipt.getYieldDate(), receipt)!=null)
                    throw new BusinessException("理财日期收据异常");
                TradeValidation.nonNegative(receipt.getDailyYield(), "理财日期收益");
            }
            for (LocalDate date=next; !date.isAfter(chunkLast); date=date.plusDays(1)) {
                FinancialYieldRecord receipt = existing.get(date);
                BigDecimal daily = receipt==null ? order.getDailyYield() : receipt.getDailyYield();
                cumulative = cumulative.add(daily);
                TradeValidation.nonNegative(cumulative, "理财累计收益");
                if (receipt==null) {
                    FinancialYieldRecord record = new FinancialYieldRecord();
                    record.setOrderId(id); record.setUserId(ownerId); record.setProductId(order.getProductId());
                    record.setProductName(order.getProductName()); record.setYieldDate(date);
                    record.setDailyYield(daily); record.setCumulativeYield(cumulative); record.setStatus("PENDING");
                    yieldRecordRepository.save(record);
                    recordSuccess("FINANCIAL_YIELD_CALCULATE", record.getId().toString(),
                            "orderId="+id+"; userId="+ownerId+"; yieldDate="+date+"; amount="+daily, null);
                }
            }
            progress = chunkLast;
            changed = true;
        }
        if (changed) {
            order.setLastAccruedDate(progress);
            order.setAccruedYield(cumulative);
        }
        // Principal, frozen balance, order terminal state and audit commit together only after all due dates.
        if (order.getEndTime()!=null && !order.getEndTime().isAfter(now) && !progress.isBefore(last)) {
            AssetAccount fund = fund(accounts);
            TradeValidation.positive(order.getPurchaseAmount(), "申购本金");
            TradeValidation.nonNegative(fund.getAvailable(), "可用资金");
            BigDecimal before = fund.getAvailable(), frozen = fund.getFrozen();
            if (frozen==null || frozen.compareTo(order.getPurchaseAmount())<0) throw new BusinessException("冻结资金异常");
            fund.setFrozen(frozen.subtract(order.getPurchaseAmount()));
            fund.setAvailable(before.add(order.getPurchaseAmount()));
            assetAccountRepository.save(fund);
            order.setStatus("COMPLETED");
            changed = true;
            recordSuccess("FINANCIAL_MATURE", id.toString(), "userId="+ownerId+"; principal="+order.getPurchaseAmount()
                    +"; availableBefore="+before+"; availableAfter="+fund.getAvailable()+"; frozenBefore="+frozen+"; frozenAfter="+fund.getFrozen(), null);
        }
        if (changed) orderRepository.save(order);
    }

    @Transactional
    public void payoutYield(Long yieldRecordId) {
        Long ownerId = yieldRecordRepository.ownerId(yieldRecordId).orElseThrow(() -> new BusinessException("收益记录不存在"));
        lockUser(ownerId);
        List<AssetAccount> accounts = lockAssets(ownerId);
        payoutLocked(yieldRecordId, ownerId, accounts, LocalDateTime.now());
    }

    @Transactional
    public void payoutAllPendingYields() {
        if (BackendAccess.agentId()!=null)
            throw new org.springframework.security.access.AccessDeniedException("代理不能执行全租户收益发放");
        List<FinancialYieldRecordRepository.PendingOwner> pending = yieldRecordRepository.pendingOwners();
        TreeSet<Long> owners = new TreeSet<>();
        for (FinancialYieldRecordRepository.PendingOwner receipt : pending) owners.add(receipt.getUserId());
        // Preserve the public full-batch transaction: all users first, then all assets, then yield IDs.
        for (Long owner : owners) lockUser(owner);
        Map<Long, List<AssetAccount>> accounts = new HashMap<>();
        for (Long owner : owners) accounts.put(owner, lockAssets(owner));
        LocalDateTime paidAt = LocalDateTime.now();
        for (FinancialYieldRecordRepository.PendingOwner receipt : pending)
            payoutLocked(receipt.getId(), receipt.getUserId(), accounts.get(receipt.getUserId()), paidAt);
    }

    private void payoutLocked(Long id, Long expectedOwner, List<AssetAccount> accounts, LocalDateTime paidAt) {
        FinancialCurrentLocks.yield(entityManager,id);
        FinancialYieldRecord record = yieldRecordRepository.lockById(id).orElseThrow(() -> new BusinessException("收益记录不存在"));
        TenantContext.require(record.getTenantId());
        if (!expectedOwner.equals(record.getUserId())) throw new BusinessException("收益记录归属已变更");
        if ("PAID".equals(record.getStatus())) return;
        if (!"PENDING".equals(record.getStatus())) throw new BusinessException("收益记录状态异常");
        TradeValidation.nonNegative(record.getDailyYield(), "收益金额");
        AssetAccount fund = fund(accounts);
        TradeValidation.nonNegative(fund.getAvailable(), "可用资金");
        BigDecimal before = fund.getAvailable();
        fund.setAvailable(before.add(record.getDailyYield()));
        assetAccountRepository.save(fund);
        record.setStatus("PAID"); record.setPaidAt(paidAt);
        yieldRecordRepository.save(record);
        recordSuccess("FINANCIAL_YIELD_PAY", id.toString(), "userId="+record.getUserId()+"; amount="+record.getDailyYield()
                +"; availableBefore="+before+"; availableAfter="+fund.getAvailable(), null);
    }

    private UserAccount lockUser(Long ownerId) {
        FinancialCurrentLocks.user(entityManager,ownerId);
        UserAccount owner = users.lockById(ownerId).orElseThrow(() -> new BusinessException("用户不存在"));
        TenantContext.require(owner.getTenantId());
        Long agent = BackendAccess.agentId();
        if (agent!=null && !agent.equals(owner.getParentUserId()))
            throw new org.springframework.security.access.AccessDeniedException("无权操作此用户收益");
        return owner;
    }

    private List<AssetAccount> lockAssets(Long ownerId) {
        FinancialCurrentLocks.assets(entityManager,ownerId);
        return assetAccountRepository.lockByUserId(ownerId);
    }

    private void recordSuccess(String action, String object, String detail, String reason) {
        if (com.gtcfesk.exchange.control.ControlIdentity.current()!=null)
            audit.recordCurrent(action, object, detail, reason);
        else
            audit.record(null, com.gtcfesk.exchange.tenant.TenantContext.requireTenantId(), null, action, object, "SUCCESS", detail, reason);
    }
    private AssetAccount fund(List<AssetAccount> accounts) {
        return accounts.stream().filter(a -> "FUND".equals(a.getCoin())).findFirst()
                .orElseThrow(() -> new BusinessException("资金账户不存在"));
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

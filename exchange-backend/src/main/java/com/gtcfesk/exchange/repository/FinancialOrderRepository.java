package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.FinancialOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public interface FinancialOrderRepository extends com.gtcfesk.exchange.tenant.TenantRepository<FinancialOrder, Long> {
    // Current read after the caller locks the user, including under MySQL REPEATABLE_READ.
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_READ)
    java.util.Optional<FinancialOrder> findByTenantIdAndUserIdAndRequestKey(Long tenantId, Long userId, String requestKey);

    @org.springframework.data.jpa.repository.Query("SELECT x.id FROM FinancialOrder x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.status='IN_PROGRESS' ORDER BY x.id")
    java.util.List<Long> unsettledIds();

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT x FROM FinancialOrder x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.id=:id")
    java.util.Optional<FinancialOrder> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    List<FinancialOrder> findByTenantIdAndUserIdOrderByPurchaseTimeDesc(Long tenantId, Long userId);
    List<FinancialOrder> findByTenantIdAndUserIdAndStatusOrderByPurchaseTimeDesc(Long tenantId, Long userId, String status);
    List<FinancialOrder> findAllByTenantIdOrderByPurchaseTimeDesc(Long tenantId);
    List<FinancialOrder> findByTenantIdAndStatusOrderByPurchaseTimeDesc(Long tenantId, String status);
    List<FinancialOrder> findByTenantIdAndProductId(Long tenantId, Long productId);
    
    // 统计今日订单数
    long countByTenantIdAndPurchaseTimeBetween(Long tenantId, LocalDateTime start, LocalDateTime end);
    
    // 查询今日订单
    List<FinancialOrder> findByTenantIdAndPurchaseTimeBetween(Long tenantId, LocalDateTime start, LocalDateTime end);
    
    // 查询最近有交易的用户ID（去重）
    @org.springframework.data.jpa.repository.Query("SELECT DISTINCT o.userId FROM FinancialOrder o WHERE o.tenantId = ?#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (o.purchaseTime > ?1)")
    Set<Long> findDistinctUserIdByPurchaseTimeAfter(LocalDateTime time);
}


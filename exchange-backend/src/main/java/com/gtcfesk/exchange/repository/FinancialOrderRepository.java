package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.FinancialOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface FinancialOrderRepository extends com.gtcfesk.exchange.tenant.TenantRepository<FinancialOrder, Long> {
    // Current read after the caller locks the user and assets, including under MySQL REPEATABLE_READ.
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_READ)
    Optional<FinancialOrder> findByTenantIdAndUserIdAndRequestKey(Long tenantId, Long userId, String requestKey);

    @Query("SELECT x.id FROM FinancialOrder x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.status='IN_PROGRESS' ORDER BY x.id")
    List<Long> unsettledIds();

    @Query("SELECT x.id FROM FinancialOrder x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.status='IN_PROGRESS' AND x.id>:after ORDER BY x.id")
    List<Long> unsettledIdsAfter(@org.springframework.data.repository.query.Param("after") Long after, Pageable page);

    // Routing only. The locked order must still match this owner before any mutation.
    @Query("SELECT x.userId FROM FinancialOrder x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.id=:id")
    Optional<Long> ownerId(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM FinancialOrder x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.id=:id")
    Optional<FinancialOrder> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    List<FinancialOrder> findByTenantIdAndUserIdOrderByPurchaseTimeDesc(Long tenantId, Long userId);
    List<FinancialOrder> findByTenantIdAndUserIdAndStatusOrderByPurchaseTimeDesc(Long tenantId, Long userId, String status);
    List<FinancialOrder> findAllByTenantIdOrderByPurchaseTimeDesc(Long tenantId);
    List<FinancialOrder> findByTenantIdAndStatusOrderByPurchaseTimeDesc(Long tenantId, String status);
    List<FinancialOrder> findByTenantIdAndProductId(Long tenantId, Long productId);

    long countByTenantIdAndPurchaseTimeBetween(Long tenantId, LocalDateTime start, LocalDateTime end);
    List<FinancialOrder> findByTenantIdAndPurchaseTimeBetween(Long tenantId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT DISTINCT o.userId FROM FinancialOrder o WHERE o.tenantId = ?#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (o.purchaseTime > ?1)")
    Set<Long> findDistinctUserIdByPurchaseTimeAfter(LocalDateTime time);
}

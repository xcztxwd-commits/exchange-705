package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.FinancialYieldRecord;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FinancialYieldRecordRepository extends com.gtcfesk.exchange.tenant.TenantRepository<FinancialYieldRecord, Long> {
    interface PendingOwner {
        Long getId();
        Long getUserId();
    }

    @Query("SELECT x.id FROM FinancialYieldRecord x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.status='PENDING' ORDER BY x.id")
    List<Long> unsettledIds();

    // Scalar projections do not preload stale managed yield entities while waiting for funding locks.
    @Query("SELECT x.id AS id, x.userId AS userId FROM FinancialYieldRecord x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.status='PENDING' ORDER BY x.id")
    List<PendingOwner> pendingOwners();

    @Query("SELECT x.userId FROM FinancialYieldRecord x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.id=:id")
    Optional<Long> ownerId(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM FinancialYieldRecord x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.id=:id")
    Optional<FinancialYieldRecord> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM FinancialYieldRecord x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.orderId=:order ORDER BY x.id")
    List<FinancialYieldRecord> lockByOrder(@org.springframework.data.repository.query.Param("order") Long order);

    // Only the unprocessed bounded date segment, in the same stable ID lock order as payout.
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT x FROM FinancialYieldRecord x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.orderId=:order AND x.yieldDate BETWEEN :first AND :last ORDER BY x.id")
    List<FinancialYieldRecord> lockByOrderAndDateRange(@org.springframework.data.repository.query.Param("order") Long order,
            @org.springframework.data.repository.query.Param("first") LocalDate first,
            @org.springframework.data.repository.query.Param("last") LocalDate last);

    List<FinancialYieldRecord> findByTenantIdAndOrderIdOrderByYieldDateDesc(Long tenantId, Long orderId);
    List<FinancialYieldRecord> findByTenantIdAndUserIdOrderByYieldDateDesc(Long tenantId, Long userId);
    List<FinancialYieldRecord> findByTenantIdAndOrderIdAndStatusOrderByYieldDateDesc(Long tenantId, Long orderId, String status);
    FinancialYieldRecord findByTenantIdAndOrderIdAndYieldDate(Long tenantId, Long orderId, LocalDate yieldDate);
    List<FinancialYieldRecord> findByTenantIdAndStatusOrderByYieldDateAsc(Long tenantId, String status);
}

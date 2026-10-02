package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.FinancialYieldRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface FinancialYieldRecordRepository extends com.gtcfesk.exchange.tenant.TenantRepository<FinancialYieldRecord, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT x.id FROM FinancialYieldRecord x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.status='PENDING' ORDER BY x.id")
    java.util.List<Long> unsettledIds();

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT x FROM FinancialYieldRecord x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.id=:id")
    java.util.Optional<FinancialYieldRecord> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    // A locking read sees commits made while waiting for the order lock, even under an outer REPEATABLE_READ job.
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT x FROM FinancialYieldRecord x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.orderId=:order ORDER BY x.yieldDate")
    List<FinancialYieldRecord> lockByOrder(@org.springframework.data.repository.query.Param("order") Long order);

    List<FinancialYieldRecord> findByTenantIdAndOrderIdOrderByYieldDateDesc(Long tenantId, Long orderId);
    List<FinancialYieldRecord> findByTenantIdAndUserIdOrderByYieldDateDesc(Long tenantId, Long userId);
    List<FinancialYieldRecord> findByTenantIdAndOrderIdAndStatusOrderByYieldDateDesc(Long tenantId, Long orderId, String status);
    FinancialYieldRecord findByTenantIdAndOrderIdAndYieldDate(Long tenantId, Long orderId, LocalDate yieldDate);
    List<FinancialYieldRecord> findByTenantIdAndStatusOrderByYieldDateAsc(Long tenantId, String status);
}




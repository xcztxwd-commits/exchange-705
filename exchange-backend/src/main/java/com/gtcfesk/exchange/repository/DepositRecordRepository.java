package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.DepositRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface DepositRecordRepository extends com.gtcfesk.exchange.tenant.TenantRepository<DepositRecord, Long> {
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_READ)
    java.util.Optional<DepositRecord> findByTenantIdAndCreatedByTypeAndCreatedByIdAndIdempotencyKey(Long tenantId, String type, Long id, String key);

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DepositRecord d where d.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} and d.id=:id")
    java.util.Optional<DepositRecord> lockById(@Param("id") Long id);

    @Query("select d.id from DepositRecord d where d.tenantId=:tenant and d.createdByType=:type and d.createdById=:actor and d.idempotencyKey=:key")
    java.util.Optional<Long> findReplayId(@Param("tenant") Long tenant,@Param("type") String type,@Param("actor") Long actor,@Param("key") String key);

    // Non-locking scalar routing only. Authorization/state are checked again after the user lock.
    @Query("select d.userId from DepositRecord d where d.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} and d.id=:id")
    java.util.Optional<Long> findOwnerIdById(@Param("id") Long id);

    List<DepositRecord> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId);
    List<DepositRecord> findByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);
    
    @org.springframework.data.jpa.repository.Query("SELECT SUM(d.amount) FROM DepositRecord d WHERE d.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND ((d.source IS NULL OR d.source <> 'ADMIN_MANUAL') AND d.status = :status AND d.createdAt >= :start AND d.createdAt <= :end)")
    java.math.BigDecimal sumAmountByStatusAndCreatedAtBetween(
        @Param("status") String status,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );
}




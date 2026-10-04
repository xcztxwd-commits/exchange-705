package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.WithdrawRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface WithdrawRecordRepository extends com.gtcfesk.exchange.tenant.TenantRepository<WithdrawRecord, Long> {
    // Current read after the caller locks the user, including under MySQL REPEATABLE_READ.
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_READ)
    java.util.Optional<WithdrawRecord> findByTenantIdAndUserIdAndRequestKey(Long tenantId, Long userId, String requestKey);

    @Query("select w.userId from WithdrawRecord w where w.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} and w.id=:id")
    java.util.Optional<Long> findOwnerIdById(@Param("id") Long id);

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WithdrawRecord w where w.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} and w.id=:id")
    java.util.Optional<WithdrawRecord> lockById(@Param("id") Long id);

    // A pre-lock hint may skip rate preparation; the receipt is rechecked with a current read.
    @Query("select w.id from WithdrawRecord w where w.tenantId=:tenantId and w.userId=:userId and w.requestKey=:requestKey")
    java.util.Optional<Long> findReplayId(@Param("tenantId") Long tenantId,@Param("userId") Long userId,@Param("requestKey") String requestKey);

    List<WithdrawRecord> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId);
    List<WithdrawRecord> findByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);
    List<WithdrawRecord> findByTenantIdAndStatusAndTypeOrderByCreatedAtDesc(Long tenantId, String status, String type);
    List<WithdrawRecord> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);
    
    @org.springframework.data.jpa.repository.Query("SELECT SUM(w.amount) FROM WithdrawRecord w WHERE w.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND ((w.status = 'APPROVED' OR w.status = 'COMPLETED') AND w.createdAt >= :start AND w.createdAt <= :end)")
    java.math.BigDecimal sumAmountByApprovedStatusAndCreatedAtBetween(
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );
}


package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.LoanRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoanRecordRepository extends com.gtcfesk.exchange.tenant.TenantRepository<LoanRecord, Long> {
    // Current read after the caller locks the user, including under MySQL REPEATABLE_READ.
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_READ)
    java.util.Optional<LoanRecord> findByTenantIdAndUserIdAndRequestKey(Long tenantId, Long userId, String requestKey);

    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("SELECT x FROM LoanRecord x WHERE x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND x.id=:id")
    java.util.Optional<LoanRecord> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    List<LoanRecord> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId);
    List<LoanRecord> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);
}




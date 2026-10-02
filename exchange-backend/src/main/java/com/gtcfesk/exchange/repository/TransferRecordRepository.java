package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.TransferRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransferRecordRepository extends com.gtcfesk.exchange.tenant.TenantRepository<TransferRecord, Long> {
    @org.springframework.transaction.annotation.Transactional(readOnly=true)
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_READ)
    java.util.Optional<TransferRecord> findByTenantIdAndUserIdAndRequestId(Long tenantId, Long userId, String requestId);

    List<TransferRecord> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId);
    
    Page<TransferRecord> findByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId, Pageable pageable);
}




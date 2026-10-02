package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.KycRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KycRecordRepository extends com.gtcfesk.exchange.tenant.TenantRepository<KycRecord, Long> {
    Optional<KycRecord> findByTenantIdAndUserId(Long tenantId, Long userId);
    
    Optional<KycRecord> findFirstByTenantIdAndUserIdOrderByCreatedAtDesc(Long tenantId, Long userId);
    
    List<KycRecord> findByTenantIdAndStatus(Long tenantId, String status);
    
    List<KycRecord> findByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);
    
    Page<KycRecord> findByTenantIdAndStatus(Long tenantId, String status, Pageable pageable);
}


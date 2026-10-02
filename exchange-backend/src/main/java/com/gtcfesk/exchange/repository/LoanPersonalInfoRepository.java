package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.LoanPersonalInfo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LoanPersonalInfoRepository extends com.gtcfesk.exchange.tenant.TenantRepository<LoanPersonalInfo, Long> {
    Optional<LoanPersonalInfo> findByTenantIdAndUserId(Long tenantId, Long userId);
    
    List<LoanPersonalInfo> findByTenantIdAndStatusOrderByCreatedAtDesc(Long tenantId, String status);
    
    List<LoanPersonalInfo> findAllByTenantIdOrderByCreatedAtDesc(Long tenantId);
}




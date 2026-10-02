package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.OptionDuration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OptionDurationRepository extends com.gtcfesk.exchange.tenant.TenantRepository<OptionDuration, Long> {
    
    List<OptionDuration> findByTenantIdAndEnabledTrueOrderBySortOrderAsc(Long tenantId);
    
    List<OptionDuration> findAllByTenantIdOrderBySortOrderAsc(Long tenantId);
    
    java.util.Optional<OptionDuration> findByTenantIdAndDuration(Long tenantId, Integer duration);
}


package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.SystemConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SystemConfigRepository extends com.gtcfesk.exchange.tenant.TenantRepository<SystemConfig, Long> {
    Optional<SystemConfig> findByTenantIdAndConfigKey(Long tenantId, String configKey);
}








package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.DepositSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DepositSettingRepository extends com.gtcfesk.exchange.tenant.TenantRepository<DepositSetting, Long> {
    Optional<DepositSetting> findByTenantIdAndNetwork(Long tenantId, String network);
    Optional<DepositSetting> findByTenantIdAndNetworkAndType(Long tenantId, String network, String type);
    List<DepositSetting> findByTenantIdAndTypeAndEnabled(Long tenantId, String type, Boolean enabled);
}


package com.gtcfesk.exchange.repository;

import com.gtcfesk.exchange.entity.LoanSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LoanSettingRepository extends com.gtcfesk.exchange.tenant.TenantRepository<LoanSetting, Long> {
    @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_READ)
    @org.springframework.data.jpa.repository.Query("select x from LoanSetting x where x.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} and x.id=:id")
    java.util.Optional<LoanSetting> lockById(@org.springframework.data.repository.query.Param("id") Long id);

    List<LoanSetting> findByTenantIdAndEnabledTrueOrderByDaysAsc(Long tenantId);
}




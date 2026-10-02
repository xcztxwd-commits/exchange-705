package com.gtcfesk.exchange.activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
public interface TrialLedgerRepository extends com.gtcfesk.exchange.tenant.TenantRepository<TrialLedger, Long>{
 Page<TrialLedger> findByTenantIdAndUserIdOrderByIdDesc(Long tenantId, Long userId,Pageable page);
}

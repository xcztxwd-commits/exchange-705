package com.gtcfesk.exchange.demo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
public interface DemoLedgerRepository extends com.gtcfesk.exchange.tenant.TenantRepository<DemoLedger, String> {
    Page<DemoLedger> findByTenantIdAndUserId(Long tenantId, Long userId, Pageable page);
}

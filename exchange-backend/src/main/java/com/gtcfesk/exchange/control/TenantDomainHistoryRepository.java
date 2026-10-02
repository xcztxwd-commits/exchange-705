package com.gtcfesk.exchange.control;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface TenantDomainHistoryRepository extends JpaRepository<TenantDomainHistory,Long> {
 java.util.Optional<TenantDomainHistory> findByHostname(String host);
}

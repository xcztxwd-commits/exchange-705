package com.gtcfesk.exchange.control;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface TenantPolicyRepository extends JpaRepository<TenantPolicy,Long> {
 java.util.Optional<TenantPolicy> findByTenantIdAndKey(Long tenantId,String key);
 java.util.List<TenantPolicy> findByTenantId(Long tenantId);
}

package com.gtcfesk.exchange.control;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface BackendLoginRepository extends JpaRepository<BackendLogin,Long> {
 java.util.Optional<BackendLogin> findByNormalizedAccount(String account);
 java.util.Optional<BackendLogin> findByTenantIdAndAdminUserId(Long tenantId,Long id);
 java.util.Optional<BackendLogin> findByTenantIdAndUserId(Long tenantId,Long id);
 java.util.List<BackendLogin> findByTenantId(Long tenantId);
}

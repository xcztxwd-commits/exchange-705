package com.gtcfesk.exchange.control;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface TenantRepository extends JpaRepository<Tenant,Long> {
 java.util.Optional<Tenant> findByFrontendHost(String host);
 @Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE) @Query("select t from Tenant t where t.id=:id") java.util.Optional<Tenant> lock(@Param("id") Long id);
}

package com.gtcfesk.exchange.control;
import java.util.*;
import javax.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface TenantDomainBindingRepository extends JpaRepository<TenantDomainBinding,String>{
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select b from TenantDomainBinding b where b.hostname=:host") Optional<TenantDomainBinding> lock(@Param("host") String host);
 List<TenantDomainBinding> findByTenantIdOrderByRoleAscHostnameAsc(Long tenant);
 List<TenantDomainBinding> findByTenantIdAndRoleAndStatusIn(Long tenant,String role,Collection<String> states);
 List<TenantDomainBinding> findByTenantIdAndStatusIn(Long tenant,Collection<String> states);
}

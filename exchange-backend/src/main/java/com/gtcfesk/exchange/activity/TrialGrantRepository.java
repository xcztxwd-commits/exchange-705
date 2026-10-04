package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.tenant.TenantRepository;
import java.util.*;
public interface TrialGrantRepository extends TenantRepository<TrialGrant,Long> {
 List<TrialGrant> findByTenantIdAndUserIdOrderByIdAsc(Long tenantId,Long userId);
 @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select g from TrialGrant g where g.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} and g.userId=:user order by g.id")
 List<TrialGrant> lockByUserId(@org.springframework.data.repository.query.Param("user") Long user);
 // Snapshot lookup only: missing keys must not take a range gap before the campaign lock.
 Optional<TrialGrant> findByTenantIdAndUserIdAndRequestKey(Long tenantId,Long userId,String requestKey);
 boolean existsByTenantIdAndCampaignIdAndUserId(Long tenantId,Long campaignId,Long userId);
}

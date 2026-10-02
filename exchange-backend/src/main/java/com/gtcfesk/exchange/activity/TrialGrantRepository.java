package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.tenant.TenantRepository;
import java.util.*;
public interface TrialGrantRepository extends TenantRepository<TrialGrant,Long> {
 List<TrialGrant> findByTenantIdAndUserIdOrderByIdAsc(Long tenantId,Long userId);
 Optional<TrialGrant> findByTenantIdAndUserIdAndRequestKey(Long tenantId,Long userId,String requestKey);
 boolean existsByTenantIdAndCampaignIdAndUserId(Long tenantId,Long campaignId,Long userId);
}

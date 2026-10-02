package com.gtcfesk.exchange.activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
import java.util.Optional;
public interface ActivityDeliveryRepository extends com.gtcfesk.exchange.tenant.TenantRepository<ActivityDelivery, Long>{
 Optional<ActivityDelivery> findByTenantIdAndCampaignIdAndUserId(Long tenantId, Long campaignId,Long userId);
 Page<ActivityDelivery> findByTenantIdAndUserIdOrderByIdDesc(Long tenantId, Long userId,Pageable page);
 long countByTenantIdAndCampaignId(Long tenantId, Long id);
 long countByTenantIdAndCampaignIdAndReceivedAtIsNotNull(Long tenantId, Long id);
 long countByTenantIdAndCampaignIdAndOpenedAtIsNotNull(Long tenantId, Long id);
 long countByTenantIdAndCampaignIdAndClosedAtIsNotNull(Long tenantId, Long id);
 long countByTenantIdAndCampaignIdAndClosedAtIsNotNullAndOpenedAtIsNull(Long tenantId, Long id);
}

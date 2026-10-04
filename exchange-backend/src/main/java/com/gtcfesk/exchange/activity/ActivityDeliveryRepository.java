package com.gtcfesk.exchange.activity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.*;
import java.util.Optional;
public interface ActivityDeliveryRepository extends com.gtcfesk.exchange.tenant.TenantRepository<ActivityDelivery, Long>{
 Optional<ActivityDelivery> findByTenantIdAndCampaignIdAndUserId(Long tenantId, Long campaignId,Long userId);
 @org.springframework.data.jpa.repository.Lock(javax.persistence.LockModeType.PESSIMISTIC_WRITE)
 @org.springframework.data.jpa.repository.Query("select d from ActivityDelivery d where d.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} and d.campaignId=:campaign and d.userId=:user")
 Optional<ActivityDelivery> lockByCampaignAndUser(@org.springframework.data.repository.query.Param("campaign") Long campaign,
                                                  @org.springframework.data.repository.query.Param("user") Long user);
 Page<ActivityDelivery> findByTenantIdAndUserIdOrderByIdDesc(Long tenantId, Long userId,Pageable page);
 long countByTenantIdAndCampaignId(Long tenantId, Long id);
 long countByTenantIdAndCampaignIdAndReceivedAtIsNotNull(Long tenantId, Long id);
 long countByTenantIdAndCampaignIdAndOpenedAtIsNotNull(Long tenantId, Long id);
 long countByTenantIdAndCampaignIdAndClosedAtIsNotNull(Long tenantId, Long id);
 long countByTenantIdAndCampaignIdAndClosedAtIsNotNullAndOpenedAtIsNull(Long tenantId, Long id);
}

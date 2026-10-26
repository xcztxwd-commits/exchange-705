package com.gtcfesk.exchange.activity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import javax.persistence.LockModeType;
import java.util.Optional;
public interface ActivityCampaignRepository extends com.gtcfesk.exchange.tenant.TenantRepository<ActivityCampaign, Long>{
 @org.springframework.data.jpa.repository.Query("select c.campaignKind from ActivityCampaign c where c.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} and c.id=:id") Optional<String> findCampaignKind(@Param("id") Long id);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @org.springframework.data.jpa.repository.Query("select c from ActivityCampaign c WHERE c.tenantId = :#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} AND (c.id=:id)") Optional<ActivityCampaign> lock(@Param("id") Long id);
}

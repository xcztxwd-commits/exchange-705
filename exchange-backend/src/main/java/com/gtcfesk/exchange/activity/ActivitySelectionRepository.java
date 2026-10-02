package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.tenant.TenantRepository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import javax.persistence.LockModeType;
import java.util.Optional;
public interface ActivitySelectionRepository extends TenantRepository<ActivitySelection,Long> {
 Optional<ActivitySelection> findByTenantIdAndCampaignIdAndOperationId(Long tenant,Long campaign,String operation);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select s from ActivitySelection s where s.tenantId=:#{T(com.gtcfesk.exchange.tenant.TenantContext).requireTenantId()} and s.campaignId=:campaign and s.operationId=:operation")
 Optional<ActivitySelection> lock(@Param("campaign") Long campaign,@Param("operation") String operation);
}

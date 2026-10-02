package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.tenant.TenantRepository;
import java.util.Optional;
public interface ActivitySendReceiptRepository extends TenantRepository<ActivitySendReceipt,Long> {
 Optional<ActivitySendReceipt> findByTenantIdAndCampaignIdAndOperationId(Long tenant,Long campaign,String operation);
}

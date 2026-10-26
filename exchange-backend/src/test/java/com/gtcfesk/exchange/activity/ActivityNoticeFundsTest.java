package com.gtcfesk.exchange.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityNoticeFundsTest {
 @Test void bothClaimRoutesRejectNoticeBeforeAnyFinancialLockOrAccountCreation(){
  try(TenantContext.Scope ignored=TenantContext.open(1L)){
   ActivityCampaignRepository campaigns=mock(ActivityCampaignRepository.class);
   ActivityDeliveryRepository deliveries=mock(ActivityDeliveryRepository.class);
   UserAccountRepository users=mock(UserAccountRepository.class);TrialFunds funds=mock(TrialFunds.class);
   ActivityService service=new ActivityService(campaigns,deliveries,users,funds,new ObjectMapper());
   ActivityDelivery delivery=new ActivityDelivery();delivery.setId(3L);delivery.setCampaignId(7L);delivery.setUserId(2L);
   when(campaigns.findCampaignKind(7L)).thenReturn(Optional.of("NOTICE"));
   when(deliveries.findByTenantIdAndId(1L,3L)).thenReturn(Optional.of(delivery));
   assertTrue(assertThrows(BusinessException.class,()->service.claim(2L,3L,"notice-claim-0001")).getMessage().contains("仅展示"));
   assertTrue(assertThrows(BusinessException.class,()->service.claimPublic(2L,7L,"notice-public-001")).getMessage().contains("仅展示"));
   verifyNoInteractions(funds,users);verify(campaigns,never()).lock(anyLong());verify(deliveries,never()).save(any());
  }
 }
}

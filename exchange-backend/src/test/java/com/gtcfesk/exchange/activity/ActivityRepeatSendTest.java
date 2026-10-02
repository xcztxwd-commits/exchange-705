package com.gtcfesk.exchange.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityRepeatSendTest {
 @Test void resendIsOptInAndPreservesClaimAndCounters(){
  try(TenantContext.Scope ignored=TenantContext.open(1L)){
   ActivityCampaignRepository campaigns=mock(ActivityCampaignRepository.class);
   ActivityDeliveryRepository deliveries=mock(ActivityDeliveryRepository.class);
   UserAccountRepository users=mock(UserAccountRepository.class);
   TrialFunds funds=mock(TrialFunds.class);
   ActivityService service=new ActivityService(campaigns,deliveries,users,funds,new ObjectMapper());
   ActivityCampaign campaign=new ActivityCampaign();campaign.setStatus("ACTIVE");campaign.setRecentLoginDays(0);
   UserAccount user=new UserAccount();user.setId(2L);user.setStatus("normal");
   ActivityDelivery delivery=new ActivityDelivery();delivery.setId(3L);
   LocalDateTime before=LocalDateTime.now().minusDays(1);
   delivery.setSentAt(before);delivery.setOpenedAt(before);delivery.setClosedAt(before);delivery.setReceivedAt(before);delivery.setClaimedAt(before);delivery.setOpenCount(4);
   when(campaigns.lock(7L)).thenReturn(Optional.of(campaign));
   when(users.findAllByTenantIdAndIdIn(eq(1L),any())).thenReturn(Collections.singletonList(user));
   when(deliveries.findByTenantIdAndCampaignIdAndUserId(1L,7L,2L)).thenReturn(Optional.of(delivery));
   assertEquals(1,service.send(7L,Arrays.asList(2L,2L),"admin").get("duplicates"));
   verify(deliveries,never()).save(any());
   campaign.setAllowRepeatSend(true);
   assertEquals(1,service.send(7L,Arrays.asList(2L,2L),"admin").get("sent"));
   verify(deliveries).save(delivery);
   assertNull(delivery.getOpenedAt());assertNull(delivery.getClosedAt());assertNull(delivery.getReceivedAt());
   assertEquals(before,delivery.getClaimedAt());assertEquals(4,delivery.getOpenCount());
   assertTrue(delivery.getSentAt().isAfter(before));assertEquals("admin",delivery.getSentBy());
   verifyNoInteractions(funds);
  }
 }
}

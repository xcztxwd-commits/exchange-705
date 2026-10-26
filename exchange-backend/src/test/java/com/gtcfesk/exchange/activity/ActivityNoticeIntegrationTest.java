package com.gtcfesk.exchange.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.UserAccount;
import com.gtcfesk.exchange.repository.UserAccountRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(ActivityIntegrationTest.Config.class)
class ActivityNoticeIntegrationTest {
 @Autowired ActivityService service;
 @Autowired ActivityCampaignRepository campaigns;
 @Autowired ActivityDeliveryRepository deliveries;
 @Autowired UserAccountRepository users;
 @Autowired TrialAccountRepository trials;
 @Autowired TrialGrantRepository grants;
 @Autowired TrialLedgerRepository ledger;
 private TenantContext.Scope scope;
 private Long user;
 private final ObjectMapper mapper=new ObjectMapper();

 @BeforeEach void setup(){
  scope=TenantContext.open(1L);UserAccount account=new UserAccount();
  account.setEmail(UUID.randomUUID()+"@notice.test");account.setPasswordHash("notice-test-only");account.setLastLoginAt(LocalDateTime.now());
  user=users.saveAndFlush(account).getId();
 }
 @AfterEach void cleanup(){scope.close();}
 private ActivityCampaign notice(){
  ActivityCampaign campaign=new ActivityCampaign();campaign.setName("Editable notice "+UUID.randomUUID());campaign.setCampaignKind("NOTICE");
  campaign.setStatus("ACTIVE");campaign.setAmount(BigDecimal.ZERO);campaign.setBudget(BigDecimal.ZERO);campaign.setMaxClaims(0);campaign.setRecentLoginDays(0);
  campaign.setAutoSendEnabled(true);campaign.setAllowRepeatSend(true);campaign.setAutoPopup(true);campaign.setAnimation("NONE");
  campaign.setPositions(Arrays.asList("AUTH_HOME","AUTH_TRADE","AUTH_PROFILE","SUPPORT"));campaign.setTriggerConditions(Collections.singletonList("LOGIN"));
  campaign.setStartsAt(LocalDateTime.now().minusDays(1));campaign.setEndsAt(LocalDateTime.now().plusDays(1));
  campaign.setTranslations("{\"zh-CN\":{\"title\":\"百万用户庆典\",\"body\":\"活动公告\",\"terms\":\"联系客服\"},\"ja\":{\"title\":\"100万人達成記念\",\"body\":\"お知らせ\",\"terms\":\"サポート\"}}");
  campaign.setLayoutJson(AnnouncementDesignFixture.design(mapper).toString());return campaign;
 }
 private ActivityDelivery delivery(ActivityCampaign campaign){return deliveries.findByTenantIdAndCampaignIdAndUserId(1L,campaign.getId(),user).orElseThrow(AssertionError::new);}
 private ActivityContentPatch patch(ActivityCampaign campaign,String design){
  ActivityContentPatch patch=new ActivityContentPatch();patch.setRowVersion(campaign.getRowVersion());patch.setName(campaign.getName());
  patch.setDefaultLocale(campaign.getDefaultLocale());patch.setTranslations(campaign.getTranslations());patch.setLayoutJson(design);return patch;
 }
 private void noMoney(){
  assertFalse(trials.findByTenantIdAndId(1L,user).isPresent());assertTrue(grants.findByTenantIdAndUserIdOrderByIdAsc(1L,user).isEmpty());
  assertEquals(0,ledger.findByTenantIdAndUserIdOrderByIdDesc(1L,user,PageRequest.of(0,10)).getTotalElements());
 }

 @Test void noticeFollowsAllLoginEnabledStatusesAndStillRejectsDisabledUsers(){
  ActivityCampaign campaign=service.save(null,notice());
  for(String status:Arrays.asList("normal","active","NORMAL","ACTIVE")){
   UserAccount account=users.findByTenantIdAndId(1L,user).orElseThrow(AssertionError::new);account.setStatus(status);users.saveAndFlush(account);
   service.trigger(user,"LOGIN","AUTH_HOME");ActivityDelivery delivered=delivery(campaign);assertNull(delivered.getOpenedAt(),status);
   service.event(user,delivered.getId(),"OPENED");
  }
  ActivityDelivery before=delivery(campaign);
  for(String status:Arrays.asList("disabled","frozen","banned")){
   UserAccount account=users.findByTenantIdAndId(1L,user).orElseThrow(AssertionError::new);account.setStatus(status);users.saveAndFlush(account);
   service.trigger(user,"LOGIN","AUTH_HOME");ActivityDelivery after=delivery(campaign);
   assertEquals(before.getSentAt(),after.getSentAt(),status);assertNotNull(after.getOpenedAt(),status);
  }
  assertEquals(1,deliveries.countByTenantIdAndCampaignId(1L,campaign.getId()));noMoney();
 }
 @Test void noticePersistsTriggersOnLoginAndResetsReadStateWithoutQuotaOrCredit(){
  ActivityCampaign campaign=service.save(null,notice());assertEquals("NOTICE",service.get(campaign.getId()).getCampaignKind());
  service.trigger(user,"LOGIN","AUTH_HOME");ActivityDelivery first=delivery(campaign);
  Map<?,?> visible=(Map<?,?>)service.message(user,first.getId()).get("campaign");
  assertEquals("NOTICE",visible.get("campaignKind"));assertEquals(Boolean.TRUE,visible.get("hasQuota"));assertEquals(0,((BigDecimal)visible.get("amount")).signum());
  service.event(user,first.getId(),"OPENED");service.event(user,first.getId(),"CLOSED");
  service.trigger(user,"LOGIN","AUTH_HOME");ActivityDelivery replay=delivery(campaign);
  assertEquals(first.getId(),replay.getId());assertNull(replay.getOpenedAt());assertNull(replay.getClosedAt());assertNull(replay.getReceivedAt());
  assertEquals(1,replay.getOpenCount());assertEquals(1,replay.getCloseCount());assertEquals(1,deliveries.countByTenantIdAndCampaignId(1L,campaign.getId()));
  assertThrows(BusinessException.class,()->service.claim(user,replay.getId(),"notice-claim-test01"));
  assertThrows(BusinessException.class,()->service.claimPublic(user,campaign.getId(),"notice-public-test1"));
  assertEquals(0,service.get(campaign.getId()).getClaimCount());assertEquals(0,service.get(campaign.getId()).getGranted().signum());noMoney();
 }
 @Test void noticeTemplateRemainsDraftAndCopiesPersistIndependently(){
  ActivityCampaign input=notice();input.setTemplate(true);ActivityCampaign template=service.save(null,input);
  assertEquals("DRAFT",template.getStatus());assertFalse(template.isAutoSendEnabled());assertTrue(template.isTemplate());
  service.trigger(user,"LOGIN","AUTH_HOME");assertFalse(deliveries.findByTenantIdAndCampaignIdAndUserId(1L,template.getId(),user).isPresent());
  assertThrows(BusinessException.class,()->service.saveAutoSend(template.getId(),template));
  ActivityCampaign copy=notice();copy.setStatus("DRAFT");copy.setAutoSendEnabled(false);copy.setLayoutJson(template.getLayoutJson());
  ActivityCampaign saved=service.save(null,copy);assertNotEquals(template.getId(),saved.getId());assertFalse(saved.isTemplate());assertEquals("NOTICE",saved.getCampaignKind());
  assertEquals(template.getLayoutJson(),service.get(saved.getId()).getLayoutJson());noMoney();
 }
 @Test void loginResendReturnsOldDeliveryToFirstInboxPage(){
  ActivityCampaign campaign=service.save(null,notice());service.trigger(user,"LOGIN","AUTH_HOME");Long original=delivery(campaign).getId();
  for(int i=0;i<21;i++){
   ActivityCampaign later=notice();later.setAutoSendEnabled(false);later=service.save(null,later);
   service.send(later.getId(),Collections.singletonList(user),"notice-test");
  }
  assertTrue(service.inbox(user,0).getContent().stream().noneMatch(row->original.equals(((ActivityDelivery)row.get("delivery")).getId())));
  service.trigger(user,"LOGIN","AUTH_HOME");
  assertEquals(original,((ActivityDelivery)service.inbox(user,0).getContent().get(0).get("delivery")).getId());noMoney();
 }
 @Test void noticeCannotAcceptRewardSettingsAndLegacyTrialDefaultsStayPositive(){
  ActivityCampaign input=notice();input.setAmount(BigDecimal.ONE);assertThrows(BusinessException.class,()->service.save(null,input));
  input.setAmount(BigDecimal.ZERO);input.setBudget(BigDecimal.ONE);assertThrows(BusinessException.class,()->service.save(null,input));
  input.setBudget(BigDecimal.ZERO);input.setAllowRepeatClaim(true);assertThrows(BusinessException.class,()->service.save(null,input));
  input.setAllowRepeatClaim(false);input.setClaimValidityDays(3);assertThrows(BusinessException.class,()->service.save(null,input));
  input.setClaimValidityDays(null);input.setCampaignKind("OTHER");assertThrows(BusinessException.class,()->service.save(null,input));
  ActivityCampaign legacy=new ActivityCampaign();assertEquals("TRIAL",legacy.getCampaignKind());
  legacy.setName("Legacy gift");legacy.setTranslations(input.getTranslations());legacy.setAmount(BigDecimal.ZERO);assertThrows(BusinessException.class,()->service.save(null,legacy));noMoney();
 }
 @Test void sentCampaignKindCannotChangeAndAutoSettingsUsePersistedKind(){
  ActivityCampaign campaign=service.save(null,notice());service.trigger(user,"LOGIN","AUTH_HOME");
  ActivityCampaign settings=service.get(campaign.getId());settings.setCampaignKind("TRIAL");
  ActivityCampaign unchanged=service.saveAutoSend(campaign.getId(),settings);assertEquals("NOTICE",unchanged.getCampaignKind());
  settings.setAmount(new BigDecimal("300"));settings.setBudget(new BigDecimal("300000"));settings.setMaxClaims(1000);settings.setLayoutJson(null);
  assertThrows(BusinessException.class,()->service.save(campaign.getId(),settings));assertEquals("NOTICE",service.get(campaign.getId()).getCampaignKind());noMoney();
 }
 @Test void noticeContentUsesOptimisticVersionAndDoesNotAdmitRewardLayouts(){
  ActivityCampaign campaign=service.save(null,notice());ActivityContentPatch first=patch(campaign,campaign.getLayoutJson());first.setName("Updated notice");
  ActivityCampaign updated=service.saveContent(campaign.getId(),first);assertEquals("Updated notice",updated.getName());
  assertEquals(409,assertThrows(ResponseStatusException.class,()->service.saveContent(campaign.getId(),first)).getRawStatusCode());
  String claimLayout="{\"version\":1,\"locales\":{\"zh-CN\":{\"pages\":[{\"id\":\"gift\",\"nodes\":[{\"type\":\"button\",\"action\":\"claim\"}]}]}}}";
  assertThrows(BusinessException.class,()->service.saveContent(campaign.getId(),patch(updated,claimLayout)));
  assertEquals(updated.getLayoutJson(),service.get(campaign.getId()).getLayoutJson());
  ActivityCampaign trial=new ActivityCampaign();trial.setName("Actual reward");trial.setTranslations(campaign.getTranslations());trial=service.save(null,trial);
  final ActivityCampaign reward=trial;assertThrows(BusinessException.class,()->service.saveContent(reward.getId(),patch(reward,campaign.getLayoutJson())));noMoney();
 }
 @Test void noticeEventsAndContentStayTenantAndRecipientScoped(){
  ActivityCampaign campaign=service.save(null,notice());service.trigger(user,"LOGIN","AUTH_HOME");ActivityDelivery delivery=delivery(campaign);
  UserAccount other=new UserAccount();other.setEmail(UUID.randomUUID()+"@notice.test");other.setPasswordHash("notice-test-only");Long otherUser=users.saveAndFlush(other).getId();
  assertThrows(BusinessException.class,()->service.event(otherUser,delivery.getId(),"OPENED"));
  scope.close();try(TenantContext.Scope otherTenant=TenantContext.open(98765L)){
   assertThrows(BusinessException.class,()->service.get(campaign.getId()));assertThrows(BusinessException.class,()->service.message(user,delivery.getId()));
   assertThrows(BusinessException.class,()->service.saveContent(campaign.getId(),patch(campaign,campaign.getLayoutJson())));
  }finally{scope=TenantContext.open(1L);}noMoney();
 }
}

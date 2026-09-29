package com.gtcfesk.exchange.activity;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.config.*;
import com.gtcfesk.exchange.common.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.user.*;
import io.jsonwebtoken.impl.DefaultClaims;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.context.annotation.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.util.ReflectionTestUtils;
import javax.servlet.Filter;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(ActivitySecurityTest.Config.class) @WebAppConfiguration
@org.springframework.test.context.TestPropertySource(properties={"jwt.secret=activity-test-only-not-a-production-key","jwt.expireSeconds=60"})
class ActivitySecurityTest {
 static ActivityService service=mock(ActivityService.class);static TrialFunds funds=mock(TrialFunds.class);static KycIdentityService identity=mock(KycIdentityService.class);static AssetAccountRepository assets=mock(AssetAccountRepository.class);
 @Configuration @EnableWebMvc @Import({SecurityConfig.class,JwtFilter.class,GlobalExceptionHandler.class,ActivityPrivacy.class,com.gtcfesk.exchange.demo.DemoModeBoundary.class})
 static class Config {
  @Bean JwtUtil jwt(){return mock(JwtUtil.class);}
  @Bean KycIdentityService identity(){return ActivitySecurityTest.identity;}
  @Bean SystemConfigService configs(){return mock(SystemConfigService.class);}
  @Bean SystemConfigRepository configRepository(){return mock(SystemConfigRepository.class);}
  @Bean UserAccountRepository users(){return mock(UserAccountRepository.class);}
  @Bean AdminUserRepository admins(){return mock(AdminUserRepository.class);}
  @Bean ActivityController activity(){return new ActivityController(service,funds,mock(TrialLedgerRepository.class));}
  @Bean AdminActivityController admin(){return new AdminActivityController(service,mock(ActivityCampaignRepository.class),mock(ActivityDeliveryRepository.class),funds,mock(TrialLedgerRepository.class));}
  @Bean WithdrawController withdraw(){WithdrawController c=new WithdrawController(mock(WithdrawRecordRepository.class),assets,mock(UserDigitalAddressRepository.class),mock(UserBankCardRepository.class),mock(FiatCurrencyService.class));ReflectionTestUtils.setField(c,"identityService",identity);return c;}
 }
 @Autowired WebApplicationContext context;@Autowired @Qualifier("springSecurityFilterChain") Filter security;@Autowired JwtUtil jwt;@Autowired UserAccountRepository users;@Autowired AdminUserRepository admins;
 MockMvc mvc;
 @BeforeEach void setup(){
  reset(service,funds,identity,assets,jwt,users,admins);mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(security).build();
  UserAccount u=new UserAccount();u.setId(7L);u.setStatus("normal");u.setUserType("agent");u.setCurrentToken("session");u.setPasswordHash("hash");when(users.findById(7L)).thenReturn(Optional.of(u));when(jwt.credentialKey("hash")).thenReturn("credential");
  AdminUser admin=new AdminUser();admin.setId(7L);admin.setEnabled(true);admin.setRole("super_admin");admin.setCurrentToken("session");admin.setPasswordHash("hash");when(admins.findById(7L)).thenReturn(Optional.of(admin));
  for(String type:Arrays.asList("user","agent","admin")){DefaultClaims c=new DefaultClaims();c.setSubject(type+"-7");c.setExpiration(new Date(System.currentTimeMillis()+60000));c.put("userType",type);c.put("sid","session");c.put("credential","credential");when(jwt.parse(type)).thenReturn(c);}
 }
 @Test void anonymousCannotReadOrClaim() throws Exception {mvc.perform(get("/api/activity/inbox")).andExpect(status().isUnauthorized());mvc.perform(post("/api/activity/messages/1/claim")).andExpect(status().isUnauthorized());verifyNoInteractions(service);}
 @Test void userAndAgentCannotControlCampaigns() throws Exception {for(String token:Arrays.asList("user","agent")){mvc.perform(post("/api/admin/activities/1/send").header("Authorization","Bearer "+token).contentType("application/json").content("[7]")).andExpect(status().isForbidden());}verifyNoInteractions(service);}
 @Test void usersAndAgentsCannotReadOrWriteMaterialLibrary()throws Exception{for(String token:Arrays.asList("user","agent")){mvc.perform(get("/api/admin/activity-materials").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());mvc.perform(post("/api/admin/activity-materials").header("Authorization","Bearer "+token).contentType("application/json").content("{}" )).andExpect(status().isForbidden());}}
 @Test void claimAlwaysUsesAuthenticatedOwnerNotBody() throws Exception {when(service.claim(7L,1L)).thenReturn(new TrialAccount());mvc.perform(post("/api/activity/messages/1/claim").header("Authorization","Bearer user").contentType("application/json").content("{\"userId\":99,\"amount\":999999}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(service).claim(7L,1L);}
 @Test void demoCannotClaimRealPromotionalCredit() throws Exception {mvc.perform(post("/api/activity/messages/1/claim").header("Authorization","Bearer user").header("X-Account-Mode","DEMO")).andExpect(status().isConflict());verifyNoInteractions(service);}
 @Test void trialCreditNeverBypassesWithdrawalKyc() throws Exception {doThrow(new KycRequiredException("NOT_VERIFIED")).when(identity).requireApproved(7L);mvc.perform(post("/api/withdraw/submit").header("Authorization","Bearer user").contentType("application/json").content("{\"amount\":10,\"type\":\"digital\"}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.errorCode").value("KYC_REQUIRED"));verifyNoInteractions(assets,funds);}
 @Test void adminControlsUsePermissionAnnotations(){for(java.lang.reflect.Method m:AdminActivityController.class.getDeclaredMethods()){if(java.lang.reflect.Modifier.isPublic(m.getModifiers())){AdminPermission permission=m.getAnnotation(AdminPermission.class);assertNotNull(permission,m.getName());assertEquals("announcement",permission.menu());}}}
}

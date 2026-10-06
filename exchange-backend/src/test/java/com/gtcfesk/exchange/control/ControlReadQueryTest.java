package com.gtcfesk.exchange.control;

import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.support.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.*;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Disposable real H2/JPA queries: data isolation, projection safety and zero read-side business mutations. */
@SpringJUnitConfig(ControlReadQueryTest.Config.class)
class ControlReadQueryTest {
 @Configuration @Import({ControlSupportIsolationTest.Config.class,ControlReadQueryService.class})
 static class Config { @Bean JdbcTemplate jdbcTemplate(DataSource ds){return new JdbcTemplate(ds);} }
 @Autowired ControlReadQueryService queries;@Autowired SupportService support;@Autowired SupportSettings settings;
 @Autowired JdbcTemplate jdbc;@Autowired PlatformTransactionManager manager;
 @PersistenceContext EntityManager em;
 static AtomicLong ids=new AtomicLong(100);long tenant;Long user;
 @BeforeEach void setup(){tenant=ids.incrementAndGet();switchTenant(tenant);SupportSettings.Settings s=new SupportSettings.Settings();s.mode="internal";when(settings.get()).thenReturn(s);when(settings.welcome(any(),anyString(),isNull())).thenReturn("hello");tx(()->{UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@test.invalid");u.setPasswordHash("never-return-password");u.setRemark("内部客户备注");u.setUserType("agent");em.persist(u);user=u.getId();});asControl(false);}
 @AfterEach void cleanup(){SecurityContextHolder.clearContext();TenantContext.clear();}
 void switchTenant(long t){TenantContext.clear();TenantContext.open(t);}
 void tx(Runnable work){new TransactionTemplate(manager).execute(s->{work.run();return null;});}
 void asControl(boolean access){UsernamePasswordAuthenticationToken a=new UsernamePasswordAuthenticationToken("99",null,Collections.singleton(new SimpleGrantedAuthority(access?"ROLE_CONTROL_ACCESS":"ROLE_CONTROL")));a.setDetails(new ControlIdentity(99L,access?tenant:null,access?"access-session":null));SecurityContextHolder.getContext().setAuthentication(a);}
 void asUser(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user.toString(),null,Collections.singleton(new SimpleGrantedAuthority("ROLE_USER"))));}
 @SuppressWarnings("unchecked") List<Map<String,Object>> rows(Map<String,Object> r){return (List<Map<String,Object>>)r.get("rows");}
 @Test void recordsAreTenantBoundAndNeverReturnCredentials(){
  tx(()->{KycRecord k=new KycRecord();k.setUserId(user);k.setRealName("person");k.setIdNumber("SECRET-ID-5678");em.persist(k);AdminUser a=new AdminUser();a.setAccount("admin");a.setEmail("a@test.invalid");a.setRole("admin");a.setPasswordHash("never-return-password");a.setCurrentToken("never-return-token");em.persist(a);});
  Map<String,Object> k=queries.records("kyc",user,null,1,20);assertEquals(1L,k.get("total"));assertEquals("***5678",rows(k).get(0).get("id_number"));
  assertEquals("内部客户备注",rows(k).get(0).get("user_remark"));assertTrue(rows(k).get(0).get("user_email").toString().endsWith("@test.invalid"));
  assertEquals(1L,queries.records("kyc",user," TEST.INVALID ",null,1,20).get("total"));assertEquals(0L,queries.records("kyc",user,"nonexistent",null,1,20).get("total"));
  assertEquals("内部客户备注",rows(queries.records("agents",null,null,1,20)).get(0).get("remark"));
  Map<String,Object> a=queries.records("admins",null,null,1,20);assertEquals(1L,a.get("total"));assertFalse(a.toString().contains("never-return"));assertFalse(rows(a).get(0).containsKey("password_hash"));
  assertEquals(1L,queries.records("agents",null,null,1,20).get("total"));switchTenant(tenant+1000);assertEquals(0L,queries.records("kyc",user,null,1,20).get("total"));assertEquals(0L,queries.records("admins",null,null,1,20).get("total"));assertEquals(0L,queries.records("agents",null,null,1,20).get("total"));
 }
 @Test void conversationsOffsetRangeIsHalfOpenTenantScopedAndReadOnly() throws Exception {
  long[] chat=chat();jdbc.update("UPDATE support_conversation SET created_at=? WHERE tenant_id=? AND id=?",java.time.LocalDateTime.parse("2026-01-02T00:00:00"),tenant,chat[0]);
  Map<String,Object> before=jdbc.queryForMap("SELECT * FROM support_conversation WHERE tenant_id=? AND id=?",tenant,chat[0]);
  assertEquals(1,queries.conversations(user,null,"2026-01-02T08:00:00+08:00","2026-01-02T08:01:00+08:00",0).size());
  assertEquals(1,queries.conversations(user,null,"2026-01-02T00:00:00Z","2026-01-02T00:01:00Z",0).size());
  assertTrue(queries.conversations(user,null,"2026-01-01T23:59:00Z","2026-01-02T00:00:00Z",0).isEmpty());
  List<Map<String,Object>> filtered=queries.conversations(user,null," TEST.INVALID ",null,null,0);
  assertEquals(1,filtered.size());assertEquals("内部客户备注",filtered.get(0).get("user_remark"));assertTrue(queries.conversations(user,null,"missing@example.com",null,null,0).isEmpty());
  assertEquals(before,jdbc.queryForMap("SELECT * FROM support_conversation WHERE tenant_id=? AND id=?",tenant,chat[0]));
  switchTenant(tenant+1000);assertTrue(queries.conversations(user,null,null,null,0).isEmpty());
 }
 @Test void allConversationsAreGloballyPagedFilteredLabelledAndReadOnly(){
  String email="chat-all-"+UUID.randomUUID()+"@test.invalid";long other=tenant+10000;
  for(long owner:new long[]{tenant,other})jdbc.update("INSERT INTO tenant(id,code,name,status,template_version,policy_version,session_version,config_ready,domain_verified,row_version) VALUES (?,?,?,'ACTIVE','safe-v1',0,0,TRUE,TRUE,0)",owner,"chat-all-"+owner,"Tenant "+owner);
  jdbc.update("UPDATE user_account SET email=? WHERE tenant_id=? AND id=?",email,tenant,user);
  Long[] users={user,null},admins={null,null};List<Long> expected=new ArrayList<>();
  switchTenant(other);tx(()->{UserAccount u=new UserAccount();u.setEmail(email);u.setRemark("Other tenant remark");u.setPasswordHash("never-return-password");em.persist(u);users[1]=u.getId();});
  for(int index=0;index<2;index++){final int n=index;switchTenant(index==0?tenant:other);tx(()->{AdminUser a=new AdminUser();a.setAccount("chat-admin-"+UUID.randomUUID());a.setEmail("staff-"+UUID.randomUUID()+"@test.invalid");a.setPasswordHash("never-return-password");a.setRole("admin");em.persist(a);admins[n]=a.getId();});}
  for(int index=0;index<35;index++){
   final int n=index%2;long owner=n==0?tenant:other;switchTenant(owner);
   tx(()->{SupportConversation c=new SupportConversation();c.setUserId(users[n]);c.setAdminId(admins[n]);c.setClientIp("192.0.2.1");c.setStatus("CLOSED");c.setLegalHold(n==1);c.setAdminReadId(9);c.setUserReadId(7);em.persist(c);expected.add(c.getId());});
   jdbc.update("UPDATE support_conversation SET created_at=?,closed_at=? WHERE tenant_id=? AND id=?",java.time.LocalDateTime.parse("2026-01-02T00:00:00").plusSeconds(index/2),java.time.LocalDateTime.parse("2026-01-03T00:00:00"),owner,expected.get(index));
  }
  List<Map<String,Object>> before=jdbc.queryForList("SELECT * FROM support_conversation WHERE tenant_id IN (?,?) ORDER BY id",tenant,other);
  TenantContext.clear();asControl(false);
  List<Map<String,Object>> first=queries.allConversations(null,null,email,null,null,0),second=queries.allConversations(null,null,email,null,null,1);
  assertEquals(30,first.size());assertEquals(5,second.size());assertTrue(queries.allConversations(null,null,email,null,null,2).isEmpty());
  List<Long> actual=new ArrayList<>();for(Map<String,Object> row:first)actual.add(((Number)row.get("id")).longValue());for(Map<String,Object> row:second)actual.add(((Number)row.get("id")).longValue());Collections.reverse(expected);assertEquals(expected,actual);
  Set<Long> owners=new HashSet<>();for(Map<String,Object> row:first){long owner=((Number)row.get("tenant_id")).longValue();owners.add(owner);assertEquals("Tenant "+owner,row.get("tenant_name"));assertEquals(email,row.get("user_email"));assertEquals(owner==tenant?"内部客户备注":"Other tenant remark",row.get("user_remark"));assertFalse(row.containsKey("password_hash"));assertFalse(row.containsKey("client_ip"));}
  assertEquals(new HashSet<>(Arrays.asList(tenant,other)),owners);assertEquals(18,queries.allConversations(user,null,email,null,null,0).size());assertEquals(17,queries.allConversations(null,admins[1],email,null,null,0).size());assertTrue(queries.allConversations(null,null,"missing-"+email,null,null,0).isEmpty());
  assertEquals(10,queries.allConversations(null,null,email,"2026-01-02T08:00:00+08:00","2026-01-02T08:00:05+08:00",0).size());
  assertEquals(before,jdbc.queryForList("SELECT * FROM support_conversation WHERE tenant_id IN (?,?) ORDER BY id",tenant,other));assertNull(TenantContext.currentTenantId());
  switchTenant(other);List<Map<String,Object>> scoped=queries.conversations(null,null,email,null,null,0);assertEquals(17,scoped.size());assertTrue(scoped.stream().allMatch(row->((Number)row.get("tenant_id")).longValue()==other));
 }
 @Test void allConversationsRequireIndependentControlWithoutAnyTenantScope(){
  assertThrows(org.springframework.security.access.AccessDeniedException.class,()->queries.allConversations(null,null,null,null,null,0));
  TenantContext.clear();asControl(true);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->queries.allConversations(null,null,null,null,null,0));
  asUser();assertThrows(org.springframework.security.access.AccessDeniedException.class,()->queries.allConversations(null,null,null,null,null,0));SecurityContextHolder.clearContext();assertThrows(org.springframework.security.access.AccessDeniedException.class,()->queries.allConversations(null,null,null,null,null,0));
  asControl(false);assertThrows(IllegalArgumentException.class,()->queries.allConversations(-1L,null,null,null,null,0));assertThrows(IllegalArgumentException.class,()->queries.allConversations(null,null,null,null,null,-1));assertThrows(IllegalArgumentException.class,()->queries.allConversations(null,null,null,null,null,100001));assertThrows(IllegalArgumentException.class,()->queries.allConversations(null,null,null,"2026-01-01T00:00:00Z",null,0));
 }
 @Test void supportRangesRequireOffsetPairIncreasingAndBounded(){
  for(String[] r:new String[][]{{"2026-01-01T00:00:00", "2026-01-02T00:00:00"},{"2026-01-02T00:00:00Z","2026-01-01T00:00:00Z"},{"2026-01-01T00:00:00Z","2026-01-01T00:00:00Z"},{"2025-01-01T00:00:00Z","2026-01-03T00:00:00Z"},{"2026-01-01T00:00:00Z",null}})assertThrows(RuntimeException.class,()->queries.conversations(null,null,r[0],r[1],0));
  assertThrows(RuntimeException.class,()->queries.conversations(-1L,null,null,null,0));assertThrows(RuntimeException.class,()->queries.conversations(null,null,null,null,100001));asControl(true);assertThrows(RuntimeException.class,()->queries.conversations(null,null,null,null,0));
 }
 @Test void callerBoundaryAndFixedQueryInputsFailClosed(){
  asControl(true);assertThrows(RuntimeException.class,()->queries.records("admins",null,null,1,20));asControl(false);
  assertThrows(RuntimeException.class,()->queries.records("admins; DELETE",null,null,1,20));assertThrows(RuntimeException.class,()->queries.records("kyc",null,null,0,20));assertThrows(RuntimeException.class,()->queries.records("kyc",null,null,1,101));
  SecurityContextHolder.clearContext();assertThrows(RuntimeException.class,queries::statistics);
 }
 @Test void statisticsStayInsideTenantAndPreserveAccountType(){
  tx(()->{AssetAccount a=new AssetAccount();a.setUserId(user);a.setCoin("FUND");a.setAvailable(new BigDecimal("12.50"));em.persist(a);AssetAccount b=new AssetAccount();b.setUserId(user);b.setCoin("CONTRACT");b.setAvailable(new BigDecimal("3.25"));em.persist(b);});
  Map<String,Object> report=queries.statistics();assertEquals(tenant,report.get("tenantId"));assertEquals(1L,((Map<?,?>)report.get("counts")).get("users"));assertEquals(2,((List<?>)report.get("assets")).size());
  switchTenant(tenant+1000);assertEquals(0L,((Map<?,?>)queries.statistics().get("counts")).get("users"));assertTrue(((List<?>)queries.statistics().get("assets")).isEmpty());
 }
 long[] chat() throws Exception {
  asUser();long c=support.start("192.0.2.10").getId();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",out);
  SupportMessage m=support.send(c,false,UUID.randomUUID().toString(),"proof",new MockMultipartFile("image","test.png","image/png",out.toByteArray()));asControl(false);return new long[]{c,m.getId()};
 }
 @Test void attachmentAndEvidenceReadDoNotMarkReadOrCreateStaff() throws Exception {
  long[] ids=chat();Map<String,Object> before=jdbc.queryForMap("SELECT * FROM support_conversation WHERE tenant_id=? AND id=?",tenant,ids[0]);
  assertTrue(queries.attachment(ids[0],ids[1]).length>8);Map<String,Object> evidence=queries.evidence(ids[0]);assertEquals(true,evidence.get("chainValid"));assertEquals(99L,evidence.get("exportedBy"));assertEquals("CONTROL",evidence.get("exportedByType"));assertEquals(tenant,evidence.get("tenantId"));assertEquals(1,((Map<?,?>)evidence.get("imagesPngBase64")).size());
  assertEquals(before,jdbc.queryForMap("SELECT * FROM support_conversation WHERE tenant_id=? AND id=?",tenant,ids[0]));assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM support_presence WHERE tenant_id=?",Long.class,tenant));assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM admin_user WHERE tenant_id=?",Long.class,tenant));
  switchTenant(tenant+1000);assertThrows(RuntimeException.class,()->queries.attachment(ids[0],ids[1]));assertThrows(RuntimeException.class,()->queries.evidence(ids[0]));
 }
 @Test void corruptAttachmentIsNotServedAndEvidenceReportsFalse() throws Exception {
  long[] ids=chat();jdbc.update("UPDATE support_attachment SET content=? WHERE tenant_id=? AND message_id=?",new byte[]{1,2,3},tenant,ids[1]);assertThrows(RuntimeException.class,()->queries.attachment(ids[0],ids[1]));assertEquals(false,queries.evidence(ids[0]).get("chainValid"));
 }
 @Test void controllerRequiresAuditBeforeReturningEvidence() {
  TenantRepository tenants=mock(TenantRepository.class);ControlAuditService audit=mock(ControlAuditService.class);ControlReadQueryService read=mock(ControlReadQueryService.class);when(tenants.findById(tenant)).thenReturn(Optional.of(new Tenant()));when(read.evidence(5)).thenReturn(Collections.singletonMap("chainValid",true));
  ControlReadController controller=new ControlReadController(null,null,tenants,audit,jdbc,read);org.springframework.http.ResponseEntity<?> response=controller.evidence(tenant,5);assertEquals("true",response.getHeaders().getFirst("X-Evidence-Chain-Valid"));assertTrue(response.getHeaders().getCacheControl().contains("no-store"));verify(audit).record(eq(99L),eq(tenant),isNull(),eq("CHAT_EVIDENCE_EXPORT"),eq("5"),eq("SUCCESS"),eq("read-only"),isNull());
  doThrow(new IllegalStateException("audit down")).when(audit).record(any(),any(),any(),any(),any(),any(),any(),any());assertThrows(RuntimeException.class,()->controller.evidence(tenant,5));
 }
 @Test void tenantPolicyMetadataNeverContainsConfigValuesAndChecksPermissions(){
  TenantRepository tenants=mock(TenantRepository.class);TenantPolicyRepository repo=mock(TenantPolicyRepository.class);AdminPermissionService permissions=mock(AdminPermissionService.class);Tenant t=new Tenant();t.setId(tenant);t.setName("test");when(tenants.findById(tenant)).thenReturn(Optional.of(t));
  TenantPolicy secret=new TenantPolicy();secret.setTenantId(tenant);secret.setKey("config.mail.password");secret.setValue("highly-sensitive");secret.setLocked(true);TenantPolicy channel=new TenantPolicy();channel.setKey("config.support.channel");channel.setValue("internal");channel.setLocked(true);when(repo.findByTenantId(tenant)).thenReturn(Arrays.asList(secret,channel));
  AdminTenantPolicyController controller=new AdminTenantPolicyController(tenants,repo,permissions);Object result=controller.settings();assertFalse(result.toString().contains("highly-sensitive"));assertTrue(result.toString().contains("mail.password"));assertTrue(result.toString().contains("internal"));verify(permissions).require("settings","");assertFalse(controller.support().toString().contains("mail.password"));doThrow(new org.springframework.security.access.AccessDeniedException("no access")).when(permissions).require("settings","");assertThrows(RuntimeException.class,controller::settings);
 }
}

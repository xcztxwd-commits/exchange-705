package com.gtcfesk.exchange.insights;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContext;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real SQL/MVC/JWT/tenant/permissions and service-context restart, never a production database. */
@org.springframework.context.annotation.Import(BootTenantFixture.class)
@org.junit.jupiter.api.extension.ExtendWith(TenantOneFixture.class)
@TestPropertySource(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:multitenant-market-test.sql","spring.redis.host=127.0.0.1","spring.redis.port=1","spring.redis.password=","platform.base-domain=mt705.test","platform.admin-origin=https://admin.mt705.test","platform.control-origin=https://control.mt705.test","security.trusted-proxies=127.0.0.1","market.exchange.stream-enabled=false","market.depth.enabled=false","calendar.sync.enabled=false","calendar.reminders.enabled=false"})
@SpringBootTest(classes=CalendarIntegrationTest.App.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:calendar_stage2;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=update","spring.jpa.show-sql=false","spring.jpa.open-in-view=false","logging.level.root=ERROR","jwt.secret=Y2FsZW5kYXItdGVzdC1vbmx5LW5vdC1wcm9kdWN0aW9uLXNlY3JldA=="})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CalendarIntegrationTest {
    @org.springframework.context.annotation.Configuration @org.springframework.boot.test.context.TestComponent
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages="com.gtcfesk.exchange",repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
    @org.springframework.boot.autoconfigure.domain.EntityScan("com.gtcfesk.exchange")
    @org.springframework.context.annotation.ComponentScan(basePackages="com.gtcfesk.exchange",excludeFilters={
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.CUSTOM,classes=org.springframework.boot.context.TypeExcludeFilter.class),
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,classes=com.gtcfesk.exchange.ExchangeBackendApplication.class),
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.REGEX,pattern="com\\.gtcfesk\\.exchange\\.support\\.UnifiedInbox.*Fixture.*")})
    static class App { }
    @MockBean com.gtcfesk.exchange.market.ForexQuoteMarketService quotes;
    @MockBean com.gtcfesk.exchange.market.MarketInstrumentCatalog catalog;
    @MockBean com.gtcfesk.exchange.market.MarketOrderProcessor processor;
    @MockBean com.gtcfesk.exchange.market.RedisMarketService redis;
    @MockBean com.gtcfesk.exchange.service.EmailService email;
    @MockBean com.gtcfesk.exchange.security.RegistrationSecurity registration;
    @SpyBean CalendarSync sync;
    @Autowired CalendarService calendar;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired SystemConfigService configs;
    @Autowired AdminUserRepository admins;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminRoleMenuRepository grants;
    @Autowired AdminMenuRepository menus;
    @Autowired UserAccountRepository users;
    @Autowired UserMenuRepository userMenus;
    @Autowired UserActionRepository userActions;
    @Autowired JwtUtil jwt;
    @Autowired TenantJobRunner jobs;
    @Autowired com.gtcfesk.exchange.control.BackendLoginRegistry names;
    @Autowired ApplicationContext context;
    @LocalServerPort int port;
    String writer,reader,denied,userToken,secondToken,tenantTwo;
    long userId;
    static String restartId,restartToken;
    static long restartUser,startupOne,startupTwo;
    static Instant restartAt;
    static final Path REPORTS=Paths.get("../reports/calendar-20261003");
    static final String API="/api/admin/insights/calendar";
    String token(Long id,String type,String sid,String password){Map<String,Object> c=new HashMap<>();c.put("userType",type);c.put("sid",sid);c.put("credential",jwt.credentialKey(password));return jwt.generateToken(type+"-"+id,c);}
    String admin(String role){AdminUser a=new AdminUser();a.setAccount("calendar_qa_"+UUID.randomUUID());a.setEmail(a.getAccount()+"@example.invalid");a.setRole(role);a.setEnabled(true);a.setPasswordHash("calendar-test-only");a.setCurrentToken(UUID.randomUUID().toString());a=admins.saveAndFlush(a);names.register("ADMIN",a.getId(),a.getAccount());return token(a.getId(),"admin",a.getCurrentToken(),a.getPasswordHash());}
    String limited(boolean view){AdminRole role=new AdminRole();role.setRoleCode("calendar_"+UUID.randomUUID().toString().substring(0,8));role.setRoleName(role.getRoleCode());role.setStatus("active");role=roles.saveAndFlush(role);if(view){AdminRoleMenu g=new AdminRoleMenu();g.setRoleId(role.getId());g.setMenuId(menus.findByMenuCode("calendar").get().getId());grants.saveAndFlush(g);}return admin(role.getRoleCode());}
    UserAccount user(){UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@example.invalid");u.setPasswordHash("calendar-test-only");u.setCurrentToken(UUID.randomUUID().toString());return users.saveAndFlush(u);}
    @BeforeEach void setup(TestInfo test)throws Exception{
        boolean recovery=test.getTestMethod().get().getName().startsWith("restartRecovered");
        if(!recovery){for(String table:Arrays.asList("calendar_reminder","calendar_audit","calendar_event","calendar_source_update"))db.update("DELETE FROM "+table);db.update("UPDATE calendar_source SET next_attempt=NULL,lease_until=NULL,parsed_json=NULL,payload=NULL,status='NEVER_FETCHED',last_success=NULL,requests_today=0,failures=0");}
        writer=admin("super_admin");reader=limited(true);denied=limited(false);configs.saveConfig("support.settings","{\"mode\":\"off\",\"inboxEnabled\":true}","isolated calendar acceptance");
        UserAccount u=user();userId=u.getId();userToken=token(u.getId(),"user",u.getCurrentToken(),u.getPasswordHash());u=user();secondToken=token(u.getId(),"user",u.getCurrentToken(),u.getPasswordHash());
        TenantContext.clear();try(TenantContext.Scope scope=TenantContext.open(2L)){tenantTwo=admin("super_admin");configs.saveConfig("support.settings","{\"mode\":\"off\",\"inboxEnabled\":true}","isolated tenant two");}finally{TenantContext.open(1L);}
        if(!Boolean.getBoolean("calendar.live"))doAnswer(call->{String source=call.getArgument(0);CalendarSync.HttpResult r=new CalendarSync.HttpResult();r.status=source.equals("BLS_CALENDAR")?403:200;if(r.status==200){String file=source.toLowerCase(Locale.ROOT).replace('_','-');r.body=CalendarParserTest.raw(file);}return r;}).when(sync).fetch(anyString(),any(CalendarSource.class));
        Files.createDirectories(REPORTS);
    }
    MvcResult send(String method,String path,String token,Object body)throws Exception{
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r="GET".equals(method)?get(path):"PUT".equals(method)?put(path):"DELETE".equals(method)?delete(path):post(path);if(token!=null)r.header("Authorization","Bearer "+token);if(body!=null)r.contentType("application/json").content(json.writeValueAsString(body));return mvc.perform(r).andReturn();
    }
    JsonNode ok(MvcResult r)throws Exception{assertEquals(200,r.getResponse().getStatus(),r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));return json.readTree(r.getResponse().getContentAsByteArray());}
    JsonNode detail(String id)throws Exception{return ok(send("GET",API+"/"+id,writer,null));}
    CalendarData future(String period,Instant time){CalendarData d=CalendarData.metric("CORE_PCE_MOM");d.statisticalPeriod=period;d.sourceUrl=CalendarParser.BEA_ICS;d.sourceChannel="TEST_ONLY_FIXTURE";d.releaseAt=time.truncatedTo(ChronoUnit.MINUTES);d.timePrecision="MINUTE";d.sourceAsOf=Instant.now();d.validate();return d;}
    String seed(CalendarData d){CalendarParser.Batch b=new CalendarParser.Batch();b.events.add(d);calendar.importBatch(b,false,null);return d.eventId;}
    JsonNode correction(String id,ObjectNode data,boolean lock)throws Exception{return ok(send("PUT",API+"/"+id,writer,new LinkedHashMap<String,Object>(){{put("rowVersion",data.path("rowVersion").asLong());put("reason","隔离测试核验依据");put("manualLock",lock);put("data",data);}}));}
    JsonNode publish(String id,boolean published,String status)throws Exception{JsonNode d=detail(id);Map<String,Object> p=new LinkedHashMap<>();p.put("rowVersion",d.path("rowVersion").asLong());p.put("reason","隔离测试已核验材料");p.put("published",published);p.put("status",status);return ok(send("POST",API+"/"+id+"/publication",writer,p));}
    JsonNode reminder(String id,String token,int lead)throws Exception{return ok(send("PUT","/api/user/calendar/reminders/"+id,token,new LinkedHashMap<String,Object>(){{put("leadMinutes",lead);put("timezone","Asia/Singapore");}}));}
    JsonNode importMaterial(List<CalendarData> rows,String token)throws Exception{Map<String,Object> b=new LinkedHashMap<>();b.put("format","VERIFIED_JSON");b.put("content",json.writeValueAsString(rows));b.put("verified",true);b.put("material","核验官方 BLS/BEA 日程与发布材料，来源链接随条目保存");return ok(send("POST",API+"/import",token,b));}

    @Test @Order(1) void verifiedOfficialMaterialDraftPublicationActualAssociationAndAudit()throws Exception{
        CalendarData nfp=CalendarData.metric("NFP_CHANGE");nfp.statisticalPeriod="2026-09";nfp.releaseAt=Instant.parse("2026-10-02T12:30:00Z");nfp.timePrecision="MINUTE";nfp.sourceUrl="https://www.bls.gov/schedule/2026/home.htm";nfp.validate();
        JsonNode imported=importMaterial(Collections.singletonList(nfp),writer);assertEquals("VERIFIED_MANUAL_IMPORT",imported.path("importKind").asText());assertEquals(1,imported.path("changed").asInt());assertEquals(404,send("GET","/api/insights/calendar/"+nfp.eventId,null,null).getResponse().getStatus());assertFalse(detail(nfp.eventId).path("published").asBoolean());
        publish(nfp.eventId,true,"SCHEDULED");correction(nfp.eventId,(ObjectNode)detail(nfp.eventId),false);sync.sync("BLS_DATA");JsonNode d=ok(send("GET","/api/insights/calendar/"+nfp.eventId,null,null));assertEquals(CalendarData.metric("NFP_CHANGE").title,d.path("title").asText());assertEquals("29",d.path("actual").asText());assertEquals("133",d.path("previous").asText());assertEquals("LATEST_TIME_SERIES",d.path("actualBasis").asText());assertFalse(d.path("historicalInitialKnown").asBoolean());assertTrue(d.path("originalReleaseValue").isNull());assertTrue(d.path("forecast").isNull());
        json.writeValue(REPORTS.resolve("api-real-nfp.json").toFile(),d);assertTrue(ok(send("GET",API+"/"+nfp.eventId+"/audit",writer,null)).size()>=4);
        CalendarParser.Batch revised=CalendarParser.bls(CalendarParserTest.raw("bls-data"),CalendarParserTest.NOW,json);CalendarData changed=CalendarParserTest.find(revised.observations,"NFP_CHANGE","2026-09");changed.actual="30";changed.footnotes="TEST_ONLY revision fixture, not an official observed release";calendar.importBatch(revised,false,null);assertTrue(detail(nfp.eventId).path("isRevised").asBoolean());assertEquals(nfp.eventId,detail(nfp.eventId).path("eventId").asText());assertFalse(detail(nfp.eventId).path("historicalInitialKnown").asBoolean());
        CalendarData pce=CalendarData.metric("CORE_PCE_MOM");pce.statisticalPeriod="2026-08";pce.actual="0.2";pce.actualBasis="OFFICIAL_MATERIAL";pce.sourceUrl="https://www.bea.gov/news/2026/personal-income-and-outlays-august-2026";pce.releaseAt=Instant.parse("2026-09-30T12:30:00Z");pce.timePrecision="MINUTE";pce.validate();importMaterial(Collections.singletonList(pce),writer);assertEquals("0.2",detail(pce.eventId).path("actual").asText());assertTrue(detail(pce.eventId).path("previous").isNull());
    }
    @Test @Order(1) void verifiedOfficialUnemploymentAndCpiKeepPeriodAndSpecificMetric()throws Exception{
        // BLS official year calendar: Employment September on Oct 2; CPI August on Sep 11, both 08:30 Eastern.
        CalendarData unemployment=CalendarData.metric("UNEMPLOYMENT_RATE");unemployment.statisticalPeriod="2026-09";unemployment.releaseAt=Instant.parse("2026-10-02T12:30:00Z");unemployment.timePrecision="MINUTE";unemployment.sourceUrl="https://www.bls.gov/schedule/2026/home.htm";unemployment.validate();
        CalendarData cpi=CalendarData.metric("CPI_YOY");cpi.statisticalPeriod="2026-08";cpi.releaseAt=Instant.parse("2026-09-11T12:30:00Z");cpi.timePrecision="MINUTE";cpi.sourceUrl=unemployment.sourceUrl;cpi.validate();
        importMaterial(Arrays.asList(unemployment,cpi),writer);for(CalendarData row:Arrays.asList(unemployment,cpi)){publish(row.eventId,true,"SCHEDULED");correction(row.eventId,(ObjectNode)detail(row.eventId),false);}sync.sync("BLS_DATA");
        JsonNode u=ok(send("GET","/api/insights/calendar/"+unemployment.eventId,null,null)),c=ok(send("GET","/api/insights/calendar/"+cpi.eventId,null,null));assertEquals("4.2",u.path("actual").asText());assertEquals("4.1",u.path("previous").asText());assertEquals("3.4",c.path("actual").asText());assertEquals("3.4",c.path("previous").asText());assertEquals("NSA",c.path("seasonality").asText());assertEquals("YOY",c.path("comparison").asText());assertEquals("2026-08",c.path("statisticalPeriod").asText());assertTrue(c.path("forecast").isNull());assertFalse(c.path("historicalInitialKnown").asBoolean());
        json.writeValue(REPORTS.resolve("api-real-unemployment.json").toFile(),u);json.writeValue(REPORTS.resolve("api-real-cpi.json").toFile(),c);
    }
    @Test @Order(2) void durableOfficialReplayCacheHealthBls403AndNoPerVisitorRequests()throws Exception{
        for(String source:CalendarSync.URLS.keySet())sync.sync(source);assertEquals(6,sync.history("BLS_CALENDAR",0).size()+sync.history("BEA_CALENDAR",0).size()+sync.history("FED_CALENDAR",0).size()+sync.history("BEA_DATA",0).size()+sync.history("FED_DATA",0).size()+sync.history("BLS_DATA",0).size());
        assertEquals("BLOCKED",sync.status().get(0).get("status"));assertEquals(403,sync.status().get(0).get("httpStatus"));assertEquals(false,sync.sync("BLS_CALENDAR").get("requested"));verify(sync,times(1)).fetch(eq("BLS_CALENDAR"),any(CalendarSource.class));
        CalendarData gdp=CalendarParser.beaRss(CalendarParserTest.raw("bea-data"),CalendarParserTest.NOW).events.get(0);JsonNode gd=detail(gdp.eventId);assertEquals("2.2",gd.path("actual").asText());assertEquals("2.5",gd.path("previous").asText());json.writeValue(REPORTS.resolve("api-real-gdp.json").toFile(),gd);
        List<CalendarData> fed=CalendarParser.fedRss(CalendarParserTest.raw("fed-data"),CalendarParserTest.NOW,CalendarParser.fedCalendar(CalendarParserTest.raw("fed-calendar"),CalendarParserTest.NOW).events).events;CalendarData fd=CalendarParserTest.find(fed,"FOMC_DECISION","2026-09");sync.importCached("FED_CALENDAR");assertEquals("MINUTE",detail(fd.eventId).path("timePrecision").asText());
        CalendarData pce=CalendarParserTest.find(CalendarParser.ics("BEA",CalendarParserTest.raw("bea-calendar"),CalendarParserTest.NOW).events,"CORE_PCE_MOM","2026-09");assertTrue(detail(pce.eventId).path("actual").isNull());long before=db.queryForObject("SELECT COUNT(*) FROM calendar_event",Long.class);assertEquals(0,sync.importAllCached());assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM calendar_event",Long.class));
        for(int i=0;i<4;i++)ok(send("GET","/api/insights/calendar?from=2026-09-01&to=2026-12-31",null,null));verify(sync,times(1)).fetch(eq("BEA_CALENDAR"),any(CalendarSource.class));json.writeValue(REPORTS.resolve("source-replay-health.json").toFile(),sync.status());
    }
    @Test @Order(3) void updatesStableIdsPostponeCancelAndManualPrecedence()throws Exception{
        CalendarData d=future("2030-01",Instant.now().plusSeconds(7200));String id=seed(d);assertEquals(0,calendar.importBatch(batch(d),false,null));
        d.releaseAt=d.releaseAt.plusSeconds(3600);d.releaseDate=null;d.sourceRevision=1;calendar.importBatch(batch(d),false,null);assertEquals("POSTPONED",detail(id).path("status").asText());assertEquals(id,detail(id).path("eventId").asText());
        ObjectNode edit=(ObjectNode)detail(id);edit.put("adminEstimate","-0.1");edit.put("estimateReason","独立管理员估计的隔离测试");correction(id,edit,true);d.releaseAt=d.releaseAt.plusSeconds(3600);d.releaseDate=null;assertEquals(0,calendar.importBatch(batch(d),false,null));assertNotEquals(d.releaseAt.toString(),detail(id).path("releaseAt").asText());assertEquals("ADMIN_ESTIMATE",detail(id).path("estimateKind").asText());assertTrue(detail(id).path("forecast").isNull());
        JsonNode audit=ok(send("GET",API+"/"+id+"/audit",writer,null));assertTrue(audit.toString().contains("SKIPPED_MANUAL_LOCK"));correction(id,(ObjectNode)detail(id),false);calendar.importBatch(batch(d),false,null);assertEquals(d.releaseAt.toString(),detail(id).path("releaseAt").asText());d.status="CANCELLED";calendar.importBatch(batch(d),false,null);assertEquals("CANCELLED",detail(id).path("status").asText());
        ObjectNode invalid=(ObjectNode)detail(id);invalid.put("unit","INDEX");Map<String,Object> bad=new HashMap<>();bad.put("rowVersion",invalid.path("rowVersion").asLong());bad.put("reason","wrong unit");bad.put("data",invalid);bad.put("manualLock",true);assertEquals(400,send("PUT",API+"/"+id,writer,bad).getResponse().getStatus());bad.put("rowVersion",-1);assertEquals(409,send("PUT",API+"/"+id,writer,bad).getResponse().getStatus());
    }
    static CalendarParser.Batch batch(CalendarData d){CalendarParser.Batch b=new CalendarParser.Batch();b.events.add(d);return b;}
    @Test @Order(4) void windowMonthEndPaginationStatusFiltersAndLegalIcs()throws Exception{
        String first=seed(future("2030-02",Instant.parse("2030-01-31T15:30:00Z")));seed(future("2030-03",Instant.parse("2030-02-01T15:30:00Z")));
        JsonNode one=ok(send("GET","/api/insights/calendar?from=2030-01-31&to=2030-01-31&size=1",null,null));assertEquals(1,one.path("totalElements").asInt());assertEquals(first,one.path("content").path(0).path("eventId").asText());assertEquals(1,ok(send("GET","/api/insights/calendar?from=2030-01-31&to=2030-02-01&page=1&size=1",null,null)).path("content").size());assertEquals(0,ok(send("GET","/api/insights/calendar?from=2030-01-31&to=2030-02-01&country=CN",null,null)).path("totalElements").asInt());
        for(String query:Arrays.asList("from=2030-02-01&to=2030-01-01","from=2030-01-01&to=2031-01-02","size=101","metric=INVALID","status=FAKE"))assertEquals(400,send("GET","/api/insights/calendar?"+query,null,null).getResponse().getStatus());
        CalendarData past=future("2020-01",Instant.parse("2020-02-01T13:30:00Z"));seed(past);assertEquals(1,ok(send("GET","/api/insights/calendar?from=2020-02-01&to=2020-02-01&status=AWAITING_RELEASE",null,null)).path("totalElements").asInt());assertEquals(0,ok(send("GET","/api/insights/calendar?from=2020-02-01&to=2020-02-01&status=SCHEDULED",null,null)).path("totalElements").asInt());
        MvcResult ics=send("GET","/api/insights/calendar/"+first+".ics",null,null);assertEquals(200,ics.getResponse().getStatus());assertTrue(ics.getResponse().getContentType().startsWith("text/calendar"));assertTrue(ics.getResponse().getContentAsString().contains("DTSTART:20300131T153000Z"));
        CalendarData fed=CalendarData.metric("FOMC_DECISION");fed.statisticalPeriod="2030-04";fed.sourceUrl=CalendarParser.FED_CAL;fed.releaseDate=LocalDate.of(2030,4,30);fed.timePrecision="DATE";fed.validate();seed(fed);assertTrue(send("GET","/api/insights/calendar/"+fed.eventId+".ics",null,null).getResponse().getContentAsString().contains("STATUS:TENTATIVE"));assertEquals(409,send("PUT","/api/user/calendar/reminders/"+fed.eventId,userToken,Collections.singletonMap("leadMinutes",15)).getResponse().getStatus());
    }
    @Test @Order(5) void permissionsTenantEnvironmentHostAndPrivateDraftBoundaries()throws Exception{
        assertEquals(401,send("GET",API,null,null).getResponse().getStatus());assertEquals(403,send("GET",API,denied,null).getResponse().getStatus());ok(send("GET",API,reader,null));
        for(String path:Arrays.asList(API+"/import",API+"/sources/BEA_CALENDAR/sync"))assertEquals(403,send("POST",path,reader,Collections.emptyMap()).getResponse().getStatus());assertEquals(401,send("GET","/api/user/calendar/reminders",null,null).getResponse().getStatus());assertEquals(401,send("GET","/api/user/calendar/reminders",writer,null).getResponse().getStatus());
        String id=seed(future("2030-05",Instant.now().plusSeconds(6000)));assertEquals(403,send("PUT",API+"/"+id,reader,Collections.emptyMap()).getResponse().getStatus());assertEquals(403,send("POST",API+"/"+id+"/publication",reader,Collections.emptyMap()).getResponse().getStatus());assertEquals(404,send("GET",API+"/"+id,tenantTwo,null).getResponse().getStatus());
        assertEquals(404,mvc.perform(get("/api/insights/calendar/"+id).header("Host","b.mt705.test")).andReturn().getResponse().getStatus());assertEquals(401,mvc.perform(get("/api/user/calendar/reminders").header("Host","b.mt705.test").header("Authorization","Bearer "+userToken)).andReturn().getResponse().getStatus());assertEquals(403,mvc.perform(get("/api/insights/calendar").header("Host","unverified.example.invalid")).andReturn().getResponse().getStatus());
        db.update("UPDATE calendar_event SET environment='DEMO' WHERE event_id=?",id);assertEquals(404,send("GET","/api/insights/calendar/"+id+"?environment=DEMO&tenantId=1",null,null).getResponse().getStatus());db.update("UPDATE calendar_event SET environment='REAL' WHERE event_id=?",id);publish(id,false,"SCHEDULED");assertEquals(404,send("GET","/api/insights/calendar/"+id,null,null).getResponse().getStatus());
    }
    @Test @Order(6) void durableReminderIdempotenceRecipientIsolationAndInboxRead()throws Exception{
        Instant at=Instant.now().plusSeconds(1200).truncatedTo(ChronoUnit.MINUTES);String id=seed(future("2030-06",at));reminder(id,userToken,15);reminder(id,userToken,15);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM calendar_reminder",Integer.class));assertEquals(0,calendar.dispatchDue(at.minusSeconds(901)));assertEquals(1,calendar.dispatchDue(at.minusSeconds(899)));assertEquals(0,calendar.dispatchDue(at.minusSeconds(600)));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM inbox_letter WHERE user_id=?",Integer.class,userId));assertEquals(1,ok(send("GET","/api/user/calendar/reminders",userToken,null)).size());assertEquals(0,ok(send("GET","/api/user/calendar/reminders",secondToken,null)).size());assertEquals("DELIVERED",ok(send("GET","/api/user/calendar/reminders/"+id,userToken,null)).path(0).path("state").asText());
        JsonNode inbox=ok(send("GET","/api/user/support/unified-inbox",userToken,null));assertTrue(inbox.toString().contains(id));assertFalse(ok(send("GET","/api/user/support/unified-inbox",secondToken,null)).toString().contains(id));json.writeValue(REPORTS.resolve("reminder-inbox.json").toFile(),inbox);
        reminder(id,userToken,15);assertEquals(0,calendar.dispatchDue(at.minusSeconds(300)));TenantContext.clear();try{assertEquals(0,jobs.call(2L,()->calendar.dispatchDue(at.minusSeconds(300))));}finally{TenantContext.open(1L);}
    }
    @Test @Order(6) void demoRowsCannotBeReadOrDeliveredByRealService()throws Exception{
        Instant at=Instant.now().plusSeconds(1200).truncatedTo(ChronoUnit.MINUTES);String id=seed(future("2031-01",at));reminder(id,userToken,15);
        db.update("INSERT INTO calendar_event(tenant_id,environment,event_id,metric,release_date,release_at,status,published,manual_lock,data_json,upstream_hash,updated_at,row_version) SELECT tenant_id,'DEMO',event_id,metric,release_date,release_at,status,published,manual_lock,data_json,upstream_hash,updated_at,row_version FROM calendar_event WHERE event_id=? AND environment='REAL'",id);
        db.update("INSERT INTO calendar_reminder(tenant_id,environment,user_id,event_id,lead_minutes,timezone,enabled,updated_at,row_version) SELECT tenant_id,'DEMO',user_id,event_id,lead_minutes,timezone,enabled,updated_at,row_version FROM calendar_reminder WHERE event_id=? AND environment='REAL'",id);
        assertEquals(1,ok(send("GET","/api/user/calendar/reminders?environment=DEMO",userToken,null)).size());assertEquals("REAL",ok(send("GET","/api/insights/calendar/"+id+"?environment=DEMO",null,null)).path("environment").asText());
        assertEquals(1,calendar.dispatchDue(at.minusSeconds(300)));assertEquals(0,calendar.dispatchDue(at.minusSeconds(120)));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM calendar_reminder WHERE environment='DEMO' AND delivered_at IS NULL",Integer.class));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM inbox_letter WHERE user_id=? AND request_id LIKE 'CAL:REAL:%'",Integer.class,userId));
    }
    @Test @Order(7) void cancelTimeChangePublicationAndTenantFeatureStopDelivery()throws Exception{
        Instant at=Instant.now().plusSeconds(1200).truncatedTo(ChronoUnit.MINUTES);String id=seed(future("2030-07",at));reminder(id,userToken,15);assertEquals(200,send("DELETE","/api/user/calendar/reminders/"+id+"?leadMinutes=15",userToken,null).getResponse().getStatus());assertEquals(0,calendar.dispatchDue(at.minusSeconds(300)));
        reminder(id,userToken,15);ObjectNode changed=(ObjectNode)detail(id);changed.put("releaseAt",at.plusSeconds(3600).toString());changed.putNull("releaseDate");correction(id,changed,true);assertEquals(0,calendar.dispatchDue(at.minusSeconds(300)));assertEquals(1,calendar.dispatchDue(at.plusSeconds(3300)));
        String other=seed(future("2030-08",at));reminder(other,userToken,15);publish(other,true,"CANCELLED");assertEquals(0,calendar.dispatchDue(at.minusSeconds(300)));assertEquals(409,send("PUT","/api/user/calendar/reminders/"+other,userToken,Collections.emptyMap()).getResponse().getStatus());
        assertEquals("EVENT_CANCELLED",ok(send("GET","/api/user/calendar/reminders/"+other,userToken,null)).path(0).path("state").asText());
        String third=seed(future("2030-09",at));reminder(third,userToken,15);publish(third,false,"SCHEDULED");assertEquals(0,calendar.dispatchDue(at.minusSeconds(300)));publish(third,true,"SCHEDULED");configs.saveConfig("support.settings","{\"mode\":\"off\",\"inboxEnabled\":false}","isolated off");assertEquals(0,calendar.dispatchDue(at.minusSeconds(300)));assertEquals(409,send("PUT","/api/user/calendar/reminders/"+third,userToken,Collections.emptyMap()).getResponse().getStatus());
        configs.saveConfig("support.settings","{\"mode\":\"off\",\"inboxEnabled\":true}","isolated on");db.update("UPDATE tenant SET status='DISABLED' WHERE id=1");try{assertEquals(0,calendar.dispatchDue(at.minusSeconds(300)));}finally{db.update("UPDATE tenant SET status='ACTIVE' WHERE id=1");}
    }
    @Test @Order(8) void missingInputsInvalidUnitAdminActualAndSourceMalformedCacheSafety()throws Exception{
        String id=seed(future("2030-12",Instant.now().plusSeconds(7200)));ObjectNode data=(ObjectNode)detail(id);data.put("actual","0.2");data.put("previous","0");data.put("adminEstimate","-0.1");data.put("estimateReason","独立管理员估计的测试依据");JsonNode saved=correction(id,data,true);assertEquals("OFFICIAL_MATERIAL",saved.path("actualBasis").asText());assertTrue(saved.path("forecast").isNull());
        Map<String,Object> malformed=new HashMap<>();malformed.put("format","VERIFIED_JSON");malformed.put("verified",true);malformed.put("material","isolated invalid-input test");malformed.put("content","[invalid]");assertEquals(400,send("POST",API+"/import",writer,malformed).getResponse().getStatus());malformed.put("content","[]");assertEquals(400,send("POST",API+"/import",writer,malformed).getResponse().getStatus());malformed.put("content","[{}]");assertEquals(400,send("POST",API+"/import",writer,malformed).getResponse().getStatus());
        assertEquals(400,send("PUT","/api/user/calendar/reminders/"+id,userToken,Collections.singletonMap("leadMinutes",0)).getResponse().getStatus());assertEquals(400,send("PUT","/api/user/calendar/reminders/"+id,userToken,Collections.singletonMap("timezone","Not/A_Zone")).getResponse().getStatus());
        sync.sync("BEA_CALENDAR");long before=db.queryForObject("SELECT COUNT(*) FROM calendar_event",Long.class);db.update("UPDATE calendar_source SET next_attempt=NULL WHERE id='REAL:BEA_CALENDAR'");CalendarSync.HttpResult invalid=new CalendarSync.HttpResult();invalid.status=200;invalid.body="not official ICS";doReturn(invalid).when(sync).fetch(eq("BEA_CALENDAR"),any(CalendarSource.class));sync.sync("BEA_CALENDAR");assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM calendar_event",Long.class));assertTrue(sync.status().stream().anyMatch(s->s.get("sourceId").equals("BEA_CALENDAR")&&s.get("status").equals("ERROR")&&s.get("lastSuccess")!=null));
        long audits=db.queryForObject("SELECT COUNT(*) FROM calendar_audit WHERE event_id=? AND action='SKIPPED_MANUAL_LOCK'",Long.class,id);CalendarData incoming=future("2030-12",Instant.now().plusSeconds(10800));calendar.importBatch(batch(incoming),false,null);CalendarData alternate=future("2030-12",incoming.releaseAt);alternate.sourceChannel="OTHER_TEST_CHANNEL";calendar.importBatch(batch(alternate),false,null);calendar.importBatch(batch(incoming),false,null);calendar.importBatch(batch(alternate),false,null);assertEquals(audits+2,db.queryForObject("SELECT COUNT(*) FROM calendar_audit WHERE event_id=? AND action='SKIPPED_MANUAL_LOCK'",Long.class,id));
    }
    @Test @Order(8) void concurrentDispatchAndRateLimitBackoffBudgetAreBounded()throws Exception{
        Instant at=Instant.now().plusSeconds(1200).truncatedTo(ChronoUnit.MINUTES);String id=seed(future("2030-10",at));reminder(id,userToken,15);ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<Integer> a=pool.submit(TenantOneFixture.worker(()->calendar.dispatchDue(at.minusSeconds(300))));Future<Integer> b=pool.submit(TenantOneFixture.worker(()->calendar.dispatchDue(at.minusSeconds(300))));assertEquals(1,a.get(20,TimeUnit.SECONDS)+b.get(20,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
        CalendarSync.HttpResult throttle=new CalendarSync.HttpResult();throttle.status=429;throttle.retryAfter="7200";doReturn(throttle).when(sync).fetch(eq("BEA_CALENDAR"),any(CalendarSource.class));sync.sync("BEA_CALENDAR");Map<String,Object> health=sync.status().stream().filter(s->s.get("sourceId").equals("BEA_CALENDAR")).findFirst().get();assertEquals("RATE_LIMITED",health.get("status"));assertTrue(((Instant)health.get("nextAttempt")).isAfter(Instant.now().plusSeconds(7000)));assertEquals(false,sync.sync("BEA_CALENDAR").get("requested"));
        db.update("UPDATE calendar_source SET budget_date=?,requests_today=20,next_attempt=NULL WHERE id='REAL:BLS_DATA'",LocalDate.now(ZoneOffset.UTC));assertEquals(false,sync.sync("BLS_DATA").get("requested"));assertEquals("BUDGET_EXHAUSTED",sync.status().stream().filter(s->s.get("sourceId").equals("BLS_DATA")).findFirst().get().get("status"));
    }
    @Test @Order(9) @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void restartPreparedDurablePendingReminder()throws Exception{
        restartAt=Instant.now().plusSeconds(1800).truncatedTo(ChronoUnit.MINUTES);restartId=seed(future("2030-11",restartAt));restartUser=userId;restartToken=userToken;startupOne=context.getStartupDate();reminder(restartId,restartToken,15);assertEquals(0,calendar.dispatchDue(restartAt.minusSeconds(1200)));
    }
    @Test @Order(10) @DirtiesContext(methodMode=DirtiesContext.MethodMode.AFTER_METHOD)
    void restartRecoveredPendingReminderDeliveredOnceInFreshContext()throws Exception{
        assertNotEquals(startupOne,context.getStartupDate());startupTwo=context.getStartupDate();assertEquals(1,calendar.dispatchDue(restartAt.minusSeconds(300)));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM inbox_letter WHERE user_id=? AND request_id LIKE 'CAL:%'",Integer.class,restartUser));assertEquals("DELIVERED",ok(send("GET","/api/user/calendar/reminders/"+restartId,restartToken,null)).path(0).path("state").asText());
    }
    @Test @Order(11) void restartRecoveredDeliveredReceiptPreventsDuplicateAfterSecondRestart()throws Exception{
        assertNotEquals(startupTwo,context.getStartupDate());assertEquals(0,calendar.dispatchDue(restartAt.minusSeconds(120)));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM inbox_letter WHERE user_id=? AND request_id LIKE 'CAL:%'",Integer.class,restartUser));Map<String,Object> result=new LinkedHashMap<>();result.put("firstStartup",startupOne);result.put("secondStartup",startupTwo);result.put("thirdStartup",context.getStartupDate());result.put("database","isolated H2 retained across two closed/recreated service contexts");result.put("deliveryCount",1);result.put("passed",true);json.writeValue(REPORTS.resolve("restart-acceptance.json").toFile(),result);
    }
    @Test @Order(12) @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="calendar.live",matches="true")
    void actualFixedOfficialAdapterAnonymousHttpProbe()throws Exception{
        String target=System.getProperty("calendar.liveSource");if(target!=null)assertTrue(CalendarSync.URLS.containsKey(target));List<Object> result=new ArrayList<>();int requests=0;for(String source:CalendarSync.URLS.keySet()){if(target!=null&&!target.equals(source))continue;sync.sync(source);result.add(sync.status());requests++;}json.writeValue(REPORTS.resolve(target==null?"source-live-adapter.json":"source-live-adapter-"+target+".json").toFile(),result);assertEquals(requests,db.queryForObject("SELECT COUNT(*) FROM calendar_source_update",Integer.class));json.writeValue(REPORTS.resolve(target==null?"api-live-source-events.json":"api-live-source-events-"+target+".json").toFile(),calendar.list(LocalDate.of(2026,1,1),LocalDate.of(2026,12,31),null,null,null,null,0,100,false));
    }
    @Test @Order(13) @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="calendar.browserHold",matches="true")
    void browserAcceptanceWindow()throws Exception{
        sync.sync("BEA_CALENDAR");sync.sync("BEA_DATA");sync.sync("FED_CALENDAR");sync.sync("FED_DATA");sync.sync("BLS_CALENDAR");Map<String,Object> a=new LinkedHashMap<>();a.put("port",port);a.put("writer",writer);a.put("reader",reader);a.put("denied",denied);Files.deleteIfExists(REPORTS.resolve("browser.done"));json.writeValue(REPORTS.resolve("browser-access.json").toFile(),a);long end=System.currentTimeMillis()+480000;
        try{while(!Files.exists(REPORTS.resolve("browser.done"))&&System.currentTimeMillis()<end)Thread.sleep(300);assertEquals("passed",new String(Files.readAllBytes(REPORTS.resolve("browser.done")),java.nio.charset.StandardCharsets.UTF_8));}finally{Files.deleteIfExists(REPORTS.resolve("browser-access.json"));}
    }
    @Test @Order(14) void publicReadBudgetDoesNotTriggerOfficialFetch()throws Exception{
        CalendarController controller=context.getBean(CalendarController.class);
        @SuppressWarnings("unchecked") Map<Long,long[]> reads=(Map<Long,long[]>)org.springframework.test.util.ReflectionTestUtils.getField(controller,"reads");
        try{reads.put(1L,new long[]{System.currentTimeMillis()/60000,600});assertEquals(429,send("GET","/api/insights/calendar",null,null).getResponse().getStatus());verify(sync,never()).fetch(anyString(),any(CalendarSource.class));}finally{reads.clear();}
    }

    private UserAccount insightAgent(){UserAccount agent=user();agent.setUserType("agent");agent.setStatus("normal");agent=users.saveAndFlush(agent);names.register("AGENT",agent.getId(),agent.getEmail());return agent;}
    private String insightAgentToken(UserAccount agent){return token(agent.getId(),"agent",agent.getCurrentToken(),agent.getPasswordHash());}
    private void grantInsights(UserAccount agent){
        for(String code:Arrays.asList("calendar","news","traders")){
            AdminMenu menu=menus.findByMenuCode(code).orElseThrow(AssertionError::new);UserMenu binding=new UserMenu();binding.setUserId(agent.getId());binding.setMenuId(menu.getId());userMenus.saveAndFlush(binding);
            // Grant existing catalog actions so write denials prove the unchanged admin-only service boundary.
            for(AdminMenu button:menus.findByStatusOrderBySortOrderAsc("active"))if("button".equals(button.getMenuType())&&menu.getId().equals(button.getParentId())){UserAction action=new UserAction();action.setUserId(agent.getId());action.setMenuId(menu.getId());action.setActionCode(button.getMenuCode().substring(button.getMenuCode().indexOf(':')+1));userActions.saveAndFlush(action);}
        }
    }
    @Test @Order(15) void grantedAgentUuidInsightReadsAreScopedAndWritesRemainDenied()throws Exception{
        String calendarId=seed(future("2030-04",Instant.now().plusSeconds(7200)));
        String newsApi="/api/admin/insights/news",tradersApi="/api/admin/insights/traders";
        Map<String,Object> material=new LinkedHashMap<>();material.put("sourceId","FED");material.put("format","RSS");material.put("verified",true);material.put("material","TEST_ONLY official headline boundary");material.put("content","<rss><channel><item><title>TEST_ONLY granted agent UUID</title><link>https://www.federalreserve.gov/newsevents/pressreleases/test-agent-uuid.htm</link></item></channel></rss>");ok(send("POST",newsApi+"/import",writer,material));
        String newsId=ok(send("GET",newsApi,writer,null)).path("content").get(0).path("articleId").asText();
        Map<String,Object> data=new LinkedHashMap<>();data.put("name","TEST_ONLY granted agent UUID");data.put("currency","USD");data.put("statisticStart","2020-01-01T00:00:00Z");data.put("statisticEnd","2021-01-01T00:00:00Z");
        Map<String,Object> create=new LinkedHashMap<>();create.put("data",data);create.put("reason","TEST_ONLY agent scope regression");String traderId=ok(send("POST",tradersApi,writer,create)).path("traderId").asText();
        List<String> details=Arrays.asList(API+"/"+calendarId,newsApi+"/"+newsId,tradersApi+"/"+traderId);
        UserAccount agent=insightAgent();String agentToken=insightAgentToken(agent);
        for(String path:details)assertEquals(403,send("GET",path,agentToken,null).getResponse().getStatus(),path);
        grantInsights(agent);
        for(String path:Arrays.asList(API,newsApi,tradersApi))ok(send("GET",path,agentToken,null));
        for(String path:details)ok(send("GET",path,agentToken,null));
        for(String path:Arrays.asList(API+"/"+calendarId+"/audit",tradersApi+"/"+traderId+"/preview",tradersApi+"/"+traderId+"/equity",tradersApi+"/"+traderId+"/history",tradersApi+"/"+traderId+"/import-template?kind=history",newsApi+"/audit"))ok(send("GET",path,agentToken,null));
        long calendarAudits=db.queryForObject("SELECT COUNT(*) FROM calendar_audit",Long.class),newsAudits=db.queryForObject("SELECT COUNT(*) FROM news_audit",Long.class),traderAudits=db.queryForObject("SELECT COUNT(*) FROM trader_audit",Long.class);
        JsonNode calendarBefore=detail(calendarId);Map<String,Object> editCalendar=new LinkedHashMap<>();editCalendar.put("rowVersion",calendarBefore.path("rowVersion").asLong());editCalendar.put("reason","TEST_ONLY agent cannot write");editCalendar.put("manualLock",true);editCalendar.put("data",calendarBefore);assertEquals(403,send("PUT",details.get(0),agentToken,editCalendar).getResponse().getStatus());
        JsonNode articleBefore=ok(send("GET",details.get(1),writer,null));Map<String,Object> editNews=new LinkedHashMap<>();editNews.put("rowVersion",articleBefore.path("rowVersion").asLong());editNews.put("reason","TEST_ONLY agent cannot write");editNews.put("category","MACRO");editNews.put("hidden",false);assertEquals(403,send("PUT",details.get(1),agentToken,editNews).getResponse().getStatus());
        assertEquals(403,send("POST",tradersApi,agentToken,create).getResponse().getStatus());
        assertEquals(calendarBefore.path("rowVersion"),detail(calendarId).path("rowVersion"));assertTrue(ok(send("GET",details.get(1),writer,null)).path("hidden").asBoolean());
        assertEquals(calendarAudits,db.queryForObject("SELECT COUNT(*) FROM calendar_audit",Long.class));assertEquals(newsAudits,db.queryForObject("SELECT COUNT(*) FROM news_audit",Long.class));assertEquals(traderAudits,db.queryForObject("SELECT COUNT(*) FROM trader_audit",Long.class));
        String otherToken;TenantContext.clear();try(TenantContext.Scope ignored=TenantContext.open(2L)){UserAccount other=insightAgent();grantInsights(other);otherToken=insightAgentToken(other);}finally{TenantContext.open(1L);}
        for(String path:details)assertEquals(404,send("GET",path,otherToken,null).getResponse().getStatus(),path);
        for(UserMenu binding:userMenus.findByTenantIdAndUserId(1L,agent.getId()))userMenus.deleteByTenantIdAndId(1L,binding.getId());userMenus.flush();for(String path:details)assertEquals(403,send("GET",path,agentToken,null).getResponse().getStatus(),path);
    }
}

package com.gtcfesk.exchange.insights;

import com.fasterxml.jackson.databind.*;import com.gtcfesk.exchange.insights.news.*;
import com.gtcfesk.exchange.admin.*;import com.gtcfesk.exchange.common.JwtUtil;import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.*;import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;import org.springframework.test.web.servlet.*;import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Uses the existing isolated app fixture; real MVC/JWT/permissions/SQL, no production database. */
@org.springframework.context.annotation.Import(BootTenantFixture.class)
@org.junit.jupiter.api.extension.ExtendWith(TenantOneFixture.class)
@TestPropertySource(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:multitenant-market-test.sql","spring.redis.host=127.0.0.1","spring.redis.port=1","spring.redis.password=","platform.base-domain=mt705.test","platform.admin-origin=https://admin.mt705.test","platform.control-origin=https://control.mt705.test","security.trusted-proxies=127.0.0.1","market.exchange.stream-enabled=false","market.depth.enabled=false","calendar.sync.enabled=false","calendar.reminders.enabled=false","news.sync.enabled=false","news.gdelt.enabled=false","news.newsdata.key=","news.newsdata.license-approved=false"})
@SpringBootTest(classes=CalendarIntegrationTest.App.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:news_stage3;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=update","spring.jpa.show-sql=false","spring.jpa.open-in-view=false","logging.level.root=ERROR","jwt.secret=bmV3cy10ZXN0LW9ubHktbm90LXByb2R1Y3Rpb24tc2VjcmV0"})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NewsIntegrationTest {
    @MockBean com.gtcfesk.exchange.market.ForexQuoteMarketService quotes;
    @MockBean com.gtcfesk.exchange.market.MarketInstrumentCatalog catalog;
    @MockBean com.gtcfesk.exchange.market.MarketOrderProcessor processor;
    @MockBean com.gtcfesk.exchange.market.RedisMarketService redis;
    @MockBean com.gtcfesk.exchange.service.EmailService email;
    @MockBean com.gtcfesk.exchange.security.RegistrationSecurity registration;
    @SpyBean NewsSync sync;@Autowired NewsService news;@Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;
    @Autowired AdminUserRepository admins;@Autowired AdminRoleRepository roles;@Autowired AdminRoleMenuRepository grants;@Autowired AdminMenuRepository menus;@Autowired JwtUtil jwt;
    @Autowired com.gtcfesk.exchange.control.BackendLoginRegistry names;@LocalServerPort int port;
    String writer,reader,denied,tenantTwo;static final String API="/api/admin/insights/news",PUBLIC="/api/insights/news";
    static final Path REPORTS=Paths.get("../reports/news-20261003");
    final Map<String,NewsSync.HttpResult> responses=new ConcurrentHashMap<>();
    String admin(String role){AdminUser a=new AdminUser();a.setAccount("news_qa_"+UUID.randomUUID());a.setEmail(a.getAccount()+"@example.invalid");a.setRole(role);a.setEnabled(true);a.setPasswordHash("news-test-only");a.setCurrentToken(UUID.randomUUID().toString());a=admins.saveAndFlush(a);names.register("ADMIN",a.getId(),a.getAccount());Map<String,Object> claims=new HashMap<>();claims.put("userType","admin");claims.put("sid",a.getCurrentToken());claims.put("credential",jwt.credentialKey(a.getPasswordHash()));return jwt.generateToken("admin-"+a.getId(),claims);}
    String limited(boolean view){AdminRole role=new AdminRole();role.setRoleCode("news_"+UUID.randomUUID().toString().substring(0,8));role.setRoleName(role.getRoleCode());role.setStatus("active");role=roles.saveAndFlush(role);if(view){AdminRoleMenu g=new AdminRoleMenu();g.setRoleId(role.getId());g.setMenuId(menus.findByMenuCode("news").get().getId());grants.saveAndFlush(g);}return admin(role.getRoleCode());}
    @BeforeEach void initialize()throws Exception {
        try(java.sql.Connection c=db.getDataSource().getConnection()){assertTrue(c.getMetaData().getURL().startsWith("jdbc:h2:mem:news_stage3"));}
        db.update("DELETE FROM news_article");db.update("DELETE FROM news_source_setting");db.update("DELETE FROM news_audit");
        db.update("UPDATE news_feed SET status='NEVER_FETCHED',last_attempt=NULL,last_success=NULL,content_as_of=NULL,next_attempt=NULL,lease_until=NULL,budget_date=NULL,requests_today=0,failures=0,item_count=0,skipped_count=0,http_status=NULL,last_error=NULL,parsed_json=NULL,etag=NULL,last_modified=NULL,response_hash=NULL");
        writer=admin("super_admin");reader=limited(true);denied=limited(false);
        TenantContext.clear();try(TenantContext.Scope ignored=TenantContext.open(2L)){tenantTwo=admin("super_admin");}finally{TenantContext.open(1L);}
        Files.createDirectories(REPORTS);clearInvocations(sync);
        if(!Boolean.getBoolean("news.live"))doAnswer(call->{String source=call.getArgument(0);NewsSync.HttpResult r=responses.get(source);if(r==null){r=new NewsSync.HttpResult();r.status=200;r.body=NewsParserTest.raw(source);r.etag="test-etag";}return r;}).when(sync).fetch(anyString(),any(NewsFeed.class));
    }
    MvcResult send(String method,String path,String token,Object body)throws Exception{org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r=method.equals("GET")?get(path):method.equals("PUT")?put(path):post(path);if(token!=null)r.header("Authorization","Bearer "+token);if(body!=null)r.contentType("application/json").content(json.writeValueAsString(body));return mvc.perform(r).andReturn();}
    JsonNode ok(MvcResult r)throws Exception{assertEquals(200,r.getResponse().getStatus(),r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));return json.readTree(r.getResponse().getContentAsByteArray());}
    JsonNode first()throws Exception{return ok(send("GET",PUBLIC,null,null)).path("content").get(0);}
    void seed(){for(String s:Arrays.asList("FED","BEA","ECB")){sync.sync(s);news.importCached(s);}}
    Map<String,Object> edit(JsonNode a,boolean hide){Map<String,Object> b=new HashMap<>();b.put("rowVersion",a.path("rowVersion").asLong());b.put("category",a.path("category").asText());b.put("sortOrder",17);b.put("hidden",hide);b.put("reason","TEST_ONLY 文章核验流程");return b;}
    Map<String,Object> source(JsonNode s,boolean enabled){Map<String,Object> b=new HashMap<>();b.put("rowVersion",s.path("rowVersion").asLong());b.put("enabled",enabled);b.put("licenseReviewed",true);b.put("licenseEvidence","官方条款核验与归属范围；TEST_ONLY 配置操作");b.put("reason","TEST_ONLY 来源开关验证");return b;}
    JsonNode source(String id)throws Exception{for(JsonNode s:ok(send("GET",API+"/sources",writer,null)))if(id.equals(s.path("sourceId").asText()))return s;throw new AssertionError(id);}
    @Test @Order(1) void officialReplayPersistenceListDetailFilterPagingAndNoPerVisitorFetch()throws Exception {
        seed();JsonNode list=ok(send("GET",PUBLIC,null,null));assertEquals(61,list.path("totalElements").asInt());assertEquals(20,list.path("content").size());assertEquals(3,list.path("sources").size());assertEquals("FRESH",list.path("dataStatus").asText());
        JsonNode a=first(),d=ok(send("GET",PUBLIC+"/"+a.path("articleId").asText(),null,null));assertEquals(a.path("articleId"),d.path("articleId"));assertFalse(d.path("hidden").asBoolean());assertFalse(d.has("body"));assertFalse(d.has("image"));
        JsonNode second=ok(send("GET",PUBLIC+"?page=1&size=1",null,null));assertEquals(1,second.path("content").size());assertNotEquals(ok(send("GET",PUBLIC+"?size=1",null,null)).path("content").get(0).path("articleId"),second.path("content").get(0).path("articleId"));
        assertEquals(15,ok(send("GET",PUBLIC+"?sourceId=FED&language=en",null,null)).path("totalElements").asInt());assertEquals(0,ok(send("GET",PUBLIC+"?category=CRYPTO",null,null)).path("totalElements").asInt());assertEquals("EMPTY",ok(send("GET",PUBLIC+"?category=CRYPTO",null,null)).path("dataStatus").asText());
        assertEquals(0,news.importCached("FED"));for(int i=0;i<4;i++)ok(send("GET",PUBLIC,null,null));verify(sync,times(1)).fetch(eq("FED"),any(NewsFeed.class));
        json.writeValue(REPORTS.resolve("api-replay-list.json").toFile(),list);json.writeValue(REPORTS.resolve("api-replay-detail.json").toFile(),d);json.writeValue(REPORTS.resolve("source-replay-health.json").toFile(),news.sources(true));
    }
    @Test @Order(2) void hidingRestoringSortingSourceToggleAndAuditTakeEffectImmediately()throws Exception {
        seed();JsonNode a=first();String id=a.path("articleId").asText();JsonNode hidden=ok(send("PUT",API+"/"+id,writer,edit(a,true)));assertEquals(404,send("GET",PUBLIC+"/"+id,null,null).getResponse().getStatus());assertTrue(ok(send("GET",API+"?hidden=true",writer,null)).path("totalElements").asInt()>0);
        JsonNode restored=ok(send("PUT",API+"/"+id,writer,edit(hidden,false)));assertEquals(id,first().path("articleId").asText());assertEquals(409,send("PUT",API+"/"+id,writer,edit(a,true)).getResponse().getStatus());
        String sid=a.path("sourceId").asText();ok(send("PUT",API+"/sources/"+sid,writer,source(source(sid),false)));assertEquals(404,send("GET",PUBLIC+"/"+id,null,null).getResponse().getStatus());assertEquals(0,ok(send("GET",PUBLIC+"?sourceId="+sid,null,null)).path("totalElements").asInt());
        ok(send("PUT",API+"/sources/"+sid,writer,source(source(sid),true)));assertEquals(id,ok(send("GET",PUBLIC+"/"+id,null,null)).path("articleId").asText());assertTrue(ok(send("GET",API+"/audit",writer,null)).size()>=4);assertFalse(restored.path("hidden").asBoolean());
    }
    @Test @Order(3) void menuActionPermissionsAndWriteValidation()throws Exception {
        seed();JsonNode a=first();String id=a.path("articleId").asText();assertEquals(401,send("GET",API,null,null).getResponse().getStatus());assertEquals(403,send("GET",API,denied,null).getResponse().getStatus());ok(send("GET",API,reader,null));
        assertEquals(403,send("PUT",API+"/"+id,reader,edit(a,true)).getResponse().getStatus());assertEquals(403,send("POST",API+"/sources/FED/sync",reader,new HashMap<>()).getResponse().getStatus());assertEquals(403,send("PUT",API+"/sources/FED",reader,source(source("FED"),false)).getResponse().getStatus());
        Map<String,Object> b=edit(a,false);b.put("category","CRYPTO");assertEquals(400,send("PUT",API+"/"+id,writer,b).getResponse().getStatus());b.put("category","MACRO");b.put("reason","");assertEquals(400,send("PUT",API+"/"+id,writer,b).getResponse().getStatus());
        assertEquals(400,send("PUT",API+"/sources/NEWSDATA",writer,source(source("NEWSDATA"),true)).getResponse().getStatus());for(String q:Arrays.asList("size=101","page=-1","category=INVALID","sourceId=INVALID","language=bad!","from=2026-10-03T00:00:00Z&to=2026-10-01T00:00:00Z"))assertEquals(400,send("GET",PUBLIC+"?"+q,null,null).getResponse().getStatus());assertEquals(401,send("PUT",PUBLIC+"/"+id,null,edit(a,true)).getResponse().getStatus());
    }
    @Test @Order(4) void tenantAndEnvironmentIsolationWithSharedPublicMetadata()throws Exception {
        seed();String id=first().path("articleId").asText();assertEquals(404,send("GET",API+"/"+id,tenantTwo,null).getResponse().getStatus());
        TenantContext.clear();try(TenantContext.Scope ignored=TenantContext.open(2L)){assertEquals(15,news.importCached("FED"));}finally{TenantContext.open(1L);}
        assertEquals(15,ok(send("GET",API+"?sourceId=FED",tenantTwo,null)).path("totalElements").asInt());JsonNode fed=ok(send("GET",PUBLIC+"?sourceId=FED",null,null)).path("content").get(0);String fid=fed.path("articleId").asText();ok(send("PUT",API+"/sources/FED",writer,source(source("FED"),false)));assertEquals(404,send("GET",PUBLIC+"/"+fid,null,null).getResponse().getStatus());ok(send("GET",API+"/"+fid,tenantTwo,null));
        long before=db.queryForObject("SELECT COUNT(*) FROM news_article WHERE tenant_id=1 AND environment='REAL'",Long.class);
        db.update("INSERT INTO news_article(tenant_id,environment,article_id,source_id,category,language,published_at,discovered_at,updated_at,hidden,sort_order,data_json,upstream_hash,row_version) SELECT tenant_id,'DEMO',article_id,source_id,category,language,published_at,discovered_at,updated_at,hidden,sort_order,data_json,upstream_hash,row_version FROM news_article WHERE tenant_id=1 AND environment='REAL'");
        assertEquals(before,ok(send("GET",API,writer,null)).path("totalElements").asLong());assertEquals("REAL",first().path("environment").asText());
    }
    @Test @Order(5) void verifiedManualImportRemainsHiddenAndUnknownTimeIsNotInvented()throws Exception {
        Map<String,Object> b=new HashMap<>();b.put("sourceId","FED");b.put("format","RSS");b.put("content",NewsParserTest.rss("TEST_ONLY 未知时间标题","https://www.federalreserve.gov/newsevents/pressreleases/test-news.htm",null));b.put("material","TEST_ONLY 合法材料核验流程");b.put("verified",true);
        JsonNode imported=ok(send("POST",API+"/import",writer,b));assertEquals(1,imported.path("changed").asInt());JsonNode a=ok(send("GET",API,writer,null)).path("content").get(0);assertEquals("UNKNOWN",a.path("timePrecision").asText());assertTrue(a.path("publishedAt").isNull());String id=a.path("articleId").asText();assertEquals(404,send("GET",PUBLIC+"/"+id,null,null).getResponse().getStatus());
        ok(send("PUT",API+"/"+id,writer,edit(a,false)));assertEquals("TEST_ONLY 未知时间标题",ok(send("GET",PUBLIC+"/"+id,null,null)).path("title").asText());assertEquals(0,ok(send("GET",PUBLIC+"?from=2026-10-01T00:00:00Z&to=2026-10-03T23:59:59Z",null,null)).path("totalElements").asInt());assertEquals(0,ok(send("POST",API+"/import",writer,b)).path("changed").asInt());
        b.put("verified",false);assertEquals(400,send("POST",API+"/import",writer,b).getResponse().getStatus());b.put("verified",true);ok(send("PUT",API+"/sources/FED",writer,source(source("FED"),false)));assertEquals(400,send("POST",API+"/import",writer,b).getResponse().getStatus());assertEquals(403,send("POST",API+"/import",reader,b).getResponse().getStatus());
    }
    void allow(String id){db.update("UPDATE news_feed SET next_attempt=NULL,lease_until=NULL WHERE id=?","REAL:"+id);}
    NewsSync.HttpResult response(int status,String body,String retry){NewsSync.HttpResult r=new NewsSync.HttpResult();r.status=status;r.body=body;r.retryAfter=retry;return r;}
    @Test @Order(6) void notModifiedRateLimitedTimeoutStaleAndDailyBudgetKeepOldCache()throws Exception {
        seed();long count=db.queryForObject("SELECT COUNT(*) FROM news_article",Long.class);String hash=String.valueOf(sync.status("FED").get("responseHash"));allow("FED");responses.put("FED",response(304,null,null));sync.sync("FED");assertEquals("OK",sync.status("FED").get("status"));assertEquals(hash,sync.status("FED").get("responseHash"));assertEquals(0,news.importCached("FED"));
        allow("FED");responses.put("FED",response(429,null,"7200"));sync.sync("FED");assertEquals("RATE_LIMITED",sync.status("FED").get("status"));assertTrue(((Instant)sync.status("FED").get("nextAttempt")).isAfter(Instant.now().plusSeconds(7100)));assertEquals(false,sync.sync("FED").get("requested"));assertEquals(count,db.queryForObject("SELECT COUNT(*) FROM news_article",Long.class));
        allow("FED");doThrow(new java.net.SocketTimeoutException("TEST_ONLY transport error with apikey=not-a-real-key")).when(sync).fetch(eq("FED"),any(NewsFeed.class));sync.sync("FED");assertEquals("ERROR",sync.status("FED").get("status"));assertFalse(sync.status("FED").toString().contains("apikey"));
        db.update("UPDATE news_feed SET last_success=? WHERE id='REAL:BEA'",java.sql.Timestamp.from(Instant.now().minusSeconds(7201)));assertTrue((Boolean)sync.status("BEA").get("stale"));assertEquals("PARTIAL",ok(send("GET",PUBLIC,null,null)).path("dataStatus").asText());
        allow("ECB");db.update("UPDATE news_feed SET requests_today=24,budget_date=CURRENT_DATE WHERE id='REAL:ECB'");assertEquals(false,sync.sync("ECB").get("requested"));assertEquals("BUDGET_EXHAUSTED",sync.status("ECB").get("status"));
    }
    @Test @Order(7) void emptySourceAnd304WithoutCacheRemainHonest(){responses.put("FED",response(304,null,null));sync.sync("FED");assertEquals("ERROR",sync.status("FED").get("status"));assertEquals("304_WITHOUT_CACHE",sync.status("FED").get("lastError"));responses.put("BEA",response(200,"<rss><channel/></rss>",null));sync.sync("BEA");assertEquals("EMPTY",sync.status("BEA").get("status"));assertEquals(0,news.importCached("BEA"));assertEquals(false,sync.sync("GDELT").get("requested"));assertEquals(false,sync.sync("NEWSDATA").get("requested"));}
    @Test @Order(8) void sourceRequestLeaseAndConcurrentImportAreIdempotent()throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);try{Future<Map<String,Object>> a=pool.submit(()->sync.sync("FED")),b=pool.submit(()->sync.sync("FED"));boolean aa=(Boolean)a.get().get("requested"),bb=(Boolean)b.get().get("requested");assertNotEquals(aa,bb);verify(sync,times(1)).fetch(eq("FED"),any(NewsFeed.class));
            Callable<Integer> task=()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){return news.importCached("FED");}};Future<Integer> x=pool.submit(task),y=pool.submit(task);assertEquals(15,x.get()+y.get());assertEquals(15,db.queryForObject("SELECT COUNT(*) FROM news_article",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test @Order(9) void noSecretsInSourceApiAndPlatformAnnouncementsRetainTheirResponsibility()throws Exception {
        JsonNode s=ok(send("GET",API+"/sources",writer,null));assertEquals(5,s.size());assertFalse(s.toString().contains("apikey="));assertFalse(s.toString().contains("newsdata.key"));assertFalse(s.toString().contains("parsedJson"));assertEquals("UNCONFIGURED",source("NEWSDATA").path("status").asText());assertEquals(200,send("GET","/api/user/announcements",null,null).getResponse().getStatus());assertEquals(400,send("POST",API+"/sources/arbitrary/sync",writer,null).getResponse().getStatus());}
    // A bounded run can cross one UTC-minute boundary; 1201 reads exhaust at least one 600-read bucket.
    @Test @Order(99) @Timeout(55) void publicReadBudgetIsLocalNotAnUpstreamAmplifier()throws Exception{int result=0;for(int i=0;i<1201;i++){result=send("GET",PUBLIC,null,null).getResponse().getStatus();if(result==429)break;}assertEquals(429,result);verify(sync,never()).fetch(anyString(),any(NewsFeed.class));}
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="news.live",matches="true")
    void liveOfficialAdaptersIntoIsolatedDatabase()throws Exception {
        List<Map<String,Object>> results=new ArrayList<>();for(String s:Arrays.asList("FED","BEA","ECB")){Map<String,Object> r=sync.sync(s);r.put("changed",news.importCached(s));results.add(r);}json.writeValue(REPORTS.resolve("source-live-adapter.json").toFile(),results);
        JsonNode list=ok(send("GET",PUBLIC,null,null));json.writeValue(REPORTS.resolve("api-live-list.json").toFile(),list);assertTrue(list.path("totalElements").asInt()>0);json.writeValue(REPORTS.resolve("api-live-detail.json").toFile(),first());
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="news.browserHold",matches="true")
    void browserAcceptanceWindow()throws Exception {
        seed();Map<String,Object> info=new LinkedHashMap<>();info.put("port",port);info.put("writer",writer);info.put("reader",reader);info.put("denied",denied);info.put("testOnly",true);Path p=REPORTS.resolve("browser-access.tmp.json");json.writeValue(p.toFile(),info);try{Thread.sleep(1200000);}finally{Files.deleteIfExists(p);}
    }
}

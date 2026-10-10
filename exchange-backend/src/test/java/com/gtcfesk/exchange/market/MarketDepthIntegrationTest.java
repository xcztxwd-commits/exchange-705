package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.common.JwtUtil;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.tenant.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real MVC/JWT/permissions/SQL/cache and actual downstream socket, isolated H2 and loopback provider fixtures. */
@org.springframework.context.annotation.Import(BootTenantFixture.class)
@org.junit.jupiter.api.extension.ExtendWith(TenantOneFixture.class)
@TestPropertySource(properties={"spring.sql.init.mode=always","spring.sql.init.schema-locations=classpath:multitenant-market-test.sql","spring.redis.host=127.0.0.1","spring.redis.port=1","spring.redis.password=","platform.base-domain=mt705.test","platform.admin-origin=https://admin.mt705.test","platform.control-origin=https://control.mt705.test","security.trusted-proxies=127.0.0.1","market.exchange.stream-enabled=false","market.depth.enabled=true","market.depth.allow-test-sources=true"})
@SpringBootTest(classes=MarketDepthIntegrationTest.App.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:market_depth;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","spring.jpa.hibernate.ddl-auto=create-drop","spring.jpa.show-sql=false","spring.jpa.open-in-view=false","logging.level.root=ERROR","jwt.secret=ZGVwdGgtdGVzdC1vbmx5LW5vdC1hLXJlYWwtc2VjcmV0LWtleQ=="})
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class MarketDepthIntegrationTest {
    @org.springframework.context.annotation.Configuration @org.springframework.boot.test.context.TestComponent
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories(basePackages="com.gtcfesk.exchange",repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
    @org.springframework.boot.autoconfigure.domain.EntityScan("com.gtcfesk.exchange")
    @org.springframework.context.annotation.ComponentScan(basePackages="com.gtcfesk.exchange",excludeFilters={
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.CUSTOM,classes=org.springframework.boot.context.TypeExcludeFilter.class),
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,classes=com.gtcfesk.exchange.ExchangeBackendApplication.class),
        @org.springframework.context.annotation.ComponentScan.Filter(type=org.springframework.context.annotation.FilterType.REGEX,pattern="com\\.gtcfesk\\.exchange\\.support\\.UnifiedInbox.*Fixture.*")})
    static class App { }
    static final HttpServer upstream;
    static {try{upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);upstream.createContext("/",r->{
        String body=r.getRequestURI().getPath().endsWith("exchangeInfo")?"{\"symbols\":[{\"symbol\":\"BTCUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"BTC\",\"quoteAsset\":\"USDT\"},{\"symbol\":\"ETHUSDT\",\"status\":\"TRADING\",\"baseAsset\":\"ETH\",\"quoteAsset\":\"USDT\"}]}":MarketDepthTest.BOOK;
        byte[] data=body.getBytes(StandardCharsets.UTF_8);r.getResponseHeaders().add("Content-Type","application/json");r.sendResponseHeaders(200,data.length);r.getResponseBody().write(data);r.close();});upstream.start();}catch(Exception e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void endpoints(DynamicPropertyRegistry registry){String url="http://127.0.0.1:"+upstream.getAddress().getPort();registry.add("market.exchange.spot-url",()->url);registry.add("market.exchange.futures-url",()->url);}
    @AfterAll static void closeUpstream(){upstream.stop(0);}
    @MockBean ForexQuoteMarketService quotes;
    @MockBean MarketInstrumentCatalog catalog;
    @MockBean MarketOrderProcessor processor;
    @MockBean RedisMarketService redis;
    @MockBean com.gtcfesk.exchange.service.EmailService email;
    @MockBean com.gtcfesk.exchange.security.RegistrationSecurity registration;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TradingSymbolRepository symbols;
    @Autowired SystemConfigService configs;
    @Autowired MarketDepthService depth;
    @Autowired AdminUserRepository admins;
    @Autowired AdminRoleRepository roles;
    @Autowired AdminRoleMenuRepository grants;
    @Autowired AdminMenuRepository menus;
    @Autowired JwtUtil jwt;
    @Autowired com.gtcfesk.exchange.control.BackendLoginRegistry names;
    @LocalServerPort int port;
    String writer,reader,denied,tenantTwo;
    String token(String role){AdminUser a=new AdminUser();a.setAccount("depth_qa_"+UUID.randomUUID());a.setEmail(a.getAccount()+"@example.invalid");a.setRole(role);a.setEnabled(true);a.setPasswordHash("depth-test-only");a.setCurrentToken(UUID.randomUUID().toString());a=admins.saveAndFlush(a);names.register("ADMIN",a.getId(),a.getAccount());Map<String,Object> claims=new HashMap<>();claims.put("userType","admin");claims.put("sid",a.getCurrentToken());claims.put("credential",jwt.credentialKey(a.getPasswordHash()));return jwt.generateToken("admin-"+a.getId(),claims);}
    String limited(boolean view){AdminRole role=new AdminRole();role.setRoleCode("depth_"+UUID.randomUUID().toString().substring(0,8));role.setRoleName(role.getRoleCode());role.setStatus("active");role=roles.saveAndFlush(role);if(view){AdminRoleMenu g=new AdminRoleMenu();g.setRoleId(role.getId());g.setMenuId(menus.findByMenuCode("settings").get().getId());grants.saveAndFlush(g);}return token(role.getRoleCode());}
    void seed(String code,String category){if(symbols.findByTenantIdAndSymbol(TenantContext.requireTenantId(),code).isPresent())return;TradingSymbol s=MarketDepthTest.symbol(1,code,category);s.setId(null);s.setName("隔离深度测试 "+code);s.setMarketSource("exchange");symbols.saveAndFlush(s);}
    @BeforeEach void setup(){configs.saveConfig(MarketDepthService.ENABLED_KEY,"true","isolated test");depth.disableTenant(1L);seed("BTCUSDT","Crypto");seed("ETHUSDT","Crypto");seed("EURUSD","Forex");seed("FAKEUSDT","Crypto");seed("PRIVATEUSDT","Crypto");writer=token("super_admin");reader=limited(true);denied=limited(false);
        TenantContext.clear();try(TenantContext.Scope scope=TenantContext.open(2L)){configs.saveConfig(MarketDepthService.ENABLED_KEY,"true","isolated test");depth.disableTenant(2L);seed("BTCUSDT","Crypto");tenantTwo=token("super_admin");}finally{TenantContext.open(1L);}}
    JsonNode getPublic(String symbol,String host)throws Exception{return json.readTree(mvc.perform(get("/api/market/depth/"+symbol).header("Host",host)).andReturn().getResponse().getContentAsString());}
    int save(String tk,String body)throws Exception{return mvc.perform(put("/api/admin/market/depth/settings").header("Authorization","Bearer "+tk).contentType("application/json").content(body)).andReturn().getResponse().getStatus();}
    @Test void publicHttpPrecisionVisibilityCacheAndErrors()throws Exception{
        assertEquals(400,mvc.perform(get("/api/market/depth/BTCUSDT?levels=0")).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(get("/api/market/depth/BTCUSDT?marketType=margin")).andReturn().getResponse().getStatus());
        assertEquals(404,mvc.perform(get("/api/market/depth/UNKNOWN")).andReturn().getResponse().getStatus());
        assertEquals("UNSUPPORTED",getPublic("EURUSD",BootTenantFixture.FRONT).path("status").asText());
        assertEquals("market_type_mismatch",json.readTree(mvc.perform(get("/api/market/depth/BTCUSDT?marketType=swap")).andReturn().getResponse().getContentAsString()).path("reason").asText());
        getPublic("BTCUSDT",BootTenantFixture.FRONT);depth.tick();MarketDepthTest.until(()->"LIVE".equals(depth.read("BTCUSDT",20,null,"rest:integration").get("status")));
        JsonNode result=getPublic("BTCUSDT",BootTenantFixture.FRONT);assertEquals("100.00000000000000000001",result.path("bids").path(0).path("price").asText());assertEquals("REST_POLL",result.path("refreshMethod").asText());assertTrue(result.path("cacheHit").asBoolean());assertTrue(result.path("sourceAsOf").isNull());assertEquals("BASE_ASSET",result.path("quantityUnit").asText());
        assertEquals("LIVE",getPublic("BTCUSDT","b.mt705.test").path("status").asText());assertEquals("UNSUPPORTED",getPublic("PRIVATEUSDT","b.mt705.test").path("status").asText());
        getPublic("FAKEUSDT",BootTenantFixture.FRONT);depth.tick();MarketDepthTest.until(()->"UNSUPPORTED".equals(depth.read("FAKEUSDT",20,null,"rest:fake").get("status")));
        assertEquals(200,save(writer,"{\"enabled\":false}"));assertEquals("DISABLED",getPublic("BTCUSDT",BootTenantFixture.FRONT).path("status").asText());assertEquals("LIVE",getPublic("BTCUSDT","b.mt705.test").path("status").asText());
    }
    @Test void permissionsStrictSavePersistenceAndTenantIsolation()throws Exception{
        assertEquals(401,mvc.perform(get("/api/admin/market/depth/status")).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/admin/market/depth/status").header("Authorization","Bearer "+denied)).andReturn().getResponse().getStatus());
        assertEquals(200,mvc.perform(get("/api/admin/market/depth/status").header("Authorization","Bearer "+reader)).andReturn().getResponse().getStatus());assertEquals(403,save(reader,"{\"enabled\":false}"));
        assertEquals(400,save(writer,"{\"enabled\":\"false\"}"));assertEquals(400,save(writer,"{\"enabled\":false,\"provider\":\"okx\"}"));
        assertEquals(200,save(writer,"{\"enabled\":false}"));assertEquals("false",configs.getConfigValue(MarketDepthService.ENABLED_KEY));
        JsonNode a=json.readTree(mvc.perform(get("/api/admin/market/depth/status").header("Authorization","Bearer "+writer)).andReturn().getResponse().getContentAsString());assertFalse(a.path("enabled").asBoolean());assertEquals("binance",a.path("provider").asText());
        JsonNode b=json.readTree(mvc.perform(get("/api/admin/market/depth/status").header("Authorization","Bearer "+tenantTwo)).andReturn().getResponse().getContentAsString());assertTrue(b.path("enabled").asBoolean());assertEquals(1,b.path("instruments").size());
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->configs.saveConfig(MarketDepthService.ENABLED_KEY,"yes","invalid"));
        assertEquals(200,save(writer,"{\"enabled\":true}"));assertEquals("true",configs.getConfigValue(MarketDepthService.ENABLED_KEY));
    }
    @Test void realDownstreamSocketSwitchDisableUnsubscribeAndDisconnect()throws Exception{
        BlockingQueue<JsonNode> messages=new LinkedBlockingQueue<>();CompletableFuture<okhttp3.WebSocket> opened=new CompletableFuture<>();
        okhttp3.OkHttpClient socketClient=new okhttp3.OkHttpClient();
        // The standard Tomcat client replaces Host from the loopback URI. Use the existing client that preserves the tenant virtual host.
        okhttp3.WebSocket session=socketClient.newWebSocket(new okhttp3.Request.Builder().url("ws://127.0.0.1:"+port+"/api/ws/market")
            .header("Host",BootTenantFixture.FRONT).header("X-Forwarded-Host",BootTenantFixture.FRONT).header("Origin","https://"+BootTenantFixture.FRONT).build(),new okhttp3.WebSocketListener(){
                @Override public void onOpen(okhttp3.WebSocket socket,okhttp3.Response response){opened.complete(socket);}
                @Override public void onFailure(okhttp3.WebSocket socket,Throwable failure,okhttp3.Response response){opened.completeExceptionally(failure);}
                @Override public void onMessage(okhttp3.WebSocket socket,String text){try{messages.add(json.readTree(text));}catch(Exception failure){throw new IllegalStateException(failure);}}
            });
        try{
            opened.get(8,TimeUnit.SECONDS);
            assertTrue(session.send("{\"action\":\"subscribeDepth\",\"symbol\":\"BTCUSDT\"}"));assertEquals("BTCUSDT",nextDepth(messages).path("data").path("symbol").asText());
            assertTrue(session.send("{\"action\":\"subscribeDepth\",\"symbol\":\"ETHUSDT\",\"levels\":1}"));messages.clear();JsonNode switched=nextDepth(messages);if(!"ETHUSDT".equals(switched.path("data").path("symbol").asText()))switched=nextDepth(messages);assertEquals("ETHUSDT",switched.path("data").path("symbol").asText());assertEquals(1,switched.path("data").path("requestedLevels").asInt());
            assertEquals(200,save(writer,"{\"enabled\":false}"));JsonNode disabled=nextDepth(messages);if(!"DISABLED".equals(disabled.path("data").path("status").asText()))disabled=nextDepth(messages);assertEquals("DISABLED",disabled.path("data").path("status").asText());assertEquals(0,disabled.path("data").path("bids").size());
            messages.clear();Thread.sleep(1300);assertTrue(messages.isEmpty(),"disabled data frames must stop after one state notification");
            save(writer,"{\"enabled\":true}");assertEquals("ETHUSDT",nextDepth(messages).path("data").path("symbol").asText());
            assertTrue(session.send("{\"action\":\"unsubscribeDepth\"}"));Thread.sleep(100);messages.clear();Thread.sleep(1200);assertTrue(messages.isEmpty());
            assertTrue(session.send("{\"action\":\"subscribeDepth\",\"symbol\":\"BTCUSDT\"}"));nextDepth(messages);
        }finally{session.close(1000,"fixture finished");socketClient.dispatcher().executorService().shutdown();socketClient.connectionPool().evictAll();}MarketDepthTest.until(()->((Number)depth.status().get("activeReferences")).intValue()==0);
    }
    JsonNode nextDepth(BlockingQueue<JsonNode> messages)throws Exception{long end=System.currentTimeMillis()+7000;while(System.currentTimeMillis()<end){JsonNode n=messages.poll(1,TimeUnit.SECONDS);if(n!=null&&"depth".equals(n.path("type").asText()))return n;}throw new AssertionError("No downstream depth frame");}
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="depth.browserHold",matches="true")
    void browserAcceptanceWindow()throws Exception{
        depth.read("BTCUSDT",20,null,"rest:browser");depth.tick();MarketDepthTest.until(()->"LIVE".equals(depth.read("BTCUSDT",20,null,"rest:browser").get("status")));
        Map<String,Object> access=new LinkedHashMap<>();access.put("port",port);access.put("writer",writer);access.put("reader",reader);access.put("denied",denied);
        Path directory=Paths.get("../reports/depth-20261003");Files.createDirectories(directory);Files.deleteIfExists(directory.resolve("browser.done"));json.writeValue(directory.resolve("browser-access.json").toFile(),access);
        long end=System.currentTimeMillis()+480000;try{while(!Files.exists(directory.resolve("browser.done"))&&System.currentTimeMillis()<end)Thread.sleep(300);assertTrue(Files.exists(directory.resolve("browser.done")),"browser acceptance must report completion");assertEquals("passed",new String(Files.readAllBytes(directory.resolve("browser.done")),java.nio.charset.StandardCharsets.UTF_8));}finally{Files.deleteIfExists(directory.resolve("browser-access.json"));}
    }
}

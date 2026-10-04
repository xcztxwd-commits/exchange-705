package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.control.Tenant;
import com.gtcfesk.exchange.control.TenantRepository;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.socket.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real MySQL/Redis acceptance; adapters are explicit and never replace market facts or transactions. */
class S2EngineAcceptanceTest {
    S2RuntimeMysqlTest f;
    @BeforeAll static void identity() throws Exception { S2RuntimeMysqlTest.identity(); }
    @BeforeEach void setup(){f=new S2RuntimeMysqlTest();f.setup();}
    @AfterEach void close(){f.close();}
    static void evidence(String name,Object value) throws Exception {
        String directory=System.getenv("S2_ACCEPTANCE_OUTPUT");assertNotNull(directory);
        Files.writeString(Path.of(directory,name+".json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }
    @Test void nestedSourceAndPumpShareOnePhysicalWorkQuota() {
        long now=System.currentTimeMillis();PersistentPriceControl.Task task=f.legacy(now-86400000,86400);
        f.store.transaction(()->{f.controls.sourceQuote(f.symbol,f.raw(now,true),now);f.controls.pump(f.symbol,f.raw(now,true),now,60000);f.controls.advance(f.symbol.getId(),now);return null;});
        assertEquals(512,f.count(task.id));assertTrue(Boolean.TRUE.equals(f.controls.display(f.symbol,Map.of(),now).get("engineLag")));
        f.controls.pump(f.symbol,f.raw(now,true),now,60000);assertEquals(1024,f.count(task.id));
    }
    ForexQuoteMarketService market(ControlHistoryStore store,PersistentPriceControl controls){
        ForexQuoteMarketService market=new ForexQuoteMarketService();TradingSymbolRepository symbols=mock(TradingSymbolRepository.class);
        when(symbols.findAllByTenantId(1L)).thenReturn(List.of(f.symbol));when(symbols.findByTenantIdAndId(1L,f.symbol.getId())).thenReturn(Optional.of(f.symbol));
        ReflectionTestUtils.setField(market,"symbols",symbols);ReflectionTestUtils.setField(market,"controls",controls);ReflectionTestUtils.setField(market,"controlHistory",store);
        ReflectionTestUtils.setField(market,"klineMerger",new ControlledKlineMerger(store));ReflectionTestUtils.setField(market,"redis",mock(RedisMarketService.class));market.refreshSymbols();return market;
    }
    @Test void actualGetWsAndOrderPriceSqlIsPureWhileEngineCommitsSeparately() throws Exception {
        long now=System.currentTimeMillis();PersistentPriceControl.Task task=f.legacy(now-10000,600);
        f.controls.pump(f.symbol,f.raw(now,true),now,60000);
        try(Connection connection=S2RuntimeMysqlTest.data.getConnection()){
            SingleConnectionDataSource source=new SingleConnectionDataSource(connection,true);JdbcTemplate readDb=new JdbcTemplate(source);
            long connectionId=readDb.queryForObject("SELECT CONNECTION_ID()",Long.class);
            Timestamp from=readDb.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class);
            ControlHistoryStore reader=new ControlHistoryStore(readDb,new DataSourceTransactionManager(source));PersistentPriceControl pure=new PersistentPriceControl(reader);
            ForexQuoteMarketService market=market(reader,pure);
            MarketPriceController http=new MarketPriceController();ReflectionTestUtils.setField(http,"marketService",market);
            org.springframework.test.web.servlet.MockMvc mvc=MockMvcBuilders.standaloneSetup(http).build();
            Tenant tenant=new Tenant();tenant.setId(1L);tenant.setStatus("ACTIVE");tenant.setDomainVerified(true);tenant.setFrontendHost("s2.invalid");
            TenantRepository tenants=mock(TenantRepository.class);when(tenants.findById(1L)).thenReturn(Optional.of(tenant));
            MarketWebSocketHandler ws=new MarketWebSocketHandler();ReflectionTestUtils.setField(ws,"marketService",market);ReflectionTestUtils.setField(ws,"tenants",tenants);
            WebSocketSession session=mock(WebSocketSession.class);when(session.getAttributes()).thenReturn(Map.of("tenantId",1L,"frontendHost","s2.invalid"));when(session.isOpen()).thenReturn(true);
            ws.afterConnectionEstablished(session);ws.handleTextMessage(session,new TextMessage("{\"action\":\"subscribe\",\"symbols\":[\""+f.symbol.getSymbol()+"\"]}"));
            ExecutorService engine=Executors.newSingleThreadExecutor();
            try {
                CountDownLatch started=new CountDownLatch(1);
                Future<?> writer=engine.submit(()->{try(TenantContext.Scope scope=TenantContext.open(1L)){
                    for(int i=1;i<=20;i++){long tick=System.currentTimeMillis();f.controls.pump(f.symbol,f.raw(tick,true),tick,60000);started.countDown();Thread.sleep(50);}
                }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}});
                assertTrue(started.await(10,TimeUnit.SECONDS));
                for(int i=0;i<40;i++){
                    assertEquals(200,mvc.perform(get("/api/market/price/"+f.symbol.getSymbol())).andReturn().getResponse().getStatus());
                    ws.push();assertNotNull(market.freshPrice(f.symbol.getSymbol()));market.controlStatus(f.symbol.getId());
                    new ControlledKlineMerger(reader).merge(f.symbol.getId(),"1m",20,now,Map.of("data",Map.of("kline_list",List.of())),null);
                    Thread.sleep(10);
                }
                writer.get(30,TimeUnit.SECONDS);
                Map<String,Object> committed=pure.display(f.symbol,Map.of(),now);Object expiry=committed.get("executionExpiresAt"),version=committed.get("quoteVersion");long samples=f.count(task.id);
                for(int i=0;i<40;i++){
                    assertEquals(200,mvc.perform(get("/api/market/price/"+f.symbol.getSymbol())).andReturn().getResponse().getStatus());
                    ws.push();assertNotNull(market.freshPrice(f.symbol.getSymbol()));market.controlStatus(f.symbol.getId());
                    new ControlledKlineMerger(reader).merge(f.symbol.getId(),"1m",20,now,Map.of("data",Map.of("kline_list",List.of())),null);
                }
                assertEquals(samples,f.count(task.id));assertEquals(expiry,pure.display(f.symbol,Map.of(),now).get("executionExpiresAt"));assertEquals(version,pure.display(f.symbol,Map.of(),now).get("quoteVersion"));
                List<String> sql=f.store.db.queryForList("SELECT argument FROM mysql.general_log WHERE thread_id=? AND event_time>=? AND command_type='Query' ORDER BY event_time",String.class,connectionId,from);
                assertFalse(sql.isEmpty());
                for(String query:sql){String lower=query.trim().toLowerCase(Locale.ROOT);assertFalse(lower.matches("(?s).*(for update|lock in share mode).*"),query);assertFalse(lower.matches("(?s)^(insert|update|delete|replace|alter|create|truncate).*"),query);}
                evidence("pure-read-sql",Map.of("mysqlConnectionId",connectionId,"actualQueries",sql,"httpRequests",80,"wsPushCycles",80,"orderPriceCalls",80,"concurrentEngineCommits",20,"committedSamples",samples,"expiry",expiry,"version",version));
                verify(session,timeout(5000).atLeastOnce()).sendMessage(any());
            } finally {engine.shutdownNow();ws.destroy();market.stop();}
        }
    }
    @Test void redisRealCacheLossRestartAndLateCallbackCannotOverwrite() throws Exception {
        long now=System.currentTimeMillis();f.controls.pump(f.symbol,f.raw(now,true),now,60000);
        LettuceConnectionFactory connection=new LettuceConnectionFactory(S2RuntimeMysqlTest.ownedRedisConfiguration(),org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration.builder().commandTimeout(java.time.Duration.ofSeconds(1)).build());connection.afterPropertiesSet();StringRedisTemplate redis=new StringRedisTemplate(connection);
        RedisMarketService first=new RedisMarketService(),late=new RedisMarketService();ReflectionTestUtils.setField(first,"redisTemplate",redis);ReflectionTestUtils.setField(late,"redisTemplate",redis);
        String symbol="acceptance:"+f.symbol.getId(),key="tenant:1:market:price:"+symbol;
        try {
            Map<String,Object> old=f.controls.display(f.symbol,Map.of(),now);f.controls.pump(f.symbol,f.raw(now+1000,true),now+1000,60000);Map<String,Object> fresh=f.controls.display(f.symbol,Map.of(),now);
            first.savePrice(symbol,fresh);first.savePrice(symbol,old);first.flushPrices();assertEquals(((Number)fresh.get("quoteVersion")).longValue(),((Number)first.getPrice(symbol).get("quoteVersion")).longValue());
            late.savePrice(symbol,old);late.flushPrices();assertEquals(((Number)fresh.get("quoteVersion")).longValue(),((Number)first.getPrice(symbol).get("quoteVersion")).longValue());
            redis.delete(key);assertNull(first.getPrice(symbol));assertEquals(fresh.get("price"),f.controls.display(f.symbol,Map.of(),now).get("price"));
            String id=((Map<String,String>)S2RuntimeMysqlTest.fixture.get("container_ids")).get("redis");
            try {docker("stop",id);first.savePrice(symbol,fresh);first.flushPrices();}
            finally {docker("start",id);}
            boolean ready=false;for(int i=0;i<50;i++){try{redis.getConnectionFactory().getConnection().ping();ready=true;break;}catch(Exception retry){Thread.sleep(200);}}
            assertTrue(ready);first.flushPrices();assertEquals(((Number)fresh.get("quoteVersion")).longValue(),((Number)first.getPrice(symbol).get("quoteVersion")).longValue());
            evidence("redis-restart",Map.of("containerId",id,"cacheLossDbQuoteUnchanged",true,"lateCallbackRejected",true,"pendingRepublishedAfterRestart",true));
        } finally {first.stopPriceWriter();late.stopPriceWriter();connection.destroy();}
    }
    static void docker(String operation,String id) throws Exception {
        if(S2RuntimeMysqlTest.jointFixture()){
            assertTrue(Set.of("start","stop").contains(operation));
            assertEquals(id,S2RuntimeMysqlTest.verifyContainer("redis",operation.equals("stop")));
        }
        Process process=new ProcessBuilder("docker",operation,id).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes());assertTrue(process.waitFor(30,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),output);
        if(S2RuntimeMysqlTest.jointFixture())assertEquals(id,S2RuntimeMysqlTest.verifyContainer("redis",operation.equals("start")));
    }
    @Test void newSchemaRejectsCrossTenantIdentityAndOldPackage() throws Exception {
        long now=System.currentTimeMillis();f.controls.pump(f.symbol,f.raw(now,true),now,60000);
        assertThrows(Exception.class,()->f.store.db.update("INSERT INTO market_engine_runtime(tenant_id,symbol_id) VALUES(2,?)",f.symbol.getId()));
        assertThrows(Exception.class,()->f.store.db.update("UPDATE market_engine_runtime SET tenant_id=2 WHERE tenant_id=1 AND symbol_id=?",f.symbol.getId()));
        java.lang.reflect.Method verify=com.gtcfesk.exchange.tenant.SchemaPackageGuard.class.getDeclaredMethod("verify",Connection.class,long.class);verify.setAccessible(true);
        try(Connection connection=S2RuntimeMysqlTest.data.getConnection()){
            assertThrows(java.lang.reflect.InvocationTargetException.class,()->verify.invoke(null,connection,2026100201L));
            if(S2RuntimeMysqlTest.jointFixture()){
                assertThrows(java.lang.reflect.InvocationTargetException.class,()->verify.invoke(null,connection,2026100305L));
                if(S2RuntimeMysqlTest.joint.schemaEpoch()==2026100404L)assertThrows(java.lang.reflect.InvocationTargetException.class,()->verify.invoke(null,connection,2026100403L));
                verify.invoke(null,connection,S2RuntimeMysqlTest.joint.schemaEpoch());
            }else verify.invoke(null,connection,2026100305L);
        }
        if(S2RuntimeMysqlTest.jointFixture())assertEquals(0,f.store.db.queryForObject("SELECT business_activation_ready FROM tenant_schema_version WHERE version=?",Integer.class,S2RuntimeMysqlTest.joint.schemaEpoch()));
        assertEquals(0,f.store.db.queryForObject("SELECT business_activation_ready FROM tenant_schema_version WHERE version=2026100305",Integer.class));
    }
    @Test void actualProcessDeathAndLeaseTakeoverResumeExactFullDay() throws Exception {
        long now=System.currentTimeMillis();PersistentPriceControl.Task task=f.legacy(now-86400000,86400);f.controls.pump(f.symbol,f.raw(now,false),now,60000);
        waitForLease(f.store,f.symbol.getId());
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        ProcessBuilder launcher=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xmx512m","-Dlogback.configurationFile="+System.getProperty("logback.configurationFile"),"-cp",classpath,S2CrashWorker.class.getName(),f.symbol.getId().toString(),Long.toString(now));
        if(S2RuntimeMysqlTest.jointFixture())launcher.command().add(2,"-Djoint.s2.fixture="+System.getProperty("joint.s2.fixture"));
        Process child=launcher.redirectErrorStream(true).start();
        String log=new String(child.getInputStream().readAllBytes());assertTrue(child.waitFor(60,TimeUnit.SECONDS));assertEquals(73,child.exitValue(),log);assertTrue(log.contains("S2_COMMITTED_BEFORE_PROCESS_DEATH"),log);
        assertEquals(1024,f.count(task.id));assertThrows(Exception.class,()->f.controls.pump(f.symbol,f.raw(now,false),now,60000));
        waitForLease(f.store,f.symbol.getId());ControlHistoryStore successor=new ControlHistoryStore(new JdbcTemplate(S2RuntimeMysqlTest.data),new DataSourceTransactionManager(S2RuntimeMysqlTest.data));PersistentPriceControl controls=new PersistentPriceControl(successor);
        while(controls.latest(f.symbol.getId()).running())controls.pump(f.symbol,f.raw(now,false),now,60000);
        assertEquals(86401,f.count(task.id));assertEquals(task.plannedEnd,controls.latest(f.symbol.getId()).sampledUntil);
        assertEquals(86401,f.store.db.queryForObject("SELECT COUNT(DISTINCT generated_at) FROM market_control_sample WHERE tenant_id=1 AND task_id=?",Integer.class,task.id));
        evidence("process-death-takeover",Map.of("childExit",child.exitValue(),"childLog",log,"points",f.count(task.id),"actualLeaseExpiryWait",true,"fencedOriginalOwner",true,"exactEndpoint",task.plannedEnd));
    }
    static void waitForLease(ControlHistoryStore store,long symbol) throws Exception {
        for(int i=0;i<170;i++){if(store.db.queryForObject("SELECT lease_until<=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED) FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Boolean.class,symbol))return;Thread.sleep(100);}
        fail("real database lease did not expire");
    }
}

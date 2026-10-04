package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.control.ControlIdentity;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Requires this run's new full Docker IDs/UUID. No fallback to an existing fixture or H2. */
class S2RuntimeMysqlTest {
    static Map<String,Object> fixture;static DriverManagerDataSource data;
    static JointS2Fixture joint;
    static boolean jointFixture(){return System.getProperty("joint.s2.fixture")!=null;}
    static void jointIdentity(boolean initial) throws Exception {
        if(joint==null)joint=JointS2Fixture.open(initial);else joint.verifyUnchanged();
        fixture=joint.fixture;data=joint.data;
    }
    static void identityForCrashWorker() throws Exception {
        if(jointFixture())jointIdentity(false);else identity();
    }
    ControlHistoryStore store;PersistentPriceControl controls;TradingSymbol symbol;TenantContext.Scope scope;
    static java.util.concurrent.atomic.AtomicLong ids=new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis()*1000);
    @BeforeAll static void identity() throws Exception {
        if(jointFixture()){jointIdentity(true);return;}
        String path=System.getenv("S2_FIXTURE_FILE");assertNotNull(path,"S2_FIXTURE_FILE must explicitly name owned private identity");
        fixture=new ObjectMapper().readValue(Files.readAllBytes(Path.of(path)),Map.class);
        assertEquals("01a10171-9c92-76f2-837c-6489387190b5",fixture.get("owner"));
        assertEquals(33358,fixture.get("mysql_port"));assertEquals(33658,fixture.get("redis_port"));assertTrue(((String)fixture.get("database")).matches("mt705_s2_resume_[0-9a-f]{16}"));
        Map<String,String> full=(Map<String,String>)fixture.get("container_ids");
        assertEquals(Set.of("mysql","redis"),full.keySet());
        verifyContainer("mysql",true);verifyContainer("redis",true);
        assertEquals("jdbc:mysql://127.0.0.1:33358/"+fixture.get("database"),((String)fixture.get("jdbc")).split("\\?")[0]);
        data=new DriverManagerDataSource((String)fixture.get("jdbc"),(String)fixture.get("username"),(String)fixture.get("password"));
        assertEquals(fixture.get("server_uuid"),new JdbcTemplate(data).queryForObject("SELECT @@server_uuid",String.class));assertEquals(fixture.get("database"),new JdbcTemplate(data).queryForObject("SELECT DATABASE()",String.class));
    }
    static int redisPort(){assertNotNull(fixture);if(jointFixture()){assertNotNull(joint);assertEquals(33319,fixture.get("redis_port"));return 33319;}assertEquals(33658,fixture.get("redis_port"));return ((Number)fixture.get("redis_port")).intValue();}
    static org.springframework.data.redis.connection.RedisStandaloneConfiguration ownedRedisConfiguration(){
        org.springframework.data.redis.connection.RedisStandaloneConfiguration config=new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",redisPort());
        String password=(String)fixture.get("redis_password");assertNotNull(password,"Current owned Redis credentials required");assertFalse(password.isBlank());
        config.setPassword(password);return config;
    }
    @SuppressWarnings("unchecked") static String verifyContainer(String kind,boolean running)throws Exception{
        if(jointFixture()){assertNotNull(joint);return joint.verifyContainer(kind,running);}
        assertTrue(Set.of("mysql","redis").contains(kind));assertNotNull(fixture);
        Map<String,String> ids=(Map<String,String>)fixture.get("container_ids");String id=ids.get(kind);
        assertNotNull(id);assertTrue(id.matches("[0-9a-f]{64}"));
        Process inspect=new ProcessBuilder("docker","inspect","--type","container",id).start();
        List<Map<String,Object>> found=new ObjectMapper().readValue(inspect.getInputStream().readAllBytes(),List.class);assertEquals(0,inspect.waitFor());assertEquals(1,found.size());
        Map<String,Object> row=found.get(0),config=(Map<String,Object>)row.get("Config"),labels=(Map<String,Object>)config.get("Labels"),state=(Map<String,Object>)row.get("State");
        assertEquals(id,row.get("Id"));assertEquals(running,state.get("Running"));
        assertEquals(fixture.get("run"),labels.get("com.gtcfesk.s2.run"));assertEquals(fixture.get("owner"),labels.get("com.gtcfesk.s2.owner"));assertEquals("true",labels.get("com.gtcfesk.multitenant.test"));
        if(kind.equals("mysql"))assertTrue(((String)config.get("Image")).matches(".*mysql:5\\.7.*"));else assertEquals("redis:7-alpine",config.get("Image"));
        Map<String,Object> host=(Map<String,Object>)row.get("HostConfig"),ports=(Map<String,Object>)host.get("PortBindings");
        String internal=kind.equals("mysql")?"3306/tcp":"6379/tcp";int expected=kind.equals("mysql")?33358:redisPort();
        assertEquals(Set.of(internal),ports.keySet());List<Map<String,String>> binding=(List<Map<String,String>>)ports.get(internal);
        assertEquals(1,binding.size());assertEquals("127.0.0.1",binding.get(0).get("HostIp"));assertEquals(String.valueOf(expected),binding.get(0).get("HostPort"));
        return id;
    }
    @BeforeEach void setup(){scope=TenantContext.open(1L);store=new ControlHistoryStore(new JdbcTemplate(data),new DataSourceTransactionManager(data));controls=new PersistentPriceControl(store);symbol=new TradingSymbol();symbol.setTenantId(1L);symbol.setId(ids.incrementAndGet());symbol.setSymbol("S2_"+symbol.getId());symbol.setPricePrecision(2);symbol.setIsEnabled(true);if(jointFixture()){
            symbol.setBaseCurrency("TEST");symbol.setName(symbol.getSymbol());symbol.setMarketSource("yahoo");symbol.setSourceCategory(symbol.getCategory());
            store.db.update("INSERT INTO trading_symbol(id,tenant_id,symbol,base_currency,quote_currency,name,category,is_enabled,price_precision,control_enabled,row_version,market_source,source_category) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                symbol.getId(),symbol.getTenantId(),symbol.getSymbol(),symbol.getBaseCurrency(),symbol.getQuoteCurrency(),symbol.getName(),symbol.getCategory(),symbol.getIsEnabled(),symbol.getPricePrecision(),symbol.getControlEnabled(),symbol.getRowVersion(),symbol.getMarketSource(),symbol.getSourceCategory());
        }else{store.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,1)",symbol.getId());}}
    @AfterEach void close(){SecurityContextHolder.clearContext();scope.close();}
    Map<String,Object> raw(long time,boolean available){Map<String,Object> q=new HashMap<>();q.put("price",new BigDecimal("90.00"));q.put("timestamp",time);q.put("sourceTimestamp",time);q.put("fetchedAt",time);q.put("expiresAt",time+60000);q.put("available",available);q.put("sourceAvailable",available);q.put("eventId","source-"+time);return q;}
    PersistentPriceControl.Task legacy(long start,int duration){symbol.setControlEnabled(true);symbol.setControlStartedAt(start);symbol.setControlDurationSeconds(duration);symbol.setControlStartPrice(new BigDecimal("90"));symbol.setControlTargetPrice(new BigDecimal("100"));symbol.setControlIntensity(1);symbol.setControlRandomOscillation(false);controls.importLegacy(symbol);symbol.setControlStartedAt(null);symbol.setControlEnabled(false);return controls.latest(symbol.getId());}
    long count(String id){return store.db.queryForObject("SELECT COUNT(*) FROM market_control_sample WHERE tenant_id=1 AND task_id=?",Long.class,id);}
    @Test void realCommitBlocksRollbackSecondAndRetryNoDuplicate(){
        long now=System.currentTimeMillis();PersistentPriceControl.Task t=legacy(now-86400000,86400);
        controls.pump(symbol,raw(now,false),now,60000);assertEquals(512,count(t.id));
        long first=controls.latest(symbol.getId()).sampledUntil;
        assertThrows(IllegalStateException.class,()->store.transaction(()->{controls.pump(symbol,raw(now,false),now,60000);throw new IllegalStateException("block2 injected before commit");}));
        JdbcTemplate independent=new JdbcTemplate(data);assertEquals(first,independent.queryForObject("SELECT sampled_until FROM market_control_task WHERE tenant_id=1 AND id=?",Long.class,t.id));assertEquals(512,count(t.id));
        controls.pump(symbol,raw(now,false),now,60000);assertEquals(1024,count(t.id));
        // Lost acknowledgement after a real commit: a retry resumes persisted watermark, not old memory.
        try{controls.pump(symbol,raw(now,false),now,60000);throw new IllegalStateException("acknowledgement lost");}catch(IllegalStateException expected){}
        controls.pump(symbol,raw(now,false),now,60000);assertEquals(2048,count(t.id));
        while(controls.latest(symbol.getId()).running())controls.pump(symbol,raw(now,false),now,60000);
        PersistentPriceControl.Task done=controls.latest(symbol.getId());assertEquals(86401,count(t.id));assertEquals(t.plannedEnd,done.sampledUntil);assertEquals(t.plannedEnd,done.endedAt);
        assertEquals(86401,independent.queryForObject("SELECT COUNT(DISTINCT generated_at) FROM market_control_sample WHERE tenant_id=1 AND task_id=?",Integer.class,t.id));
        assertEquals(0,independent.queryForObject("SELECT COUNT(*) FROM market_control_sample WHERE tenant_id=1 AND task_id=? AND generated_at>?",Integer.class,t.id,t.plannedEnd));
    }
    @Test void stopPersistsExactCutoffThroughTakeoverAndLateWorkerCannotWrite(){
        long now=System.currentTimeMillis();PersistentPriceControl.Task t=legacy(now-86400000,86400);long cutoff=t.startedAt+40000500;
        controls.stopAndHold(symbol.getId(),cutoff);assertEquals(cutoff,controls.latest(symbol.getId()).stopAt);assertEquals(512,count(t.id));
        String old=store.runtime.owner;long gen=store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId());
        assertThrows(Exception.class,()->new ControlHistoryStore(new JdbcTemplate(data),new DataSourceTransactionManager(data)).locked(symbol.getId(),()->null));
        // Only this owned test runtime's lease is advanced; no earlier resource is touched.
        store.db.update("UPDATE market_engine_runtime SET lease_until=0 WHERE tenant_id=1 AND symbol_id=?",symbol.getId());
        ControlHistoryStore successor=new ControlHistoryStore(new JdbcTemplate(data),new DataSourceTransactionManager(data));PersistentPriceControl next=new PersistentPriceControl(successor);
        next.pump(symbol,raw(now,false),now,60000);assertThrows(Exception.class,()->controls.pump(symbol,raw(now,false),now,60000));
        assertNotEquals(old,successor.runtime.owner);assertEquals(gen+1,successor.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId()));
        while(next.latest(symbol.getId()).running())next.pump(symbol,raw(now,false),now,60000);
        PersistentPriceControl.Task done=next.latest(symbol.getId());assertEquals("STOPPED",done.status);assertEquals(cutoff,done.endedAt);assertEquals(t.startedAt+40000000,done.sampledUntil);assertEquals(40001,count(t.id));
        assertThrows(Exception.class,()->store.db.update("UPDATE market_control_task SET sampled_until=sampled_until+1000 WHERE tenant_id=1 AND id=?",t.id));
        assertThrows(Exception.class,()->store.db.update("INSERT INTO market_control_sample(tenant_id,task_id,generated_at,price) VALUES(1,?,?,99)",t.id,cutoff+1000));
    }
    @Test void pureReadSnapshotDoesNotAdvanceOrRenewAndRollbackInvisible(){
        long now=System.currentTimeMillis();PersistentPriceControl.Task t=legacy(now-10000,60);
        controls.pump(symbol,raw(now,true),now,60000);Map<String,Object> quote=controls.display(symbol,raw(now+40000,true),now);
        long version=((Number)quote.get("quoteVersion")).longValue(),expiry=((Number)quote.get("executionExpiresAt")).longValue(),samples=count(t.id);
        for(int i=0;i<100;i++){
            Map<String,Object> read=controls.display(symbol,raw(now+40000,true),now+1000);assertEquals(version,((Number)read.get("quoteVersion")).longValue());assertEquals(expiry,((Number)read.get("executionExpiresAt")).longValue());
            Map<String,Object> status=new HashMap<>();controls.status(symbol,status,raw(now+40000,true),now+1000);
        }
        assertEquals(samples,count(t.id));assertFalse(Boolean.TRUE.equals(controls.display(symbol,raw(expiry+1,true),expiry+1).get("available")));
        assertThrows(IllegalStateException.class,()->store.transaction(()->{controls.pump(symbol,raw(now+1000,true),now+1000,60000);throw new IllegalStateException("snapshot rollback");}));
        assertEquals(version,((Number)controls.display(symbol,Collections.emptyMap(),now).get("quoteVersion")).longValue());assertEquals(samples,count(t.id));
        StringRedisTemplate redis;LettuceConnectionFactory connection=new LettuceConnectionFactory(ownedRedisConfiguration());connection.afterPropertiesSet();redis=new StringRedisTemplate(connection);
        try{String key="tenant:1:s2:"+symbol.getId();redis.opsForValue().set(key,store.encode(quote));redis.delete(key);assertEquals(quote.get("price"),controls.display(symbol,Collections.emptyMap(),now).get("price"));}
        finally{connection.destroy();}
    }
    @Test void boundedBudgetAndTenantIsolation() throws Exception {
        try(ControlPlanBudget.Lease a=store.budget.acquire(32L*1024*1024)){
            assertThrows(Exception.class,()->store.budget.acquire(1));
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                worker.submit(()->{
                    assertNull(TenantContext.currentTenantId());
                    try(TenantContext.Scope b=TenantContext.open(2L);ControlPlanBudget.Lease available=store.budget.acquire(65536)){
                        assertEquals(65536L,store.budget.metrics().get("activePlanBytes"));
                        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                            ()->controls.display(symbol,Collections.emptyMap(),System.currentTimeMillis()));
                        TradingSymbol own=new TradingSymbol();own.setTenantId(2L);own.setId(symbol.getId());
                        Map<String,Object> pending=controls.display(own,Collections.emptyMap(),System.currentTimeMillis());
                        assertEquals("engine_pending",pending.get("status"));
                        assertEquals(false,pending.get("tradeAvailable"));
                        assertFalse(pending.containsKey("price"));assertFalse(pending.containsKey("taskId"));
                    }
                    assertNull(TenantContext.currentTenantId());
                }).get(10,TimeUnit.SECONDS);
            } finally {worker.shutdownNow();}
            assertEquals(1L,TenantContext.currentTenantId());
        }
        assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));
    }
    @Test void snapshotRequiresActiveLeaseAndRollbackPreservesCommittedVersion(){
        long now=System.currentTimeMillis();controls.pump(symbol,raw(now,true),now,60000);
        Map<String,Object> committed=controls.display(symbol,Collections.emptyMap(),now);
        long version=((Number)committed.get("quoteVersion")).longValue();
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,
            ()->store.runtime.snapshot(symbol.getId(),raw(now+1000,true),Collections.emptyMap(),now+1000));
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->store.locked(symbol.getId(),()->{
            store.db.update("UPDATE market_engine_runtime SET lease_until=0 WHERE tenant_id=1 AND symbol_id=?",symbol.getId());
            store.runtime.snapshot(symbol.getId(),raw(now+1000,true),Collections.emptyMap(),now+1000);return null;
        }));
        assertEquals(version,((Number)controls.display(symbol,Collections.emptyMap(),now).get("quoteVersion")).longValue());
        assertEquals(committed.get("price"),controls.display(symbol,Collections.emptyMap(),now).get("price"));
    }
    @Test void durableAcceptedKeyConflictCancelAndWorkerReceipt(){
        ForexQuoteMarketService market=new ForexQuoteMarketService();TradingSymbolRepository repository=mock(TradingSymbolRepository.class);when(repository.findAllByTenantId(1L)).thenReturn(Collections.singletonList(symbol));when(repository.findByTenantIdAndId(1L,symbol.getId())).thenReturn(Optional.of(symbol));
        when(repository.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));
        ReflectionTestUtils.setField(market,"symbols",repository);ReflectionTestUtils.setField(market,"controls",controls);ReflectionTestUtils.setField(market,"controlHistory",store);ReflectionTestUtils.setField(market,"redis",mock(RedisMarketService.class));market.refreshSymbols();
        controls.sourceQuote(symbol,raw(System.currentTimeMillis(),true),System.currentTimeMillis());controls.pump(symbol,raw(System.currentTimeMillis(),true),System.currentTimeMillis(),60000);
        ControlAuditService audit=mock(ControlAuditService.class);MarketControlCommands commands=new MarketControlCommands(store,market,mock(TenantJobRunner.class),audit);
        UsernamePasswordAuthenticationToken identity=new UsernamePasswordAuthenticationToken("s2",null,Collections.emptyList());identity.setDetails(new ControlIdentity(7L,1L,"s2-session"));SecurityContextHolder.getContext().setAuthentication(identity);
        TargetControlOptions options=new TargetControlOptions();options.setDeviationBandMode("MANUAL");options.setDeviationBandPercent(new BigDecimal("1"));options.setStepFormula("0.1");String key="s2_repeat_key_000001";
        Map<String,Object> accepted=commands.accept(symbol.getId(),20,new BigDecimal("91.00"),1,false,key,options);assertEquals("ACCEPTED",accepted.get("state"));
        assertEquals(accepted.get("commandId"),commands.accept(symbol.getId(),20,new BigDecimal("91"),1,false,key,options).get("commandId"));
        assertThrows(Exception.class,()->commands.accept(symbol.getId(),20,new BigDecimal("92"),1,false,key,options));
        commands.runOne();Map<String,Object> running=commands.query(symbol.getId(),key);assertEquals("RUNNING",running.get("state"),()->running.toString());
        commands.runOne();assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
        verify(audit).record(eq(7L),eq(1L),eq("s2-session"),eq("ai-control-command.activate"),eq(String.valueOf(accepted.get("commandId"))),eq("RUNNING"),eq("{}"),isNull());
        assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));
    }
}

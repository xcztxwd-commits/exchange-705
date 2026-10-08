package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.AdminAiControlController;
import com.gtcfesk.exchange.config.GlobalExceptionHandler;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.apache.catalina.Context;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual MySQL 5.7/fences, market engine, durable queue and loopback Tomcat/controller.
 * Provider, repository and audit boundaries are fixtures; this is not production security/JPA or load acceptance.
 */
class ControlRecoveryMysqlTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static DriverManagerDataSource data;
    static Map<String,Object> fixture;
    static final AtomicLong ids=new AtomicLong(System.currentTimeMillis()*1000);
    static ControlRecoveryMysqlTest httpOwner;
    ControlHistoryStore store;
    PersistentPriceControl controls;
    ForexQuoteMarketService market;
    MarketControlCommands commands;
    TradingSymbol symbol;
    TenantContext.Scope scope;
    final AtomicBoolean expireAtHold=new AtomicBoolean(), forbidHold=new AtomicBoolean(),forbidFinalization=new AtomicBoolean();
    final AtomicInteger activatedHoldCalls=new AtomicInteger();
    final List<MarketControlCommands> queues=new ArrayList<>();
    final AtomicReference<Map<String,Object>> source=new AtomicReference<>();

    @BeforeAll static void ownedFixture() throws Exception {
        String path=System.getenv("CONTROL_RECOVERY_FIXTURE");
        Assumptions.assumeTrue(path!=null,"Opt-in only: run scripts/market/run_control_recovery_mysql.py");
        fixture=JSON.readValue(Files.readAllBytes(Paths.get(path)),Map.class);
        assertEquals("OWNED_CONTROL_RECOVERY_MYSQL_57",fixture.get("kind"));
        String id=String.valueOf(fixture.get("containerId"));assertTrue(id.matches("[a-f0-9]{64}"));
        Process inspect=new ProcessBuilder("docker","inspect","--type","container",id).start();
        Map<String,Object> row=(Map<String,Object>)JSON.readValue(inspect.getInputStream().readAllBytes(),List.class).get(0);
        assertEquals(0,inspect.waitFor());assertEquals(id,row.get("Id"));assertEquals("/"+fixture.get("name"),row.get("Name"));
        Map<String,Object> config=(Map<String,Object>)row.get("Config"),labels=(Map<String,Object>)config.get("Labels");
        assertEquals(fixture.get("owner"),labels.get("com.gtcfesk.control-recovery.owner"));
        assertEquals(fixture.get("run"),labels.get("com.gtcfesk.control-recovery.run"));
        assertEquals("true",labels.get("com.gtcfesk.multitenant.test"));assertEquals("mysql:5.7",config.get("Image"));
        Map<String,Object> ports=(Map<String,Object>)((Map<String,Object>)row.get("HostConfig")).get("PortBindings");
        assertEquals(Collections.singleton("3306/tcp"),ports.keySet());
        List<Map<String,String>> bindings=(List<Map<String,String>>)ports.get("3306/tcp");assertEquals(1,bindings.size());
        assertEquals("127.0.0.1",bindings.get(0).get("HostIp"));assertEquals(String.valueOf(fixture.get("port")),bindings.get(0).get("HostPort"));
        String database=String.valueOf(fixture.get("database"));assertTrue(database.matches("mt705_control_recovery_[a-f0-9]{16}"));
        String url=String.valueOf(fixture.get("url"));assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:"+fixture.get("port")+"/"+database+"?"));
        assertEquals("control_recovery",fixture.get("username"));
        data=new DriverManagerDataSource(url,String.valueOf(fixture.get("username")),String.valueOf(fixture.get("password")));
        JdbcTemplate db=new JdbcTemplate(data);assertEquals(database,db.queryForObject("SELECT DATABASE()",String.class));
        assertEquals(fixture.get("serverUuid"),db.queryForObject("SELECT @@server_uuid",String.class));
        assertTrue(db.queryForObject("SELECT VERSION()",String.class).startsWith("5.7."));
        assertEquals("latin1_swedish_ci", db.queryForObject("SELECT table_collation FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='market_control_command'", String.class));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='market_control_command' AND character_set_name IS NOT NULL AND character_set_name<>'latin1'", Integer.class));
        assertEquals("varchar(255)", db.queryForObject("SELECT column_type FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='market_control_command' AND column_name='message'", String.class));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name LIKE 's2_%'",Integer.class)>=40);
    }

    @BeforeEach void setup(){
        scope=TenantContext.open(1L);S2CommandAcceptanceTest.identity(1L);
        JdbcTemplate observed=new JdbcTemplate(data){
            @Override public List<Map<String,Object>> queryForList(String sql,Object... arguments){if(forbidFinalization.get() && sql.startsWith("SELECT f.task_id,f.history_pending_until"))throw new AssertionError("Due old history finalization entered emergency lane");return super.queryForList(sql,arguments);}
            @Override public int update(String sql,Object... arguments){
                if(sql.startsWith("UPDATE market_control_hold SET reference_price=")){
                    activatedHoldCalls.incrementAndGet();
                    if(forbidHold.get())throw new AssertionError("Emergency SOURCE entered hold activation");
                    if(expireAtHold.compareAndSet(true,false)){
                        // Test-only lease expiry, not a task status mutation. Original trigger must reject this write.
                        super.update("UPDATE market_engine_runtime SET lease_until=CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)-1 WHERE tenant_id=1 AND symbol_id=?",symbol.getId());
                    }
                }
                return super.update(sql,arguments);
            }
        };
        store=new ControlHistoryStore(observed,new DataSourceTransactionManager(data));controls=new PersistentPriceControl(store);
        symbol=newSymbol(1L);market=newMarket(store,controls,symbol);commands=newQueue(store,market);
        doNothing().when(market).start();
        source.set(raw(System.currentTimeMillis(),true));
        doAnswer(call->new LinkedHashMap<>(source.get())).when(market).getPrice(nullable(String.class),nullable(String.class));
    }
    @AfterEach void cleanup(){for(MarketControlCommands queue:queues)queue.stop();SecurityContextHolder.clearContext();scope.close();}
    TradingSymbol newSymbol(long tenant){TradingSymbol s=new TradingSymbol();s.setTenantId(tenant);s.setId(ids.incrementAndGet());s.setSymbol("RECOVERY_"+s.getId());s.setIsEnabled(true);s.setPricePrecision(2);store.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,?)",s.getId(),tenant);return s;}
    ForexQuoteMarketService newMarket(ControlHistoryStore history,PersistentPriceControl durable,TradingSymbol... symbols){
        S2CommandAcceptanceTest helper=new S2CommandAcceptanceTest();return helper.newMarket(history,durable,symbols);
    }
    MarketControlCommands newQueue(ControlHistoryStore history,ForexQuoteMarketService service){MarketControlCommands queue=new MarketControlCommands(history,service,mock(TenantJobRunner.class),mock(ControlAuditService.class));queues.add(queue);return queue;}
    Map<String,Object> raw(long now,boolean available){Map<String,Object> q=S2CommandAcceptanceTest.raw(now);q.put("available",available);q.put("sourceAvailable",available);return q;}
    TargetControlOptions options(){return new S2CommandAcceptanceTest().options();}
    Map<String,Object> accept(String key){return commands.accept(symbol.getId(),20,new BigDecimal("91"),1,false,key,options());}
    void prime(){long now=System.currentTimeMillis();source.set(raw(now,true));controls.sourceQuote(symbol,source.get(),now);controls.pump(symbol,source.get(),now,60000);}
    long samples(String id){return store.db.queryForObject("SELECT COUNT(*) FROM market_control_sample WHERE tenant_id=1 AND task_id=?",Long.class,id);}
    PersistentPriceControl.Task startShort(){long now=System.currentTimeMillis();return controls.start(symbol,raw(now,true),new BigDecimal("90"),2,new BigDecimal("91"),1,false,false,"recovery_task_"+UUID.randomUUID(),new RecoveryOptions());}

    @Test void endpointRealFenceRollsBackFinalSampleSnapshotAndResumesOriginalWatermark(){
        prime();PersistentPriceControl.Task task=startShort();controls.pump(symbol,raw(task.startedAt+1000,true),task.startedAt+1000,60000);
        long beforeSamples=samples(task.id),beforeWatermark=controls.latest(symbol.getId()).sampledUntil;
        Map<String,Object> before=store.db.queryForMap("SELECT snapshot_version,quote_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId());
        expireAtHold.set(true);RuntimeException failure=assertThrows(RuntimeException.class,()->controls.pump(symbol,raw(task.plannedEnd,true),task.plannedEnd,60000));
        SQLException sql=null;for(Throwable cause=failure;cause!=null;cause=cause.getCause())if(cause instanceof SQLException)sql=(SQLException)cause;
        assertNotNull(sql,"Actual unchanged MySQL hold trigger must fail");assertEquals("45000",sql.getSQLState());assertEquals(1644,sql.getErrorCode());assertTrue(sql.getMessage().contains("ENGINE_FENCED"));
        assertEquals(beforeWatermark,controls.latest(symbol.getId()).sampledUntil);assertEquals(beforeSamples,samples(task.id));assertEquals("RUNNING",controls.latest(symbol.getId()).status);
        assertEquals(before,store.db.queryForMap("SELECT snapshot_version,quote_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId()));
        controls.pump(symbol,raw(task.plannedEnd,true),task.plannedEnd,60000);
        assertEquals("COMPLETED",controls.latest(symbol.getId()).status);assertEquals(task.plannedEnd,controls.latest(symbol.getId()).sampledUntil);assertEquals(3,samples(task.id));
        assertEquals(3,store.db.queryForObject("SELECT COUNT(DISTINCT generated_at) FROM market_control_sample WHERE tenant_id=1 AND task_id=?",Integer.class,task.id));
    }

    @Test void emergencySourceNeverActivatesHoldKeepsCommittedHistoryAndRejectsStalePrice(){
        prime();PersistentPriceControl.Task task=startShort();long before=samples(task.id),watermark=task.sampledUntil;forbidHold.set(true);
        source.set(raw(System.currentTimeMillis()-120000,false));
        market.manualControl(symbol.getId(),false,BigDecimal.ZERO);
        PersistentPriceControl.Task stopped=controls.latest(symbol.getId());assertFalse(stopped.running());assertEquals(watermark,stopped.sampledUntil);assertEquals(before,samples(task.id));assertEquals(0,activatedHoldCalls.get());
        assertNull(store.db.queryForObject("SELECT activated_at FROM market_control_hold WHERE tenant_id=1 AND task_id=?",Long.class,task.id));
        assertEquals("SOURCE",store.db.queryForObject("SELECT state FROM market_control_flow WHERE tenant_id=1 AND task_id=?",String.class,task.id));
        Map<String,Object> quote=controls.display(symbol,source.get(),System.currentTimeMillis());assertEquals(false,quote.get("available"));assertEquals(false,quote.get("tradeAvailable"));
        long count=store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId());
        controls.pump(symbol,source.get(),System.currentTimeMillis(),60000);assertEquals(count,store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId()));
        source.set(raw(System.currentTimeMillis(),true));controls.sourceQuote(symbol,source.get(),System.currentTimeMillis());controls.pump(symbol,source.get(),System.currentTimeMillis(),60000);
        assertEquals(true,controls.display(symbol,source.get(),System.currentTimeMillis()).get("available"));assertEquals(before,samples(task.id));
    }

    @Test void manualOffsetOutageCannotRenewTradableQuoteFromRetainedSourcePrice(){
        prime();market.manualControl(symbol.getId(),true,new BigDecimal("5"));long now=System.currentTimeMillis();
        Map<String,Object> initial=controls.display(symbol,source.get(),now);assertEquals("MANUAL",initial.get("controlState"));assertEquals(true,initial.get("tradeAvailable"));
        Map<String,Object> retained=store.lastQuote(symbol.getId());long events=store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId());
        Map<String,Object> missing=raw(now+100,false);missing.remove("price");missing.put("sourceTimestamp",now-120000);
        controls.pump(symbol,missing,now+100,60000);
        Map<String,Object> quote=controls.display(symbol,missing,now+100),status=new LinkedHashMap<>();controls.status(symbol,status,missing,now+100);
        assertEquals("MANUAL",quote.get("controlState"));assertEquals(false,quote.get("sourceAvailable"));assertEquals(false,quote.get("available"));assertEquals(false,quote.get("tradeAvailable"));
        assertEquals("MANUAL",status.get("controlState"));assertEquals(false,status.get("sourceAvailable"));assertEquals(false,status.get("available"));assertEquals(false,status.get("tradeAvailable"));
        assertEquals(true,status.get("degraded"));assertEquals("WAITING_VALID_SOURCE",status.get("progressStatus"));
        assertEquals(retained,store.lastQuote(symbol.getId()));assertEquals(events,store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId()));
        Map<String,Object> fresh=raw(now+200,true);fresh.put("price",new BigDecimal("93"));controls.sourceQuote(symbol,fresh,now+200);
        Map<String,Object> recovered=controls.display(symbol,fresh,now+200);assertEquals(true,recovered.get("tradeAvailable"));assertEquals(0,new BigDecimal("98").compareTo(ControlHistoryStore.number(recovered.get("price"))));
    }

    @Test void emergencyHistoryFinalizationPublishesOnlyPreviouslyCommittedRange(){
        prime();PersistentPriceControl.Task task=startShort();long watermark=task.sampledUntil,before=samples(task.id);forbidHold.set(true);
        long now=System.currentTimeMillis();controls.emergencySource(symbol.getId(),now);
        assertEquals(watermark,store.db.queryForObject("SELECT history_pending_until FROM market_control_flow WHERE tenant_id=1 AND task_id=?",Long.class,task.id));
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_publication WHERE tenant_id=1 AND task_id=?",Integer.class,task.id));
        assertEquals(before,samples(task.id));controls.pump(symbol,raw(now+1100,true),now+1100,60000);
        assertEquals(watermark,store.db.queryForObject("SELECT to_at FROM market_control_publication WHERE tenant_id=1 AND task_id=?",Long.class,task.id));
        assertNull(store.db.queryForObject("SELECT history_pending_until FROM market_control_flow WHERE tenant_id=1 AND task_id=?",Long.class,task.id));
        assertEquals(before,samples(task.id));assertEquals(0,activatedHoldCalls.get());
    }

    @Test void statusReadsDetectStalledWatermarkWithoutSamplingOrRenewingLease(){
        prime();long now=System.currentTimeMillis();PersistentPriceControl.Task task=controls.start(symbol,raw(now,true),new BigDecimal("90"),10,new BigDecimal("91"),1,false,false,"status_progress_original",new RecoveryOptions());controls.pump(symbol,raw(now,true),now,60000);
        Map<String,Object> committed=store.db.queryForMap("SELECT lease_until,snapshot_version,committed_at FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId());long before=samples(task.id);
        Map<String,Object> status=new LinkedHashMap<>();controls.status(symbol,status,raw(now+30000,true),now+30000);
        assertEquals(task.sampledUntil,QuoteState.time(status.get("sampledUntil")));assertEquals(before,samples(task.id));
        assertEquals(true,status.get("degraded"));assertTrue(QuoteState.time(status.get("controlLagMillis"))>2000);assertEquals("ENGINE_LAG",status.get("progressStatus"));
        assertEquals(committed,store.db.queryForMap("SELECT lease_until,snapshot_version,committed_at FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId()));
        assertEquals(false,controls.display(symbol,raw(now+30000,true),now+30000).get("available"));
    }

    @Test void missingOnlyFinalSecondStillBecomesDegradedAndNontradableAfterDeadline(){
        prime();PersistentPriceControl.Task task=startShort();controls.pump(symbol,raw(task.startedAt+1000,true),task.startedAt+1000,60000);
        Map<String,Object> status=new LinkedHashMap<>();long now=task.plannedEnd+3000;controls.status(symbol,status,raw(now,true),now);
        assertEquals(1000,QuoteState.time(status.get("controlLagMillis")));assertEquals(true,status.get("degraded"));assertEquals("ENGINE_LAG",status.get("progressStatus"));
        Map<String,Object> display=controls.display(symbol,raw(now,true),now);assertEquals(false,display.get("available"));assertEquals(false,display.get("tradeAvailable"));
        assertEquals(task.plannedEnd-1000,controls.latest(symbol.getId()).sampledUntil);
    }

    @Test void v4EmergencySourceAndRestoreNeverDecodeOldPlan(){
        prime();accept("v4_poison_source");commands.runOne();assertEquals(4,controls.latest(symbol.getId()).algorithmVersion);
        ControlHistoryStore poisoned=spy(store);doThrow(new AssertionError("Old V4 plan decoded during emergency/recovery")).when(poisoned).plan(anyString());
        ReflectionTestUtils.setField(controls,"store",poisoned);ReflectionTestUtils.setField(market,"controlHistory",poisoned);
        market.manualControl(symbol.getId(),false,BigDecimal.ZERO);assertFalse(controls.latest(symbol.getId()).running());
        // Separate actual V4 target, then RESTORE bypasses its old plan and incomplete endpoint.
        ReflectionTestUtils.setField(controls,"store",store);ReflectionTestUtils.setField(market,"controlHistory",store);prime();accept("v4_poison_restore_target");commands.runOne();assertEquals(4,controls.latest(symbol.getId()).algorithmVersion);
        ReflectionTestUtils.setField(controls,"store",poisoned);ReflectionTestUtils.setField(market,"controlHistory",poisoned);
        market.restoreControl(symbol.getId(),20,1,false,"v4_poison_restore");assertEquals("RESTORE",controls.latest(symbol.getId()).kind);
        verify(poisoned,never()).plan(anyString());
    }

    @Test void dueOlderHistoryFailureCannotPoisonEmergencySource() throws Exception {
        prime();PersistentPriceControl.Task older=startShort();controls.emergencySource(symbol.getId(),System.currentTimeMillis());
        Thread.sleep(1100);long now=System.currentTimeMillis();controls.start(symbol,raw(now,true),new BigDecimal("90"),20,new BigDecimal("91"),1,false,false,"newer_source_poison",new RecoveryOptions());
        assertTrue(store.db.queryForObject("SELECT history_retry_at FROM market_control_flow WHERE tenant_id=1 AND task_id=?",Long.class,older.id)<=System.currentTimeMillis());
        // HistoryOrdering is final; poison its actual JDBC finalization entry, without adding a mock dependency.
        forbidFinalization.set(true);market.manualControl(symbol.getId(),false,BigDecimal.ZERO);
        assertFalse(controls.latest(symbol.getId()).running());assertNotNull(store.db.queryForObject("SELECT history_pending_until FROM market_control_flow WHERE tenant_id=1 AND task_id=?",Long.class,older.id));
    }

    @Test void physicalTransaction512PointBudgetAlsoBoundsHoldAndRecovery(){
        for(boolean restore:new boolean[]{false,true}){
            symbol=newSymbol(1L);long now=System.currentTimeMillis(),started=now-511000;
            symbol.setControlEnabled(true);symbol.setControlStartedAt(started);symbol.setControlDurationSeconds(511);symbol.setControlStartPrice(new BigDecimal("90"));symbol.setControlTargetPrice(new BigDecimal("91"));symbol.setControlIntensity(1);symbol.setControlRandomOscillation(false);controls.importLegacy(symbol);symbol.setControlEnabled(false);symbol.setControlStartedAt(null);
            PersistentPriceControl.Task task=controls.latest(symbol.getId());RecoveryOptions recovery=new RecoveryOptions();recovery.setAutoRestore(restore);
            store.locked(symbol.getId(),()->{new ControlHoldService(store).prepare(task,raw(now,true));new ControlRecoveryFlow(store,new ControlHoldService(store)).create(task,recovery);return null;});
            store.locked(symbol.getId(),()->{controls.pump(symbol,raw(now,true),now,60000);controls.sourceQuote(symbol,raw(now+1000,true),now+1000);return null;});
            assertEquals(512,samples(task.id),"Same physical transaction exceeded shared limit: restore="+restore);
            controls.sourceQuote(symbol,raw(now+2000,true),now+2000);assertTrue(samples(task.id)>512,"Next short transaction must still make progress");
        }
    }

    @Test void elapsedWriterBudgetAbortsWithoutExtendingFifteenSecondLeaseOrPublishing(){
        prime();Map<String,Object> committed=store.db.queryForMap("SELECT lease_until,snapshot_version,committed_at FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId());
        RuntimeException failure=assertThrows(RuntimeException.class,()->store.locked(symbol.getId(),()->{try{Thread.sleep(4600);}catch(InterruptedException e){throw new RuntimeException(e);}store.runtime.requireBudget();return null;}));
        assertTrue(failure.getMessage().startsWith("ENGINE_BUDGET"),failure.getMessage());
        assertEquals(committed,store.db.queryForMap("SELECT lease_until,snapshot_version,committed_at FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId()));
    }

    @Test void realJdbcSelectDeadlineRollsBackAndNextSourceWriterRemainsUsable(){
        prime();Map<String,Object> committed=store.db.queryForMap("SELECT lease_until,snapshot_version,committed_at FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId());long start=System.nanoTime();
        RuntimeException failure=assertThrows(RuntimeException.class,()->store.locked(symbol.getId(),()->store.db.queryForObject("SELECT SLEEP(6)",Integer.class)));
        assertEquals("ENGINE_BUDGET",MarketEngineFailure.normalize(failure));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<6500,"Real JDBC deadline was not applied");
        assertEquals(committed,store.db.queryForMap("SELECT lease_until,snapshot_version,committed_at FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId()));
        long now=System.currentTimeMillis();Map<String,Object> fresh=raw(now,true);controls.sourceQuote(symbol,fresh,now);controls.pump(symbol,fresh,now,60000);
        assertEquals(true,controls.display(symbol,fresh,now).get("available"));assertTrue(store.db.queryForObject("SELECT snapshot_version FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId())>((Number)committed.get("snapshot_version")).longValue());
    }

    @Test void restoreAfterCompletedTargetAndManualOffsetLeavesPreparationAndReachesSource() throws Exception {
        List<TradingSymbol> registered=new ArrayList<>();registered.add(symbol);
        for(int i=1;i<25;i++)registered.add(newSymbol(1L));
        market=newMarket(store,controls,registered.toArray(new TradingSymbol[0]));
        doAnswer(call->new LinkedHashMap<>(source.get())).when(market).getPrice(nullable(String.class),nullable(String.class));
        commands=newQueue(store,market);
        prime();TargetControlOptions targetOptions=options();targetOptions.setStepFormula("1");targetOptions.setDeviationBandPercent(new BigDecimal("3"));
        commands.accept(symbol.getId(),3,new BigDecimal("91"),1,false,"target_before_manual_restore",targetOptions);commands.runOne();
        assertEquals("RUNNING",commands.query(symbol.getId(),"target_before_manual_restore").get("state"),()->commands.query(symbol.getId(),"target_before_manual_restore").toString());
        PersistentPriceControl.Task target=controls.latest(symbol.getId());
        Thread.sleep(Math.max(0,target.plannedEnd-System.currentTimeMillis()+1));
        prime();assertEquals("COMPLETED",controls.latest(symbol.getId()).status);
        assertEquals("HOLDING",flow(target.id).get("state"));
        Map<String,Object> manual=market.manualControl(symbol.getId(),true,new BigDecimal("5"));
        assertEquals("MANUAL",manual.get("controlState"));
        String key="restore_after_target_manual";
        commands.acceptRestore(symbol.getId(),2,1,false,key);commands.runOne();
        Map<String,Object> receipt=commands.query(symbol.getId(),key);
        assertEquals("RUNNING",receipt.get("state"),receipt.toString());
        PersistentPriceControl.Task restore=controls.latest(symbol.getId());
        assertEquals("RESTORE",restore.kind);assertNotEquals(target.id,restore.id);
        assertEquals(0,new BigDecimal("95").compareTo(restore.startPrice));
        assertEquals("RECOVERING",flow(restore.id).get("state"));
        long start=((Number)flow(restore.id).get("recovery_started_at")).longValue();
        source.set(priced(start+1001,"91"));market.completeControls(start+1001);
        source.set(priced(start+2002,"92"));market.completeControls(start+2002);
        assertEquals("SOURCE",flow(restore.id).get("state"));
        assertEquals(0,new BigDecimal("92").compareTo(displayPrice(controls,source.get(),start+2002)));
    }

    @Test void gradualRestorePausesOutageAndComponentRestartThenEndsOnActualDynamicSource(){
        prime();market.manualControl(symbol.getId(),true,new BigDecimal("5"));String key="restore_outage_restart";
        commands.acceptRestore(symbol.getId(),2,1,false,key);commands.runOne();assertEquals("RUNNING",commands.query(symbol.getId(),key).get("state"));
        PersistentPriceControl.Task task=controls.latest(symbol.getId());long now=System.currentTimeMillis();controls.pump(symbol,raw(now,true),now,60000);
        long before=samples(task.id);controls.pump(symbol,raw(now+100,false),now+100,60000);
        Map<String,Object> paused=store.db.queryForMap("SELECT state,remaining_millis FROM market_control_flow WHERE tenant_id=1 AND task_id=?",task.id);
        assertEquals("WAITING_SOURCE",paused.get("state"));assertTrue(((Number)paused.get("remaining_millis")).longValue()>0);assertEquals(before,samples(task.id));
        PersistentPriceControl restarted=new PersistentPriceControl(store);long restartAt=now+5000;restarted.pump(symbol,raw(restartAt,false),restartAt,60000);
        assertEquals(paused,store.db.queryForMap("SELECT state,remaining_millis FROM market_control_flow WHERE tenant_id=1 AND task_id=?",task.id));assertEquals(before,samples(task.id));
        Map<String,Object> fresh=raw(restartAt+1,true);fresh.put("price",new BigDecimal("92"));restarted.sourceQuote(symbol,fresh,restartAt+1);
        long duration=((Number)paused.get("remaining_millis")).longValue(),finish=restartAt+1+duration+1;
        long middle=restartAt+1+Math.min(1000,Math.max(1,duration/2));Map<String,Object> intermediate=raw(middle,true);intermediate.put("price",new BigDecimal("93"));restarted.sourceQuote(symbol,intermediate,middle);
        Map<String,Object> changed=raw(finish,true);changed.put("price",new BigDecimal("94"));restarted.sourceQuote(symbol,changed,finish);
        assertEquals("SOURCE",store.db.queryForObject("SELECT state FROM market_control_flow WHERE tenant_id=1 AND task_id=?",String.class,task.id));
        assertEquals(0,new BigDecimal("94").compareTo(ControlHistoryStore.number(restarted.display(symbol,changed,finish).get("price"))));
    }

    Map<String,Object> flow(String id){return store.db.queryForMap("SELECT * FROM market_control_flow WHERE tenant_id=1 AND task_id=?",id);}
    Map<String,Object> priced(long at,String price){Map<String,Object> quote=raw(at,true);quote.put("price",new BigDecimal(price));return quote;}
    BigDecimal displayPrice(PersistentPriceControl engine,Map<String,Object> quote,long at){return ControlHistoryStore.number(engine.display(symbol,quote,at).get("price"));}
    PersistentPriceControl.Task restoreTask(int duration,String key){
        prime();market.manualControl(symbol.getId(),true,new BigDecimal("5"));commands.acceptRestore(symbol.getId(),duration,1,false,key);commands.runOne();
        assertEquals("RUNNING",commands.query(symbol.getId(),key).get("state"));PersistentPriceControl.Task task=controls.latest(symbol.getId());assertEquals("RECOVERING",flow(task.id).get("state"));return task;
    }
    PersistentPriceControl.Task targetTask(int duration,String key,RecoveryOptions recovery){
        prime();long now=System.currentTimeMillis();return controls.start(symbol,raw(now,true),new BigDecimal("90"),duration,new BigDecimal("94"),1,false,false,key,recovery);
    }

    @Test void targetBusinessStopHoldsCommittedOffsetAcrossComponentRestartAndDynamicSource(){
        PersistentPriceControl.Task task=targetTask(4,"target_business_stop",new RecoveryOptions());long cutoff=task.startedAt+1500;
        controls.pump(symbol,priced(cutoff,"90"),cutoff,60000);controls.stopAndHold(symbol.getId(),cutoff);controls.pump(symbol,priced(cutoff,"90"),cutoff,60000);
        PersistentPriceControl.Task stopped=controls.latest(symbol.getId());assertEquals("STOPPED",stopped.status);assertEquals(cutoff,stopped.stopAt.longValue());assertEquals(task.startedAt+1000,stopped.sampledUntil);
        BigDecimal retained=displayPrice(controls,priced(cutoff,"90"),cutoff),offset=retained.subtract(new BigDecimal("90"));long count=samples(task.id);
        PersistentPriceControl restarted=new PersistentPriceControl(store);long observed=cutoff+1000;Map<String,Object> changed=priced(observed,"93");restarted.sourceQuote(symbol,changed,observed);
        assertEquals("HOLDING",flow(task.id).get("state"));assertEquals(0,new BigDecimal("93").add(offset).compareTo(displayPrice(restarted,changed,observed)));
        assertEquals(stopped.sampledUntil,restarted.latest(symbol.getId()).sampledUntil);assertEquals(count+1,samples(task.id));assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_hold WHERE tenant_id=1 AND task_id=? AND activated_at IS NOT NULL AND released_at IS NULL",Integer.class,task.id));
    }

    @Test void schedulerGapDoesNotActLikeBusinessPauseAndCatchesUpOnlyCommittedTargetSamples(){
        PersistentPriceControl.Task task=targetTask(4,"scheduler_gap",new RecoveryOptions());long first=task.startedAt+1000;controls.pump(symbol,priced(first,"90"),first,60000);
        long count=samples(task.id);Map<String,Object> committed=store.db.queryForMap("SELECT quote_json,snapshot_version FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId());
        long later=task.startedAt+3500;Map<String,Object> status=new LinkedHashMap<>();controls.status(symbol,status,priced(later,"90"),later);
        assertEquals(count,samples(task.id));assertEquals(committed,store.db.queryForMap("SELECT quote_json,snapshot_version FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId()));assertTrue(((Number)status.get("remainingSeconds")).longValue()<=1);
        PersistentPriceControl restarted=new PersistentPriceControl(store);restarted.pump(symbol,priced(later,"90"),later,60000);
        assertEquals(task.startedAt+3000,restarted.latest(symbol.getId()).sampledUntil);assertEquals(4,samples(task.id));assertEquals("RUNNING",restarted.latest(symbol.getId()).status);
        restarted.pump(symbol,priced(task.plannedEnd,"90"),task.plannedEnd,60000);assertEquals("COMPLETED",restarted.latest(symbol.getId()).status);assertEquals("HOLDING",flow(task.id).get("state"));
    }

    @Test void restoreBusinessStopThenNewRestoreStartsAtActualCommittedDisplayAndEndsWithZeroOffset() throws Exception {
        PersistentPriceControl.Task original=restoreTask(3,"restore_stop_first");Thread.sleep(40);long observed=System.currentTimeMillis();Map<String,Object> changed=priced(observed,"92");source.set(changed);controls.sourceQuote(symbol,changed,observed);
        BigDecimal shown=displayPrice(controls,changed,observed);long stopAt=System.currentTimeMillis();controls.stopAndHold(symbol.getId(),stopAt);controls.pump(symbol,changed,stopAt,60000);assertEquals("HOLDING",flow(original.id).get("state"));
        BigDecimal held=displayPrice(controls,changed,stopAt);assertEquals(0,shown.compareTo(held));
        PersistentPriceControl restarted=new PersistentPriceControl(store);ForexQuoteMarketService reopened=newMarket(store,restarted,symbol);doAnswer(call->new LinkedHashMap<>(source.get())).when(reopened).getPrice(nullable(String.class),nullable(String.class));MarketControlCommands queue=newQueue(store,reopened);
        queue.acceptRestore(symbol.getId(),2,1,false,"restore_stop_second");queue.runOne();PersistentPriceControl.Task next=restarted.latest(symbol.getId());assertNotEquals(original.id,next.id);assertEquals(0,held.compareTo(next.startPrice));
        assertEquals(original.id,queue.acceptRestore(symbol.getId(),3,1,false,"restore_stop_first").get("taskId"));queue.runOne();assertEquals(next.id,restarted.latest(symbol.getId()).id);
        long recoveryStart=((Number)flow(next.id).get("recovery_started_at")).longValue(),finish=recoveryStart+2001;
        restarted.sourceQuote(symbol,priced(recoveryStart+1000,"93"),recoveryStart+1000);Map<String,Object> fresh=priced(finish,"94");restarted.sourceQuote(symbol,fresh,finish);
        assertEquals("SOURCE",flow(next.id).get("state"));assertEquals(0,new BigDecimal("94").compareTo(displayPrice(restarted,fresh,finish)));assertEquals(0,reopened.commandConfig(symbol.getId()).getControlPriceOffset().signum());
        assertEquals(2,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
    }

    @Test void restoreOutageBusinessStopCreatesDurableHoldRatherThanFrozenHealthyPrice(){
        PersistentPriceControl.Task task=restoreTask(3,"restore_outage_stop");long start=((Number)flow(task.id).get("recovery_started_at")).longValue(),active=start+400;
        Map<String,Object> valid=priced(active,"90");controls.sourceQuote(symbol,valid,active);BigDecimal before=displayPrice(controls,valid,active);long count=samples(task.id);
        controls.pump(symbol,raw(active+1,false),active+1,60000);assertEquals("WAITING_SOURCE",flow(task.id).get("state"));assertEquals(count,samples(task.id));
        controls.stopAndHold(symbol.getId(),active+2);assertEquals("HOLDING",flow(task.id).get("state"));
        assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_hold WHERE tenant_id=1 AND task_id=? AND activated_at IS NOT NULL AND released_at IS NULL",Integer.class,task.id),"Business STOP during WAITING_SOURCE must persist a real hold, not a frozen healthy flow");
        PersistentPriceControl restarted=new PersistentPriceControl(store);long freshAt=active+200;Map<String,Object> changed=priced(freshAt,"93");restarted.sourceQuote(symbol,changed,freshAt);
        assertEquals("HOLDING",flow(task.id).get("state"));assertEquals(0,before.add(new BigDecimal("3")).compareTo(displayPrice(restarted,changed,freshAt)));assertTrue(((Number)flow(task.id).get("last_at")).longValue()>=freshAt);assertEquals(count+1,samples(task.id));
        Map<String,Object> status=new LinkedHashMap<>();restarted.status(symbol,status,changed,freshAt);assertEquals(false,status.get("restoring"));assertEquals("HOLDING",status.get("controlState"));
    }

    @Test void recoveryEmergencySourceAcrossComponentRestartCannotBeRevivedByOldQueueOrOriginalKey(){
        String key="restore_emergency_source";PersistentPriceControl.Task task=restoreTask(3,key);long start=((Number)flow(task.id).get("recovery_started_at")).longValue(),at=start+500;
        Map<String,Object> valid=priced(at,"92");controls.sourceQuote(symbol,valid,at);long count=samples(task.id),watermark=((Number)flow(task.id).get("last_at")).longValue();
        controls.emergencySource(symbol.getId(),at+1);controls.pumpSource(symbol,raw(at+2,false),at+2,60000);assertEquals("SOURCE",flow(task.id).get("state"));assertEquals(false,controls.display(symbol,raw(at+2,false),at+2).get("tradeAvailable"));
        PersistentPriceControl restarted=new PersistentPriceControl(store);MarketControlCommands reopened=newQueue(store,newMarket(store,restarted,symbol));assertEquals(task.id,reopened.acceptRestore(symbol.getId(),3,1,false,key).get("taskId"));
        reopened.runOne();commands.runOne();assertEquals("SOURCE",flow(task.id).get("state"));assertEquals(watermark,((Number)flow(task.id).get("last_at")).longValue());assertEquals(count,samples(task.id));
        long freshAt=at+200;Map<String,Object> fresh=priced(freshAt,"94");restarted.sourceQuote(symbol,fresh,freshAt);commands.runOne();assertEquals("SOURCE",flow(task.id).get("state"));assertEquals(0,new BigDecimal("94").compareTo(displayPrice(restarted,fresh,freshAt)));assertEquals(count,samples(task.id));
    }

    @Test void repeatedSourceOutagesPreserveRemainingRecoveryTimeAndNeverCreateOutageSamples(){
        PersistentPriceControl.Task task=restoreTask(1,"restore_repeated_outages");PersistentPriceControl engine=controls;long next=((Number)flow(task.id).get("recovery_started_at")).longValue()+100,previousRemaining=1000;
        for(int cycle=0;cycle<3;cycle++){
            Map<String,Object> valid=priced(next,String.valueOf(90+cycle));engine.sourceQuote(symbol,valid,next);BigDecimal shown=displayPrice(engine,valid,next);long count=samples(task.id);
            engine.pump(symbol,raw(next+1,false),next+1,60000);Map<String,Object> paused=flow(task.id);long remaining=((Number)paused.get("remaining_millis")).longValue();assertEquals("WAITING_SOURCE",paused.get("state"));assertTrue(remaining<previousRemaining);assertTrue(remaining>0);assertEquals(count,samples(task.id));
            if(cycle==1)engine=new PersistentPriceControl(store);engine.pump(symbol,raw(next+300,false),next+300,60000);assertEquals(paused,flow(task.id));assertEquals(count,samples(task.id));
            long back=next+301;Map<String,Object> restored=priced(back,String.valueOf(93+cycle));engine.sourceQuote(symbol,restored,back);assertEquals("RECOVERING",flow(task.id).get("state"));assertEquals(0,shown.compareTo(displayPrice(engine,restored,back)));assertEquals(remaining,((Number)flow(task.id).get("remaining_millis")).longValue());previousRemaining=remaining;next=back+100;
        }
        Map<String,Object> current=flow(task.id);long finish=((Number)current.get("recovery_started_at")).longValue()+((Number)current.get("remaining_millis")).longValue()+1;Map<String,Object> finalSource=priced(finish,"98");engine.sourceQuote(symbol,finalSource,finish);assertEquals("SOURCE",flow(task.id).get("state"));assertEquals(0,new BigDecimal("98").compareTo(displayPrice(engine,finalSource,finish)));
    }

    @Test void recoverySourceJumpAndLateOrDuplicateProviderEventsCannotReverseCommittedWatermarks(){
        PersistentPriceControl.Task task=restoreTask(2,"restore_source_jump");long start=((Number)flow(task.id).get("recovery_started_at")).longValue(),at=start+500;Map<String,Object> newer=priced(at,"100");controls.sourceQuote(symbol,newer,at);
        Map<String,Object> committedFlow=flow(task.id),committedQuote=store.db.queryForMap("SELECT quote_json,snapshot_version FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId()),last=store.lastQuote(symbol.getId());long count=samples(task.id),events=store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId());
        controls.sourceQuote(symbol,priced(at-1000,"1"),at+100);controls.sourceQuote(symbol,newer,at+101);
        assertEquals(committedFlow,flow(task.id));assertEquals(committedQuote,store.db.queryForMap("SELECT quote_json,snapshot_version FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId()));assertEquals(last,store.lastQuote(symbol.getId()));assertEquals(count,samples(task.id));assertEquals(events,store.db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId()));
        Map<String,Object> jump=priced(start+1200,"70");controls.sourceQuote(symbol,jump,start+1200);assertTrue(((Number)flow(task.id).get("last_at")).longValue()>((Number)committedFlow.get("last_at")).longValue());BigDecimal shown=displayPrice(controls,jump,start+1200);assertTrue(shown.compareTo(new BigDecimal("70"))>=0 && shown.compareTo(new BigDecimal("75"))<=0);
        long finish=start+2001;Map<String,Object> finalSource=priced(finish,"80");controls.sourceQuote(symbol,finalSource,finish);assertEquals("SOURCE",flow(task.id).get("state"));assertEquals(0,new BigDecimal("80").compareTo(displayPrice(controls,finalSource,finish)));
    }

    @Test void quickAutoRecoveryCannotOverrideExplicitStopAtLastMillisecond(){
        RecoveryOptions options=new RecoveryOptions();options.setAutoRestore(true);options.setRestoreMode("QUICK");PersistentPriceControl.Task task=targetTask(2,"quick_stop_boundary",options);long cutoff=task.plannedEnd-1;
        controls.stopAndHold(symbol.getId(),cutoff);controls.stopAndHold(symbol.getId(),cutoff);PersistentPriceControl.Task stopped=controls.latest(symbol.getId());assertEquals("STOPPED",stopped.status);assertEquals(cutoff,stopped.stopAt.longValue());assertEquals(task.plannedEnd-1000,stopped.sampledUntil);
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_sample WHERE tenant_id=1 AND task_id=? AND generated_at=?",Integer.class,task.id,task.plannedEnd));long count=samples(task.id);
        Map<String,Object> changed=priced(task.plannedEnd,"93");controls.sourceQuote(symbol,changed,task.plannedEnd);assertEquals("HOLDING",flow(task.id).get("state"));assertEquals(count+1,samples(task.id));assertEquals("STOPPED",controls.latest(symbol.getId()).status);assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_resume WHERE tenant_id=1 AND task_id=?",Integer.class,task.id));
    }

    @Test void quickRecoveryAtExactEndpointIsUniqueAndRepeatedSameEventCannotAddAnotherSample(){
        RecoveryOptions options=new RecoveryOptions();options.setAutoRestore(true);options.setRestoreMode("QUICK");PersistentPriceControl.Task task=targetTask(2,"quick_exact_endpoint",options);Map<String,Object> changed=priced(task.plannedEnd,"93");
        controls.sourceQuote(symbol,changed,task.plannedEnd);assertEquals("COMPLETED",controls.latest(symbol.getId()).status);assertEquals("SOURCE",flow(task.id).get("state"));assertEquals(task.plannedEnd+1,((Number)flow(task.id).get("last_at")).longValue());assertEquals(0,new BigDecimal("93").compareTo(displayPrice(controls,changed,task.plannedEnd)));
        long count=samples(task.id);controls.sourceQuote(symbol,changed,task.plannedEnd);controls.pump(symbol,changed,task.plannedEnd,60000);assertEquals(count,samples(task.id));assertEquals(count,store.db.queryForObject("SELECT COUNT(DISTINCT generated_at) FROM market_control_sample WHERE tenant_id=1 AND task_id=?",Long.class,task.id));assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_resume WHERE tenant_id=1 AND task_id=?",Integer.class,task.id));
    }

    @Test void repeatedStopSourceAndOriginalRestoreReceiptNeverCreateOrRestartAnotherTask(){
        String key="restore_repeat_original";PersistentPriceControl.Task task=restoreTask(3,key);Map<String,Object> receipt=commands.query(symbol.getId(),key);assertEquals(receipt.get("commandId"),commands.acceptRestore(symbol.getId(),3,1,false,key).get("commandId"));commands.runOne();
        commands.stopControl(symbol.getId(),key);commands.stopControl(symbol.getId(),key);assertEquals("HOLDING",flow(task.id).get("state"));assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_hold WHERE tenant_id=1 AND task_id=? AND activated_at IS NOT NULL AND released_at IS NULL",Integer.class,task.id));
        commands.manualControl(symbol.getId(),false,BigDecimal.ZERO,key);commands.manualControl(symbol.getId(),false,BigDecimal.ZERO,key);assertEquals("SOURCE",flow(task.id).get("state"));long count=samples(task.id);
        assertEquals(receipt.get("commandId"),commands.acceptRestore(symbol.getId(),3,1,false,key).get("commandId"));commands.runOne();commands.stopControl(symbol.getId(),key);assertEquals("SOURCE",flow(task.id).get("state"));assertEquals(count,samples(task.id));
        assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",Integer.class,symbol.getId(),key));
    }

    @Test void recoverySchedulerLongGapRebasesFromCommittedDisplayRatherThanJumpingToSource(){
        PersistentPriceControl.Task task=restoreTask(3,"restore_scheduler_gap");long start=((Number)flow(task.id).get("recovery_started_at")).longValue(),progressAt=start+500;
        Map<String,Object> valid=priced(progressAt,"91");controls.sourceQuote(symbol,valid,progressAt);Map<String,Object> committed=flow(task.id);BigDecimal shown=displayPrice(controls,valid,progressAt);long count=samples(task.id);
        long afterGap=progressAt+4000;Map<String,Object> fresh=priced(afterGap,"93");controls.sourceQuote(symbol,fresh,afterGap);Map<String,Object> resumed=flow(task.id);
        assertEquals("RECOVERING",resumed.get("state"),"Unobserved same-process scheduler downtime must not finish recovery");assertEquals(afterGap,((Number)resumed.get("recovery_started_at")).longValue());assertEquals(2500,((Number)resumed.get("remaining_millis")).longValue());
        assertEquals(0,shown.compareTo(displayPrice(controls,fresh,afterGap)));assertEquals(count+1,samples(task.id));assertTrue(((Number)resumed.get("last_at")).longValue()>((Number)committed.get("last_at")).longValue());
    }

    @Test void legacyTaskOnlyRestoreKeyCannotMisassociateQueueReceiptOrClearNewManualRule(){
        prime();market.manualControl(symbol.getId(),true,new BigDecimal("5"));String key="legacy_restore_task_only";market.restoreControl(symbol.getId(),2,1,false,key);PersistentPriceControl.Task legacy=controls.latest(symbol.getId());
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",Integer.class,symbol.getId(),key));
        market.manualControl(symbol.getId(),false,BigDecimal.ZERO);market.manualControl(symbol.getId(),true,new BigDecimal("7"));long now=System.currentTimeMillis();Map<String,Object> before=controls.display(symbol,source.get(),now);assertEquals("MANUAL",before.get("controlState"));BigDecimal price=ControlHistoryStore.number(before.get("price"));
        org.springframework.web.server.ResponseStatusException rejected=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->commands.acceptRestore(symbol.getId(),2,1,false,key));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,rejected.getStatus());assertEquals("LEGACY_REQUEST_KEY_REQUIRES_RECONCILIATION",rejected.getReason());
        assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->commands.query(symbol.getId(),key));Map<String,Object> after=controls.display(symbol,source.get(),System.currentTimeMillis());assertEquals(legacy.id,controls.latest(symbol.getId()).id);
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",Integer.class,symbol.getId(),key));
        assertEquals("MANUAL",after.get("controlState"),"Task-only request-key replay must not clear a newer explicit manual rule");assertEquals(0,price.compareTo(ControlHistoryStore.number(after.get("price"))));assertEquals(true,market.commandConfig(symbol.getId()).getControlEnabled());assertEquals(0,new BigDecimal("7").compareTo(market.commandConfig(symbol.getId()).getControlPriceOffset()));
        assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
    }

    @Test void legacyTaskOnlyStartKeyIsExplicitlyRejectedWithoutCommandOrCurrentTaskMutation(){
        String key="legacy_start_task_only";PersistentPriceControl.Task legacy=targetTask(2,key,new RecoveryOptions());controls.emergencySource(symbol.getId(),System.currentTimeMillis());
        PersistentPriceControl.Task current=targetTask(2,"newer_current_target",new RecoveryOptions());assertNotEquals(legacy.id,current.id);
        Map<String,Object> before=store.db.queryForMap("SELECT * FROM market_control_task WHERE tenant_id=1 AND id=?",current.id),snapshot=store.db.queryForMap("SELECT quote_json,status_json,snapshot_version,control_revision FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId());
        org.springframework.web.server.ResponseStatusException rejected=assertThrows(org.springframework.web.server.ResponseStatusException.class,()->commands.accept(symbol.getId(),2,new BigDecimal("94"),1,false,key,options()));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT,rejected.getStatus());assertEquals("LEGACY_REQUEST_KEY_REQUIRES_RECONCILIATION",rejected.getReason());assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->commands.query(symbol.getId(),key));
        assertEquals(current.id,controls.latest(symbol.getId()).id);assertEquals(before,store.db.queryForMap("SELECT * FROM market_control_task WHERE tenant_id=1 AND id=?",current.id));assertEquals(snapshot,store.db.queryForMap("SELECT quote_json,status_json,snapshot_version,control_revision FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",symbol.getId()));
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",Integer.class,symbol.getId(),key));assertEquals(2,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
    }

    @Test void acceptStartAndRestoreDoesNotWaitForBusyEngineTransaction() throws Exception {
        prime();CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService writer=Executors.newSingleThreadExecutor();
        try{
            Future<?> busy=writer.submit(()->{try(TenantContext.Scope tenant=TenantContext.open(1L)){store.locked(symbol.getId(),()->{locked.countDown();try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;});}});
            assertTrue(locked.await(5,TimeUnit.SECONDS));long start=System.nanoTime();Map<String,Object> accepted=accept("busy_accept_start");assertEquals("ACCEPTED",accepted.get("state"));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500,"Acceptance waited on runtime writer");
            release.countDown();busy.get(10,TimeUnit.SECONDS);
        }finally{release.countDown();writer.shutdownNow();}
        commands.cancel(symbol.getId(),"busy_accept_start");
        TradingSymbol restoreSymbol=newSymbol(1L);ForexQuoteMarketService restoreMarket=newMarket(store,controls,restoreSymbol);MarketControlCommands restoreQueue=newQueue(store,restoreMarket);
        store.locked(restoreSymbol.getId(),()->null);ExecutorService writer2=Executors.newSingleThreadExecutor();CountDownLatch locked2=new CountDownLatch(1),release2=new CountDownLatch(1);
        try{
            Future<?> busy=writer2.submit(()->{try(TenantContext.Scope tenant=TenantContext.open(1L)){store.locked(restoreSymbol.getId(),()->{locked2.countDown();try{assertTrue(release2.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;});}});
            assertTrue(locked2.await(5,TimeUnit.SECONDS));long start=System.nanoTime();Map<String,Object> accepted=restoreQueue.acceptRestore(restoreSymbol.getId(),20,1,false,"busy_accept_restore");assertEquals("RESTORE",accepted.get("action"));assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<1500,"Restore acceptance waited on runtime writer");release2.countDown();busy.get(10,TimeUnit.SECONDS);
        }finally{release2.countDown();writer2.shutdownNow();}
        restoreQueue.cancel(restoreSymbol.getId(),"busy_accept_restore");
    }

    @Test void unknownCancellationIsDurableBlocksLateAcceptAndIsTenantScoped() throws Exception {
        String key="unknown_cancel_original";Map<String,Object> cancelled=commands.cancel(symbol.getId(),key);assertEquals("CANCELLED",cancelled.get("state"));assertNotNull(cancelled.get("commandId"));
        MarketControlCommands reopened=newQueue(store,newMarket(store,new PersistentPriceControl(store),symbol));assertEquals(cancelled.get("commandId"),reopened.query(symbol.getId(),key).get("commandId"));
        assertEquals("CANCELLED",reopened.accept(symbol.getId(),20,new BigDecimal("91"),1,false,key,options()).get("state"));assertEquals("CANCELLED",reopened.acceptRestore(symbol.getId(),20,1,false,key).get("state"));reopened.runOne();
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
        ExecutorService other=Executors.newSingleThreadExecutor();
        try{other.submit(()->{try(TenantContext.Scope tenant=TenantContext.open(2L)){
            S2CommandAcceptanceTest.identity(2L);TradingSymbol tenant2=newSymbol(2L);MarketControlCommands own=newQueue(store,newMarket(store,controls,tenant2));
            assertEquals("ACCEPTED",own.accept(tenant2.getId(),20,new BigDecimal("91"),1,false,key,options()).get("state"));
            assertThrows(RuntimeException.class,()->own.query(symbol.getId(),key));own.cancel(tenant2.getId(),key);
        }finally{SecurityContextHolder.clearContext();}}).get(10,TimeUnit.SECONDS);}finally{other.shutdownNow();}
    }

    @Test void preparingWorkerCannotResurrectAfterCancellationOrEmergencySource() throws Exception {
        prime();String key="preparing_late_worker";accept(key);CountDownLatch prepared=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
        doAnswer(call->{PersistentPriceControl.Prepared plan=(PersistentPriceControl.Prepared)call.callRealMethod();prepared.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){plan.close();throw new RuntimeException(e);}return plan;}).when(market).prepareCommand(anyLong(),anyInt(),any(BigDecimal.class),anyInt(),anyBoolean(),any(TargetControlOptions.class),anyLong());
        try{
            Future<?> turn=worker.submit(()->{try(TenantContext.Scope tenant=TenantContext.open(1L)){commands.runOne();}});assertTrue(prepared.await(10,TimeUnit.SECONDS));assertEquals("PREPARING",commands.query(symbol.getId(),key).get("state"));
            controls.emergencySource(symbol.getId(),System.currentTimeMillis());release.countDown();turn.get(10,TimeUnit.SECONDS);
            assertEquals("CANCELLED",commands.query(symbol.getId(),key).get("state"));assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));
        }finally{release.countDown();worker.shutdownNow();}
    }

    @Test void actualLeaseExpiryAllowsNewProcessButNeverReauthorizesOldWorker() throws Exception {
        prime();String key="restart_original_key";Map<String,Object> accepted=accept(key);commands.stop();
        long expiry=store.db.queryForObject("SELECT lease_until FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId());
        Thread.sleep(Math.max(0,expiry-store.runtime.clock()+50));
        ControlHistoryStore nextStore=new ControlHistoryStore(new JdbcTemplate(data),new DataSourceTransactionManager(data));PersistentPriceControl nextControls=new PersistentPriceControl(nextStore);
        ForexQuoteMarketService nextMarket=newMarket(nextStore,nextControls,symbol);MarketControlCommands next=newQueue(nextStore,nextMarket);
        assertEquals(accepted.get("commandId"),next.query(symbol.getId(),key).get("commandId"));nextControls.sourceQuote(symbol,raw(System.currentTimeMillis(),true),System.currentTimeMillis());nextControls.pump(symbol,raw(System.currentTimeMillis(),true),System.currentTimeMillis(),60000);
        next.runOne();assertEquals("RUNNING",next.query(symbol.getId(),key).get("state"));assertEquals(accepted.get("commandId"),next.accept(symbol.getId(),20,new BigDecimal("91"),1,false,key,options()).get("commandId"));
        assertThrows(RuntimeException.class,()->controls.pump(symbol,raw(System.currentTimeMillis(),true),System.currentTimeMillis(),60000));
        next.runOne();assertEquals(1,nextStore.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
        nextControls.emergencySource(symbol.getId(),System.currentTimeMillis());
    }

    @Test void committedAcceptanceSurvivesActualJvmHaltAndReplaysOnlyOneTask() throws Exception {
        String key="jvm_halt_original_"+UUID.randomUUID().toString().replace("-","");
        String java=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path log=Paths.get(System.getenv("CONTROL_RECOVERY_OUTPUT"),"child-"+key+".log");
        Process child=new ProcessBuilder(java,"-cp",classpath,ControlRecoveryAcceptanceCrashChild.class.getName(),key).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            assertTrue(child.waitFor(30,TimeUnit.SECONDS),"Child did not halt after commit");String output=Files.readString(log,StandardCharsets.UTF_8);assertEquals(74,child.exitValue(),output);
            String marker=Arrays.stream(output.split("\\R")).filter(line->line.startsWith("CONTROL_RECOVERY_ACCEPTED_COMMITTED ")).findFirst().orElseThrow(()->new AssertionError("Committed receipt marker absent: "+log));
            String[] parts=marker.split(" ");symbol.setId(Long.parseLong(parts[2]));symbol.setSymbol("RECOVERY_"+symbol.getId());
            assertEquals(parts[1],commands.query(symbol.getId(),key).get("commandId"));assertEquals("ACCEPTED",commands.query(symbol.getId(),key).get("state"));
            assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()),"Acceptance must not acquire engine lease");
            prime();commands.runOne();assertEquals("RUNNING",commands.query(symbol.getId(),key).get("state"));assertEquals(parts[1],accept(key).get("commandId"));commands.runOne();
            assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=? AND request_key=?",Integer.class,symbol.getId(),key));
        }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}}
    }

    @Test void cancellationRepeatableReadSnapshotCannotHideWinningActivation() throws Exception {
        prime();market.manualControl(symbol.getId(),true,new BigDecimal("5"));String key="cancel_activation_wins";
        commands.acceptRestore(symbol.getId(),20,1,false,key);
        CountDownLatch activationLocked=new CountDownLatch(1),cancelUpdateIssued=new CountDownLatch(1);
        AtomicReference<Map<String,Object>> snapshot=new AtomicReference<>();AtomicInteger cancelChanged=new AtomicInteger(-1);
        AtomicLong cancelConnection=new AtomicLong();AtomicBoolean paused=new AtomicBoolean(),blockedUpdateObserved=new AtomicBoolean();
        JdbcTemplate observed=new JdbcTemplate(data){
            @Override public Map<String,Object> queryForMap(String sql,Object... args){
                Map<String,Object> row=super.queryForMap(sql,args);
                if(Thread.currentThread().getName().equals("mysql-race-worker") && sql.startsWith("SELECT * FROM market_control_command") && sql.endsWith("FOR UPDATE") && "PREPARING".equals(row.get("state")) && paused.compareAndSet(false,true)){
                    activationLocked.countDown();
                    try{
                        assertTrue(cancelUpdateIssued.await(5,TimeUnit.SECONDS));long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                        while(!blockedUpdateObserved.get() && System.nanoTime()<deadline){
                            for(Map<String,Object> process:super.queryForList("SHOW FULL PROCESSLIST"))
                                if(((Number)process.get("Id")).longValue()==cancelConnection.get() && String.valueOf(process.get("Info")).startsWith("UPDATE market_control_command SET state='CANCELLED'"))blockedUpdateObserved.set(true);
                            if(!blockedUpdateObserved.get())Thread.sleep(10);
                        }
                        assertTrue(blockedUpdateObserved.get(),"MySQL did not observe cancellation UPDATE while activation held the command row lock");
                    }catch(InterruptedException e){throw new RuntimeException(e);}
                }
                return row;
            }
            @Override public List<Map<String,Object>> queryForList(String sql,Object... args){
                List<Map<String,Object>> rows=super.queryForList(sql,args);
                if(Thread.currentThread().getName().equals("mysql-race-cancel") && sql.startsWith("SELECT * FROM market_control_command WHERE tenant_id=? AND symbol_id=? AND request_key=?") && !rows.isEmpty()){
                    snapshot.compareAndSet(null,new LinkedHashMap<>(rows.get(0)));
                    execute((Connection connection)->{assertEquals(Connection.TRANSACTION_REPEATABLE_READ,connection.getTransactionIsolation());return null;});
                }
                return rows;
            }
            @Override public int update(String sql,Object... args){
                if(Thread.currentThread().getName().equals("mysql-race-cancel") && sql.startsWith("UPDATE market_control_command SET state='CANCELLED'")){
                    assertNotNull(snapshot.get());assertEquals("PREPARING",snapshot.get().get("state"));
                    cancelConnection.set(queryForObject("SELECT CONNECTION_ID()",Long.class));cancelUpdateIssued.countDown();
                    int changed=super.update(sql,args);cancelChanged.set(changed);return changed;
                }
                return super.update(sql,args);
            }
        };
        ReflectionTestUtils.setField(store,"db",observed);
        ExecutorService worker=Executors.newSingleThreadExecutor(r->new Thread(r,"mysql-race-worker"));
        ExecutorService canceller=Executors.newSingleThreadExecutor(r->new Thread(r,"mysql-race-cancel"));
        try{
            Future<?> activation=worker.submit(()->{try(TenantContext.Scope tenant=TenantContext.open(1L)){commands.runOne();}});
            assertTrue(activationLocked.await(5,TimeUnit.SECONDS));
            Future<Map<String,Object>> cancellation=canceller.submit(()->{try(TenantContext.Scope tenant=TenantContext.open(1L)){S2CommandAcceptanceTest.identity(1L);return commands.cancel(symbol.getId(),key);}finally{SecurityContextHolder.clearContext();}});
            Map<String,Object> receipt=cancellation.get(10,TimeUnit.SECONDS);activation.get(10,TimeUnit.SECONDS);
            assertEquals(0,cancelChanged.get(),"Conditional cancellation must lose to the committed activation");
            assertTrue(blockedUpdateObserved.get(),"Cancellation UPDATE did not wait on the actual MySQL command row lock");
            Map<String,Object> committed=commands.query(symbol.getId(),key);assertEquals("RUNNING",committed.get("state"));
            assertEquals(committed.get("state"),receipt.get("state"),"Cancellation receipt came from the stale PREPARING RR snapshot");
            assertEquals(committed.get("taskId"),receipt.get("taskId"));assertNotNull(receipt.get("taskId"));
            assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=? AND request_key=?",Integer.class,symbol.getId(),key));
        }finally{cancelUpdateIssued.countDown();worker.shutdownNow();canceller.shutdownNow();}
    }

    @Test void readyCommitThenCancelPreventsLateActivation() throws Exception {
        prime();String key="ready_late_worker";accept(key);CountDownLatch ready=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService worker=Executors.newSingleThreadExecutor();
        JdbcTemplate observed=new JdbcTemplate(data){@Override public int update(String sql,Object... args){int changed=super.update(sql,args);if(sql.contains("SET state='READY'"))org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){@Override public void afterCommit(){ready.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}}});return changed;}};
        ReflectionTestUtils.setField(store,"db",observed);
        try{
            Future<?> turn=worker.submit(()->{try(TenantContext.Scope tenant=TenantContext.open(1L)){commands.runOne();}});assertTrue(ready.await(10,TimeUnit.SECONDS));assertEquals("READY",commands.query(symbol.getId(),key).get("state"));
            commands.cancel(symbol.getId(),key);release.countDown();turn.get(10,TimeUnit.SECONDS);assertEquals("CANCELLED",commands.query(symbol.getId(),key).get("state"));
            assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));
        }finally{release.countDown();worker.shutdownNow();}
    }

    @Test void tomcatStartAndRestoreLost202ResponsesRecoverByOriginalKey() throws Exception {
        prime();httpOwner=this;
        try(HttpHarness http=new HttpHarness()){
            String key="socket_lost_start";http.discard202("start",key);Map<String,Object> receipt=http.query(key);assertEquals("START",receipt.get("action"));assertEquals("ACCEPTED",receipt.get("state"));commands.runOne();assertEquals("RUNNING",http.query(key).get("state"));
            controls.emergencySource(symbol.getId(),System.currentTimeMillis());controls.pump(symbol,raw(System.currentTimeMillis(),true),System.currentTimeMillis(),60000);
            String restore="socket_lost_restore";http.discard202("restore",restore);assertEquals("RESTORE",http.query(restore).get("action"));commands.runOne();assertEquals("RUNNING",http.query(restore).get("state"));
            assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=? AND request_key=?",Integer.class,symbol.getId(),key));
            assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=? AND request_key=?",Integer.class,symbol.getId(),restore));
        }finally{httpOwner=null;}
    }

    private String rawMessage(String key) { return store.db.queryForObject("SELECT message FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?", String.class, symbol.getId(), key); }
    private void messageReceipt(String key, String message) {
        assertEquals(message, commands.query(symbol.getId(), key).get("message"));
        String raw = rawMessage(key);assertTrue(raw.startsWith("~mcc1~"));assertTrue(raw.length() <= 255);assertTrue(raw.chars().allMatch(c -> c < 128));
    }
    @Test void nativeLatin1RejectsOriginalChineseButUnknownCancelRoundTripsAndBlocksLateRequests() {
        String key="latin1_unknown_cancel";commands.cancel(symbol.getId(),key);messageReceipt(key,"控制请求已取消");
        RuntimeException error=assertThrows(RuntimeException.class,()->store.db.update("UPDATE market_control_command SET message=? WHERE tenant_id=1 AND symbol_id=? AND request_key=?","控制请求已取消",symbol.getId(),key));
        SQLException sql=null;for(Throwable cause=error;cause!=null;cause=cause.getCause())if(cause instanceof SQLException)sql=(SQLException)cause;
        assertNotNull(sql);assertEquals(1366,sql.getErrorCode());messageReceipt(key,"控制请求已取消");
        MarketControlCommands reopened=newQueue(store,newMarket(store,new PersistentPriceControl(store),symbol));
        assertEquals("控制请求已取消",reopened.query(symbol.getId(),key).get("message"));
        assertEquals("CANCELLED",reopened.accept(symbol.getId(),20,new BigDecimal("91"),1,false,key,options()).get("state"));
        assertEquals("CANCELLED",reopened.acceptRestore(symbol.getId(),20,1,false,key).get("state"));
        store.db.update("UPDATE market_control_command SET message=? WHERE tenant_id=1 AND symbol_id=? AND request_key=?","Legacy cancelled: café",symbol.getId(),key);
        assertEquals("Legacy cancelled: café",reopened.query(symbol.getId(),key).get("message"));
    }
    @Test void nativeLatin1NewUnicodeParametersAndPreparedJsonRetainPlanAndHash() {
        prime();String key="latin1_unicode_plan";TargetControlOptions options=options();options.setStepFormula("0.1 − 0");
        AtomicReference<String> ready=new AtomicReference<>();
        JdbcTemplate observed=new JdbcTemplate(data){@Override public int update(String sql,Object... args){int changed=super.update(sql,args);if(sql.contains("SET state='READY'"))ready.set((String)args[0]);return changed;}};
        ReflectionTestUtils.setField(store,"db",observed);
        commands.accept(symbol.getId(),20,new BigDecimal("91"),1,false,key,options);
        Map<String,Object> row=store.db.queryForMap("SELECT parameters_json,parameter_hash FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",symbol.getId(),key);
        String parameters=(String)row.get("parameters_json");assertTrue(parameters.chars().allMatch(c->c<128));assertEquals("0.1 − 0",store.decode(parameters).get("stepFormula"));
        commands.runOne();assertEquals("RUNNING",commands.query(symbol.getId(),key).get("state"));assertNotNull(ready.get());assertTrue(ready.get().chars().allMatch(c->c<128));
        assertEquals("0.1 − 0",store.decode((String)store.decode(ready.get()).get("parameters_json")).get("stepFormula"));
        assertEquals(row.get("parameter_hash"),store.db.queryForObject("SELECT parameter_hash FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",String.class,symbol.getId(),key));
        controls.emergencySource(symbol.getId(),System.currentTimeMillis());
    }
    @Test void nativeLatin1LongestActualInvalidFormulaReachesDurableFailedReceipt() {
        prime();String key="latin1_formula_failed";TargetControlOptions options=options();options.setStepFormula(String.join("",Collections.nCopies(256,"中")));
        commands.accept(symbol.getId(),20,new BigDecimal("91"),1,false,key,options);commands.runOne();
        assertEquals("FAILED",commands.query(symbol.getId(),key).get("state"));assertEquals("INVALID_FORMULA",commands.query(symbol.getId(),key).get("errorCode"));
        messageReceipt(key,"单步幅度公式第257字符：未知变量或函数；允许start/target/gap/duration/intensity/tick/base及abs/min/max");
    }
    @Test void nativeLatin1LongestBoundedWorkerFailureRemainsCompleteAndNotTruncated() {
        prime();String key="latin1_corridor_failed";String message="偏差带过窄，无法容纳当前波动；请增加执行时间、降低波动强度或手动放宽偏差带后重新预览，不会自动扩大手动范围";
        accept(key);doThrow(new BalancedControlPlan.Failure("CORRIDOR_STEP_INFEASIBLE",message)).when(market).prepareCommand(anyLong(),anyInt(),any(BigDecimal.class),anyInt(),anyBoolean(),any(TargetControlOptions.class),anyLong());
        commands.runOne();assertEquals("FAILED",commands.query(symbol.getId(),key).get("state"));messageReceipt(key,message);assertEquals(218,rawMessage(key).length());
    }
    @Test void nativeLatin1DeferredAndExhaustedReceiptsPreserveCompleteMessages() {
        String key="latin1_deferred_retry";accept(key);
        Map<String,Object> command=store.db.queryForMap("SELECT * FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",symbol.getId(),key);
        for(int attempt=1;attempt<=5;attempt++){
            ReflectionTestUtils.invokeMethod(commands,"defer",command,symbol.getId(),"ENGINE_BUSY",null);
            assertEquals(attempt,commands.query(symbol.getId(),key).get("retryCount"));
            messageReceipt(key,attempt==5?"控制命令有限重试已耗尽":"引擎暂不可用，等待有限退避");
        }
        assertEquals("FAILED",commands.query(symbol.getId(),key).get("state"));assertEquals("COMMAND_RETRY_EXHAUSTED",commands.query(symbol.getId(),key).get("errorCode"));
    }
    @Test void nativeLatin1PendingCancelAndEmergencySourceShareLosslessMessageCodec() {
        prime();String cancelled="latin1_pending_cancel",emergency="latin1_emergency";
        accept(cancelled);commands.cancel(symbol.getId(),cancelled);messageReceipt(cancelled,"控制准备已取消");
        accept(emergency);controls.emergencySource(symbol.getId(),System.currentTimeMillis());messageReceipt(emergency,"应急回源已取消准备");
        assertEquals("CANCELLED",commands.query(symbol.getId(),emergency).get("state"));assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
    }

    @Configuration @EnableWebMvc static class HttpConfiguration {
        @Bean static org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor fixtureWiring(){return new org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessor(){@Override public boolean postProcessAfterInstantiation(Object bean,String name){return !"market".equals(name);}};}
        @Bean ForexQuoteMarketService market(){return httpOwner.market;}
        @Bean PersistentPriceControl controls(){return httpOwner.controls;}
        @Bean MarketControlCommands commands(){return httpOwner.commands;}
        @Bean AdminAiControlController controller(){return new AdminAiControlController();}
        @Bean GlobalExceptionHandler errors(){return new GlobalExceptionHandler();}
    }
    final class HttpHarness implements AutoCloseable {
        final Tomcat tomcat=new Tomcat();final CountDownLatch committed=new CountDownLatch(1),clientClosed=new CountDownLatch(1);final AtomicInteger acceptedStatus=new AtomicInteger();final AtomicInteger drops=new AtomicInteger();int port;
        HttpHarness() throws Exception {
            Path temp=Paths.get(System.getenv("CONTROL_RECOVERY_OUTPUT"),"tomcat-"+symbol.getId());Files.createDirectories(temp);tomcat.setBaseDir(temp.toString());tomcat.setPort(0);tomcat.getConnector().setProperty("address","127.0.0.1");
            Context context=tomcat.addContext("",temp.toString());context.setParentClassLoader(getClass().getClassLoader());
            AnnotationConfigWebApplicationContext application=new AnnotationConfigWebApplicationContext();application.register(HttpConfiguration.class);
            Tomcat.addServlet(context,"dispatcher",new DispatcherServlet(application)).setLoadOnStartup(1);context.addServletMappingDecoded("/*","dispatcher");
            context.getPipeline().addValve(new ValveBase(){@Override public void invoke(Request request,Response response)throws java.io.IOException,javax.servlet.ServletException{
                try(TenantContext.Scope tenant=TenantContext.open(1L)){S2CommandAcceptanceTest.identity(1L);getNext().invoke(request,response);
                    if("yes".equals(request.getHeader("X-Acceptance-Drop"))){acceptedStatus.set(response.getStatus());drops.incrementAndGet();committed.countDown();try{assertTrue(clientClosed.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new java.io.IOException(e);}}
                }finally{SecurityContextHolder.clearContext();}
            }});
            try{tomcat.start();port=tomcat.getConnector().getLocalPort();}catch(Exception failed){tomcat.stop();tomcat.destroy();throw failed;}
        }
        void discard202(String action,String key)throws Exception {
            String payload="{\"requestKey\":\""+key+"\",\"durationSeconds\":20,\"intensity\":1,\"randomOscillation\":false"+(action.equals("start")?",\"targetPrice\":91,\"deviationBandMode\":\"MANUAL\",\"deviationBandPercent\":1,\"stepFormula\":\"0.1\"":"")+"}";
            byte[] body=payload.getBytes(StandardCharsets.UTF_8);int before=drops.get();
            try(Socket socket=new Socket("127.0.0.1",port)){
                String head="POST /api/admin/ai-control/"+symbol.getId()+"/"+action+" HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: "+body.length+"\r\nX-Acceptance-Drop: yes\r\nConnection: close\r\n\r\n";
                socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));socket.getOutputStream().write(body);socket.getOutputStream().flush();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(drops.get()==before && System.nanoTime()<deadline)Thread.sleep(10);assertEquals(before+1,drops.get());assertEquals(202,acceptedStatus.get());
                socket.setSoLinger(true,0); // Actual client disconnect; no response bytes consumed.
            }finally{clientClosed.countDown();}
        }
        Map<String,Object> query(String key)throws Exception {HttpURLConnection connection=(HttpURLConnection)new URL("http://127.0.0.1:"+port+"/api/admin/ai-control/"+symbol.getId()+"/commands?requestKey="+key).openConnection();connection.setConnectTimeout(5000);connection.setReadTimeout(5000);try{assertEquals(200,connection.getResponseCode());return JSON.readValue(connection.getInputStream(),Map.class);}finally{connection.disconnect();}}
        @Override public void close() throws Exception {clientClosed.countDown();tomcat.stop();tomcat.destroy();}
    }
}

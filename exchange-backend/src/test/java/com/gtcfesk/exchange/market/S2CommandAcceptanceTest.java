package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.control.ControlIdentity;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.tenant.TenantJobRunner;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL commands, fenced preparation and receipts. Uses only the explicitly owned S2 fixture. */
class S2CommandAcceptanceTest {
    ControlHistoryStore store;
    PersistentPriceControl controls;
    TradingSymbol symbol;
    ForexQuoteMarketService market;
    ControlAuditService audit;
    MarketControlCommands commands;
    TenantContext.Scope scope;
    final List<MarketControlCommands> queues=new ArrayList<>();

    @BeforeAll static void ownedMysql() throws Exception { S2RuntimeMysqlTest.identity(); }

    @BeforeEach void setup(){
        scope=TenantContext.open(1L);
        store=newStore();controls=new PersistentPriceControl(store);
        symbol=newSymbol(1L);
        market=newMarket(store,controls,symbol);
        audit=mock(ControlAuditService.class);commands=newCommands(store,market,audit);
        identity(1L);
    }
    @AfterEach void cleanup(){
        // Cancel only this test's pending symbol; a failed assertion cannot poison the next acceptance turn.
        if(symbol!=null && store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND state IN ('ACCEPTED','PREPARING','READY')",Integer.class,symbol.getId())>0)commands.cancel(symbol.getId(),null);
        for(MarketControlCommands queue:queues)queue.stop();
        SecurityContextHolder.clearContext();scope.close();
    }
    ControlHistoryStore newStore(){return new ControlHistoryStore(new JdbcTemplate(S2RuntimeMysqlTest.data),new DataSourceTransactionManager(S2RuntimeMysqlTest.data));}
    TradingSymbol newSymbol(long tenant){
        TradingSymbol value=new TradingSymbol();value.setTenantId(tenant);value.setId(S2RuntimeMysqlTest.ids.incrementAndGet());
        value.setSymbol("S2_COMMAND_"+value.getId());value.setPricePrecision(2);value.setIsEnabled(true);
        store.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,?)",value.getId(),tenant);return value;
    }
    ForexQuoteMarketService newMarket(ControlHistoryStore history,PersistentPriceControl durable,TradingSymbol... symbols){
        ForexQuoteMarketService service=spy(new ForexQuoteMarketService());
        TradingSymbolRepository repository=mock(TradingSymbolRepository.class);
        when(repository.findByTenantIdAndId(anyLong(),anyLong())).thenAnswer(call->Arrays.stream(symbols)
            .filter(value->Objects.equals(value.getTenantId(),call.getArgument(0)) && Objects.equals(value.getId(),call.getArgument(1))).findFirst());
        when(repository.findAllByTenantId(anyLong())).thenAnswer(call->{List<TradingSymbol> list=new ArrayList<>();for(TradingSymbol value:symbols)if(Objects.equals(value.getTenantId(),call.getArgument(0)))list.add(value);return list;});
        when(repository.saveAndFlush(any())).thenAnswer(call->call.getArgument(0));
        ReflectionTestUtils.setField(service,"symbols",repository);ReflectionTestUtils.setField(service,"controls",durable);
        ReflectionTestUtils.setField(service,"controlHistory",history);ReflectionTestUtils.setField(service,"redis",mock(RedisMarketService.class));
        service.refreshSymbols();return service;
    }
    MarketControlCommands newCommands(ControlHistoryStore history,ForexQuoteMarketService service,ControlAuditService events){
        MarketControlCommands queue=new MarketControlCommands(history,service,mock(TenantJobRunner.class),events);queues.add(queue);return queue;
    }
    static void identity(long tenant){
        UsernamePasswordAuthenticationToken principal=new UsernamePasswordAuthenticationToken("s2-command",null,Collections.emptyList());
        principal.setDetails(new ControlIdentity(7L,tenant,"s2-command-session"));SecurityContextHolder.getContext().setAuthentication(principal);
    }
    static void backendIdentity(String principal,String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal,null,role==null?Collections.emptyList():Collections.singletonList(new SimpleGrantedAuthority(role))));}
    TargetControlOptions options(){TargetControlOptions result=new TargetControlOptions();result.setDeviationBandMode("MANUAL");result.setDeviationBandPercent(new BigDecimal("1"));result.setStepFormula("0.1");return result;}
    Map<String,Object> accept(String key){return commands.accept(symbol.getId(),20,new BigDecimal("91.00"),1,false,key,options());}
    Map<String,Object> receipt(String key){return commands.query(symbol.getId(),key);}
    long taskCount(){return store.db.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId());}
    void prime(){long now=System.currentTimeMillis();controls.sourceQuote(symbol,raw(now),now);controls.pump(symbol,raw(now),now,60000);}
    static Map<String,Object> raw(long now){Map<String,Object> result=new HashMap<>();result.put("price",new BigDecimal("90.00"));result.put("timestamp",now);result.put("sourceTimestamp",now);result.put("fetchedAt",now);result.put("expiresAt",now+60000);result.put("available",true);result.put("sourceAvailable",true);result.put("eventId","s2-command-source-"+UUID.randomUUID());return result;}

    @Test void concurrentSameKeyReceiptsConflictAndChangedConfigRetry() throws Exception {
        String key="s2_command_same_key_01";ExecutorService callers=Executors.newFixedThreadPool(8);CountDownLatch go=new CountDownLatch(1);
        List<Future<Map<String,Object>>> receipts=new ArrayList<>();
        try {
            for(int i=0;i<8;i++)receipts.add(callers.submit(()->{
                try(TenantContext.Scope tenant=TenantContext.open(1L)){identity(1L);assertTrue(go.await(10,TimeUnit.SECONDS));return accept(key);}
                finally{SecurityContextHolder.clearContext();}
            }));
            go.countDown();String commandId=null;
            for(Future<Map<String,Object>> future:receipts){Map<String,Object> accepted=future.get(20,TimeUnit.SECONDS);assertEquals("ACCEPTED",accepted.get("state"));if(commandId==null)commandId=(String)accepted.get("commandId");assertEquals(commandId,accepted.get("commandId"));}
            assertEquals(1,store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",Integer.class,symbol.getId(),key));
            verify(audit,times(1)).record(eq(7L),eq(1L),eq("s2-command-session"),eq("ai-control-command.accept"),eq(commandId),eq("ACCEPTED"),eq("{}"),isNull());
            assertThrows(BusinessException.class,()->commands.accept(symbol.getId(),20,new BigDecimal("92"),1,false,key,options()));
            TargetControlOptions equivalent=options();equivalent.setDeviationBandPercent(new BigDecimal("1.00"));
            assertEquals(commandId,commands.accept(symbol.getId(),20,new BigDecimal("91"),1,false,key,equivalent).get("commandId"));
            symbol.setIsEnabled(false);symbol.setPricePrecision(0);
            assertEquals(commandId,accept(key).get("commandId"));assertEquals("ACCEPTED",receipt(key).get("state"));
            commands.runOne();assertEquals("FAILED",receipt(key).get("state"));assertEquals("START_BASIS_CHANGED",receipt(key).get("errorCode"));assertEquals(0,taskCount());
            assertEquals(commandId,accept(key).get("commandId"));
            identity(2L);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->accept(key));
        } finally {go.countDown();callers.shutdownNow();identity(1L);}
    }

    @Test void preparingStopIsAtomicAndLateWorkerCannotActivate() throws Exception {
        prime();String key="s2_command_stop_preparing_01";accept(key);
        try(PreparingPause pause=new PreparingPause(commands,market)){
            pause.await();assertEquals("PREPARING",receipt(key).get("state"));
            Map<String,Object> stopped=commands.stopControl(symbol.getId(),key);assertNotNull(stopped);
            assertEquals("CANCELLED",receipt(key).get("state"));assertEquals("CONTROL_CANCELLED",receipt(key).get("errorCode"));
            pause.finish();assertEquals("CANCELLED",receipt(key).get("state"));assertEquals(0,taskCount());
            assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));
        }
    }

    @Test void realBackendPrincipalShapesBindActorAndRejectOtherAuthorities(){
        String[][] allowed={{"17","ROLE_ADMIN"},{"18","ROLE_SUPER_ADMIN"},{"agent-19","ROLE_AGENT"}};
        for(int i=0;i<allowed.length;i++){
            backendIdentity(allowed[i][0],allowed[i][1]);String key="s2_command_backend_actor_0"+i;Map<String,Object> accepted=accept(key);
            Map<String,Object> row=store.db.queryForMap("SELECT actor_id,session_id,tenant_id FROM market_control_command WHERE tenant_id=1 AND id=?",accepted.get("commandId"));
            assertEquals(17L+i,((Number)row.get("actor_id")).longValue());assertNull(row.get("session_id"));assertEquals(1L,((Number)row.get("tenant_id")).longValue());commands.cancel(symbol.getId(),key);
        }
        String[][] denied={{"20",null},{"20","ROLE_USER"},{"agent-20","ROLE_ADMIN"},{"20","ROLE_AGENT"},{"0","ROLE_ADMIN"},{"-20","ROLE_SUPER_ADMIN"},{"9223372036854775808","ROLE_ADMIN"}};
        for(String[] attempt:denied){backendIdentity(attempt[0],attempt[1]);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->accept("s2_command_backend_denied_01"));}
        UsernamePasswordAuthenticationToken unauthenticated=new UsernamePasswordAuthenticationToken("21",null);SecurityContextHolder.getContext().setAuthentication(unauthenticated);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->accept("s2_command_backend_denied_01"));
        identity(2L);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->accept("s2_command_backend_denied_01"));identity(1L);
        assertEquals(3,store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));assertEquals(0,taskCount());
    }

    @Test void tenantQueue32Rejects33ButRetryAndOtherTenantRemainAvailable() throws Exception {
        List<TradingSymbol> configs=new ArrayList<>();configs.add(symbol);for(int i=1;i<33;i++)configs.add(newSymbol(1L));
        market=newMarket(store,controls,configs.toArray(new TradingSymbol[0]));commands=newCommands(store,market,audit);List<Long> acceptedSymbols=new ArrayList<>();
        try{
            Map<String,Object> first=null;
            for(int i=0;i<32;i++){Map<String,Object> accepted=commands.accept(configs.get(i).getId(),20,new BigDecimal("91"),1,false,"s2_queue_boundary_"+String.format("%02d",i),options());acceptedSymbols.add(configs.get(i).getId());if(i==0)first=accepted;assertEquals("ACCEPTED",accepted.get("state"));}
            assertEquals(32,store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=1 AND state IN ('ACCEPTED','PREPARING','READY')",Integer.class));
            BusinessException full=assertThrows(BusinessException.class,()->commands.accept(configs.get(32).getId(),20,new BigDecimal("91"),1,false,"s2_queue_boundary_33",options()));assertTrue(full.getMessage().startsWith("COMMAND_QUEUE_FULL"));
            assertEquals(first.get("commandId"),commands.accept(symbol.getId(),20,new BigDecimal("91.00"),1,false,"s2_queue_boundary_00",options()).get("commandId"));assertEquals(first.get("commandId"),commands.query(symbol.getId(),"s2_queue_boundary_00").get("commandId"));
            ExecutorService tenant2=Executors.newSingleThreadExecutor();
            try{tenant2.submit(()->{try(TenantContext.Scope other=TenantContext.open(2L)){
                identity(2L);TradingSymbol own=newSymbol(2L);MarketControlCommands queue=newCommands(store,newMarket(store,controls,own),mock(ControlAuditService.class));
                try{assertEquals("ACCEPTED",queue.accept(own.getId(),20,new BigDecimal("91"),1,false,"s2_queue_tenant2_01",options()).get("state"));}finally{queue.cancel(own.getId(),null);}
            }finally{SecurityContextHolder.clearContext();}}).get(20,TimeUnit.SECONDS);}finally{tenant2.shutdownNow();}
            assertEquals(32,store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=1 AND state IN ('ACCEPTED','PREPARING','READY')",Integer.class));
            assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE tenant_id=1 AND symbol_id=?",Integer.class,configs.get(32).getId()));
        }finally{for(Long id:acceptedSymbols)commands.cancel(id,null);identity(1L);}
    }

    @Test void committedAcceptanceSurvivesChildJvmHalt74AndOnlyActivatesOneTask() throws Exception {
        String key="s2_child_accept_"+UUID.randomUUID().toString().replace("-","");
        String javaExecutable=Paths.get(System.getProperty("java.home"),"bin","java.exe").toString();String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path outputDirectory=Paths.get(Objects.requireNonNull(System.getenv("S2_ACCEPTANCE_OUTPUT"),"owned S2_ACCEPTANCE_OUTPUT required")).toAbsolutePath().normalize();Files.createDirectories(outputDirectory);
        Path childLog=outputDirectory.resolve("s2-command-child-"+key+".log");
        Process child=new ProcessBuilder(javaExecutable,"-cp",classpath,S2CommandAcceptanceCrashChild.class.getName(),key).redirectErrorStream(true).redirectOutput(childLog.toFile()).start();
        try{
            boolean halted=child.waitFor(30,TimeUnit.SECONDS);String output=Files.readString(childLog,java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(halted,"child must halt after committed acceptance; actual log "+childLog+"\n"+output);
            assertEquals(74,child.exitValue(),output);String marker=Arrays.stream(output.split("\\R")).filter(line->line.startsWith("S2_ACCEPTED_COMMITTED ")).findFirst().orElseThrow(()->new AssertionError("committed ACCEPTED marker absent"));
            String[] fields=marker.split(" ");assertEquals(3,fields.length);long id=Long.parseLong(fields[2]);symbol.setId(id);symbol.setSymbol("S2_COMMAND_"+id);
            market=newMarket(store,controls,symbol);commands=newCommands(store,market,audit);Map<String,Object> original=receipt(key);assertEquals("ACCEPTED",original.get("state"));assertEquals(fields[1],original.get("commandId"));assertEquals(fields[1],accept(key).get("commandId"));assertEquals(0,taskCount());
            assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Integer.class,id),"acceptance must not acquire a writer lease, even before process death");
            prime();commands.runOne();assertEquals("RUNNING",receipt(key).get("state"));assertEquals(fields[1],accept(key).get("commandId"));assertEquals(receipt(key).get("taskId"),accept(key).get("taskId"));commands.runOne();assertEquals(1,taskCount());
        }finally{if(child.isAlive()){child.destroyForcibly();child.waitFor(10,TimeUnit.SECONDS);}}
    }

    @Test void busyOtherSymbolDoesNotStarveOwnedCommand(){
        TradingSymbol blockedSymbol=newSymbol(1L);ControlHistoryStore otherWriter=newStore();
        MarketControlCommands blocked=newCommands(otherWriter,newMarket(otherWriter,new PersistentPriceControl(otherWriter),blockedSymbol),mock(ControlAuditService.class));
        String blockedKey="s2_command_busy_first_01",healthyKey="s2_command_healthy_next_01";
        long now=System.currentTimeMillis();new PersistentPriceControl(otherWriter).sourceQuote(blockedSymbol,raw(now),now);
        blocked.accept(blockedSymbol.getId(),20,new BigDecimal("91"),1,false,blockedKey,options());
        try {
            prime();accept(healthyKey);commands.runOne();
            assertEquals("RUNNING",receipt(healthyKey).get("state"),()->receipt(healthyKey).toString());assertEquals(1,taskCount());
            assertEquals("ACCEPTED",blocked.query(blockedSymbol.getId(),blockedKey).get("state"));
        } finally{blocked.cancel(blockedSymbol.getId(),blockedKey);}
    }

    @Test void preparingVersionChangeRejectsActivation() throws Exception { basisChange(value->value.setRowVersion(value.getRowVersion()+1),"version"); }
    @Test void preparingPrecisionChangeRejectsActivationWithoutVersionBump() throws Exception { basisChange(value->value.setPricePrecision(3),"precision"); }
    @Test void preparingSourceChangeRejectsActivationWithoutVersionBump() throws Exception { basisChange(value->{value.setSourceCategory("Forex");value.setMarketSource("Yahoo");value.setAlltickSymbol("EURUSD=X");},"source"); }
    @Test void preparingDeadlineExpiryRejectsActivation() throws Exception {
        prime();String key="s2_command_expired_preparing_01";accept(key);
        try(PreparingPause pause=new PreparingPause(commands,market)){
            pause.await();store.db.update("UPDATE market_control_command SET expires_at=0 WHERE tenant_id=1 AND symbol_id=? AND request_key=?",symbol.getId(),key);
            pause.finish();assertBasisRejected(key);
        }
    }
    void basisChange(Consumer<TradingSymbol> change,String kind) throws Exception {
        prime();String key="s2_command_basis_"+kind+"_01";accept(key);
        try(PreparingPause pause=new PreparingPause(commands,market)){pause.await();change.accept(symbol);pause.finish();assertBasisRejected(key);}
    }
    void assertBasisRejected(String key){assertEquals("FAILED",receipt(key).get("state"));assertEquals("START_BASIS_CHANGED",receipt(key).get("errorCode"));assertEquals(0,taskCount());assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));}

    @Test void takeoverResumesSeedAndFencesOldPrepared() throws Exception {
        prime();String key="s2_command_generation_takeover_01";accept(key);
        long seed=store.db.queryForObject("SELECT seed FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",Long.class,symbol.getId(),key);
        try(PreparingPause pause=new PreparingPause(commands,market)){
            pause.await();long oldGeneration=store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId());
            store.db.update("UPDATE market_engine_runtime SET lease_until=0 WHERE tenant_id=1 AND symbol_id=?",symbol.getId());
            ControlHistoryStore successor=newStore();PersistentPriceControl next=new PersistentPriceControl(successor);
            MarketControlCommands recovered=newCommands(successor,newMarket(successor,next,symbol),mock(ControlAuditService.class));recovered.runOne();
            assertEquals("RUNNING",receipt(key).get("state"),()->receipt(key).toString());
            assertEquals(oldGeneration+1,store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId()));
            assertEquals(seed,successor.db.queryForObject("SELECT seed FROM market_control_plan WHERE tenant_id=1 AND task_id=?",Long.class,next.latest(symbol.getId()).id));assertEquals(1,taskCount());
            pause.finish();assertEquals("RUNNING",receipt(key).get("state"));assertEquals(1,taskCount());
            assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));
        }
    }

    @Test void readyTransientFailureRemainsRecoverableAndKeepsSeed() throws Exception {
        prime();String key="s2_command_ready_recovery_01";accept(key);AtomicBoolean fail=new AtomicBoolean(true);
        doAnswer(call->{if(fail.getAndSet(false))throw new TransientDataAccessResourceException("injected activation outage before commit");return call.callRealMethod();})
            .when(market).activateCommand(anyLong(),anyInt(),any(BigDecimal.class),anyInt(),anyBoolean(),anyString(),any(TargetControlOptions.class),any(PersistentPriceControl.Prepared.class));
        commands.runOne();assertEquals("READY",receipt(key).get("state"));assertEquals(0,taskCount());
        Map<String,Object> row=store.db.queryForMap("SELECT seed,prepared_json FROM market_control_command WHERE tenant_id=1 AND symbol_id=? AND request_key=?",symbol.getId(),key);
        assertNotNull(row.get("prepared_json"));long seed=((Number)row.get("seed")).longValue();assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));
        Thread.sleep(Math.max(0,((Number)receipt(key).get("retryAt")).longValue()-store.runtime.clock()+10));
        commands.runOne();assertEquals("RUNNING",receipt(key).get("state"),()->receipt(key).toString());assertEquals(seed,store.db.queryForObject("SELECT seed FROM market_control_plan WHERE tenant_id=1 AND task_id=?",Long.class,controls.latest(symbol.getId()).id));assertEquals(1,taskCount());
        commands.runOne();assertEquals(1,taskCount());assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));
    }

    @Test void takeoverExpiresOldUnclaimedReceiptInsteadOfLeavingItPending() throws Exception {
        prime();String key="s2_command_expired_takeover_01";accept(key);
        try(PreparingPause pause=new PreparingPause(commands,market)){
            pause.await();long previousGeneration=store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId());
            store.db.update("UPDATE market_control_command SET expires_at=0 WHERE tenant_id=1 AND symbol_id=? AND request_key=?",symbol.getId(),key);
            store.db.update("UPDATE market_engine_runtime SET lease_until=0 WHERE tenant_id=1 AND symbol_id=?",symbol.getId());
            ControlHistoryStore successor=newStore();ForexQuoteMarketService successorMarket=newMarket(successor,new PersistentPriceControl(successor),symbol);
            MarketControlCommands recovered=newCommands(successor,successorMarket,mock(ControlAuditService.class));recovered.runOne();
            assertEquals(previousGeneration+1,store.db.queryForObject("SELECT writer_generation FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?",Long.class,symbol.getId()));
            assertEquals("FAILED",receipt(key).get("state"));assertEquals("START_BASIS_CHANGED",receipt(key).get("errorCode"));assertEquals(0,taskCount());
            verify(successorMarket,never()).prepareCommand(anyLong(),anyInt(),any(BigDecimal.class),anyInt(),anyBoolean(),any(TargetControlOptions.class),anyLong());
            recovered.runOne();assertEquals("FAILED",receipt(key).get("state"));pause.finish();assertBasisRejected(key);
        }
    }

    @Test void committedActivationLostAcknowledgementOnlyReturnsOriginalTask(){
        prime();String key="s2_command_commit_ack_lost_01";Map<String,Object> accepted=accept(key);
        doAnswer(call->{
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){throw new IllegalStateException("injected lost activation acknowledgement after commit");}});
            return null;
        }).when(audit).record(eq(7L),eq(1L),eq("s2-command-session"),eq("ai-control-command.activate"),anyString(),eq("RUNNING"),eq("{}"),isNull());
        commands.runOne();Map<String,Object> committed=receipt(key);assertEquals("RUNNING",committed.get("state"));assertNotNull(committed.get("taskId"));
        JdbcTemplate independent=new JdbcTemplate(S2RuntimeMysqlTest.data);
        assertEquals(committed.get("taskId"),independent.queryForObject("SELECT task_id FROM market_control_command WHERE tenant_id=1 AND id=?",String.class,accepted.get("commandId")));
        assertEquals(accepted.get("commandId"),accept(key).get("commandId"));assertEquals(committed.get("taskId"),accept(key).get("taskId"));
        commands.runOne();assertEquals(1,taskCount());assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));
    }

    @Test void tenantBoundQueryRetryAndCancellationNeverTouchAnotherTenant() throws Exception {
        String key="s2_command_tenant_boundary_01";Map<String,Object> accepted=accept(key);
        ExecutorService separate=Executors.newSingleThreadExecutor();
        try {separate.submit(()->{try(TenantContext.Scope second=TenantContext.open(2L)){
            identity(2L);TradingSymbol own=newSymbol(2L);ForexQuoteMarketService otherMarket=newMarket(store,controls,own);
            MarketControlCommands other=newCommands(store,otherMarket,mock(ControlAuditService.class));
            assertThrows(BusinessException.class,()->other.query(symbol.getId(),key));assertThrows(BusinessException.class,()->other.cancel(symbol.getId(),key));
            assertThrows(BusinessException.class,()->other.query(own.getId(),key));
            Map<String,Object> ownCommand=other.accept(own.getId(),20,new BigDecimal("91"),1,false,key,options());assertNotEquals(accepted.get("commandId"),ownCommand.get("commandId"));
            assertEquals(own.getId(),other.query(own.getId(),key).get("symbolId"));other.cancel(own.getId(),key);assertEquals("CANCELLED",other.query(own.getId(),key).get("state"));
        } finally{SecurityContextHolder.clearContext();}}).get(20,TimeUnit.SECONDS);}finally{separate.shutdownNow();}
        assertEquals("ACCEPTED",receipt(key).get("state"));assertEquals(accepted.get("commandId"),receipt(key).get("commandId"));commands.cancel(symbol.getId(),key);
    }

    /** Pause outside any transaction after real plan generation, allowing STOP/config/takeover to race it. */
    static final class PreparingPause implements AutoCloseable {
        final CountDownLatch prepared=new CountDownLatch(1),release=new CountDownLatch(1);
        final ExecutorService worker=Executors.newSingleThreadExecutor();final Future<?> turn;
        PreparingPause(MarketControlCommands commands,ForexQuoteMarketService market){
            doAnswer(call->{PersistentPriceControl.Prepared plan=(PersistentPriceControl.Prepared)call.callRealMethod();prepared.countDown();
                try {assertTrue(release.await(20,TimeUnit.SECONDS),"test must release prepared worker");return plan;}
                catch(Exception|AssertionError failure){plan.close();throw failure;}
            }).when(market).prepareCommand(anyLong(),anyInt(),any(BigDecimal.class),anyInt(),anyBoolean(),any(TargetControlOptions.class),anyLong());
            turn=worker.submit(()->{try(TenantContext.Scope context=TenantContext.open(1L)){commands.runOne();}});
        }
        void await() throws Exception {assertTrue(prepared.await(20,TimeUnit.SECONDS),"real preparation must complete before race assertion");}
        void finish() throws Exception {release.countDown();turn.get(20,TimeUnit.SECONDS);}
        @Override public void close() throws Exception {release.countDown();try{turn.get(20,TimeUnit.SECONDS);}finally{worker.shutdownNow();}}
    }
}

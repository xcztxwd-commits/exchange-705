package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual MySQL and the actual bounded engine scheduler; repository/provider/audit are explicit adapters.
 * These raw component samples are not production latency percentiles or full application authorization evidence.
 */
class S2TenantFairnessMysqlTest {
    @BeforeAll static void ownedMysql() throws Exception { S2RuntimeMysqlTest.identity(); }
    ControlHistoryStore store;
    PersistentPriceControl controls;
    ForexQuoteMarketService market;
    MarketControlCommands commands;
    TradingSymbol a, b;
    DataSourceTransactionManager transactions;
    final ExecutorService holder = Executors.newSingleThreadExecutor();
    final CountDownLatch locked = new CountDownLatch(1), release = new CountDownLatch(1);
    final Map<String,Object> evidence = new LinkedHashMap<>();
    String key = "s2_fairness_" + UUID.randomUUID();

    <T> T tenant(long id, Supplier<T> operation) {
        assertNull(TenantContext.currentTenantId(), "test orchestration must not reuse a tenant scope");
        try (TenantContext.Scope ignored = TenantContext.open(id)) { return operation.get(); }
        finally { SecurityContextHolder.clearContext(); }
    }
    @BeforeEach void setup() {
        transactions = new DataSourceTransactionManager(S2RuntimeMysqlTest.data);
        store = new ControlHistoryStore(new JdbcTemplate(S2RuntimeMysqlTest.data), transactions); controls = new PersistentPriceControl(store);
        S2CommandAcceptanceTest helper = new S2CommandAcceptanceTest(); helper.store = store;
        a = tenant(1, () -> helper.newSymbol(1));
        b = tenant(2, () -> helper.newSymbol(2));
        for (TradingSymbol symbol : Arrays.asList(a,b)) tenant(symbol.getTenantId(), () -> {
            symbol.setCategory("Metal"); symbol.setSourceCategory("Metal"); symbol.setQuoteCurrency("USD"); return null;
        });
        tenant(1, () -> { market = helper.newMarket(store, controls, a, b); return null; });
        tenant(2, () -> { market.refreshSymbols(); return null; });
        ReflectionTestUtils.setField(market, "tenantJobs", new TenantJobRunner(store.db, transactions));
        doAnswer(call -> S2CommandAcceptanceTest.raw(System.currentTimeMillis())).when(market).getPrice(anyString(), any());
        commands = new MarketControlCommands(store, market, new TenantJobRunner(store.db, transactions), mock(ControlAuditService.class));
        for (TradingSymbol symbol : Arrays.asList(a,b)) tenant(symbol.getTenantId(), () -> {
            long now = System.currentTimeMillis(); Map<String,Object> raw = S2CommandAcceptanceTest.raw(now);
            controls.sourceQuote(symbol, raw, now); controls.pump(symbol, raw, now, 60000); return null;
        });
        evidence.put("scope", "owned MySQL actual scheduler component; fixed repository/provider/audit adapters; not production P95");
        evidence.put("server_uuid", S2RuntimeMysqlTest.fixture.get("server_uuid"));
        evidence.put("container_ids", S2RuntimeMysqlTest.fixture.get("container_ids"));
        evidence.put("symbols", Map.of("tenantA", a.getId(), "tenantB", b.getId()));
    }
    @AfterEach void cleanup() throws Exception {
        release.countDown();
        if (commands != null) commands.stop();
        if (market != null) {
            market.stop();
            ThreadPoolExecutor workers = (ThreadPoolExecutor)ReflectionTestUtils.getField(market,"engines");
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS), "all actual engine lanes must stop");
        }
        holder.shutdownNow(); assertTrue(holder.awaitTermination(20,TimeUnit.SECONDS));
        SecurityContextHolder.clearContext(); assertNull(TenantContext.currentTenantId());
    }
    void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), () -> message + "; engine=" + market.engineMetrics() + "; lastAWait=" + evidence.get("last_a_wait_observation"));
    }
    long version(long tenant, long symbol) {
        return store.db.queryForObject("SELECT snapshot_version FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?", Long.class, tenant, symbol);
    }
    void schedule(long tenant) {
        tenant(tenant, () -> { ReflectionTestUtils.invokeMethod(market,"scheduleEngine",false); return null; });
    }
    @SuppressWarnings("unchecked") boolean active(long tenant) {
        return ((Set<Long>)ReflectionTestUtils.getField(market,"engineActive")).contains(tenant);
    }
    List<Map<String,Object>> samples(String expectedState) {
        return tenant(2, () -> {
            List<Map<String,Object>> rows = new ArrayList<>();
            for (int i=0;i<20;i++) {
                Map<String,Object> row = new LinkedHashMap<>(); long at = System.nanoTime();
                Map<String,Object> status = market.controlStatus(b.getId()); row.put("status_ms",(System.nanoTime()-at)/1e6);
                at=System.nanoTime();Map<String,Object> quote=market.snapshotPrice(b.getSymbol());row.put("quote_ms",(System.nanoTime()-at)/1e6);
                at=System.nanoTime();Map<String,Object> receipt=commands.query(b.getId(),key);row.put("command_ms",(System.nanoTime()-at)/1e6);
                assertEquals(expectedState,receipt.get("state"),receipt.toString()); assertEquals(b.getId(),receipt.get("symbolId"));
                assertEquals(2L,((Number)quote.get("tenantId")).longValue()); assertNotNull(quote.get("price")); assertTrue(Boolean.TRUE.equals(quote.get("tradeAvailable")));
                assertNotNull(status.get("quoteVersion")); row.put("quoteVersion",quote.get("quoteVersion")); row.put("commandState",receipt.get("state"));rows.add(row);
            }
            return rows;
        });
    }
    private static final String A_WAIT_SQL = "SELECT ID,DB,COMMAND,TIME,STATE,INFO FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND DB=? AND COMMAND='Query' AND TIME>=1 AND INFO LIKE '%market_engine_runtime%' AND INFO REGEXP ?";
    List<Map<String,Object>> physicalAWaits() {
        // Match this fixture's exact symbol, not a prefix or another tenant's wait.
        List<Map<String,Object>> rows = store.db.queryForList(A_WAIT_SQL, S2RuntimeMysqlTest.fixture.get("database"), "(^|[^0-9])" + a.getId() + "([^0-9]|$)");
        // Overwrite one bounded diagnostic snapshot; do not emit every polling query.
        evidence.put("last_a_wait_observation", Map.of("observedAtMs", System.currentTimeMillis(), "tenantId", 1L, "symbolId", a.getId(), "engineActive", active(1), "query", A_WAIT_SQL, "database", S2RuntimeMysqlTest.fixture.get("database"), "processes", rows));
        return rows;
    }

    @Test void exhaustedTenantAndPhysicalRowWaitCannotConsumeBothEngineLanesOrBlockAnotherTenant() throws Exception {
        tenant(2, () -> {
            S2CommandAcceptanceTest.identity(2);
            TargetControlOptions options = new TargetControlOptions(); options.setStepFormula("0.1"); options.setDeviationBandMode("MANUAL");options.setDeviationBandPercent(BigDecimal.ONE);
            assertEquals("ACCEPTED",commands.accept(b.getId(),20,new BigDecimal("91.00"),1,false,key,options).get("state")); return null;
        });
        evidence.put("normal", samples("ACCEPTED"));
        long aBefore = version(1,a.getId()), bBefore = version(2,b.getId());
        ControlPlanBudget.Lease exhausted = tenant(1, () -> {
            ControlPlanBudget.Lease lease = store.budget.acquire(32L*1024*1024);
            assertThrows(com.gtcfesk.exchange.common.BusinessException.class, () -> store.budget.acquire(1));
            assertEquals(32L*1024*1024,store.budget.metrics().get("activePlanBytes"));return lease;
        });
        Future<?> rowHolder = holder.submit(() -> tenant(1, () -> new TransactionTemplate(transactions).execute(transaction -> {
            store.db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=? FOR UPDATE",a.getId());
            locked.countDown();try {assertTrue(release.await(60,TimeUnit.SECONDS),"release actual MySQL row holder");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new RuntimeException(failure);}return null;
        })));
        try {
            assertTrue(locked.await(15,TimeUnit.SECONDS)); schedule(1);
            await(() -> active(1) && !physicalAWaits().isEmpty(),"A scheduler lane reaches its own physical MySQL row-lock wait");
            for(int i=0;i<40;i++)schedule(1);
            schedule(2); await(() -> version(2,b.getId())>bBefore && !active(2),"B actual scheduler commits while A lane waits");
            assertFalse(rowHolder.isDone()); assertTrue(active(1)); assertEquals(aBefore,version(1,a.getId()));
            assertEquals(32L*1024*1024, tenant(1, () -> store.budget.metrics().get("activePlanBytes")));
            long acceptStarted = System.nanoTime();
            tenant(2, () -> {
                commands.cancel(b.getId(), key); key = "s2_fairness_fault_" + UUID.randomUUID();
                S2CommandAcceptanceTest.identity(2);
                TargetControlOptions options = new TargetControlOptions(); options.setStepFormula("0.1");
                options.setDeviationBandMode("MANUAL"); options.setDeviationBandPercent(BigDecimal.ONE);
                assertEquals("ACCEPTED", commands.accept(b.getId(),20,new BigDecimal("91.00"),1,false,key,options).get("state"));
                return null;
            });
            evidence.put("fault_accept_ms", (System.nanoTime()-acceptStarted)/1e6);
            evidence.put("fault_accepted", samples("ACCEPTED"));
            assertTrue(active(1)); assertFalse(rowHolder.isDone()); assertEquals(aBefore,version(1,a.getId()));
            List<Map<String,Object>> aWaits = physicalAWaits(); int lockWaits = aWaits.size();
            assertTrue(lockWaits > 0); evidence.put("fault_physical_lock_waits",lockWaits); evidence.put("fault_a_lock_waits",aWaits); evidence.put("fault_wait_observation_sql",A_WAIT_SQL); evidence.put("fault_wait_database",S2RuntimeMysqlTest.fixture.get("database")); evidence.put("fault_wait_symbol",a.getId());
            long aBytes = ((Number)tenant(1, () -> store.budget.metrics().get("activePlanBytes"))).longValue();
            assertEquals(32L*1024*1024,aBytes); evidence.put("fault_tenant_a_plan_bytes",aBytes);
            tenant(2, () -> {
                assertEquals(0L,store.budget.metrics().get("activePlanBytes"));
                commands.runOne();Map<String,Object> receipt=commands.query(b.getId(),key);assertEquals("RUNNING",receipt.get("state"),receipt.toString());
                assertNotNull(receipt.get("taskId"));return null;
            });
            long bRunning=version(2,b.getId());schedule(2);await(() -> version(2,b.getId())>bRunning && !active(2),"B running task publishes through actual scheduler");
            evidence.put("fault",samples("RUNNING"));evidence.put("fault_scheduler",market.engineMetrics());
            Map<String,Object> metrics=market.engineMetrics(); assertTrue(((Number)metrics.get("engineWorkers")).intValue()<=2);
            assertTrue(((Number)metrics.get("engineQueue")).intValue()<=64);assertTrue(((Number)metrics.get("engineDelayed")).longValue()>=40);
            assertEquals(0L,metrics.get("engineRejected"));assertTrue(active(1));assertFalse(rowHolder.isDone());
        } finally { exhausted.close();release.countDown();rowHolder.get(20,TimeUnit.SECONDS); }
        await(() -> !active(1) && version(1,a.getId())>aBefore,"A scheduler resumes after its own fault is removed");
        evidence.put("tenant_a_committed_versions", Map.of("beforeFault",aBefore,"afterRelease",version(1,a.getId())));
        long recoveryBefore=version(2,b.getId());schedule(2);await(() -> version(2,b.getId())>recoveryBefore && !active(2),"B remains schedulable after A recovery");
        evidence.put("recovery",samples("RUNNING"));evidence.put("recovery_scheduler",market.engineMetrics());
        tenant(1, () -> {assertEquals(0L,store.budget.metrics().get("globalPlanBytes"));return null;});
        String path=System.getenv("S2_FAIRNESS_OUTPUT");
        if(path!=null){Path target=Path.of(path);assertFalse(Files.exists(target),"preserve previous evidence");Files.writeString(target,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(evidence));}
        System.out.println("S2_FAIRNESS_COMPONENT_RAW "+new ObjectMapper().writeValueAsString(evidence));
    }
}

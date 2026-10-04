package com.gtcfesk.exchange.market;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real MySQL rollback and committed continuation across the physical 512-sample boundary. */
class S2StopBoundaryMysqlTest {
    @BeforeAll static void identity() throws Exception { S2RuntimeMysqlTest.identity(); }
    S2RuntimeMysqlTest fixture;
    ForexQuoteMarketService market;
    PersistentPriceControl.Task task;
    JdbcTemplate independent;

    @BeforeEach void setup() {
        fixture = new S2RuntimeMysqlTest(); fixture.setup();
        long start = System.currentTimeMillis() / 1000 * 1000 - 1000000;
        task = fixture.legacy(start, 2000);
        ControlHoldService holds = new ControlHoldService(fixture.store);
        fixture.controls.locked(fixture.symbol.getId(), () -> {
            holds.prepare(task, fixture.raw(start, true));
            RecoveryOptions options = new RecoveryOptions(); options.setAutoReplaceHistory(true);
            new ControlRecoveryFlow(fixture.store, holds).create(task, options); return null;
        });
        fixture.controls.pump(fixture.symbol, fixture.raw(start, true), start, 60000);
        assertEquals(1, fixture.count(task.id));
        independent = new JdbcTemplate(S2RuntimeMysqlTest.data);
        fixture.symbol.setCategory("Metal"); fixture.symbol.setSourceCategory("Metal"); fixture.symbol.setQuoteCurrency("USD");
        S2CommandAcceptanceTest helper = new S2CommandAcceptanceTest(); helper.store = fixture.store;
        market = helper.newMarket(fixture.store, fixture.controls, fixture.symbol);
        doAnswer(call -> fixture.raw(System.currentTimeMillis(), true)).when(market).getPrice(anyString(), any());
    }
    @AfterEach void cleanup() {
        if (market != null) market.stop();
        if (fixture != null) fixture.close();
    }
    Map<String,Object> committed() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("task", independent.queryForMap("SELECT * FROM market_control_task WHERE tenant_id=1 AND id=?", task.id));
        for (String table : List.of("market_control_hold", "market_control_flow", "market_control_sample", "market_control_publication"))
            result.put(table, independent.queryForList("SELECT * FROM " + table + " WHERE tenant_id=1 AND task_id=?", task.id));
        result.put("runtime", independent.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=?", fixture.symbol.getId()));
        result.put("mixed", independent.queryForList("SELECT * FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=? ORDER BY minute_at", fixture.symbol.getId()));
        return result;
    }
    void rejectedWithoutPartialCommit(Runnable operation) {
        Map<String,Object> before = committed();
        assertEquals("ENGINE_LAG", assertThrows(BalancedControlPlan.Failure.class, operation::run).code);
        assertEquals(before, committed(), "failed mutation rolls back samples, watermarks, flow, hold, publication and snapshot together");
        assertTrue(fixture.controls.latest(fixture.symbol.getId()).running());
    }
    void catchUp() {
        long now = System.currentTimeMillis();
        for (int turn=0; turn<3; turn++) {
            long before = fixture.count(task.id);
            fixture.controls.pump(fixture.symbol, fixture.raw(now, true), now, 60000);
            assertTrue(fixture.count(task.id)-before <= 512, "one physical transaction never exceeds its shared quota");
        }
        assertTrue(fixture.count(task.id) >= 1001);
        assertFalse(Boolean.TRUE.equals(fixture.controls.display(fixture.symbol, Map.of(), now).get("engineLag")));
    }
    void stoppedPrefixIsComplete() {
        PersistentPriceControl.Task old = fixture.controls.history(fixture.symbol.getId(), null).stream()
                .filter(value -> task.id.equals(value.id)).findFirst().orElseThrow();
        assertEquals("STOPPED", old.status); assertNotNull(old.endedAt);
        long expected = (old.endedAt-old.startedAt)/1000+1;
        assertEquals(expected, fixture.count(task.id));
        assertEquals(expected, independent.queryForObject("SELECT COUNT(DISTINCT generated_at) FROM market_control_sample WHERE tenant_id=1 AND task_id=?", Long.class, task.id));
        assertEquals(old.startedAt+(expected-1)*1000, old.sampledUntil);
        assertEquals(0, independent.queryForObject("SELECT COUNT(*) FROM market_control_sample WHERE tenant_id=1 AND task_id=? AND generated_at>?", Integer.class, task.id, old.endedAt));
    }
    @Test void manualMutationCannotTruncateBacklogAndSucceedsAfterIndependentEngineTurns() {
        rejectedWithoutPartialCommit(() -> market.manualControl(fixture.symbol.getId(), false, BigDecimal.ZERO));
        catchUp(); market.manualControl(fixture.symbol.getId(), false, BigDecimal.ZERO);
        stoppedPrefixIsComplete();
        assertEquals("SOURCE", independent.queryForObject("SELECT state FROM market_control_flow WHERE tenant_id=1 AND task_id=?", String.class, task.id));
        assertNotNull(independent.queryForObject("SELECT released_at FROM market_control_hold WHERE tenant_id=1 AND task_id=?", Long.class, task.id));
    }
    @Test void restoreCannotSupersedeUncommittedBacklogAndRetriesWithoutLosingOldSamples() {
        String key = "s2-stop-boundary-restore-" + UUID.randomUUID();
        rejectedWithoutPartialCommit(() -> market.restoreControl(fixture.symbol.getId(), 10, 1, false, key));
        assertEquals(1, independent.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?", Integer.class, fixture.symbol.getId()));
        catchUp(); market.restoreControl(fixture.symbol.getId(), 10, 1, false, key);
        stoppedPrefixIsComplete();
        assertEquals(2, independent.queryForObject("SELECT COUNT(*) FROM market_control_task WHERE tenant_id=1 AND symbol_id=?", Integer.class, fixture.symbol.getId()));
        PersistentPriceControl.Task restore = fixture.controls.latest(fixture.symbol.getId());
        assertNotEquals(task.id, restore.id); assertEquals("RESTORE", restore.kind); assertEquals(key, independent.queryForObject("SELECT request_key FROM market_control_task WHERE tenant_id=1 AND id=?", String.class, restore.id));
    }
}

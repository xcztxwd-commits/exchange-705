package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.gtcfesk.exchange.market.MarketSqlFixture.inTenant;

/** Isolated H2 MySQL-mode database per test. No network, user, balance, or order writes. */
class ControlRecoveryFlowTest extends PersistentPriceControlTest {
    private static RecoveryOptions enabledRecovery() {
        RecoveryOptions options = new RecoveryOptions(); options.setAutoRestore(true); return options;
    }

    PersistentPriceControl.Task start(RecoveryOptions o) {
        return controls.start(symbol, raw(System.currentTimeMillis(), true), BigDecimal.valueOf(90),
            2, BigDecimal.valueOf(110), 1, false, false, "flow-request", o);
    }
    Map<String,Object> display(long now, int price, boolean available) {
        Map<String,Object> q = raw(now, available); q.put("price", price);
        return controls.display(symbol, q, now);
    }
    Map<String,Object> flow(PersistentPriceControl.Task t) {
        return store.db.queryForMap("SELECT * FROM market_control_flow WHERE task_id=?", t.id);
    }
    void price(int expected, Map<String,Object> actual) {
        assertEquals(0, BigDecimal.valueOf(expected).compareTo(ControlHistoryStore.number(actual.get("price"))));
    }
    @Test void defaultRecoveryIsOptIn() {
        RecoveryOptions options = new RecoveryOptions();
        assertFalse(options.getAutoRestore());
        assertTrue(options.getRestoreRandomOscillation());
        assertEquals(5, options.getRestoreIntensity());
        PersistentPriceControl.Task task = start(options);
        display(task.plannedEnd, 100, true);
        assertEquals("HOLDING", flow(task).get("state"));
    }
    @Test void restartDoesNotConsumeUnobservedDowntime() throws Exception {
        PersistentPriceControl.Task t=start(enabledRecovery());display(t.plannedEnd,100,true);
        long before=System.currentTimeMillis()-20000;
        store.db.update("UPDATE market_control_flow SET state='RECOVERING',last_at=?,last_price=105,recovery_started_at=? WHERE task_id=?",before,before-4000,t.id);
        controls=new PersistentPriceControl(store);
        long now=System.currentTimeMillis();
        price(105,display(now,103,true));
        assertEquals(6000,((Number)flow(t).get("remaining_millis")).longValue());
        price(104,display(now+6000,104,true));
    }
    @Test void recoveryOutagePausesAndRebasesContinuously() {
        PersistentPriceControl.Task t=start(enabledRecovery());
        display(t.plannedEnd,100,true);
        Map<String,Object> before=display(t.plannedEnd+4000,100,true);
        Map<String,Object> outage=display(t.plannedEnd+5000,0,false);
        assertEquals("WAITING_SOURCE",outage.get("controlState"));
        long count=count("market_control_sample");
        display(t.plannedEnd+50000,0,false);assertEquals(count,count("market_control_sample"));
        Map<String,Object> resumed=display(t.plannedEnd+60000,103,true);
        assertEquals(before.get("price"),resumed.get("price"));
        price(104,display(t.plannedEnd+66000,104,true));
    }
    @Test void legacyMinutePrefixRemainsWhenNewFlowIsUnpublished() {
        long now=System.currentTimeMillis(); long minute=now/60000*60000;
        store.locked(1,()->{store.point(1,minute+1,BigDecimal.valueOf(250),true);return null;});
        RecoveryOptions o=enabledRecovery();o.setAutoReplaceHistory(false);o.setRestoreMode("QUICK");
        PersistentPriceControl.Task t=start(o);display(t.plannedEnd,90,true);
        List<Map<String,Object>> visible=store.visibleMixed(1,minute,minute+60000);
        assertFalse(visible.isEmpty());assertEquals(0,BigDecimal.valueOf(250).compareTo(ControlHistoryStore.number(visible.get(0).get("high_price"))));
    }
    @Test void migrationCanRunTwiceAndDoesNotEnrollLegacyTasks() {
        PersistentPriceControl.Task t=legacy(System.currentTimeMillis()-5000,1);
        store.migrate();store.migrate();controls.advance(1,System.currentTimeMillis());
        assertEquals(0,count("market_control_flow"));assertNotNull(controls.latest(1));
    }
    @Test void gradualStartsContinuouslyAndEndsAtMovingSource() {
        PersistentPriceControl.Task t = start(enabledRecovery());
        Map<String,Object> first = display(t.plannedEnd, 100, true);
        price(110, first); assertEquals("RECOVERING", first.get("controlState"));
        assertEquals(1, count("market_control_publication"));
        price(107, display(t.plannedEnd + 10000, 107, true));
        assertEquals("SOURCE", flow(t).get("state"));
        assertEquals(t.plannedEnd + 10000, ((Number)store.db.queryForMap("SELECT * FROM market_control_publication").get("to_at")).longValue());
        assertEquals(1, count("market_control_task"));
    }
    @Test void quickRestoreHasNoRecoveryTaskAndIsIdempotent() {
        RecoveryOptions o = enabledRecovery(); o.setRestoreMode("QUICK");
        PersistentPriceControl.Task t = start(o);
        price(95, display(t.plannedEnd, 95, true));
        long samples = count("market_control_sample");
        display(t.plannedEnd, 95, true); display(t.plannedEnd + 10, 95, true);
        assertEquals(samples, count("market_control_sample"));
        assertEquals(1, count("market_control_resume")); assertEquals(1, count("market_control_publication"));
    }
    @Test void outageWaitsWithoutSamplesAndRestartPreservesClock() {
        PersistentPriceControl.Task t = start(enabledRecovery());
        price(110, display(t.plannedEnd, 0, false));
        assertEquals("WAITING_SOURCE", flow(t).get("state"));
        long n = count("market_control_sample");
        display(t.plannedEnd + 60000, 0, false); assertEquals(n, count("market_control_sample"));
        controls = new PersistentPriceControl(store);
        price(110, display(t.plannedEnd + 90000, 103, true));
        assertEquals(t.plannedEnd + 90000, ((Number)flow(t).get("recovery_started_at")).longValue());
        controls = new PersistentPriceControl(store);
        price(108, display(t.plannedEnd + 100000, 108, true));
        assertEquals("SOURCE", flow(t).get("state"));
    }
    @Test void noAutoRestoreHoldsAndStopCancelsRecovery() {
        RecoveryOptions o = enabledRecovery(); o.setAutoRestore(false);
        PersistentPriceControl.Task t = start(o);
        assertEquals("HOLDING", display(t.plannedEnd, 90, true).get("controlState"));
        assertEquals("HOLDING", display(t.plannedEnd + 60000, 95, true).get("controlState"));
        assertNull(flow(t).get("recovery_started_at"));
        controls.stop(1, t.plannedEnd + 60001);
        assertEquals("SOURCE", flow(t).get("state"));
    }
    @Test void stopDuringRecoveryRetainsLastPriceAndNeverRestarts() {
        PersistentPriceControl.Task t = start(enabledRecovery());
        display(t.plannedEnd, 100, true);
        Map<String,Object> last = display(t.plannedEnd + 3000, 100, true);
        controls.stopAndHold(1, t.plannedEnd + 3000);
        assertEquals("HOLDING", flow(t).get("state"));
        Map<String,Object> held = display(t.plannedEnd + 60000, 90, true);
        assertEquals(last.get("price"), held.get("price"));
        assertEquals("HOLDING", flow(t).get("state"));
        controls.stop(1, t.plannedEnd + 60001);
        assertEquals("SOURCE", display(t.plannedEnd + 90000, 92, true).get("controlState"));
    }
    @Test void unpublishedHistoryReturnsToOriginalAndCanBePublishedManually() {
        RecoveryOptions o = enabledRecovery(); o.setAutoReplaceHistory(false); o.setRestoreMode("QUICK");
        PersistentPriceControl.Task t = start(o);
        long minute = t.startedAt / 60000 * 60000;
        seed(minute);
        assertFalse(store.visibleMixed(1, minute, minute + 60000).isEmpty());
        display(t.plannedEnd, 90, true);
        assertTrue(store.visibleMixed(1, minute, minute + 60000).isEmpty());
        assertTrue(count("market_control_sample") > 0); assertEquals(0, count("market_control_publication"));
        controls.replaceHistory(1, t.id); controls.replaceHistory(1, t.id);
        assertFalse(store.visibleMixed(1, minute, minute + 60000).isEmpty());
        assertEquals(1, count("market_control_publication")); assertEquals(1, count("market_source_candle"));
    }
    @Test void sameMinutePublishedTaskSurvivesUnpublishedTask() {
        RecoveryOptions o = enabledRecovery(); o.setAutoReplaceHistory(false); o.setRestoreMode("QUICK");
        PersistentPriceControl.Task a = start(o);
        display(a.plannedEnd, 90, true); controls.replaceHistory(1, a.id);
        PersistentPriceControl.Task b = controls.start(symbol, raw(System.currentTimeMillis(),true), BigDecimal.valueOf(90),
            1, BigDecimal.valueOf(200), 1, false, false, "second", o);
        display(b.plannedEnd, 90, true);
        List<Map<String,Object>> minutes = store.visibleMixed(1, a.startedAt / 60000 * 60000, b.plannedEnd + 60000);
        assertFalse(minutes.isEmpty());
        for (Map<String,Object> bar : minutes) assertTrue(ControlHistoryStore.number(bar.get("high_price")).compareTo(BigDecimal.valueOf(200)) < 0);
        assertEquals(2, count("market_control_task"));
    }
    @Test void duplicateSnapshotIsRejectedAndConcurrentRetryHasOneFlow() throws Exception {
        RecoveryOptions o = enabledRecovery(); PersistentPriceControl.Task t = start(o);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = executor.submit(inTenant(() -> {assertEquals(t.id, start(o).id);return null;}));
            Future<?> b = executor.submit(inTenant(() -> {assertEquals(t.id, start(o).id);return null;}));
            a.get(); b.get();
        } finally { executor.shutdownNow(); }
        assertEquals(1, count("market_control_flow"));
        o.setRestoreDurationSeconds(20);
        assertThrows(BusinessException.class, () -> start(o));
    }
    @Test void manualRealtimeRestoreUsesChangingQuote() {
        PersistentPriceControl.Task t = controls.startRealtimeRestore(symbol, raw(System.currentTimeMillis(),true), BigDecimal.valueOf(120), 3, 1, false, "manual");
        display(t.startedAt, 90, true);
        price(95, display(t.startedAt + 3000, 95, true));
        assertEquals("SOURCE", flow(t).get("state"));
    }
}

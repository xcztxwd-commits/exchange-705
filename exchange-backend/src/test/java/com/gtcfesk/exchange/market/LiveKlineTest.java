package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class LiveKlineTest extends TenantMarketTestContext {
    PersistentPriceControlTest fixture;
    ForexQuoteMarketService market;
    long minute;
    @BeforeEach void setup() {
        fixture = new PersistentPriceControlTest(); fixture.setup();
        fixture.symbol.setCategory("Forex"); fixture.symbol.setSourceCategory("Forex"); fixture.symbol.setMarketSource("yahoo");
        fixture.symbol.setBaseCurrency("USD"); fixture.symbol.setQuoteCurrency("USD");
        market = new S2CommandAcceptanceTest().newMarket(fixture.store,fixture.controls,fixture.symbol);
        ReflectionTestUtils.setField(market,"klineMerger",fixture.merger);
        minute = System.currentTimeMillis()/60000*60000;
    }
    @AfterEach void stop() { market.stop(); }
    Map<String,Object> source(long at, int close) {
        Map<String,Object> bar = new LinkedHashMap<>(); bar.put("timestamp",at);
        bar.put("open_price",90); bar.put("high_price",100); bar.put("low_price",80); bar.put("close_price",close); bar.put("volume",7);
        return bar;
    }
    void quote(long at, int price) {
        Map<String,Object> raw = fixture.raw(Math.min(at,System.currentTimeMillis()),true); raw.put("price",price); raw.put("fetchedAt",at);
        raw.put("expiresAt",at+60000); raw.put("eventId","event-"+at+"-"+price);
        fixture.controls.sourceQuote(fixture.symbol,raw,at);
    }
    Map<String,Object> live(String period) { return market.internalKline("TEST",period,10); }
    Map<String,Object> tail(Map<String,Object> result) { List<Map<String,Object>> bars=ControlHistoryStore.rows(result); return bars.get(bars.size()-1); }
    void price(Map<String,Object> bar,String field,int expected) { assertEquals(0,BigDecimal.valueOf(expected).compareTo(ControlHistoryStore.number(bar.get(field)))); }

    @Test void sourceQuoteUpdatesOpenCandleAndVersionWithoutChangingProtectedHistory() {
        Map<String,Object> closed=source(minute-60000,88); closed.put("controlled",true); closed.put("historyReplaced",true);
        fixture.store.db.update("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)",minute-60000,fixture.store.encode(closed),minute-1);
        fixture.store.sourceCandles(1,"1m",Arrays.asList(source(minute-60000,89),source(minute,91)),minute+500);
        String protectedBody=fixture.store.db.queryForObject("SELECT body FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1",String.class);
        Map<String,Object> beforeHistory=ControlHistoryStore.rows(live("1m")).get(0);
        quote(minute+1000,103); quote(minute+2000,79); quote(minute+3000,95);
        Map<String,Object> result=live("1m"), last=tail(result), data=(Map<String,Object>)result.get("data");
        assertEquals(true,market.snapshotPrice("TEST").get("controlHistory"));
        price(last,"open_price",90); price(last,"high_price",103); price(last,"low_price",79); price(last,"close_price",95);
        assertEquals(market.snapshotPrice("TEST").get("quoteVersion"),data.get("quoteVersion"));
        assertEquals(market.snapshotPrice("TEST").get("epoch"),data.get("epoch")); assertEquals(true,data.get("live"));
        assertEquals(protectedBody,fixture.store.db.queryForObject("SELECT body FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1",String.class));
        price(fixture.store.candles(1,"1m",minute,minute).get(0),"close_price",91);
        assertEquals(beforeHistory,ControlHistoryStore.rows(result).get(0));
    }
    @Test void minuteRolloverAndPeriodSwitchRetainPublishedExtremesAndRejectOldSourceEvents() {
        quote(minute+1000,101); quote(minute+2000,110); quote(minute+59000,99); quote(minute+61000,105);
        Map<String,Object> current=live("1m"), prior=ControlHistoryStore.rows(current).get(0);
        assertEquals(minute,ControlHistoryStore.time(prior)); price(prior,"open_price",101); price(prior,"high_price",110); price(prior,"close_price",99);
        assertEquals(minute+60000,ControlHistoryStore.time(tail(current))); price(tail(current),"open_price",105);
        for(String period:Arrays.asList("5m","15m","30m")) price(tail(live(period)),"close_price",105);
        Object version=market.snapshotPrice("TEST").get("quoteVersion"); quote(minute+2000,1);
        assertEquals(version,market.snapshotPrice("TEST").get("quoteVersion")); price(tail(live("1m")),"close_price",105);
        assertEquals(0,fixture.store.db.queryForObject("SELECT COUNT(*) FROM market_mixed_minute",Integer.class));
    }
    @Test void aNewQuoteVersionCannotBeAppliedToAnOldLiveCandleAndTenantCannotReadItsState() {
        quote(minute+1000,105);
        Map<String,Object> before=live("1m"); assertEquals(true,((Map<?,?>)before.get("data")).get("live"));
        fixture.store.db.update("UPDATE market_engine_runtime SET snapshot_version=snapshot_version+1,quote_json=REPLACE(quote_json,?,?) WHERE tenant_id=1 AND symbol_id=1", "\"quoteVersion\":1", "\"quoteVersion\":2");
        Map<String,Object> rejected=live("1m"); assertNull(((Map<?,?>)rejected.get("data")).get("quoteVersion"));
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try(TenantContext.Scope scope=TenantContext.open(2L)) {
                assertFalse(market.knownSymbol("TEST"));
                assertTrue(ControlHistoryStore.rows(market.internalKline("TEST","1m",10)).isEmpty());
            }
        }).join();
    }
    @Test void queuedSourceAfterANewerPumpRetainsExtremesAndUsesPublicationTime() {
        quote(minute+1000,103); quote(minute+3000,79);
        Map<String,Object> raw=fixture.raw(Math.min(minute+3000,System.currentTimeMillis()),true);
        raw.put("price",95); raw.put("fetchedAt",minute+2000); raw.put("eventId","queued-new-source"); raw.put("expiresAt",minute+60000);
        fixture.controls.sourceQuote(fixture.symbol,raw,minute+2000);
        Map<String,Object> result=live("1m"), last=tail(result), q=market.snapshotPrice("TEST");
        price(last,"open_price",103); price(last,"high_price",103); price(last,"low_price",79); price(last,"close_price",95);
        assertEquals(minute+3000,QuoteState.time(q.get("committedAt")));
        assertEquals(q.get("committedAt"),((Map<?,?>)result.get("data")).get("updatedAt"));
        assertEquals(q.get("quoteVersion"),((Map<?,?>)result.get("data")).get("quoteVersion"));
    }
    @Test void weeklyAndSimulationPeriodsUseExistingCalendarAndSessionAnchors() {
        fixture.symbol.setSourceCategory("Crypto");
        quote(minute+1000,105);
        Map<String,Object> q=fixture.store.runtime.read(1,false,minute+1000);
        Map<?,?> periods=(Map<?,?>)q.get(LiveKline.KEY);
        long monday=java.time.Instant.ofEpochMilli(minute).atZone(java.time.ZoneOffset.UTC)
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toLocalDate()
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
        assertEquals(monday,ControlHistoryStore.time(((List<Map<String,Object>>)periods.get("1w")).get(0)));
        long session=minute-120000;
        fixture.symbol.setSourceCategory("Forex"); fixture.symbol.setRandomMarketEnabled(true);
        fixture.symbol.setRandomMarketStartedAt(session); fixture.symbol.setRandomMarketBasePrice(BigDecimal.valueOf(100));
        for(String period:Arrays.asList("1h","1d","1w","1M")) {
            long start="1M".equals(period)?RandomMarketPath.monthStart(minute):Math.floorDiv(minute-1800000,RandomMarketPath.duration(period))*RandomMarketPath.duration(period)+1800000;
            fixture.store.db.update("INSERT INTO market_simulation_source_candle(tenant_id,symbol_id,session_at,period,candle_at,body) VALUES(1,1,?,?,?,?)",session,period,start,fixture.store.encode(source(start,91)));
        }
        Map<String,Object> simulated=fixture.raw(System.currentTimeMillis(),true); simulated.put("price",107);
        simulated.put("simulationSession",session); LiveKline.capture(fixture.store,fixture.symbol,simulated,q,minute+2000);
        for(String period:Arrays.asList("1m","5m","15m","30m","1h","1d","1w","1M")) {
            List<Map<String,Object>> rows=(List<Map<String,Object>>)((Map<?,?>)simulated.get(LiveKline.KEY)).get(period);
            assertNotNull(rows,period); price(rows.get(rows.size()-1),"close_price",107);
            if("1h".equals(period)) assertEquals(1800000,Math.floorMod(ControlHistoryStore.time(rows.get(0)),3600000));
        }
    }
    @Test void newerSourceSessionBoundaryWinsOverRetainedFixedWidthCandle() {
        long day=minute-23*3600000L-60000;
        fixture.store.sourceCandles(1,"1d",Arrays.asList(source(day,91)),minute);
        Map<String,Object> q=fixture.raw(System.currentTimeMillis(),true); q.put("price",100);
        LiveKline.capture(fixture.store,fixture.symbol,q,Collections.emptyMap(),minute-60000);
        long next=day+23*3600000L;
        fixture.store.sourceCandles(1,"1d",Arrays.asList(source(next,92)),minute);
        Map<String,Object> after=fixture.raw(System.currentTimeMillis(),true); after.put("price",105);
        LiveKline.capture(fixture.store,fixture.symbol,after,q,minute);
        List<Map<String,Object>> rows=(List<Map<String,Object>>)((Map<?,?>)after.get(LiveKline.KEY)).get("1d");
        assertEquals(next,ControlHistoryStore.time(rows.get(rows.size()-1))); price(rows.get(rows.size()-1),"open_price",105);
    }
    @Test void controlTransitionKeepsPublishedOpenCandleExtremesAndFundingVersionRecoversOnPump() {
        quote(minute+1000,103);quote(minute+2000,79);
        Map<String,Object> previous=fixture.store.runtime.read(1,false,minute+2000);
        Map<String,Object> next=fixture.raw(Math.min(minute+3000,System.currentTimeMillis()),true);
        next.put("price",95);next.put("controlState","SOURCE");next.put("controlPublicationRevision","1:2");
        LiveKline.capture(fixture.store,fixture.symbol,next,previous,minute+3000);
        List<Map<String,Object>> rows=(List<Map<String,Object>>)((Map<?,?>)next.get(LiveKline.KEY)).get("1m");
        price(rows.get(rows.size()-1),"open_price",103);price(rows.get(rows.size()-1),"high_price",103);price(rows.get(rows.size()-1),"low_price",79);
        fixture.store.locked(1,()->{fixture.store.runtime.snapshot(1,previous,new LinkedHashMap<>(),minute+2500);return null;});
        assertNull(((Map<?,?>)live("1m").get("data")).get("quoteVersion"));
        fixture.controls.pump(fixture.symbol,next,minute+3000,60000);
        assertEquals(market.snapshotPrice("TEST").get("quoteVersion"),((Map<?,?>)live("1m").get("data")).get("quoteVersion"));
        price(tail(live("1m")),"high_price",103);price(tail(live("1m")),"low_price",79);price(tail(live("1m")),"close_price",95);
    }
}

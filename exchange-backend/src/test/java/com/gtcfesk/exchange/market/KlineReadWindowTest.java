package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Measures rows actually returned for replay, rather than just the presence of SQL LIMITs. */
class KlineReadWindowTest extends TenantMarketTestContext {
    final long at = 1700000400000L, width = 300000L;
    S4BatchHistoryTest fixture;
    S4BatchHistoryTest.RecordingJdbc db;
    ControlHistoryStore store;
    ControlledKlineMerger merger;

    @BeforeEach void setup() {
        fixture = new S4BatchHistoryTest(); fixture.setup();
        db = fixture.db; store = fixture.store; merger = new ControlledKlineMerger(store);
        db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1)");
    }
    Map<String,Object> bar(long minute, String price) { return fixture.bar(minute, price); }
    Map<String,Object> external() {
        return Map.of("ret",200,"data",Map.of("code","TEST","source","fixture","kline_list",Collections.emptyList()));
    }
    void nativeBars(int count) {
        List<Object[]> rows = new ArrayList<>();
        for (int i=0;i<count;i++) rows.add(new Object[]{1L,1L,"5m",at+i*width,store.encode(bar(at+i*width,"90")),at+(i+1)*width});
        db.batchUpdate("INSERT INTO market_source_candle VALUES(?,?,?,?,?,?)",rows);
    }
    void controlled(long minute, boolean published) {
        String id="control-"+minute;
        fixture.task(1,1,id,minute+1000,minute+3000,true);
        if (published) db.update("INSERT INTO market_control_publication VALUES(1,?,?,?,?)",id,minute+3000,minute+1000,minute+3000);
        db.update("INSERT INTO market_control_sample VALUES(1,?,?,10)",id,minute+1000);
        db.update("INSERT INTO market_mixed_minute VALUES(1,1,?,?,?)",minute,store.encode(bar(minute,"99999")),minute+5000);
        db.update("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(1,?,1,?,?,50)",id,minute+5000,minute+5000);
    }
    void oldHistory(int first, int end) {
        List<Object[]> rows=new ArrayList<>();
        for (int i=first;i<end;i++) {
            long minute=at-(i+1)*60000L;
            rows.add(new Object[]{1L,1L,minute,store.encode(bar(minute,"88888")),minute+5000});
        }
        db.batchUpdate("INSERT INTO market_mixed_minute VALUES(?,?,?,?,?)",rows);
        rows.clear();
        for (int i=first;i<end;i++) for(int event=0;event<12;event++) {
            long time=at-(i+1)*60000L+5000+event;
            rows.add(new Object[]{1L,"old-"+i+"-"+event,1L,time,time,BigDecimal.ONE});
        }
        db.batchUpdate("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(?,?,?,?,?,?)",rows);
    }
    int replays() { return db.calls.stream().filter(call->call.sql.contains("UNION ALL")).mapToInt(call->call.rows).sum(); }
    Map<String,Object> read(int limit, Long cursor) { return merger.merge(1,"5m",limit,cursor,external(),ignored->fail("A read must not request backfill")); }
    List<Long> times(Map<String,Object> response) {
        List<Long> times=new ArrayList<>();for(Map<String,Object> row:ControlHistoryStore.rows(response)) times.add(ControlHistoryStore.time(row));return times;
    }
    void price(Map<String,Object> row, String field, String expected) {
        assertEquals(0,new BigDecimal(expected).compareTo(ControlHistoryStore.number(row.get(field))));
    }

    @Test void completeNativePageAndTenTimesOldHistoryReplayOnlyTheNecessaryMinuteOnce() {
        nativeBars(200); controlled(at+199*width+60000,true);
        controlled(at-200*60000L,true); oldHistory(0,10);
        Map<Integer,Map<String,Object>> before=new HashMap<>();
        for (int limit:List.of(2,20,200)) {
            db.clear(); Map<String,Object> result=read(limit,null); before.put(limit,result);
            assertEquals(limit,ControlHistoryStore.rows(result).size()); assertEquals(1,replays());
            price(ControlHistoryStore.rows(result).get(limit-1),"close_price","50");
            assertTrue(db.calls.stream().noneMatch(call->call.sql.contains("ORDER BY minute_at DESC")),"Complete pages never seek the old directory");
        }
        // Both the unrelated mixed directory and its source ledger grow exactly tenfold.
        oldHistory(10,100);
        for (int limit:List.of(2,20,200)) {
            db.clear(); assertEquals(before.get(limit),read(limit,null)); assertEquals(1,replays());
        }
    }

    @Test void candidatePeriodsThatCannotEnterTheFinalPageAreNotReplayed() {
        nativeBars(3);
        for(int i=0;i<8;i++) controlled(at+i*width+60000,true);
        db.clear(); Map<String,Object> result=read(2,at+8*width);
        assertEquals(List.of(at+6*width,at+7*width),times(result)); assertEquals(2,replays());
    }

    @Test void shortPageSkipsHiddenSourceCandidatesAndContinuesToOlderPublishedHistory() {
        nativeBars(1); controlled(at-width+60000,false);
        controlled(at-2*width+60000,true); controlled(at-3*width+60000,true);
        db.clear(); Map<String,Object> result=read(3,at);
        assertEquals(List.of(at-3*width,at-2*width,at),times(result)); assertEquals(3,replays());
        for(Map<String,Object> row:ControlHistoryStore.rows(result).subList(0,2)) assertEquals(true,row.get("historyReplaced"));
        db.clear(); Map<String,Object> older=read(2,at-2*width-1);
        assertEquals(List.of(at-3*width),times(older)); assertEquals(1,replays());
    }

    @Test void hiddenCandidateKeepsItsNativeSourceBarAndManualPointsRemainVisible() {
        nativeBars(2); long minute=at+width+60000; controlled(minute,false);
        db.clear(); Map<String,Object> result=read(2,null);
        assertEquals(List.of(at,at+width),times(result)); price(ControlHistoryStore.rows(result).get(1),"close_price","90");
        Map<String,Object> mixed=bar(minute,"99999");
        mixed.put("manualPoints",List.of(Map.of("generated_at",minute+6000,"price",new BigDecimal("75"))));
        db.update("UPDATE market_mixed_minute SET body=? WHERE tenant_id=1 AND symbol_id=1 AND minute_at=?",store.encode(mixed),minute);
        db.clear(); result=read(2,null); assertEquals(1,replays());
        price(ControlHistoryStore.rows(result).get(1),"close_price","75");
    }

    void orderingPolicy() {
        db.update("INSERT INTO market_history_ordering(tenant_id,symbol_id,ordering_version,from_minute,source_sequence,scope_sha256,evidence_sha256,responses_json,sealed_at,writer_generation) VALUES(1,1,2,?,100000,?,?,'{}',?,1)",
            at-60000,HistoryOrdering.sha("scope"),HistoryOrdering.sha("evidence"),at-60000);
    }
    @Test void hiddenUnseededMinutesStillValidateFutureEventSequences() {
        nativeBars(1); controlled(at+60000,false); orderingPolicy();
        BusinessException failure=assertThrows(BusinessException.class,()->read(1,null));
        assertTrue(failure.getMessage().contains("Future source event lacks post-cutover sequence"));
    }
    @Test void unknownNativeSessionStillValidatesHiddenMinuteOrderingWithoutInventingBars() {
        controlled(at+60000,false); orderingPolicy();
        BusinessException failure=assertThrows(BusinessException.class,()->merger.merge(1,"1d",2,at,external(),null,false));
        assertTrue(failure.getMessage().contains("Future source event lacks post-cutover sequence"));
        db.update("DELETE FROM market_history_ordering");
        assertTrue(ControlHistoryStore.rows(merger.merge(1,"1d",2,at,external(),null,false)).isEmpty());
    }
    @Test void legacyTieGuardSurvivesCandidateSelection() {
        nativeBars(1); controlled(at+60000,true);
        db.update("INSERT INTO market_source_tick VALUES(1,1,?,?,11),(1,1,?,?,12)",at+70001,at+70000,at+70002,at+70000);
        assertTrue(assertThrows(BusinessException.class,()->read(1,null)).getMessage().contains("HISTORY_LEGACY_ORDER_PENDING"));
    }
    @Test void websocketTwoAndHomeTwentyUseTheSameBoundedConsumerSnapshot() {
        nativeBars(200); controlled(at+199*width+60000,true); controlled(at-60000,true);
        TradingSymbol symbol=new TradingSymbol();symbol.setId(1L);symbol.setTenantId(1L);symbol.setSymbol("TEST");
        symbol.setCategory("Crypto");symbol.setSourceCategory("Crypto");symbol.setMarketSource("binance");symbol.setIsEnabled(true);symbol.setPricePrecision(2);
        ForexQuoteMarketService market=new S2CommandAcceptanceTest().newMarket(store,new PersistentPriceControl(store),symbol);
        ReflectionTestUtils.setField(market,"klineMerger",merger);
        try {
            db.clear(); market.readSnapshot(()->{
                assertEquals(2,ControlHistoryStore.rows(market.internalKline("TEST","5m",2)).size());
                assertEquals(20,ControlHistoryStore.rows(market.internalKline("TEST","5m",20)).size());return null;
            });
            assertEquals(2,replays(),"Each shared read replays its required minute once");
            java.util.concurrent.CompletableFuture.runAsync(()->{
                try(TenantContext.Scope ignored=TenantContext.open(2L)) { assertTrue(ControlHistoryStore.rows(read(20,null)).isEmpty()); }
            }).join();
        } finally { market.stop(); }
    }
}

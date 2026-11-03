package com.gtcfesk.exchange.market;

import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent acceptance at the hidden-directory and JDBC pagination boundaries. */
class KlineIndependentAcceptanceTest extends TenantMarketTestContext {
    KlineReadWindowTest f;
    @BeforeEach void setup() { f=new KlineReadWindowTest(); f.setup(); }

    @Test void shortPageSearchesPastMoreThanLimitPlusTwoHiddenSourcePeriods() {
        f.nativeBars(1);
        for(int i=1;i<=9;i++) f.controlled(f.at-i*f.width+60000,false);
        f.controlled(f.at-10*f.width+60000,true);
        f.controlled(f.at-11*f.width+60000,true);
        f.db.clear();
        Map<String,Object> result=f.read(3,f.at);
        assertEquals(List.of(f.at-11*f.width,f.at-10*f.width,f.at),f.times(result));
        for(Map<String,Object> row:ControlHistoryStore.rows(result).subList(0,2)) {
            assertEquals(true,row.get("historyReplaced")); f.price(row,"close_price","50");
        }
        assertEquals("original_source_candles",((Map<?,?>)result.get("data")).get("missingData"));
        assertEquals(11,f.replays(),"Each examined controlled minute must be reconstructed once");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={1,2,20,200,1000})
    void fullNativePagesRemainBoundedAfterTenfoldUnrelatedGrowth(int limit) {
        f.nativeBars(1000); f.controlled(f.at+999*f.width+60000,true);
        f.controlled(f.at-1000*60000L,true); f.oldHistory(0,50);
        f.db.clear(); Map<String,Object> before=f.read(limit,null);
        assertEquals(limit,ControlHistoryStore.rows(before).size());assertEquals(1,f.replays());
        f.oldHistory(50,500);f.db.clear();assertEquals(before,f.read(limit,null));assertEquals(1,f.replays());
        assertTrue(f.db.calls.stream().noneMatch(c->c.sql.contains("ORDER BY minute_at DESC")));
    }

    @Test void equalReceivedEventsAndSamplesCrossTwoPageBoundariesExactlyOnce() {
        f.nativeBars(1);long minute=f.at+60000;f.controlled(minute,true);
        String task="control-"+minute;List<Object[]> samples=new ArrayList<>(),events=new ArrayList<>();
        for(int i=0;i<1201;i++) {
            samples.add(new Object[]{1L,task,minute+1001+i,new java.math.BigDecimal("10")});
            events.add(new Object[]{1L,"dense-"+i,1L,minute+10000+i,minute+7000,new java.math.BigDecimal("60").add(java.math.BigDecimal.valueOf(i,3))});
        }
        f.db.batchUpdate("INSERT INTO market_control_sample VALUES(?,?,?,?)",samples);
        f.db.batchUpdate("INSERT INTO market_source_event(tenant_id,event_id,symbol_id,source_time,received_at,price) VALUES(?,?,?,?,?,?)",events);
        f.db.clear();Map<String,Object> result=f.read(1,f.at);
        assertEquals(List.of(f.at),f.times(result));f.price(ControlHistoryStore.rows(result).get(0),"close_price","61.200");
        assertEquals(1202,f.replays());
        assertEquals(List.of(500,500,202),f.db.calls.stream().filter(c->c.sql.contains("UNION ALL")).map(c->c.rows).collect(java.util.stream.Collectors.toList()));
        assertEquals(1202,f.db.calls.stream().filter(c->c.sql.startsWith("SELECT s.generated_at,s.price")).mapToInt(c->c.rows).sum());
    }

    @Test void mixedAndMinuteKeysCrossFiveHundredRowsWithoutReplayingTheNeededMinuteTwice() {
        List<Object[]> rows=new ArrayList<>();
        for(int i=0;i<1501;i++) {long minute=f.at+i*60000L;rows.add(new Object[]{1L,1L,minute,f.store.encode(f.bar(minute,"90")),minute+5000});}
        f.db.batchUpdate("INSERT INTO market_mixed_minute VALUES(?,?,?,?,?)",rows);
        long controlled=f.at+1441*60000L;f.db.update("DELETE FROM market_mixed_minute WHERE minute_at=?",controlled);f.controlled(controlled,true);
        for(int i=0;i<2;i++) {long start=f.at+i*86400000L;f.db.update("INSERT INTO market_source_candle VALUES(1,1,'1d',?,?,?)",start,f.store.encode(f.bar(start,"90")),start+86400000L);}
        f.db.clear();Map<String,Object> result=f.merger.merge(1,"1d",2,f.at+86400000L,f.external(),null);
        assertEquals(List.of(f.at,f.at+86400000L),f.times(result));assertEquals(1,f.replays());
        List<Map<String,Object>> bars=ControlHistoryStore.rows(result);
        assertEquals(1440,((Number)bars.get(0).get("minuteCount")).intValue());assertEquals(61,((Number)bars.get(1).get("minuteCount")).intValue());
        assertEquals(1501,f.db.calls.stream().filter(c->c.sql.startsWith("SELECT body FROM market_mixed_minute")&&c.sql.contains(" IN (")).mapToInt(c->c.rows).sum());
    }

    @Test void shortPageCrossesAWholeDirectoryPageOfHiddenPeriods() {
        f.nativeBars(1);
        for(int i=1;i<=523;i++) f.controlled(f.at-i*f.width+60000,false);
        for(int i=524;i<=542;i++) f.controlled(f.at-i*f.width+60000,true);
        f.db.clear();Map<String,Object> result=f.read(20,f.at);
        List<Long> expected=new ArrayList<>();for(int i=542;i>=524;i--)expected.add(f.at-i*f.width);expected.add(f.at);
        assertEquals(expected,f.times(result));assertEquals(542,f.replays());
    }

    @Test void offsetSessionOverlappingDirectorySlicesNeverReconstructsAMinuteTwice() {
        long hour=3600000L,start=Math.floorDiv(f.at,hour)*hour+hour/2;
        f.db.update("INSERT INTO market_source_candle VALUES(1,1,'1h',?,?,?)",start,f.store.encode(f.bar(start,"90")),start+hour);
        for(int i=1;i<=14;i++) for(long offset:new long[]{600000L,2400000L})f.controlled(start-i*hour+offset,i>=13);
        f.db.clear();Map<String,Object> result=f.merger.merge(1,"1h",3,start,f.external(),null,false);
        assertEquals(List.of(start-14*hour,start-13*hour,start),f.times(result));assertEquals(28,f.replays());
    }
}

package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** H2 unit contract; actual source OHLC/MySQL/GET-no-DML coverage is in S2SimulationSourceMysqlTest. */
class S2CanonicalKlineMergerTest extends TenantMarketTestContext {
    ControlHistoryStore store;
    ControlledKlineMerger merger;
    @BeforeEach void setup() {
        DriverManagerDataSource data = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        store = new ControlHistoryStore(new JdbcTemplate(data),new DataSourceTransactionManager(data));
        MarketSqlFixture.schema(store.db); store.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1)");
        merger = new ControlledKlineMerger(store);
    }
    Map<String,Object> candle(long at,int open,int high,int low,int close) {
        return new LinkedHashMap<>(Map.of("timestamp",at,"open_price",new BigDecimal(open),"high_price",new BigDecimal(high),
                "low_price",new BigDecimal(low),"close_price",new BigDecimal(close),"volume",7));
    }
    Map<String,Object> external(List<Map<String,Object>> rows,long session) {
        return Map.of("ret",200,"data",Map.of("kline_list",rows,"simulationSession",session));
    }
    void price(Map<String,Object> row,String name,int expected) { assertEquals(0,new BigDecimal(expected).compareTo(ControlHistoryStore.number(row.get(name)))); }
    @Test void sourceOnlyOneMinuteCopiesCanonicalRowsAndBoundsItsReadWithoutMixedRows() {
        long at=Instant.parse("2026-03-10T12:00:00Z").toEpochMilli();
        List<Map<String,Object>> rows=List.of(candle(at,90,95,89,91),candle(at+60000,91,96,90,92),candle(at+120000,92,97,91,93));
        rows.get(1).put("canonicalTag","unchanged");List<long[]> calls=new ArrayList<>();
        Map<String,Object> result=merger.merge(1,"1m",2,at+120000,external(List.of(),0),null,true,(from,to)->{
            calls.add(new long[]{from,to});List<Map<String,Object>> page=new ArrayList<>();
            for(Map<String,Object> row:rows)if(ControlHistoryStore.time(row)>=from && ControlHistoryStore.time(row)<=to)page.add(row);return page;
        });
        assertEquals(rows.subList(1,3),ControlHistoryStore.rows(result));assertEquals(1,calls.size());
        assertEquals(at+60000,calls.get(0)[0]);assertEquals(at+179999,calls.get(0)[1]);
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_mixed_minute",Integer.class));
    }
    @Test void sourceOnlyAggregatesExistingPeriodBucketsIncludingUtcEpochZeroWeekAndCalendarMonth() {
        long at=Instant.parse("2026-04-01T00:00:00Z").toEpochMilli();
        for(String interval:List.of("5m","1h","1d","1w","1M")) {
            long width=RandomMarketPath.duration(interval);
            long boundary="1M".equals(interval)?RandomMarketPath.monthStart(at):Math.floorDiv(at,width)*width;
            long previous="1M".equals(interval)?RandomMarketPath.monthStart(boundary-1):boundary-width;
            List<Map<String,Object>> source=List.of(candle(boundary-60000,90,95,88,91),candle(boundary,91,98,89,96),candle(boundary+60000,96,99,90,97));
            Map<String,Object> result=merger.merge(1,interval,3,boundary+60000,external(List.of(),0),null,true,(from,to)->source);
            List<Map<String,Object>> bars=ControlHistoryStore.rows(result);assertEquals(2,bars.size(),interval);
            assertEquals(previous,ControlHistoryStore.time(bars.get(0)),interval);assertEquals(boundary,ControlHistoryStore.time(bars.get(1)),interval);
            price(bars.get(1),"open_price",91);price(bars.get(1),"high_price",99);price(bars.get(1),"low_price",89);price(bars.get(1),"close_price",97);price(bars.get(1),"volume",14);
        }
    }
    @Test void capturedProviderAnchorAndPrefixRemainWhenMixedMinutesOverlaySimulation() {
        long anchor=Instant.parse("2026-03-10T09:30:00Z").toEpochMilli(), session=anchor+15*60000;
        Map<String,Object> prefix=candle(anchor,80,120,70,90), active=candle(anchor+20*60000,100,110,99,105);
        Map<String,Object> input=external(List.of(prefix),session);
        Map<String,Object> first=ControlHistoryStore.rows(merger.merge(1,"1h",2,anchor+30*60000,input,null,true,(from,to)->List.of(active))).get(0);
        assertEquals(anchor,ControlHistoryStore.time(first));price(first,"open_price",80);price(first,"high_price",120);price(first,"low_price",70);price(first,"close_price",105);price(first,"volume",7);
        Map<String,Object> mixed=candle(anchor+20*60000,100,130,95,125);
        store.db.update("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,1,?,?,?)",ControlHistoryStore.time(mixed),store.encode(mixed),ControlHistoryStore.time(mixed));
        String committed=store.db.queryForObject("SELECT body FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1",String.class);
        Map<String,Object> overlaid=ControlHistoryStore.rows(merger.merge(1,"1h",2,anchor+30*60000,input,null,true,(from,to)->List.of(active))).get(0);
        assertEquals(anchor,ControlHistoryStore.time(overlaid));price(overlaid,"open_price",80);price(overlaid,"high_price",130);price(overlaid,"low_price",70);price(overlaid,"close_price",125);price(overlaid,"volume",7);
        assertEquals(true,overlaid.get("controlled"));assertEquals(committed,store.db.queryForObject("SELECT body FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=1",String.class));
    }
    @Test void durableConsumerReadsOnlyCapturedRequestedPeriodFromItsCurrentSession() {
        long anchor=Instant.parse("2026-03-10T09:30:00Z").toEpochMilli(),session=anchor+15*60000;
        Map<String,Object> prefix=candle(anchor,80,120,70,90);
        for(long ownedSession:List.of(session,session-60000))store.db.update("INSERT INTO market_simulation_source_candle(tenant_id,symbol_id,session_at,period,candle_at,body) VALUES(1,1,?,'1h',?,?)",ownedSession,anchor,store.encode(ownedSession==session?prefix:candle(anchor,999,999,999,999)));
        TradingSymbol symbol=new TradingSymbol();symbol.setTenantId(1L);symbol.setId(1L);symbol.setSymbol("S2_PREFIX");symbol.setPricePrecision(2);symbol.setIsEnabled(true);symbol.setRandomMarketEnabled(true);symbol.setRandomMarketStartedAt(session);symbol.setRandomMarketBasePrice(new BigDecimal("90"));
        S2CommandAcceptanceTest helper=new S2CommandAcceptanceTest();helper.store=store;
        ForexQuoteMarketService market=helper.newMarket(store,new PersistentPriceControl(store),symbol);ReflectionTestUtils.setField(market,"klineMerger",merger);
        try {
            Map<String,Object> result=ReflectionTestUtils.invokeMethod(market,"durableSimulationKline",symbol,"1h",2,anchor+1000);
            assertEquals(List.of(store.decode(store.encode(prefix))),ControlHistoryStore.rows(result));
            assertEquals(2,store.db.queryForObject("SELECT COUNT(*) FROM market_simulation_source_candle",Integer.class));
        } finally {market.stop();}
    }
}

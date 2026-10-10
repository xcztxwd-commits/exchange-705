package com.gtcfesk.exchange.market;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.gtcfesk.exchange.market.SourceHistoryGapRepairTest.*;

/** A provider quote-time tail must not hide its preceding legal period anchor. */
class LiveKlineAnchorRegressionTest extends TenantMarketTestContext {
    @ParameterizedTest @ValueSource(strings={"1h","1d","1w"})
    void validAnchorSurvivesNonMinuteProviderTail(String period) {
        for (boolean simulated : Arrays.asList(false,true)) { try (Fixture f=new Fixture()) { exercise(f,period,simulated); } }
    }

    @ParameterizedTest @ValueSource(strings={"1h","1d","1w"})
    @EnabledIfEnvironmentVariable(named="KLINE_GAP_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/kline_gap_.*")
    void validAnchorSurvivesNonMinuteProviderTailOnRealMysql(String period) {
        DriverManagerDataSource data=new DriverManagerDataSource(System.getenv("KLINE_GAP_MYSQL_URL"),"root",System.getenv("KLINE_GAP_MYSQL_PASSWORD"));
        try (Fixture f=new Fixture(data,false)) {
            assertTrue(f.db.queryForObject("SELECT VERSION()",String.class).startsWith("5.7."));
            f.store.migrate();
            long id=940+Arrays.asList("1h","1d","1w").indexOf(period);
            f.config.setId(id); f.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,1)",id);
            exercise(f,period,false);
            f.config.setId(id+10); f.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,1)",id+10);
            exercise(f,period,true);
        }
    }

    @SuppressWarnings("unchecked") void exercise(Fixture f,String period,boolean simulated) {
        f.config.setMarketSource("yahoo");f.config.setSourceCategory("Forex");f.config.setCategory("Forex");
        f.config.setBaseCurrency("USD");f.config.setQuoteCurrency("USD");
        f.config.setRandomMarketEnabled(simulated);f.config.setRandomMarketStartedAt(System.currentTimeMillis()-300000);f.config.setRandomMarketBasePrice(BigDecimal.valueOf(100));
        long minute=System.currentTimeMillis()/60000*60000, at=minute+5000;
        long anchor="1h".equals(period)
            ?Math.floorDiv(minute-1800000,3600000)*3600000+1800000:minute-3600000;
        assertTrue(anchor<=at && at<RandomMarketPath.periodEnd(period,anchor));
        Map<String,Object> legal=bar(anchor),tail=bar(minute+1000);
        assertTrue(ControlHistoryStore.periodCandle(legal,period));assertFalse(ControlHistoryStore.periodCandle(tail,period));
        store(f,period,legal,at,simulated);
        Map<String,Object> before=publish(f,Collections.emptyMap(),at);
        assertEquals(anchor,ControlHistoryStore.time(((List<Map<String,Object>>)((Map<?,?>)before.get(LiveKline.KEY)).get(period)).get(0)));
        assertEquals(true,((Map<?,?>)LiveKline.merge(response(Collections.emptyList()),before,"anchor-fixture",period,10).get("data")).get("live"));
        store(f,period,tail,at,simulated);
        Map<String,Object> after=publish(f,before,at+1);
        List<Map<String,Object>> rows=(List<Map<String,Object>>)((Map<?,?>)after.get(LiveKline.KEY)).get(period);
        assertNotNull(rows,"legal "+period+" anchor must survive later non-minute provider tail");
        assertEquals(anchor,ControlHistoryStore.time(rows.get(rows.size()-1)),"provider quote-time tail cannot change legal session/calendar bucket");
        Map<String,Object> merged=LiveKline.merge(response(Collections.emptyList()),after,"anchor-fixture",period,10);
        assertEquals(true,((Map<?,?>)merged.get("data")).get("live"));
        assertEquals(after.get("quoteVersion"),((Map<?,?>)merged.get("data")).get("quoteVersion"));
        for (int price : Arrays.asList(107,141,119)) {
            after=publish(f,after,++at+2,BigDecimal.valueOf(price));
            rows=(List<Map<String,Object>>)((Map<?,?>)after.get(LiveKline.KEY)).get(period);
            Map<String,Object> current=rows.get(rows.size()-1);
            assertEquals(anchor,ControlHistoryStore.time(current));
            assertEquals(0,new BigDecimal("123").compareTo(ControlHistoryStore.number(current.get("open_price"))));
            assertEquals(0,BigDecimal.valueOf(price).compareTo(ControlHistoryStore.number(current.get("close_price"))));
            assertEquals(after.get("committedAt"),current.get("updatedAt"));
            if(price==119) {
                assertEquals(0,new BigDecimal("141").compareTo(ControlHistoryStore.number(current.get("high_price"))));
                assertEquals(0,new BigDecimal("107").compareTo(ControlHistoryStore.number(current.get("low_price"))));
            }
            merged=LiveKline.merge(response(Collections.emptyList()),after,"anchor-fixture",period,10);
            assertEquals(after.get("quoteVersion"),((Map<?,?>)merged.get("data")).get("quoteVersion"));
            assertEquals(0,ControlHistoryStore.number(after.get("price")).compareTo(ControlHistoryStore.number(ControlHistoryStore.rows(merged).get(0).get("close_price"))));
        }
    }
    void store(Fixture f,String period,Map<String,Object> bar,long at,boolean simulated) {
        if (!simulated) f.store.sourceCandles(f.config.getId(),period,Arrays.asList(bar),at);
        else f.store.locked(f.config.getId(),()->{ f.db.update("INSERT INTO market_simulation_source_candle(tenant_id,symbol_id,session_at,period,candle_at,body) VALUES(1,?,?,?,?,?)",f.config.getId(),f.config.getRandomMarketStartedAt(),period,ControlHistoryStore.time(bar),f.store.encode(bar));return null; });
    }
    Map<String,Object> publish(Fixture f,Map<String,Object> previous,long at) {
        return publish(f,previous,at,new BigDecimal("123"));
    }
    Map<String,Object> publish(Fixture f,Map<String,Object> previous,long at,BigDecimal price) {
        return f.store.locked(f.config.getId(),()->{
            Map<String,Object> q=new LinkedHashMap<>();q.put("price",price);q.put("timestamp",at);q.put("available",true);q.put("executionExpiresAt",at+60000);
            if(RandomMarketPath.enabled(f.config)) q.put("simulationSession",f.config.getRandomMarketStartedAt());
            LiveKline.capture(f.store,f.config,q,previous,at);f.store.runtime.snapshot(f.config.getId(),q,Collections.emptyMap(),at);
            return f.store.runtime.read(f.config.getId(),false,at);
        });
    }
}

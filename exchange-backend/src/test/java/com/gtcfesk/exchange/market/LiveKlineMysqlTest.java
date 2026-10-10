package com.gtcfesk.exchange.market;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.gtcfesk.exchange.tenant.TenantContext;
import static org.junit.jupiter.api.Assertions.*;
import static com.gtcfesk.exchange.market.SourceHistoryGapRepairTest.*;

/** Real MySQL 5.7 writer fences and paired repeatable-read snapshots in an owned fixture. */
@EnabledIfEnvironmentVariable(named="KLINE_GAP_MYSQL_URL", matches="jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/kline_gap_.*")
class LiveKlineMysqlTest extends TenantMarketTestContext {
    @Test void actualMinuteHistoryRejectsLegacyMonthWhitespaceAndKeepsMinuteWhitespace() throws Exception {
        DriverManagerDataSource ds=new DriverManagerDataSource(System.getenv("KLINE_GAP_MYSQL_URL"),"root",System.getenv("KLINE_GAP_MYSQL_PASSWORD"));
        try(Fixture f=new Fixture(ds,false)) {
            f.store.migrate();f.config.setId(974L);f.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(974,1)");
            long now=System.currentTimeMillis(),minute=now/60000*60000-120000;
            Map<String,Object> month=bar(minute),shortBar=bar(minute+60000);
            month.put("monthEvidence","retired month with trailing space");
            shortBar.put("minuteEvidence","keep original minute with trailing space");
            f.store.locked(974,()->{f.db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(1,974,'1M ',?,?,?),(1,974,'1m ',?,?,?)",minute,f.store.encode(month),now,minute+60000,f.store.encode(shortBar),now);return null;});
            List<Map<String,Object>> raw=f.db.queryForList("SELECT HEX(period) AS rawPeriod,candle_at,body FROM market_source_candle WHERE tenant_id=1 AND symbol_id=974 ORDER BY candle_at");
            List<Map<String,Object>> actual=f.store.candles(974,"1m",minute,minute+60000);
            Map<String,Object> proof=new LinkedHashMap<>();proof.put("mysql",f.db.queryForObject("SELECT VERSION()",String.class));proof.put("uuid",f.db.queryForObject("SELECT @@server_uuid",String.class));proof.put("raw",raw);proof.put("actualMinuteHistory",actual);proof.put("retiredWhitespace",KlineIntervals.retired("1M "));
            if(System.getenv("KLINE_GAP_QA")!=null) java.nio.file.Files.write(java.nio.file.Paths.get(System.getenv("KLINE_GAP_QA"),"month-whitespace-read-proof.json"),f.store.encode(proof).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(KlineIntervals.retired("1M "));
            assertEquals(1,actual.size(),"Actual minute reader must exclude legacy month suffix whitespace under ci collation");
            assertEquals(f.store.decode(f.store.encode(shortBar)),actual.get(0),"Original minute data remains intact");
            assertEquals(raw,f.db.queryForList("SELECT HEX(period) AS rawPeriod,candle_at,body FROM market_source_candle WHERE tenant_id=1 AND symbol_id=974 ORDER BY candle_at"));
        }
    }
    @Test void companionCaptureUsesCurrentAnchorsAfterAnotherWriterCommitsBeyondTheRepeatableReadView() {
        DriverManagerDataSource ds=new DriverManagerDataSource(System.getenv("KLINE_GAP_MYSQL_URL"),"root",System.getenv("KLINE_GAP_MYSQL_PASSWORD"));
        try(Fixture f=new Fixture(ds,false)) {
            f.store.migrate();
            for(boolean simulated:Arrays.asList(false,true)) {
                long symbol=simulated?911:910, at=System.currentTimeMillis(), minute=at/60000*60000;
                long oldStart=minute-1200000, newStart=Math.floorMod(minute-600000,3600000)==0?minute-660000:minute-600000;
                f.config.setId(symbol);f.config.setSourceCategory("Forex");f.config.setMarketSource("yahoo");
                f.config.setBaseCurrency("USD");f.config.setQuoteCurrency("JPY");
                f.config.setRandomMarketEnabled(simulated);f.config.setRandomMarketStartedAt(at-300000);
                f.config.setRandomMarketBasePrice(BigDecimal.valueOf(100));
                f.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(?,1)",symbol);
                if(!simulated) f.store.sourceCandles(symbol,"1h",Arrays.asList(bar(oldStart)),at);
                f.store.transaction(()->{
                    assertEquals("REPEATABLE-READ",f.db.queryForObject("SELECT @@session.tx_isolation",String.class));
                    f.db.queryForList("SELECT body FROM market_source_candle WHERE tenant_id=1 AND symbol_id=?",symbol);
                    // This independent committed writer follows the production runtime -> source lock order.
                    CompletableFuture.runAsync(()->{try(TenantContext.Scope ignored=TenantContext.open(1L)) {
                        f.store.locked(symbol,()->{
                            if(simulated) f.db.update("INSERT INTO market_simulation_source_candle(tenant_id,symbol_id,session_at,period,candle_at,body) VALUES(1,?,?, '1h',?,?)",symbol,f.config.getRandomMarketStartedAt(),newStart,f.store.encode(bar(newStart)));
                            else f.store.sourceCandles(symbol,"1h",Arrays.asList(bar(newStart)),at);
                            Map<String,Object> q=new LinkedHashMap<>();q.put("price",123);q.put("timestamp",at);q.put("available",true);
                            q.put("executionExpiresAt",at+60000);if(simulated) q.put("simulationSession",f.config.getRandomMarketStartedAt());
                            LiveKline.capture(f.store,f.config,q,Collections.emptyMap(),at);
                            f.store.runtime.snapshot(symbol,q,Collections.emptyMap(),at);return null;
                        });
                    }}).join();
                    f.store.locked(symbol,()->{
                        Map<String,Object> companion=new LinkedHashMap<>();companion.put("price",150);companion.put("timestamp",at);
                        companion.put("source","Yahoo");companion.put("transport","ws");companion.put("sourceAvailable",true);companion.put("eventId","rr-anchor-"+symbol);
                        assertTrue(FundingConversions.record(f.store,f.config,"JPY=X","Forex",companion,at));
                        Map<String,Object> q=f.store.decode(f.db.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=? FOR UPDATE",String.class,symbol));
                        List<Map<String,Object>> bars=(List<Map<String,Object>>)((Map<?,?>)q.get(LiveKline.KEY)).get("1h");
                        assertNotNull(bars,"committed high-period candle must remain available");
                        assertEquals(newStart,ControlHistoryStore.time(bars.get(bars.size()-1)),"old RR view must not override a committed source/session anchor");
                        assertEquals(q.get("quoteVersion"),q.get("liveQuoteVersion"));
                        assertEquals(0,BigDecimal.valueOf(123).compareTo(ControlHistoryStore.number(bars.get(bars.size()-1).get("close_price"))));return null;
                    });return null;
                });
            }
        }
    }

    @Test void publishedQuoteAndOpenCandleCommitTogetherWithoutChangingClosedHistoryOrOtherTenant() {
        DriverManagerDataSource ds=new DriverManagerDataSource(System.getenv("KLINE_GAP_MYSQL_URL"),"root",System.getenv("KLINE_GAP_MYSQL_PASSWORD"));
        try(Fixture f=new Fixture(ds,false)) {
            f.store.migrate(); f.config.setId(909L);f.config.setPricePrecision(2); f.config.setBaseCurrency("USD"); f.config.setQuoteCurrency("USD");
            f.db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(909,1)");
            PersistentPriceControl controls=new PersistentPriceControl(f.store);
            long at=System.currentTimeMillis(), minute=at/60000*60000;
            Map<String,Object> protectedBar=bar(minute-60000); protectedBar.put("controlled",true); protectedBar.put("historyReplaced",true);
            f.store.locked(909,()->{f.db.update("INSERT INTO market_mixed_minute(tenant_id,symbol_id,minute_at,body,last_event) VALUES(1,909,?,?,?)",minute-60000,f.store.encode(protectedBar),minute-1);return null;});
            String protectedBody=f.db.queryForObject("SELECT body FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=909 AND minute_at=?",String.class,minute-60000);
            f.store.sourceCandles(909,"1m",Arrays.asList(bar(minute)),at);
            for(int price:Arrays.asList(103,79,95)) {
                Map<String,Object> raw=new LinkedHashMap<>(); raw.put("price",price); raw.put("timestamp",at); raw.put("sourceTimestamp",at);
                raw.put("fetchedAt",at);raw.put("expiresAt",at+60000);raw.put("available",true);raw.put("eventId","mysql-live-"+price);
                controls.sourceQuote(f.config,raw,at);
                f.store.readConsumerSnapshot(()->{
                    Map<String,Object> q=controls.display(f.config,Collections.emptyMap(),at);
                    Map<String,Object> merged=LiveKline.merge(new ControlledKlineMerger(f.store).merge(909,"1m",10,null,response(Collections.emptyList()),null,true),q,"tenant-one","1m",10);
                    List<Map<String,Object>> bars=ControlHistoryStore.rows(merged);Map<String,Object> tail=bars.get(bars.size()-1);
                    assertEquals(true,q.get("controlHistory"));assertEquals(true,((Map<?,?>)merged.get("data")).get("live"));
                    assertEquals(q.get("quoteVersion"),((Map<?,?>)merged.get("data")).get("quoteVersion"));
                    assertEquals(q.get("committedAt"),((Map<?,?>)merged.get("data")).get("updatedAt"));
                    assertEquals(0,BigDecimal.valueOf(price).compareTo(ControlHistoryStore.number(tail.get("close_price"))));
                    if(price==95) {assertEquals(0,BigDecimal.valueOf(103).compareTo(ControlHistoryStore.number(tail.get("high_price"))));assertEquals(0,BigDecimal.valueOf(79).compareTo(ControlHistoryStore.number(tail.get("low_price"))));}
                    return null;
                });
            }
            assertEquals(protectedBody,f.db.queryForObject("SELECT body FROM market_mixed_minute WHERE tenant_id=1 AND symbol_id=909 AND minute_at=?",String.class,minute-60000));
            assertEquals(0,BigDecimal.valueOf(91).compareTo(ControlHistoryStore.number(f.store.candles(909,"1m",minute,minute).get(0).get("close_price"))));
            f.config.setQuoteCurrency("JPY");f.config.setMarketSource("yahoo");
            Map<String,Object> before=f.store.runtime.read(909,false,at), companion=new LinkedHashMap<>();
            companion.put("price",150);companion.put("timestamp",at);companion.put("source","Yahoo");companion.put("transport","ws");
            companion.put("sourceAvailable",true);companion.put("eventId","mysql-live-companion");
            f.store.locked(909,()->{assertTrue(FundingConversions.record(f.store,f.config,"JPY=X","Forex",companion,at));return null;});
            Map<String,Object> paired=f.store.runtime.read(909,false,System.currentTimeMillis());
            assertEquals(paired.get("quoteVersion"),paired.get("liveQuoteVersion"));assertEquals(before.get("executionExpiresAt"),paired.get("executionExpiresAt"));
            List<Map<String,Object>> fresh=(List<Map<String,Object>>)((Map<?,?>)paired.get(LiveKline.KEY)).get("1m");
            assertEquals(paired.get("committedAt"),fresh.get(fresh.size()-1).get("updatedAt"));
            assertEquals(0,BigDecimal.valueOf(95).compareTo(ControlHistoryStore.number(fresh.get(fresh.size()-1).get("close_price"))));
            CompletableFuture.runAsync(()->{try(TenantContext.Scope ignored=TenantContext.open(2L)) {
                assertTrue(f.store.runtime.read(909,false,at).isEmpty() || !Boolean.TRUE.equals(f.store.runtime.read(909,false,at).get("available")));
                assertEquals(0,f.db.queryForObject("SELECT COUNT(*) FROM market_engine_runtime WHERE tenant_id=2 AND symbol_id=909",Integer.class));
            }}).join();
        }
    }
}

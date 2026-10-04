package com.gtcfesk.exchange.market;

import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual H2 writer/adapter checks; MySQL triggers, lease recovery and old-history compatibility are separately certified. */
class SourceHistoryDirtyRevisionJointTest extends TenantMarketTestContext {
    static final long M=1700000400000L;
    static final class ObservedJdbc extends JdbcTemplate {
        boolean failDirty,failInput;
        final List<String> sql=new ArrayList<>();
        ObservedJdbc(DriverManagerDataSource source){super(source);}
        @Override public int update(String query,Object...arguments) {
            sql.add(query);int count=super.update(query,arguments);
            if(failInput && query.startsWith("UPDATE market_engine_runtime SET source_input_revision="))
                throw new IllegalStateException("after source input revision");
            if(failDirty && query.startsWith("UPDATE market_engine_runtime SET source_dirty_from="))
                throw new IllegalStateException("after source dirty receipt");
            return count;
        }
    }
    static final class Fixture {
        final DriverManagerDataSource data=new DriverManagerDataSource("jdbc:h2:mem:source_dirty_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        final ObservedJdbc db=new ObservedJdbc(data);
        final DataSourceTransactionManager manager=new DataSourceTransactionManager(data);
        final ControlHistoryStore store=new ControlHistoryStore(db,manager);
        final MinuteHistoryProjectionStore derived=new MinuteHistoryProjectionStore(db,manager);
        final SourceHistoryProjector adapter=new SourceHistoryProjector(store,manager);
        final long inputReceived;
        final com.gtcfesk.exchange.entity.TradingSymbol config=new com.gtcfesk.exchange.entity.TradingSymbol();
        Fixture(int count) {
            MarketSqlFixture.schema(db);
            db.execute("ALTER TABLE trading_symbol ADD COLUMN control_enabled BOOLEAN DEFAULT FALSE");
            db.execute("ALTER TABLE trading_symbol ADD COLUMN random_market_enabled BOOLEAN DEFAULT FALSE");
            db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1)");
            new ResourceDatabasePopulator(new ClassPathResource("s4-history-projection.sql")).execute(data);
            config.setTenantId(1L);config.setId(1L);config.setSymbol("DIRTY");config.setPricePrecision(4);config.setIsEnabled(true);
            List<Map<String,Object>> rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add(bar(M+i*60000L,"90.1234"));
            inputReceived=store.runtime.clock()-2000;
            store.sourceCandles(1,"1m",rows,inputReceived);
            long now=store.runtime.clock();
            new PersistentPriceControl(store).pump(config,new LinkedHashMap<>(Map.of("price",new BigDecimal("90.1234"),"timestamp",now,"sourceTimestamp",now,"available",true,"expiresAt",now+60000)),now,60000);
        }
        Map<String,Object> runtime(){return db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1");}
        Map<String,Object> authority(){Map<String,Object> row=new LinkedHashMap<>(runtime());row.keySet().removeIf(key->Arrays.asList("source_input_revision","source_dirty_from","source_dirty_to").contains(key.toLowerCase(Locale.ROOT)));return row;}
        boolean project(){Map<String,Object> route=runtime();return adapter.project(1,(String)route.get("owner_id"),number(route,"writer_generation"),number(route,"control_revision"));}
        Map<String,Object> progress(){return db.queryForMap("SELECT * FROM s4_history_projection_progress WHERE tenant_id=1 AND symbol_id=1");}
        List<Map<String,Object>> minutes(){return db.queryForList("SELECT * FROM s4_history_projection_minute WHERE tenant_id=1 AND symbol_id=1 ORDER BY minute_at");}
        void revise(long minute,String close){store.sourceCandles(1,"1m",Collections.singletonList(bar(minute,close)),store.runtime.clock()-1000);}
    }
    static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static Map<String,Object> bar(long minute,String close){
        BigDecimal open=new BigDecimal("90.1234"),price=new BigDecimal(close);
        Map<String,Object> row=new LinkedHashMap<>();row.put("timestamp",minute);row.put("open_price",open);
        row.put("high_price",open.max(price));row.put("low_price",open.min(price));row.put("close_price",price);row.put("volume",0);return row;
    }
    @Test void oneActualInputRevisionSpansThreeBoundedPublicationVersionsAndRetryDoesNotRenewAuthority(){
        Fixture f=new Fixture(1001);Map<String,Object> authority=f.authority();
        assertEquals(1,number(f.runtime(),"source_input_revision"));
        for(int turn=1;turn<=3;turn++){
            assertTrue(f.project());assertEquals(turn,number(f.progress(),"fact_version"));
            assertEquals(1,number(f.progress(),"input_revision"));assertEquals(authority,f.authority());
            assertEquals(Math.min(turn*500,1001),f.minutes().size());
        }
        assertNull(f.runtime().get("source_dirty_from"));assertNull(f.runtime().get("source_dirty_to"));
        Map<String,Object> receipt=f.progress();List<Map<String,Object>> rows=f.minutes();f.db.sql.clear();
        assertFalse(f.project());assertEquals(receipt,f.progress());assertEquals(rows,f.minutes());assertEquals(authority,f.authority());
        assertFalse(f.db.sql.stream().anyMatch(sql->sql.startsWith("INSERT INTO s4_history_projection_")||sql.startsWith("UPDATE s4_history_projection_")||sql.startsWith("UPDATE market_engine_runtime")));
    }
    @Test void identicalActualSourceDeliveryRetryDoesNotInventInputRevisionOrDirtyWork(){
        Fixture f=new Fixture(2);assertTrue(f.project());Map<String,Object> before=new LinkedHashMap<>(f.runtime());before.keySet().removeIf(key->key.equalsIgnoreCase("lease_until"));
        Map<String,Object> receipt=f.progress();List<Map<String,Object>> rows=f.minutes();
        f.store.sourceCandles(1,"1m",Arrays.asList(bar(M,"90.1234"),bar(M+60000,"90.1234")),f.inputReceived);
        Map<String,Object> after=new LinkedHashMap<>(f.runtime());after.keySet().removeIf(key->key.equalsIgnoreCase("lease_until"));
        assertEquals(before,after);assertEquals(1,number(f.runtime(),"source_input_revision"));
        assertNull(f.runtime().get("source_dirty_from"));assertEquals(receipt,f.progress());assertEquals(rows,f.minutes());assertFalse(f.project());
    }
    @Test void newLateRevisionBetweenPagesResetsEarliestDirtyCursorAndSharesItsInputRevisionAcrossRemainingPages(){
        Fixture f=new Fixture(1001);assertTrue(f.project());long watermark=number(f.progress(),"watermark");
        f.revise(M+10*60000,"96.4321");assertEquals(2,number(f.runtime(),"source_input_revision"));
        assertEquals(M+10*60000,number(f.runtime(),"source_dirty_from"));
        assertTrue(f.project());assertEquals(watermark,number(f.progress(),"watermark"));assertEquals(2,number(f.progress(),"fact_version"));
        assertEquals(2,number(f.progress(),"input_revision"));assertEquals(M+500*60000,number(f.runtime(),"source_dirty_from"));
        assertEquals(Collections.singletonList(bar(M+10*60000,"96.4321")),f.derived.readSourceWindow(1,M+10*60000,M+10*60000).minutes);
        assertTrue(f.project());assertEquals(3,number(f.progress(),"fact_version"));assertEquals(2,number(f.progress(),"input_revision"));
        assertTrue(f.project());assertEquals(4,number(f.progress(),"fact_version"));assertEquals(2,number(f.progress(),"input_revision"));
        assertEquals(1001,f.minutes().size());assertNull(f.runtime().get("source_dirty_from"));assertFalse(f.project());
        assertFalse(f.derived.readSourceWindow(1,M+10*60000,M+10*60000).pending);
    }
    @Test void failedActualSourceInputReceiptRollsBackRawBodyRevisionAndCursorBeforeRetry(){
        Fixture f=new Fixture(2);assertTrue(f.project());Map<String,Object> runtime=f.runtime(),progress=f.progress();List<Map<String,Object>> derived=f.minutes();
        List<Map<String,Object>> raw=f.db.queryForList("SELECT * FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 ORDER BY period,candle_at");
        f.db.failInput=true;assertEquals("after source input revision",assertThrows(IllegalStateException.class,()->f.revise(M,"96.4321")).getMessage());f.db.failInput=false;
        assertEquals(runtime,f.runtime());assertEquals(progress,f.progress());assertEquals(derived,f.minutes());
        assertEquals(raw,f.db.queryForList("SELECT * FROM market_source_candle WHERE tenant_id=1 AND symbol_id=1 ORDER BY period,candle_at"));
        f.revise(M,"96.4321");assertEquals(2,number(f.runtime(),"source_input_revision"));assertTrue(f.project());
        assertEquals(Collections.singletonList(bar(M,"96.4321")),f.derived.readSourceWindow(1,M,M).minutes);
    }
    @Test void genuineLateCompletedSourceRevisionRefreshesAnAlreadyWatermarkedMinuteWithoutRewinding(){
        Fixture f=new Fixture(2);assertTrue(f.project());long watermark=number(f.progress(),"watermark");
        f.revise(M,"96.4321");assertEquals(2,number(f.runtime(),"source_input_revision"));
        MinuteHistoryProjectionStore.ReadResult stale=f.derived.readSourceWindow(1,M,M);assertTrue(stale.pending);assertEquals("partial",stale.status);
        Map<String,Object> authority=f.authority();assertTrue(f.project());assertEquals(authority,f.authority());
        assertEquals(watermark,number(f.progress(),"watermark"));assertEquals(2,number(f.progress(),"fact_version"));assertEquals(2,number(f.progress(),"input_revision"));
        MinuteHistoryProjectionStore.ReadResult updated=f.derived.readSourceWindow(1,M,M);assertFalse(updated.pending);assertEquals("available",updated.status);
        assertEquals(Collections.singletonList(bar(M,"96.4321")),updated.minutes);
        assertNull(f.runtime().get("source_dirty_from"));assertFalse(f.project());
    }
    @Test void finalDirtyReceiptFailureRollsBackBodyHashProgressAndCursorTogether(){
        Fixture f=new Fixture(2);assertTrue(f.project());f.revise(M,"96.4321");
        Map<String,Object> runtime=f.runtime(),progress=f.progress();List<Map<String,Object>> rows=f.minutes();
        f.db.failDirty=true;assertEquals("after source dirty receipt",assertThrows(IllegalStateException.class,f::project).getMessage());f.db.failDirty=false;
        assertEquals(runtime,f.runtime());assertEquals(progress,f.progress());assertEquals(rows,f.minutes());
        assertTrue(f.project());assertFalse(f.derived.readSourceWindow(1,M,M).pending);
    }
    @Test void unpublishedSourceTailDoesNotHideCommittedCleanPrefixOrWriteOnRead(){
        Fixture f=new Fixture(2);assertTrue(f.project());f.revise(M+120000,"96.4321");
        Map<String,Object> authority=f.runtime(),progress=f.progress();List<Map<String,Object>> rows=f.minutes();f.db.sql.clear();
        MinuteHistoryProjectionStore.ReadResult page=f.derived.readSourceWindow(1,M,M+120000);
        assertTrue(page.pending);assertFalse(page.sourceBlocked);
        assertEquals(Arrays.asList(bar(M,"90.1234"),bar(M+60000,"90.1234")),page.minutes);
        assertEquals(authority,f.runtime());assertEquals(progress,f.progress());assertEquals(rows,f.minutes());assertTrue(f.db.sql.isEmpty());
        f.revise(M,"96.4321");assertTrue(f.derived.readSourceWindow(1,M,M+120000).sourceBlocked);
    }
    @Test void genericCommittedPublicationContractDoesNotInheritSourceDirtyEligibility(){
        Fixture f=new Fixture(2);assertTrue(f.project());List<Map<String,Object>> published=f.derived.readWindow(1,M,M).minutes;
        f.revise(M,"96.4321");f.store.locked(1,()->{f.store.manualPoint(1,M,new BigDecimal("105.4321"));return null;});
        assertNotNull(f.runtime().get("source_dirty_from"));assertFalse(f.derived.readWindow(1,M,M).pending);
        assertEquals(published,f.derived.readWindow(1,M,M).minutes);
        assertTrue(f.derived.readSourceWindow(1,M,M).pending);assertTrue(f.derived.readSourceWindow(1,M,M).minutes.isEmpty());
    }
    @Test void sourceReaderRefusesCurrentGenerationOrEligibilityChangesWithoutMutatingAuthority(){
        Fixture f=new Fixture(2);assertTrue(f.project());assertFalse(f.derived.readSourceWindow(1,M,M).pending);
        f.db.update("UPDATE market_engine_runtime SET writer_generation=writer_generation+1 WHERE tenant_id=1 AND symbol_id=1");
        Map<String,Object> runtime=f.runtime(),progress=f.progress();List<Map<String,Object>> rows=f.minutes();f.db.sql.clear();
        assertTrue(f.derived.readSourceWindow(1,M,M).pending);assertTrue(f.derived.readSourceWindow(1,M,M).minutes.isEmpty());
        assertEquals(runtime,f.runtime());assertEquals(progress,f.progress());assertEquals(rows,f.minutes());assertTrue(f.db.sql.isEmpty());
        Fixture random=new Fixture(2);assertTrue(random.project());random.db.update("UPDATE trading_symbol SET random_market_enabled=TRUE WHERE tenant_id=1 AND id=1");
        assertTrue(random.derived.readSourceWindow(1,M,M).pending);assertTrue(random.derived.readSourceWindow(1,M,M).minutes.isEmpty());
        Fixture mixed=new Fixture(2);assertTrue(mixed.project());mixed.store.locked(1,()->{mixed.store.manualPoint(1,M,new BigDecimal("105.4321"));return null;});
        assertTrue(mixed.derived.readSourceWindow(1,M,M).pending);assertTrue(mixed.derived.readSourceWindow(1,M,M).minutes.isEmpty());
    }
    @Test void protectedMixedAndBeforeInitialCoverageAreNotSilentlyRewrittenOrAcknowledged(){
        Fixture mixed=new Fixture(2);assertTrue(mixed.project());mixed.revise(M,"96.4321");
        mixed.store.locked(1,()->{mixed.store.manualPoint(1,M,new BigDecimal("105.4321"));return null;});
        Map<String,Object> runtime=mixed.runtime(),receipt=mixed.progress();List<Map<String,Object>> rows=mixed.minutes();
        List<Map<String,Object>> originalMixed=mixed.db.queryForList("SELECT * FROM market_mixed_minute ORDER BY tenant_id,symbol_id,minute_at");
        assertFalse(mixed.project());assertEquals(runtime,mixed.runtime());assertEquals(receipt,mixed.progress());assertEquals(rows,mixed.minutes());
        assertEquals(originalMixed,mixed.db.queryForList("SELECT * FROM market_mixed_minute ORDER BY tenant_id,symbol_id,minute_at"));
        Fixture earlier=new Fixture(2);assertTrue(earlier.project());earlier.revise(M-60000,"96.4321");
        Map<String,Object> before=earlier.runtime(),old=earlier.progress();List<Map<String,Object>> oldRows=earlier.minutes();
        assertTrue(assertThrows(IllegalStateException.class,earlier::project).getMessage().startsWith("SOURCE_BEFORE_INITIAL_PENDING"));
        assertEquals(before,earlier.runtime());assertEquals(old,earlier.progress());assertEquals(oldRows,earlier.minutes());
    }
}
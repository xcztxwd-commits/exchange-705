package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual producer/runtime/funding authority transactions. H2 is not a formal MySQL/funds-restore claim. */
class FundingConversionsJointTest extends TenantMarketTestContext {
    JdbcTemplate db;ControlHistoryStore store;PersistentPriceControl controls;ForexQuoteMarketService market;
    FundingQuoteAuthority authority;TransactionTemplate tx;TradingSymbolRepository repository;
    final List<TradingSymbol> catalog=new ArrayList<>();
    TradingSymbol cross;
    @BeforeEach void setup() {
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:fx_companion_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");
        db=new JdbcTemplate(source);DataSourceTransactionManager manager=new DataSourceTransactionManager(source);
        tx=new TransactionTemplate(manager);store=new ControlHistoryStore(db,manager);MarketSqlFixture.schema(db);
        for(String column:Arrays.asList("symbol VARCHAR(32)","row_version BIGINT DEFAULT 1","is_enabled BOOLEAN DEFAULT TRUE",
                "price_precision INT DEFAULT 4","market_source VARCHAR(32)","source_category VARCHAR(32)","quote_currency VARCHAR(16)",
                "base_currency VARCHAR(16)","random_market_enabled BOOLEAN DEFAULT FALSE","random_market_started_at BIGINT"))
            db.execute("ALTER TABLE trading_symbol ADD "+column);
        db.execute("CREATE TABLE funding_checkpoint(id INT PRIMARY KEY,amount DECIMAL(32,16))");
        controls=new PersistentPriceControl(store);authority=new FundingQuoteAuthority(store);market=new ForexQuoteMarketService();
        repository=mock(TradingSymbolRepository.class);
        when(repository.findAllByTenantId(1L)).thenAnswer(i -> new ArrayList<>(catalog));
        when(repository.findByTenantIdAndId(eq(1L),anyLong())).thenAnswer(i -> catalog.stream().filter(s -> s.getId().equals(i.getArgument(1))).findFirst());
        ReflectionTestUtils.setField(market,"symbols",repository);ReflectionTestUtils.setField(market,"controls",controls);
        ReflectionTestUtils.setField(market,"controlHistory",store);ReflectionTestUtils.setField(market,"redis",mock(RedisMarketService.class));
        cross=add(1,"FXCOMPANION","EUR","JPY","yahoo","Forex");market.refreshSymbols();
    }
    @AfterEach void close() throws Exception {if(market!=null)market.stop();if(db!=null)try(java.sql.Connection c=db.getDataSource().getConnection();java.sql.Statement s=c.createStatement()){s.execute("SHUTDOWN");}}
    TradingSymbol add(long id,String symbol,String base,String quote,String source,String category) {
        TradingSymbol config=new TradingSymbol();config.setTenantId(1L);config.setId(id);config.setSymbol(symbol);
        config.setBaseCurrency(base);config.setQuoteCurrency(quote);config.setMarketSource(source);config.setSourceCategory(category);
        config.setCategory(category);config.setPricePrecision(4);config.setRowVersion(1);config.setIsEnabled(true);
        config.setRandomMarketEnabled(false);catalog.add(config);
        db.update("INSERT INTO trading_symbol(id,tenant_id,symbol,market_source,source_category,quote_currency,base_currency) VALUES(?,1,?,?,?,?,?)",id,symbol,source,category,quote,base);
        return config;
    }
    Map<String,Object> raw(long at,String value) {
        Map<String,Object> raw=new LinkedHashMap<>();raw.put("timestamp",at);raw.put("price",new BigDecimal(value));raw.put("eventId",UUID.randomUUID().toString());return raw;
    }
    void ingress(String code,String category,long at,String value) {assertTrue(market.acceptQuote(code,category,raw(at,value),"http",System.currentTimeMillis()));}
    void primary(TradingSymbol config,long at) {ingress(config.getSymbol(),config.getSourceCategory(),at,"145.25");}
    Map<String,Object> prepared(TradingSymbol config,boolean margin) {
        Map<String,Object> quote=new LinkedHashMap<>(authority.prepare(config.getSymbol()));
        quote.putAll(authority.conversion(quote,config.getQuoteCurrency(),config.getMarketSource()));
        if(margin) {
            Map<String,Object> base=authority.conversion(quote,config.getBaseCurrency(),"yahoo");
            quote.put("marginConversionRequired",true);quote.put("marginConversionCurrency",config.getBaseCurrency());
            quote.put("marginBaseToUsdRate",base.get("quoteToUsdRate"));quote.put("marginRateAvailable",base.get("conversionAvailable"));
            quote.put("marginRateExpiresAt",base.get("conversionExpiresAt"));quote.put("marginConversionReceipt",base.get("conversionReceipt"));
        }
        return quote;
    }
    void accept(Map<String,Object> quote,int checkpoint) {
        tx.execute(s -> {authority.validate(Collections.singletonList(quote));db.update("INSERT INTO funding_checkpoint VALUES(?,1)",checkpoint);return null;});
    }
    void reject(Map<String,Object> quote) {
        assertThrows(BusinessException.class,() -> tx.execute(s -> {authority.validate(Collections.singletonList(quote));db.update("INSERT INTO funding_checkpoint VALUES(99,1)");return null;}));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM funding_checkpoint WHERE id=99",Integer.class));
    }
    @SuppressWarnings("unchecked") Map<String,Map<String,Object>> cache() {
        Object state=ReflectionTestUtils.invokeMethod(market,"state");Object group=((Map<?,?>)ReflectionTestUtils.getField(state,"groups")).get("Forex");
        return (Map<String,Map<String,Object>>)ReflectionTestUtils.getField(group,"quotes");
    }
    Object forexGroup() {Object state=ReflectionTestUtils.invokeMethod(market,"state");return ((Map<?,?>)ReflectionTestUtils.getField(state,"groups")).get("Forex");}
    @SuppressWarnings("unchecked") Map<String,Object> receipt(Map<String,Object> prepared) {return (Map<String,Object>)prepared.get("conversionReceipt");}

    @Test void realProducerPersistsInverseDirectMinorUnitAndCryptoCompanionsWithoutChangingSourcePrices() {
        long now=System.currentTimeMillis();
        // FX arrives before the instrument. It must not manufacture an instrument execution quote.
        ingress("JPY=X","Forex",now,"150");
        assertThrows(BusinessException.class,() -> authority.prepare(cross.getSymbol()));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM market_source_event",Integer.class));
        primary(cross,now);ingress("EURUSD=X","Forex",now,"1.10");
        Map<String,Object> quote=prepared(cross,true);
        assertEquals(0,BigDecimal.ONE.divide(new BigDecimal("150"),24,RoundingMode.HALF_UP).compareTo(new BigDecimal(quote.get("quoteToUsdRate").toString())));
        assertEquals(0,new BigDecimal("1.10").compareTo(new BigDecimal(quote.get("marginBaseToUsdRate").toString())));
        assertEquals("Yahoo",receipt(quote).get("provider"));assertEquals("JPY=X",receipt(quote).get("code"));
        assertEquals(Boolean.TRUE,receipt(quote).get("inverse"));assertNotNull(receipt(quote).get("eventId"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM market_source_event",Integer.class));
        assertEquals(0,new BigDecimal("145.25").compareTo(db.queryForObject("SELECT price FROM market_source_quote WHERE tenant_id=1 AND symbol_id=1",BigDecimal.class)));
        Map<String,Object> runtime=db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1");
        cache().clear(); // Fresh authority object uses DB, never live/Redis memory.
        authority=new FundingQuoteAuthority(store);accept(prepared(cross,true),1);
        assertEquals(runtime,db.queryForMap("SELECT * FROM market_engine_runtime WHERE tenant_id=1 AND symbol_id=1"));

        TradingSymbol minor=add(2,"MINORCOMPANION","STOCK","GBp","yahoo","US");
        TradingSymbol crypto=add(3,"CRYPTOCOMPANION","ETH","BTC","binance","Crypto");market.refreshSymbols();
        primary(minor,System.currentTimeMillis());primary(crypto,System.currentTimeMillis());
        ingress("GBPUSD=X","Forex",now,"1.25");ingress("BTCUSDT","Crypto",now,"65000");
        assertEquals(0,new BigDecimal("0.0125").compareTo(new BigDecimal(prepared(minor,false).get("quoteToUsdRate").toString())));
        assertEquals(0,new BigDecimal("65000").compareTo(new BigDecimal(prepared(crypto,false).get("quoteToUsdRate").toString())));
        accept(prepared(minor,false),2);accept(prepared(crypto,false),3);
        Map<String,Object> foreign=new LinkedHashMap<>(quote);foreign.put("tenantId",2L);reject(foreign);
    }

    @Test void duplicateAndLateFramesCannotRenewInstrumentOrFxExpiryAndStopCannotBeReauthorized() {
        long now=System.currentTimeMillis();primary(cross,now);ingress("JPY=X","Forex",now-2000,"150");ingress("EURUSD=X","Forex",now,"1.1");
        Map<String,Object> original=prepared(cross,true);String body=db.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE symbol_id=1",String.class);
        ingress("JPY=X","Forex",now-2000,"150");
        assertEquals(body,db.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE symbol_id=1",String.class));
        assertEquals(original.get("conversionExpiresAt"),prepared(cross,true).get("conversionExpiresAt"));
        cache().clear();
        assertFalse(market.acceptQuote("JPY=X","Forex",raw(now-3000,"149"),"http",System.currentTimeMillis()));
        assertEquals(body,db.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE symbol_id=1",String.class));
        ingress("JPY=X","Forex",now-1000,"151");Map<String,Object> changed=prepared(cross,true);
        for(String key:Arrays.asList("price","timestamp","executionSampledAt","executionExpiresAt")) assertEquals(original.get(key),changed.get(key));
        reject(original);accept(changed,1);
        store.locked(1,() -> {store.runtime.invalidate(1);return null;});
        ingress("JPY=X","Forex",now,"152");Map<String,Object> stopped=authority.prepare(cross.getSymbol());
        assertEquals(Boolean.FALSE,stopped.get("tradeAvailable"));assertEquals(0L,QuoteState.time(stopped.get("executionExpiresAt")));
        reject(stopped);
        assertEquals(original.get("price"),stopped.get("price"));assertEquals(original.get("timestamp"),stopped.get("timestamp"));
    }

    @Test void producerRollbackAndOutageDoNotPublishAndExpiredCompanionRollsBackFundingAtCommit() throws Exception {
        long now=System.currentTimeMillis();primary(cross,now);ingress("JPY=X","Forex",now-3000,"150");ingress("EURUSD=X","Forex",now,"1.1");
        Map<String,Object> before=db.queryForMap("SELECT * FROM market_engine_runtime WHERE symbol_id=1");Map<String,Object> quote=prepared(cross,true);
        assertThrows(IllegalStateException.class,() -> tx.execute(s -> {ingress("JPY=X","Forex",now-2000,"151");throw new IllegalStateException("after-companion-before-commit");}));
        assertEquals(before,db.queryForMap("SELECT * FROM market_engine_runtime WHERE symbol_id=1"));accept(quote,1);
        assertEquals(0,new BigDecimal("150").compareTo(new BigDecimal(cache().get("JPY=X").get("price").toString())));
        ReflectionTestUtils.invokeMethod(market,"unavailable",forexGroup(),"JPY=X");reject(quote);
        assertThrows(BusinessException.class,() -> prepared(cross,true));
        ingress("JPY=X","Forex",now-3000,"150");assertEquals(quote.get("conversionExpiresAt"),prepared(cross,true).get("conversionExpiresAt"));

        // Independent legitimate new symbol; no raw SQL alteration of a receipt to fabricate expiry.
        TradingSymbol soon=add(2,"EXPIRINGCOMPANION","STOCK","GBp","yahoo","US");market.refreshSymbols();
        long expiringNow=System.currentTimeMillis();primary(soon,expiringNow);ingress("GBPUSD=X","Forex",expiringNow-58500,"1.25");
        Map<String,Object> expiring=prepared(soon,false);long expiry=QuoteState.time(expiring.get("conversionExpiresAt"));
        assertThrows(BusinessException.class,() -> tx.execute(s -> {
            authority.validate(Collections.singletonList(expiring));db.update("INSERT INTO funding_checkpoint VALUES(99,2)");
            while(System.currentTimeMillis()<=expiry) try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            return null;
        }));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM funding_checkpoint WHERE id=99",Integer.class));
        assertThrows(BusinessException.class,() -> prepared(soon,false));
    }

    @Test void fundingRuntimeLockSerializesConcurrentFxChangeAndNewWriterDoesNotAuthorizeOldQuote() throws Exception {
        long now=System.currentTimeMillis();primary(cross,now);ingress("JPY=X","Forex",now,"150");ingress("EURUSD=X","Forex",now,"1.1");
        Map<String,Object> quote=prepared(cross,true);
        ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1),entered=new CountDownLatch(1);
        try {
            Future<?> funding=workers.submit(() -> {try(TenantContext.Scope ignored=TenantContext.open(1L)) {tx.execute(s -> {
                authority.validate(Collections.singletonList(quote));db.update("INSERT INTO funding_checkpoint VALUES(1,1)");held.countDown();
                try{assertTrue(release.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;
            });}});
            assertTrue(held.await(5,TimeUnit.SECONDS));
            Future<?> ingress=workers.submit(() -> {try(TenantContext.Scope ignored=TenantContext.open(1L)){entered.countDown();ingress("JPY=X","Forex",now+1,"151");}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,() -> ingress.get(150,TimeUnit.MILLISECONDS));
            release.countDown();funding.get(5,TimeUnit.SECONDS);ingress.get(5,TimeUnit.SECONDS);reject(quote);
            String body=db.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE symbol_id=1",String.class);
            // Expire only the test-owned lease, then exercise a genuine different runtime owner/generation.
            db.update("UPDATE market_engine_runtime SET lease_until=0 WHERE tenant_id=1 AND symbol_id=1");
            ControlHistoryStore successor=new ControlHistoryStore(db,new DataSourceTransactionManager(db.getDataSource()));
            controls=new PersistentPriceControl(successor);ReflectionTestUtils.setField(market,"controls",controls);
            ingress("JPY=X","Forex",now+2,"152");
            Map<String,Object> after=authority.prepare(cross.getSymbol());assertEquals(Boolean.FALSE,after.get("tradeAvailable"));reject(after);
            assertEquals(store.decode(body).get("price"),after.get("price"));
        } finally {release.countDown();workers.shutdownNow();}
    }
}

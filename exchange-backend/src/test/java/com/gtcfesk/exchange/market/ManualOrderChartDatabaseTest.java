package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.trade.ManualOrderPrices;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real isolated H2 history reader and transaction proxy; no network or order/fund writes. */
class ManualOrderChartDatabaseTest extends TenantMarketTestContext {
    @Test void storedMinuteCandlesRenderWithoutWaitingForProviderAndStayTenantScoped() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:manual_chart_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        JdbcTemplate db=new JdbcTemplate(ds);MarketSqlFixture.schema(db);
        DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);
        ControlHistoryStore store=new ControlHistoryStore(db,manager);
        db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1),(2,2)");
        long end=Math.floorDiv(System.currentTimeMillis(),60000)*60000;
        for(long owner:new long[]{1,2})for(int i=1;i<=240;i++){
            Map<String,Object> row=new LinkedHashMap<>();row.put("timestamp",end-i*60000L);
            for(String key:Arrays.asList("open_price","low_price","high_price","close_price"))row.put(key,owner==1?"157.5751234567890123":"999");
            db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(?,?,'1m',?,?,?)",owner,owner,end-i*60000L,store.encode(row),end-(i-1)*60000L);
        }
        List<Map<String,Object>> before=db.queryForList("SELECT * FROM market_source_candle ORDER BY tenant_id,candle_at");
        ForexQuoteMarketService market=spy(new ForexQuoteMarketService());MarketQuoteSource provider=mock(MarketQuoteSource.class);
        ReflectionTestUtils.setField(market,"source",provider);ReflectionTestUtils.setField(market,"controlHistory",store);ReflectionTestUtils.setField(market,"klineMerger",new ControlledKlineMerger(store));
        TradingSymbol symbol=new TradingSymbol();symbol.setId(1L);symbol.setSymbol("JPY=X");symbol.setSourceCategory("Forex");symbol.setMarketSource("yahoo");
        ReflectionTestUtils.setField(marketState(market),"registry",Collections.singletonMap("JPY=X",symbol));
        ProxyFactory factory=new ProxyFactory(new ManualOrderPrices(market));factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        ManualOrderPrices prices=(ManualOrderPrices)factory.getProxy();
        doAnswer(call->{assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertEquals(java.sql.Connection.TRANSACTION_REPEATABLE_READ,TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());return call.callRealMethod();}).when(market).historicalKline(anyString(),anyString(),anyInt(),anyLong());
        try {
            Map<String,Object> page=prices.chart(symbol,"UTC",end-1,200);List<Map<String,Object>> candles=(List<Map<String,Object>>)page.get("candles");
            assertEquals(200,candles.size());assertEquals("available",page.get("status"));assertFalse((Boolean)page.get("pending"));
            for(Map<String,Object> candle:candles)assertEquals("157.5751234567890123",candle.get("price"));
            assertEquals(end-200*60000L-1,page.get("nextEndTime"));verify(market,times(1)).historicalKline("JPY=X","1m",200,end-1);
            assertEquals(before,db.queryForList("SELECT * FROM market_source_candle ORDER BY tenant_id,candle_at"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM market_control_task",Integer.class));verifyNoInteractions(provider);
        } finally {market.stop();}
    }
}

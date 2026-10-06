package com.gtcfesk.exchange.market;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.trade.*;
import com.gtcfesk.exchange.user.ManualOrderHistory;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual generator, preview, H2 and canonical history; isolated local fixture, no provider or financial writes. */
class SimpleManualOrderGenerationDatabaseTest extends TenantMarketTestContext {
    static BigDecimal n(String value){return new BigDecimal(value);}
    @Test void pinnedGenerationAndItsAuthoritativePreviewReadOnlyTheSelectedMinutes() {
        DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:simple_speed_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE","sa","");JdbcTemplate db=new JdbcTemplate(ds);MarketSqlFixture.schema(db);
        for(String column:Arrays.asList("row_version BIGINT DEFAULT 0","max_leverage DECIMAL(10,2) DEFAULT 100","category VARCHAR(16) DEFAULT 'US'","lot_size DECIMAL(32,16) DEFAULT 1","fee_multiplier DECIMAL(32,16) DEFAULT 0.01","quote_currency VARCHAR(16) DEFAULT 'USD'","market_source VARCHAR(16) DEFAULT 'yahoo'","base_currency VARCHAR(16) DEFAULT 'TEST'","source_category VARCHAR(16) DEFAULT 'US'","quantity_unit_type VARCHAR(16) DEFAULT 'LOT'","spec_version BIGINT DEFAULT 1","min_order_quantity DECIMAL(32,16) DEFAULT 0.01","quantity_step DECIMAL(32,16) DEFAULT 0.01","min_order_notional DECIMAL(32,16) DEFAULT 0"))db.execute("ALTER TABLE trading_symbol ADD "+column);
        db.execute("CREATE TABLE asset_account(id BIGINT,tenant_id BIGINT,user_id BIGINT,coin VARCHAR(16),available DECIMAL(32,16),frozen DECIMAL(32,16),row_version BIGINT)");db.update("INSERT INTO asset_account VALUES(1,1,1,'CONTRACT',1000000,0,0)");db.update("INSERT INTO trading_symbol(id,tenant_id) VALUES(1,1),(2,2)");
        DataSourceTransactionManager manager=new DataSourceTransactionManager(ds);ControlHistoryStore store=new ControlHistoryStore(db,manager);long close=Math.floorDiv(System.currentTimeMillis(),60000)*60000-60000,open=close-21*60000;
        for(long tenant:new long[]{1,2})for(long time:new long[]{open,close}){
            Map<String,Object> row=new LinkedHashMap<>();row.put("timestamp",time);row.put("open_price",tenant==2?"999":time==open?"99.5":"100");row.put("low_price",tenant==2?"999":"99");row.put("high_price",tenant==2?"999":"101");row.put("close_price",row.get("open_price"));
            db.update("INSERT INTO market_source_candle(tenant_id,symbol_id,period,candle_at,body,received_at) VALUES(?,?,'1m',?,?,?)",tenant,tenant,time,store.encode(row),time+60000);
        }
        TradingSymbol symbol=new TradingSymbol();symbol.setId(1L);symbol.setTenantId(1L);symbol.setSymbol("FIXTUREUSD");symbol.setRowVersion(0);symbol.setIsEnabled(true);symbol.setCategory("US");symbol.setSourceCategory("US");symbol.setBaseCurrency("TEST");symbol.setQuoteCurrency("USD");symbol.setMarketSource("yahoo");symbol.setLotSize(n("1"));symbol.setFeeMultiplier(n("0.01"));symbol.setMaxLeverage(n("100"));symbol.setQuantityUnitType("LOT");symbol.setSpecVersion(1L);symbol.setQuantityStep(n("0.01"));symbol.setMinOrderQuantity(n("0.01"));symbol.setMinOrderNotional(BigDecimal.ZERO);
        ForexQuoteMarketService market=spy(new ForexQuoteMarketService());MarketQuoteSource provider=mock(MarketQuoteSource.class);ReflectionTestUtils.setField(market,"source",provider);ReflectionTestUtils.setField(market,"controlHistory",store);ReflectionTestUtils.setField(market,"klineMerger",new ControlledKlineMerger(store));ReflectionTestUtils.setField(marketState(market),"registry",Collections.singletonMap("FIXTUREUSD",symbol));
        ProxyFactory proxy=new ProxyFactory(new ManualOrderPrices(market));proxy.setProxyTargetClass(true);proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        TradingSymbolRepository symbols=mock(TradingSymbolRepository.class);when(symbols.findByTenantIdAndSymbol(1L,"FIXTUREUSD")).thenReturn(Optional.of(symbol));MarketCategoryService categories=mock(MarketCategoryService.class);when(categories.leverageEnabled("US")).thenReturn(true);ManualOrderHistory history=mock(ManualOrderHistory.class);
        ManualOrderService service=new ManualOrderService(ds,new ObjectMapper(),symbols,(ManualOrderPrices)proxy.getProxy(),null,history,categories);ReflectionTestUtils.setField(service,"enabled",true);
        List<Map<String,Object>> money=db.queryForList("SELECT * FROM asset_account"),source=db.queryForList("SELECT * FROM market_source_candle ORDER BY tenant_id,candle_at");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1",null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.symbol="FIXTUREUSD";r.timezone="UTC";r.specVersion=1L;r.quantityUnitType="LOT";r.userId=1L;r.openTime=open;r.closeTime=close;r.openPrice=n("99.5");r.closePrice=n("100");r.targetNet=n("55222");
        try {
            long start=System.nanoTime();Map<String,Object> out=assertTimeout(java.time.Duration.ofSeconds(2),()->service.generateSimple(r));double elapsed=(System.nanoTime()-start)/1000000.0;
            Map<?,?> calculation=(Map<?,?>)out.get("calculation");assertEquals("112697.96",calculation.get("quantity"));assertEquals(0,n("55222.0004").compareTo(n(calculation.get("net").toString())));assertNotNull(out.get("previewToken"));assertEquals("55222",((Map<?,?>)((Map<?,?>)out.get("generation")).get("originalConditions")).get("targetNet"));
            verify(market,times(2)).historicalKline("FIXTUREUSD","1m",1,open+59999);verify(market,times(2)).historicalKline("FIXTUREUSD","1m",1,close+59999);verify(market,times(4)).historicalKline(anyString(),anyString(),anyInt(),anyLong());
            assertEquals(money,db.queryForList("SELECT * FROM asset_account"));assertEquals(source,db.queryForList("SELECT * FROM market_source_candle ORDER BY tenant_id,candle_at"));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM market_control_task",Integer.class));verifyNoInteractions(provider,history);
            clearInvocations(market);r.closePrice=r.openPrice;BusinessException invalid=assertThrows(BusinessException.class,()->service.generateSimple(r));assertTrue(invalid.getMessage().contains("增加手数不能解决"));verify(market,never()).historicalKline(anyString(),anyString(),anyInt(),anyLong());
            clearInvocations(market);r.openTime=null;r.closeTime=null;r.openPrice=null;r.closePrice=null;long automaticStart=System.nanoTime();Map<String,Object> automatic=assertTimeout(java.time.Duration.ofSeconds(2),()->service.generateSimple(r));
            assertNotNull(automatic.get("previewToken"));BigDecimal actual=n(((Map<?,?>)automatic.get("calculation")).get("net").toString());assertTrue(SimpleManualOrderGenerator.matches(actual,r.targetNet,true,n("5")));
            verify(market,times(2)).historicalKline(eq("FIXTUREUSD"),eq("1m"),eq(720),anyLong());verify(market,times(2)).historicalKline(eq("FIXTUREUSD"),eq("1m"),eq(1),anyLong());verify(market,times(4)).historicalKline(anyString(),anyString(),anyInt(),anyLong());
            assertEquals(money,db.queryForList("SELECT * FROM asset_account"));assertEquals(source,db.queryForList("SELECT * FROM market_source_candle ORDER BY tenant_id,candle_at"));verifyNoInteractions(provider,history);
            System.out.println("SIMPLE_SPEED real-H2-recent-generator-preview "+(System.nanoTime()-automaticStart)/1000000.0+" ms; history-reads=4; full-seven-day-reads=0");
            System.out.println("SIMPLE_SPEED real-H2-generator-preview "+elapsed+" ms; exact-history-reads=4; provider-reads=0; financial-writes=0");
        } finally {SecurityContextHolder.clearContext();market.stop();}
    }
}

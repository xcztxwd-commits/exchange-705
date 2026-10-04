package com.gtcfesk.exchange.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.ManualOrderController;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.*;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.*;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual application services + real disposable MySQL; external market data is deterministic OHLC. */
class SimpleManualOrderMySqlIT {
    static DriverManagerDataSource source;
    static JdbcTemplate db;
    static ObjectMapper json=new ObjectMapper();
    TenantContext.Scope scope;
    ManualOrderService service;
    TradingSymbol symbol;
    TradingSymbolRepository repository;
    ForexQuoteMarketService market;
    AssetEquityStore store;
    long end,close,oldClose;
    static BigDecimal n(String value) {return new BigDecimal(value);}
    static void equal(String expected,Object value) {assertEquals(0,n(expected).compareTo(n(value.toString())));}
    static void auth(long operator,String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(operator),null,Collections.singletonList(new SimpleGrantedAuthority("ROLE_"+role))));
    }
    @BeforeAll static void schema() throws Exception {
        source=com.gtcfesk.exchange.tenant.DedicatedMysqlFixture.fromProperty("simple.mysql.fixture");db=new JdbcTemplate(source);
        assertTrue(db.queryForObject("select database()",String.class).startsWith("mt705_probe_"));
        assertEquals(2026100402L,db.queryForObject("select max(minimum_application_epoch) from tenant_schema_version",Long.class));
        assertEquals("stage2_money_a",db.queryForObject("select code from tenant where id=2",String.class));
        assertEquals(0L,db.queryForObject("select count(*) from user_account where tenant_id IN (2,3)",Long.class),"Use a fresh exclusive simple-suite clone; do not erase another suite's rows");
        System.out.println("Simple fixture MySQL "+db.queryForObject("select version()",String.class));
        // Run the shipped migration twice, including its real database guards and foreign keys.
        byte[] sql=new ClassPathResource("db/migration/V2026093001__simple_manual_orders.sql").getInputStream().readAllBytes();
        String script=new String(sql,StandardCharsets.UTF_8).replaceAll("(?m)^DELIMITER.*$","");
        try(Connection c=source.getConnection()) {
            for(int i=0;i<2;i++)ScriptUtils.executeSqlScript(c,new EncodedResource(new ByteArrayResource(script.getBytes(StandardCharsets.UTF_8)),StandardCharsets.UTF_8),false,false,"--","$$","/*","*/");
        }
    }
    @BeforeEach void setup() {
        scope=TenantContext.open(2L);auth(1,"SUPER_ADMIN");
        for(String table:Arrays.asList("manual_order_binding","manual_order_record","contract_order","asset_account","trading_symbol","asset_history_1m","asset_history_1h","asset_history_4h","asset_history_1d","asset_history_baseline","asset_history_job_state","asset_history_quote_batch","asset_history_revision","user_account"))db.update("delete from "+table+" where tenant_id IN (2,3)");
        db.update("insert into user_account(tenant_id,id,email,password_hash,row_version) values(2,1,'first@test.invalid','not-a-login',0),(2,2,'second@test.invalid','not-a-login',0),(3,3,'other-tenant@test.invalid','not-a-login',0)");
        db.update("insert into asset_account(tenant_id,user_id,coin,available,frozen,row_version) values(2,1,'CONTRACT',1000000,0,0),(2,2,'CONTRACT',1000000,0,0),(3,3,'CONTRACT',1000000,0,0)");
        db.update("insert into trading_symbol(tenant_id,id,symbol,base_currency,quote_currency,name,market_source,source_category,category,lot_size,fee_multiplier,max_leverage,control_enabled,quantity_step,min_order_quantity,min_order_notional,quantity_unit_type,spec_version) values(2,20001,'FIXTUREUSD','TEST','USD','local simulation fixture','yahoo','US','US',1,0.01,100,0,0.01,0.01,0,'LOT',1)");
        symbol=new TradingSymbol();symbol.setTenantId(2L);symbol.setId(20001L);symbol.setSymbol("FIXTUREUSD");symbol.setIsEnabled(true);symbol.setCategory("US");symbol.setSourceCategory("US");symbol.setBaseCurrency("TEST");symbol.setQuoteCurrency("USD");symbol.setMarketSource("yahoo");symbol.setLotSize(n("1"));symbol.setFeeMultiplier(n("0.01"));symbol.setMaxLeverage(n("100"));symbol.setQuantityUnitType("LOT");symbol.setSpecVersion(1L);symbol.setQuantityStep(n("0.01"));symbol.setMinOrderQuantity(n("0.01"));symbol.setMinOrderNotional(BigDecimal.ZERO);symbol.setRowVersion(0);
        repository=mock(TradingSymbolRepository.class);when(repository.findByTenantIdAndSymbol(2L,"FIXTUREUSD")).thenReturn(Optional.of(symbol));
        end=Math.floorDiv(System.currentTimeMillis(),60000)*60000;close=end-60000;oldClose=close-2*86400000;
        List<Map<String,Object>> rows=Arrays.asList(candle(close-60000,"99.5","99.2","99.7"),candle(close,"100","99.9","100.4"),candle(oldClose-60000,"119.4","119.04","119.64"),candle(oldClose,"120","119.9","120.4"),candle(end,"999","998","1000"),candle(end-7*86400000-60000,"130","129.9","130.4"));
        market=mock(ForexQuoteMarketService.class);
        when(market.historicalKline(eq("FIXTUREUSD"),eq("1m"),anyInt(),anyLong())).thenAnswer(inv->{long last=inv.<Long>getArgument(3)-59999;List<Map<String,Object>> selected=new ArrayList<>();for(Map<String,Object> row:rows){long t=((Number)row.get("timestamp")).longValue();if(t<=last && t>last-720*60000L)selected.add(row);}return Collections.singletonMap("data",Collections.singletonMap("kline_list",selected));});
        db.update("insert into asset_history_quote_batch(tenant_id,batch_id,prepared_at,evidence) values(2,'fixture',?,'{}')",System.currentTimeMillis());
        store=new AssetEquityStore(source,json);service=newService(null);
        for(int level=1;level<=3;level++)store.progress(db,"rollup_"+level,end,0,System.currentTimeMillis());
    }
    static Map<String,Object> candle(long time,String open,String low,String high) {
        Map<String,Object> row=new LinkedHashMap<>();row.put("timestamp",time);row.put("open_price",open);row.put("close_price",open);row.put("low_price",low);row.put("high_price",high);return row;
    }
    ManualOrderService newService(String failure) {
        MarketCategoryService categories=mock(MarketCategoryService.class);when(categories.leverageEnabled(any())).thenReturn(true);
        ManualOrderService s=new ManualOrderService(source,json,repository,new ManualOrderPrices(market),null,new ManualOrderHistory(store),categories) {
            @Override protected void checkpoint(String stage) {if(stage.equals(failure))throw new IllegalStateException("injected "+stage);}
        };
        ReflectionTestUtils.setField(s,"enabled",true);return s;
    }
    @AfterEach void clear() {SecurityContextHolder.clearContext();scope.close();}
    SimpleManualOrderGenerator.Request simple() {
        SimpleManualOrderGenerator.Request r=new SimpleManualOrderGenerator.Request();r.symbol="FIXTUREUSD";r.timezone="UTC";r.quantityUnitType="LOT";r.specVersion=1L;return r;
    }
    ManualOrderService.Request confirmation(ManualOrderService s,SimpleManualOrderGenerator.Request r) {
        Map<String,Object> out=s.generateSimple(r);
        ManualOrderService.Request confirmed=json.convertValue(out.get("request"),ManualOrderService.Request.class);
        confirmed.previewToken=out.get("previewToken").toString();confirmed.idempotencyKey=UUID.randomUUID().toString();return confirmed;
    }
    long createUnbound(boolean historical) {
        SimpleManualOrderGenerator.Request r=simple();if(historical)r.closePrice=n("120");
        return ((Number)service.create(confirmation(service,r)).get("orderId")).longValue();
    }
    ManualOrderService.BindRequest binding(long order,long user,boolean wallet,boolean history,ManualOrderService s) {
        ManualOrderService.BindRequest r=new ManualOrderService.BindRequest();r.userId=user;r.walletEnabled=wallet;r.historyEnabled=history;
        r.previewToken=s.previewBinding(order,r).get("previewToken").toString();r.idempotencyKey=UUID.randomUUID().toString();return r;
    }
    long count(String table) {return db.queryForObject("select count(*) from "+table+" where tenant_id=2",Long.class);}
    BigDecimal wallet(long user) {return db.queryForObject("select available from asset_account where user_id=? and coin='CONTRACT'",BigDecimal.class,user);}
    BigDecimal storedNet(long order) {return db.queryForObject("select profit-fee from contract_order where id=?",BigDecimal.class,order);}
    void point(long t,String value) {db.update("insert into asset_history_1m(tenant_id,user_id,basis_version,bucket_start,observed_at,net_equity,valuation_status,reason_code,quote_batch_id,valuation_evidence,created_at) values(2,1,'net_equity_v1',?,?,?,'COMPLETE','OK','fixture','{}',?)",t,t+60000,n(value),t+60000);}
    BigDecimal historical(long t) {return db.queryForObject("select net_equity from asset_history_1m where tenant_id=2 and user_id=1 and bucket_start=?",BigDecimal.class,t);}

    @Test void selectedChartMinutesArePreservedBySimpleAndAdvancedConfirmation() {
        long selectedOpen=close-180000,selectedClose=close-120000;
        List<Map<String,Object>> rows=Arrays.asList(candle(selectedOpen,"99.5","99.2","99.7"),candle(selectedClose,"100","99.9","100.4"),candle(close-60000,"99.5","99.2","99.7"),candle(close,"100","99.9","100.4"));
        when(market.historicalKline(eq("FIXTUREUSD"),eq("1m"),anyInt(),anyLong())).thenReturn(Collections.singletonMap("data",Collections.singletonMap("kline_list",rows)));
        ManualOrderController controller=new ManualOrderController(service);Map<String,Object> chart=controller.chart("FIXTUREUSD","UTC");assertEquals(4,((List<?>)chart.get("candles")).size());assertEquals(0,count("contract_order"));
        SimpleManualOrderGenerator.Request r=simple();r.openTime=selectedOpen;r.closeTime=selectedClose;r.openPrice=n("99.5");r.closePrice=n("100");r.quantity=n("2");
        Map<String,Object> out=controller.simpleGenerate(r);assertEquals(Instant.ofEpochMilli(selectedOpen).toString(),out.get("openUtc"));assertEquals(Instant.ofEpochMilli(selectedClose).toString(),out.get("closeUtc"));
        ManualOrderService.Request request=json.convertValue(out.get("request"),ManualOrderService.Request.class);request.previewToken=out.get("previewToken").toString();request.idempotencyKey=UUID.randomUUID().toString();long id=((Number)controller.simpleCreate(request).get("orderId")).longValue();assertNull(db.queryForMap("select user_id from contract_order where id=?",id).get("user_id"));equal("99.5",db.queryForObject("select open_price from contract_order where id=?",BigDecimal.class,id));equal("100",db.queryForObject("select close_price from contract_order where id=?",BigDecimal.class,id));
        ManualOrderGenerator.Request advanced=new ManualOrderGenerator.Request();advanced.userId=1L;advanced.symbol="FIXTUREUSD";advanced.timezone="UTC";advanced.specVersion=1L;advanced.quantityUnitType="LOT";advanced.quantity=n("2");advanced.leverage=n("100");advanced.side="BUY";
        advanced.openLocal=Instant.ofEpochMilli(selectedOpen).atZone(ZoneOffset.UTC).toLocalDateTime().toString();advanced.closeLocal=Instant.ofEpochMilli(selectedClose).atZone(ZoneOffset.UTC).toLocalDateTime().toString();advanced.openOffset="Z";advanced.closeOffset="Z";
        out=controller.generate(advanced);assertEquals(Instant.ofEpochMilli(selectedOpen).toString(),out.get("openUtc"));assertEquals(Instant.ofEpochMilli(selectedClose).toString(),out.get("closeUtc"));
        request=json.convertValue(out.get("request"),ManualOrderService.Request.class);request.previewToken=out.get("previewToken").toString();request.idempotencyKey=UUID.randomUUID().toString();controller.create(request);assertEquals(2,count("contract_order"));equal("1000000",wallet(1));
    }
    @Test void invalidChartTimesCannotReadMarketOrCreateOrders() {
        SimpleManualOrderGenerator.Request r=simple();r.openTime=close;r.closeTime=close;assertThrows(BusinessException.class,()->service.generateSimple(r));
        r.openTime=close-60000;r.closeTime=end;assertThrows(BusinessException.class,()->service.generateSimple(r));r.closeTime=close;r.openTime=end-ManualOrderGenerator.RANGE-60000;assertThrows(BusinessException.class,()->service.generateSimple(r));
        r.openTime=close-60000+1;assertThrows(BusinessException.class,()->service.generateSimple(r));assertEquals(0,count("contract_order"));verifyNoInteractions(market);
    }
    @Test void symbolOnlyIsReadOnlyUntilConfirmationThenDurableUnboundReplay() throws Exception {
        Map<String,Object> out=service.generateSimple(simple());assertEquals(0,count("contract_order"));assertNull(out.get("walletBefore"));
        assertEquals(Instant.ofEpochMilli(close).toString(),out.get("closeUtc"));assertEquals(Instant.ofEpochMilli(close-60000).toString(),out.get("openUtc"));
        equal("100",((Map<?,?>)out.get("quotes")).get("closePrice"));equal("100",((Map<?,?>)out.get("request")).get("leverage"));equal("1",((Map<?,?>)out.get("calculation")).get("quantity"));
        ManualOrderService.Request r=json.readValue(json.writeValueAsString(out.get("request")),ManualOrderService.Request.class);r.previewToken=out.get("previewToken").toString();r.idempotencyKey=UUID.randomUUID().toString();
        long id=((Number)service.create(r).get("orderId")).longValue();equal(Long.toString(id),newService(null).create(r).get("orderId"));
        Map<String,Object> order=db.queryForMap("select * from contract_order where id=?",id);assertNull(order.get("user_id"));assertEquals("CLOSED",order.get("status"));assertEquals("MANUAL_TEST",order.get("order_source"));assertEquals(1,count("manual_order_record"));equal("1000000",wallet(1));assertEquals(0,count("asset_history_1m"));
        r.openPrice=n("99.6");assertThrows(BusinessException.class,()->service.create(r));
    }
    @Test void exactDecimalManualPricesQuantityOverSliderAndExplicitHistoricalClose() throws Exception {
        SimpleManualOrderGenerator.Request r=simple();r.openPrice=n("99.5432101234567890");r.closePrice=n("100.1234567890123456");r.quantity=n("150");r.side="BUY";
        Map<String,Object> out=service.generateSimple(r);assertEquals("99.5432101234567890",((Map<?,?>)out.get("quotes")).get("openPrice"));
        ManualOrderService.Request c=json.readValue(json.writeValueAsString(out.get("request")),ManualOrderService.Request.class);c.previewToken=out.get("previewToken").toString();c.idempotencyKey=UUID.randomUUID().toString();long id=((Number)service.create(c).get("orderId")).longValue();
        equal("99.5432101234567890",db.queryForObject("select open_price from contract_order where id=?",BigDecimal.class,id));equal("100.1234567890123456",db.queryForObject("select close_price from contract_order where id=?",BigDecimal.class,id));equal("150",db.queryForObject("select quantity from contract_order where id=?",BigDecimal.class,id));
        r=simple();r.closePrice=n("120");out=service.generateSimple(r);assertEquals(Instant.ofEpochMilli(oldClose).toString(),out.get("closeUtc"));
    }
    @Test void profitOnlyStrictAdjustmentAndOriginalTargetRemainSeparate() {
        SimpleManualOrderGenerator.Request r=simple();r.targetNet=n("5999");r.allowNetAdjustment=false;
        Map<String,Object> out=service.generateSimple(r);equal("5999",((Map<?,?>)out.get("calculation")).get("net"));assertTrue(n(((Map<?,?>)out.get("calculation")).get("quantity").toString()).compareTo(n("100"))>0);
        r=simple();r.targetNet=n("5999");r.openPrice=n("99.5");r.quantity=n("12224.49");out=service.generateSimple(r);
        assertEquals("5999",((Map<?,?>)((Map<?,?>)out.get("generation")).get("originalConditions")).get("targetNet"));assertTrue(SimpleManualOrderGenerator.matches(n(((Map<?,?>)out.get("calculation")).get("net").toString()),r.targetNet,true,r.netTolerance));assertEquals(0,count("contract_order"));
    }
    @ParameterizedTest @ValueSource(ints={0,1,2}) void selectedUserFundingSwitches(int mode) {
        SimpleManualOrderGenerator.Request r=simple();r.userId=1L;r.walletEnabled=mode>0;r.historyEnabled=mode==2;
        long id=((Number)service.create(confirmation(service,r)).get("orderId")).longValue();BigDecimal net=storedNet(id);
        equal((mode>0?n("1000000").add(net):n("1000000")).toPlainString(),wallet(1));assertEquals(mode==2?1:0,count("asset_history_1m"));equal("1000000",wallet(2));
    }
    @ParameterizedTest @ValueSource(ints={0,1,2}) void laterBindingKeepsOrderAndAppliesOnlyConfirmedFunds(int mode) {
        long id=createUnbound(true);Map<String,Object> before=db.queryForMap("select * from contract_order where id=?",id);BigDecimal net=storedNet(id);
        point(oldClose-60000,"1000");point(oldClose,"1001");point(oldClose+60000,"1002");
        ManualOrderService.BindRequest r=binding(id,1,mode>0,mode==2,service);assertNull(db.queryForMap("select user_id from contract_order where id=?",id).get("user_id"));equal("1000000",wallet(1));
        service.bind(id,r);newService(null).bind(id,r);Map<String,Object> after=db.queryForMap("select * from contract_order where id=?",id);equal("1",after.get("user_id"));
        for(String field:Arrays.asList("id","open_price","close_price","open_time","close_time","quantity","leverage","profit","fee","order_source"))assertEquals(before.get(field),after.get(field),field);
        equal((mode>0?n("1000000").add(net):n("1000000")).toPlainString(),wallet(1));equal((mode==2?n("1001").add(net):n("1001")).toPlainString(),historical(oldClose));equal("1000",historical(oldClose-60000));equal((mode==2?n("1002").add(net):n("1002")).toPlainString(),historical(oldClose+60000));
        assertEquals(1,count("manual_order_binding"));assertEquals(1,count("manual_order_record"));r.userId=2L;assertThrows(BusinessException.class,()->service.bind(id,r));assertThrows(BusinessException.class,()->binding(id,2,false,false,service));
    }
    @ParameterizedTest @ValueSource(strings={"order","wallet","minutes","parent","audit"}) void createRollbackIsAtomic(String failure) {
        ManualOrderService bad=newService(failure);SimpleManualOrderGenerator.Request r=simple();r.userId=1L;r.closePrice=n("120");r.walletEnabled=true;r.historyEnabled=true;
        ManualOrderService.Request c=confirmation(bad,r);point(oldClose,"1001");
        assertThrows(IllegalStateException.class,()->bad.create(c));assertEquals(0,count("contract_order"));assertEquals(0,count("manual_order_record"));equal("1000000",wallet(1));equal("1001",historical(oldClose));assertEquals(0,count("asset_history_1h"));
        c.previewToken=service.preview(c).get("previewToken").toString();service.create(c);assertEquals(1,count("contract_order"));
    }
    @ParameterizedTest @ValueSource(strings={"binding","wallet","minutes","parent","audit"}) void bindingRollbackIsAtomic(String failure) {
        long id=createUnbound(true);point(oldClose,"1001");ManualOrderService bad=newService(failure);ManualOrderService.BindRequest r=binding(id,1,true,true,bad);
        assertThrows(IllegalStateException.class,()->bad.bind(id,r));assertNull(db.queryForMap("select user_id from contract_order where id=?",id).get("user_id"));assertEquals(0,count("manual_order_binding"));equal("1000000",wallet(1));equal("1001",historical(oldClose));assertEquals(0,count("asset_history_1h"));
        r.previewToken=service.previewBinding(id,r).get("previewToken").toString();service.bind(id,r);assertEquals(1,count("manual_order_binding"));
    }
    @ParameterizedTest @ValueSource(booleans={false,true}) void concurrentBindingsCreditExactlyOneUser(boolean sameKey) throws Exception {
        long id=createUnbound(false);ManualOrderService.BindRequest first=binding(id,1,true,false,service),second=sameKey?first:binding(id,2,true,false,service);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<Boolean>> calls=new ArrayList<>();
        try {
            for(ManualOrderService.BindRequest r:Arrays.asList(first,second))calls.add(pool.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(2L)){auth(1,"SUPER_ADMIN");start.await();try{service.bind(id,r);return true;}catch(BusinessException conflict){return false;}}finally{SecurityContextHolder.clearContext();}}));
            start.countDown();int successes=0;for(Future<Boolean> f:calls)if(f.get(30,TimeUnit.SECONDS))successes++;assertEquals(sameKey?2:1,successes);
        }finally {pool.shutdownNow();}
        equal(n("2000000").add(storedNet(id)).toPlainString(),wallet(1).add(wallet(2)));assertEquals(1,count("manual_order_binding"));
    }
    @Test void previewExpiryIdentityWalletConfigurationAndOrderChangesInvalidateConfirmation() {
        ManualOrderService.Request expiredCreate=confirmation(service,simple());Map<?,?> cache=(Map<?,?>)ReflectionTestUtils.getField(service,"previews");ReflectionTestUtils.setField(cache.get(expiredCreate.previewToken),"expires",0L);assertThrows(BusinessException.class,()->service.create(expiredCreate));
        ManualOrderService.Request c=confirmation(service,simple());ManualOrderService.Request fixed=c;auth(2,"SUPER_ADMIN");assertThrows(BusinessException.class,()->service.create(fixed));auth(1,"SUPER_ADMIN");db.update("update trading_symbol set fee_multiplier=0.02 where id=20001");assertThrows(BusinessException.class,()->service.create(fixed));db.update("update trading_symbol set fee_multiplier=0.01 where id=20001");
        long id=createUnbound(false);ManualOrderService.BindRequest r=binding(id,1,true,false,service);ManualOrderService.BindRequest walletStale=r;db.update("update asset_account set available=available+1 where user_id=1");assertThrows(BusinessException.class,()->service.bind(id,walletStale));
        r=binding(id,1,true,false,service);ManualOrderService.BindRequest stale=r;db.update("update contract_order set profit=profit+1 where id=?",id);assertThrows(BusinessException.class,()->service.bind(id,stale));
        r=binding(id,1,true,false,service);Map<?,?> binds=(Map<?,?>)ReflectionTestUtils.getField(service,"bindingPreviews");ReflectionTestUtils.setField(binds.get(r.previewToken),"expires",0L);ManualOrderService.BindRequest expired=r;assertThrows(BusinessException.class,()->service.bind(id,expired));assertEquals(0,count("manual_order_binding"));
    }
    @Test void tenantAuthorizationInvalidFlagsTamperedInputsAndDatabaseGuards() throws Exception {
        SimpleManualOrderGenerator.Request r=simple();r.walletEnabled=true;SimpleManualOrderGenerator.Request invalidFlags=r;assertThrows(BusinessException.class,()->service.generateSimple(invalidFlags));r.userId=1L;r.walletEnabled=false;r.historyEnabled=true;assertThrows(BusinessException.class,()->service.generateSimple(invalidFlags));
        r=simple();r.userId=3L;SimpleManualOrderGenerator.Request other=r;assertThrows(BusinessException.class,()->service.generateSimple(other));
        long id=createUnbound(false);ManualOrderService.BindRequest bind=new ManualOrderService.BindRequest();bind.userId=3L;assertThrows(BusinessException.class,()->service.previewBinding(id,bind));bind.userId=1L;bind.historyEnabled=true;assertThrows(BusinessException.class,()->service.previewBinding(id,bind));bind.historyEnabled=false;
        auth(1,"ADMIN");assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.generateSimple(simple()));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.previewBinding(id,bind));auth(1,"SUPER_ADMIN");
        try {TenantContext.clear();try(TenantContext.Scope ignored=TenantContext.open(3L)){assertThrows(BusinessException.class,()->service.previewBinding(id,bind));}}finally {TenantContext.open(2L);}
        ManualOrderService.Request c=confirmation(service,simple());c.openPrice=n("99.6");assertThrows(BusinessException.class,()->service.create(c));c.userId=1L;assertThrows(BusinessException.class,()->service.create(c));
        assertThrows(RuntimeException.class,()->db.update("update contract_order set order_source='USER' where id=?",id));assertThrows(RuntimeException.class,()->db.update("update contract_order set status='OPEN' where id=?",id));assertThrows(RuntimeException.class,()->db.update("update contract_order set manual_wallet_enabled=1 where id=?",id));
        assertThrows(RuntimeException.class,()->db.update("insert into contract_order(user_id,symbol,side,type,status,quantity,created_at,updated_at,row_version,limit_match_enabled) values(null,'FIXTUREUSD','BUY','MARKET','CLOSED',1,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0,0)"));
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->json.readValue("{\"symbol\":\"FIXTUREUSD\",\"tenantId\":2}",SimpleManualOrderGenerator.Request.class));
        ManualOrderController controller=new ManualOrderController(service);c.simpleMode=true;assertThrows(BusinessException.class,()->controller.preview(c));assertFalse(c.simpleMode);assertEquals(0,count("manual_order_binding"));equal("1000000",wallet(1));
    }
    @Test void incompleteFutureAndOlderThanSevenDaysCandlesCannotMatch() {
        SimpleManualOrderGenerator.Request r=simple();r.closePrice=n("130");assertThrows(BusinessException.class,()->service.generateSimple(r));
        r.closePrice=n("999");assertThrows(BusinessException.class,()->service.generateSimple(r));
        when(market.historicalKline(anyString(),anyString(),anyInt(),anyLong())).thenReturn(Collections.singletonMap("data",Collections.singletonMap("kline_list",Arrays.asList(Collections.singletonMap("timestamp",close)))));
        r.closePrice=null;assertThrows(BusinessException.class,()->service.generateSimple(r));assertEquals(0,count("contract_order"));
    }
    @Test void negativeNetDebitsOnceAndInsufficientFundsNeverMutateWallet() {
        SimpleManualOrderGenerator.Request r=simple();r.targetNet=n("-100");
        long id=((Number)service.create(confirmation(service,r)).get("orderId")).longValue();BigDecimal net=storedNet(id);
        ManualOrderService.BindRequest bind=binding(id,1,true,false,service);service.bind(id,bind);service.bind(id,bind);equal(n("1000000").add(net).toPlainString(),wallet(1));assertTrue(net.signum()<0);
        r.targetNet=n("-2000000");long tooLarge=((Number)service.create(confirmation(service,r)).get("orderId")).longValue();
        assertThrows(BusinessException.class,()->binding(tooLarge,1,true,false,service));assertNull(db.queryForMap("select user_id from contract_order where id=?",tooLarge).get("user_id"));assertEquals(1,count("manual_order_binding"));
    }
    @Test void livePriceDoesNotInvalidateFrozenPricePreview() {
        ManualOrderService.Request r=confirmation(service,simple());
        db.update("update trading_symbol set current_price=101,sparkline_data='[101]' where id=20001");
        long id=((Number)service.create(r).get("orderId")).longValue();equal("100",db.queryForObject("select close_price from contract_order where id=?",BigDecimal.class,id));
    }
    @Test void advancedModeStillUsesExactOpenAndRequiresUser() {
        ManualOrderService.Request r=new ManualOrderService.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.timezone="UTC";r.quantityUnitType="LOT";r.specVersion=1L;r.openLocal=local(close-60000);r.closeLocal=local(close);r.side="BUY";r.driver="QUANTITY";r.input=n("1");r.leverage=n("10");
        Map<String,Object> out=service.preview(r);equal("99.5",((Map<?,?>)out.get("quotes")).get("openPrice"));assertEquals("EXACT_MINUTE_OPEN",((Map<?,?>)out.get("quotes")).get("priceBasis"));r.previewToken=out.get("previewToken").toString();r.idempotencyKey=UUID.randomUUID().toString();service.create(r);equal("1000000",wallet(1));r.userId=null;assertThrows(BusinessException.class,()->service.preview(r));
    }
    static String local(long time) {return LocalDateTime.ofInstant(Instant.ofEpochMilli(time),ZoneOffset.UTC).toString();}
}

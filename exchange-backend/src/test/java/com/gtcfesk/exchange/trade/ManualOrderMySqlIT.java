package com.gtcfesk.exchange.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.common.BusinessException;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.user.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.gtcfesk.exchange.trade.ManualOrderCalculationTest.*;

/** Actual application service, full relevant schema, one disposable MySQL 5.7 database. */
class ManualOrderMySqlIT {
    static DriverManagerDataSource source;static JdbcTemplate db;static AssetEquityStore store;
    static long day=Instant.parse("2026-09-20T00:00:00Z").toEpochMilli(),t=day+11*3600000+20*60000;
    ManualOrderService service;TradingSymbol symbol;ForexQuoteMarketService market;TradingSymbolRepository repo;
    @BeforeAll static void schema() throws Exception {
        String url=System.getenv("MANUAL_TEST_JDBC");
        assertNotNull(url,"Use scripts/manual-order/Test-MySql.ps1; missing fixture is NOT a pass");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/manual_order_test.*"));
        source=new DriverManagerDataSource(url,"root","equity-fixture-only");db=new JdbcTemplate(source);
        assertTrue(db.queryForObject("select version()",String.class).startsWith("5.7."));
        assertEquals("manual_order_test",db.queryForObject("select database()",String.class));
        try(Connection c=source.getConnection()) {
            ScriptUtils.executeSqlScript(c,new ClassPathResource("manual-order-schema.sql"));
            ScriptUtils.executeSqlScript(c,new ClassPathResource("db/asset-equity/V001__net_equity_history.sql"));
            for(int i=0;i<2;i++)ScriptUtils.executeSqlScript(c,new ClassPathResource("db/manual/705-manual-history-order.sql"));
        }
        store=new AssetEquityStore(source,new ObjectMapper());
    }
    static void auth(String role){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1","unused",Collections.singletonList(new SimpleGrantedAuthority("ROLE_"+role))));}
    @BeforeEach void setup() {
        auth("SUPER_ADMIN");
        for(String table:Arrays.asList("manual_order_record","contract_order","asset_account","user_account","trading_symbol","asset_history_1m","asset_history_1h","asset_history_4h","asset_history_1d","asset_history_baseline","asset_history_job_state","asset_history_quote_batch"))db.update("delete from "+table);
        db.update("insert into user_account(id,email,password_hash,row_version) values(1,'manual-user@local.invalid','not-a-login',0),(2,'other@local.invalid','not-a-login',0)");
        db.update("insert into asset_account(user_id,coin,available,frozen,row_version) values(1,'CONTRACT',1000,0,0),(2,'CONTRACT',999,0,0)");
        db.update("insert into trading_symbol(id,symbol,base_currency,quote_currency,name,market_source,source_category,lot_size,fee_multiplier,control_enabled) values(1,'FIXTUREUSD','FIXTURE','USD','isolated fixture','yahoo','Forex',1,2,0)");
        symbol=new TradingSymbol();symbol.setId(1L);symbol.setSymbol("FIXTUREUSD");symbol.setIsEnabled(true);symbol.setLotSize(n("1"));symbol.setFeeMultiplier(n("2"));symbol.setMarketSource("yahoo");symbol.setQuoteCurrency("USD");symbol.setRowVersion(0);
        repo=mock(TradingSymbolRepository.class);when(repo.findBySymbol("FIXTUREUSD")).thenReturn(Optional.of(symbol));
        market=mock(ForexQuoteMarketService.class);
        when(market.historicalKline(eq("FIXTUREUSD"),eq("1m"),eq(2),anyLong())).thenAnswer(inv->{long minute=inv.<Long>getArgument(3)-59999;Map<String,Object> candle=new HashMap<>();candle.put("timestamp",minute);candle.put("open_price",minute==t-60000?"100":"110");return Collections.singletonMap("data",Collections.singletonMap("kline_list",Collections.singletonList(candle)));});
        service=newService(null);
        for(int level=1;level<=3;level++)store.progress(db,"rollup_"+level,day+86400000,0,System.currentTimeMillis());
    }
    ManualOrderService newService(String failure) {
        ManualOrderService s=new ManualOrderService(source,new ObjectMapper(),repo,new ManualOrderPrices(market),new ManualOrderHistory(store)) {
            @Override protected void checkpoint(String stage) {if(stage.equals(failure))throw new IllegalStateException("injected "+stage);}
        };
        ReflectionTestUtils.setField(s,"enabled",true);return s;
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();}
    ManualOrderService.Request request(boolean wallet,boolean history) {
        ManualOrderService.Request r=new ManualOrderService.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.side="BUY";r.timezone="UTC";r.openLocal=local(t-60000);r.closeLocal=local(t);r.driver="NET";r.input=n("100");r.leverage=n("10");r.walletEnabled=wallet;r.historyEnabled=history;r.idempotencyKey=UUID.randomUUID().toString();return r;
    }
    static String local(long at){return LocalDateTime.ofInstant(Instant.ofEpochMilli(at),ZoneOffset.UTC).toString();}
    Map<String,Object> create(ManualOrderService.Request r){r.previewToken=service.preview(r).get("previewToken").toString();return service.create(r);}
    void point(long user,long at,String amount){db.update("insert into asset_history_1m(user_id,basis_version,bucket_start,observed_at,net_equity,valuation_status,reason_code,quote_batch_id,valuation_evidence,created_at) values(?,'net_equity_v1',?,?,?,'INCOMPLETE','OLD_EVIDENCE','fixture','{}',?)",user,at,at+60000,amount==null?null:n(amount),at+60000);}
    BigDecimal wallet(){return db.queryForObject("select available from asset_account where user_id=1",BigDecimal.class);}
    BigDecimal value(long at){return db.queryForObject("select net_equity from asset_history_1m where user_id=1 and basis_version='net_equity_v1' and bucket_start=?",BigDecimal.class,at);}
    int count(String table){return db.queryForObject("select count(*) from "+table,Integer.class);}
    @Test void threeLegalCombinationsAndDefaults() {
        point(1,t,"1000");create(request(false,false));equal("1000",wallet());equal("1000",value(t));
        create(request(true,false));equal("1100",wallet());equal("1000",value(t));
        create(request(true,true));equal("1200",wallet());equal("1100",value(t));
        assertEquals(3,count("manual_order_record"));assertEquals(3,db.queryForObject("select count(*) from contract_order where status='CLOSED' and order_source='MANUAL_TEST' and lot_size=1 and limit_match_enabled=0",Integer.class));
        assertThrows(BusinessException.class,()->service.preview(request(false,true)));
        assertEquals(2,count("asset_history_migration"));
    }
    @Test void negativeWalletGrossFeeAndNoMarginRefund() {
        db.update("update asset_account set available=50,frozen=7 where user_id=1");ManualOrderService.Request r=request(true,false);r.side="SELL";r.input=n("-100");
        Map<String,Object> out=create(r);BigDecimal net=(BigDecimal)((Map<?,?>)out.get("calculation")).get("net");
        equal("-49.96",wallet()); // -12 per lot; 0.01 step gives -99.96, never forge exactly -100.
        equal("7",db.queryForObject("select frozen from asset_account where user_id=1",BigDecimal.class));
        equal(net.toString(),db.queryForObject("select profit-fee from contract_order",BigDecimal.class));
        // Same-minute trade: gross zero, 50 lots * fee 2 = exact -100.
        db.update("update asset_account set available=50,row_version=row_version+1 where user_id=1");r=request(true,false);r.openLocal=r.closeLocal;r.input=n("-100");create(r);equal("-50",wallet());
    }
    @Test void existingSuffixAndFinalizedBoundaryOhlc() {
        for(int m=0;m<60;m++)point(1,day+11*3600000+m*60000,"1000");
        AssetEquityJobs jobs=new AssetEquityJobs(store,new EquityValuationService(db,market));
        try {store.progress(db,"rollup_1",day+11*3600000,0,0);jobs.aggregate();}finally{jobs.stop();}
        assertTrue(count("asset_history_1h")>0);long beforeWatermark=store.state(db,"rollup_3",0)[0];create(request(true,true));
        equal("1000",value(t-60000));equal("1100",value(t));equal("1100",value(t+39*60000));
        Map<String,Object> h=db.queryForMap("select * from asset_history_1h where user_id=1 and bucket_start=?",day+11*3600000);
        equal("1000",h.get("open_value"));equal("1100",h.get("high_value"));equal("1000",h.get("low_value"));equal("1100",h.get("close_value"));assertEquals(t+60000,((Number)h.get("high_at")).longValue());assertEquals(60L,((Number)h.get("valid_sample_count")).longValue());
        assertEquals(beforeWatermark,store.state(db,"rollup_3",0)[0]);
    }
    @ParameterizedTest @ValueSource(strings={"0","-5","800"}) void nearestValidIncludingZeroNegativeAndNullRepair(String base) {
        point(1,t-120000,base);point(1,t-60000,null);point(1,t,null);point(1,t+60000,"1050");point(2,t-60000,"99999");
        create(request(true,true));equal(n(base).add(n("100")).toString(),value(t));equal("1150",value(t+60000));assertNull(value(t-60000));assertEquals(5,count("asset_history_1m"));
        Map<String,Object> row=db.queryForMap("select * from asset_history_1m where user_id=1 and bucket_start=?",t);assertEquals("OLD_EVIDENCE",row.get("reason_code"));assertEquals("{}",row.get("valuation_evidence"));assertEquals("MANUAL_CARRY",row.get("origin"));
        assertEquals(t+60000,store.source(db,0,Collections.singletonList(1L),t,t+60000,System.currentTimeMillis(),true).get(0).through);
    }
    @Test void inclusive24HoursZeroFallbackAndNoFutureOrOtherBasis() {
        point(1,t-86400000,"8");create(request(true,true));equal("108",value(t));
        db.update("delete from asset_history_1m where user_id=1");point(1,t-86460000,"900");point(1,t+60000,"900");point(2,t-60000,"999");
        point(1,t-60000,"999");db.update("update asset_history_1m set basis_version='other' where user_id=1 and bucket_start=?",t-60000);
        create(request(true,true));equal("100",value(t));assertEquals(5,count("asset_history_1m"));
        Map<String,Object> row=db.queryForMap("select * from asset_history_1m where user_id=1 and bucket_start=?",t);assertNull(row.get("observed_at"));assertNull(row.get("quote_batch_id"));assertEquals("MANUAL_ZERO",row.get("origin"));assertTrue(((Number)row.get("created_at")).longValue()>t+86400000);
    }
    @Test void idempotenceDifferentContentSameMinuteAndReverseOrder() {
        ManualOrderService.Request r=request(true,true);Map<String,Object> first=create(r);equal("100",value(t));
        assertEquals(first.get("orderId"),service.create(r).get("orderId"));equal("1100",wallet());assertEquals(1,count("contract_order"));
        r.input=n("101");final ManualOrderService.Request changed=r;assertThrows(BusinessException.class,()->service.create(changed));
        create(request(true,true));equal("200",value(t));
        r=request(true,true);r.openLocal=local(t-120000);r.closeLocal=local(t-60000);r.driver="QUANTITY";r.input=n("1"); // 110 to 100, net -12
        create(r);equal("188",value(t));equal("-12",value(t-60000));assertEquals(3,count("manual_order_record"));
    }
    @ParameterizedTest @ValueSource(strings={"order","wallet","minutes","parent","audit"}) void everyFailureRollsBackAndCanRetry(String stage) {
        point(1,t,"1000");service=newService(stage);ManualOrderService.Request r=request(true,true);r.previewToken=service.preview(r).get("previewToken").toString();
        assertThrows(IllegalStateException.class,()->service.create(r));assertEquals(0,count("contract_order"));assertEquals(0,count("manual_order_record"));equal("1000",wallet());equal("1000",value(t));assertEquals(0,count("asset_history_1h"));
        assertEquals(1,db.queryForObject("select is_free_lock('equity_v1_capture')",Integer.class));assertEquals(1,db.queryForObject("select is_free_lock('equity_v1_rollup')",Integer.class));
        service=newService(null);create(r);equal("1100",wallet());equal("1100",value(t));
    }
    @Test void concurrentSameKeyOnlyOneEffect() throws Exception {
        ManualOrderService.Request r=request(true,true);r.previewToken=service.preview(r).get("previewToken").toString();ExecutorService threads=Executors.newFixedThreadPool(4);
        try {List<Future<Map<String,Object>>> futures=new ArrayList<>();for(int i=0;i<4;i++)futures.add(threads.submit(()->{auth("SUPER_ADMIN");try{return service.create(r);}finally{SecurityContextHolder.clearContext();}}));Object id=futures.get(0).get(10,TimeUnit.SECONDS).get("orderId");for(Future<Map<String,Object>> f:futures)assertEquals(id,f.get(10,TimeUnit.SECONDS).get("orderId"));}
        finally{threads.shutdownNow();}
        equal("1100",wallet());equal("100",value(t));assertEquals(1,count("contract_order"));assertEquals(1,count("manual_order_record"));
    }
    @Test void walletVersionPreviewTamperAndDisabledRoles() {
        ManualOrderService.Request r=request(true,false);r.previewToken=service.preview(r).get("previewToken").toString();db.update("update asset_account set available=available+10,row_version=row_version+1 where user_id=1");assertThrows(BusinessException.class,()->service.create(r));equal("1010",wallet());
        r.previewToken=service.preview(r).get("previewToken").toString();r.input=n("200");assertThrows(BusinessException.class,()->service.create(r));
        ReflectionTestUtils.setField(service,"enabled",false);assertThrows(BusinessException.class,()->service.preview(r));ReflectionTestUtils.setField(service,"enabled",true);
        for(String role:Arrays.asList("USER","AGENT","ADMIN")){auth(role);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.preview(r));}assertEquals(0,count("contract_order"));
    }
    @Test void lockBusyRejectsAndOrdinaryWalletUpdateIsNotLost() throws Exception {
        ManualOrderService.Request r=request(true,true);r.previewToken=service.preview(r).get("previewToken").toString();
        try(Connection c=source.getConnection()) {JdbcTemplate other=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true));other.queryForObject("select get_lock('equity_v1_capture',0)",Integer.class);assertThrows(BusinessException.class,()->service.create(r));other.queryForObject("select release_lock('equity_v1_capture')",Integer.class);}
        assertEquals(0,count("contract_order"));service.create(r);equal("1100",wallet());
    }
    @Test void realCaptureAfterCommitNoDoubleAdditionAndManualBaselineNotPolluted() {
        create(request(true,true));AssetEquityJobs jobs=new AssetEquityJobs(store,new EquityValuationService(db,market));try{jobs.capture();}finally{jobs.stop();}
        BigDecimal latest=db.queryForObject("select net_equity from asset_history_1m where user_id=1 order by bucket_start desc limit 1",BigDecimal.class);equal("1100",latest);
        EquityValuationService.Value duplicate=new EquityValuationService.Value(1,t+60000);duplicate.finish();store.saveMinutes(db,new EquityValuationService.Batch(),Collections.singletonList(duplicate),t+60000);
        equal("100",value(t));assertTrue(db.queryForObject("select capture_from from asset_history_baseline where user_id=1",Long.class)>t+60000);
    }
    @Test void unfinishedAndDelayedParentsNotCreated() {
        long current=AssetHistoryBucket.floor(System.currentTimeMillis(),60000);ManualOrderService.Request r=request(true,true);r.openLocal=local(current-120000);r.closeLocal=local(current-60000);r.driver="QUANTITY";r.input=n("1");create(r);
        assertEquals(0,count("asset_history_1d"));
        r=request(true,true);r.closeLocal=local(current);final ManualOrderService.Request pending=r;assertThrows(BusinessException.class,()->service.preview(pending));
    }
    @Test void deferredParentsAreFinalizedByNormalRollupWithoutBackfillingMinutes() {
        point(1,t-60000,"1000");
        store.progress(db,"rollup_1",day+11*3600000,0,0);
        store.progress(db,"rollup_2",day+8*3600000,0,0);
        store.progress(db,"rollup_3",day,0,0);
        create(request(true,true));
        equal("1100",value(t));assertEquals(1,count("asset_history_1h"));
        assertEquals(0,count("asset_history_4h"));assertEquals(0,count("asset_history_1d"));
        AssetEquityJobs jobs=new AssetEquityJobs(store,new EquityValuationService(db,market));
        try {jobs.aggregate();} finally {jobs.stop();}
        assertEquals(2,count("asset_history_1m")); // Normal rollup does not fill missing minutes.
        equal("1100",db.queryForObject("select close_value from asset_history_4h where user_id=1",BigDecimal.class));
        equal("1100",db.queryForObject("select close_value from asset_history_1d where user_id=1",BigDecimal.class));
        assertEquals(1,count("asset_history_4h"));assertEquals(1,count("asset_history_1d"));
    }
    @Test void quoteReuseMissingPriceAndSameMinuteFees() {
        ManualOrderService.Request r=request(false,false);r.previewToken=service.preview(r).get("previewToken").toString();r.input=n("200");service.preview(r);verify(market,times(2)).historicalKline(anyString(),anyString(),anyInt(),anyLong());
        r.openLocal=r.closeLocal;r.driver="QUANTITY";r.input=n("1");Map<String,Object> out=create(r);equal("-2",((Map<?,?>)out.get("calculation")).get("net"));
        when(market.historicalKline(anyString(),anyString(),anyInt(),anyLong())).thenReturn(Collections.emptyMap());r.previewToken=null;assertThrows(BusinessException.class,()->service.preview(r));
    }
    @Test void concurrentCaptureRollupAndWalletCannotSeeHalfCommit() throws Exception {
        CountDownLatch walletWritten=new CountDownLatch(1),release=new CountDownLatch(1);
        service=new ManualOrderService(source,new ObjectMapper(),repo,new ManualOrderPrices(market),new ManualOrderHistory(store)) {
            @Override protected void checkpoint(String stage) {
                if(!stage.equals("wallet"))return;
                walletWritten.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout");}catch(InterruptedException e){throw new IllegalStateException(e);}
            }
        };
        ReflectionTestUtils.setField(service,"enabled",true);
        ManualOrderService.Request r=request(true,true);r.previewToken=service.preview(r).get("previewToken").toString();
        ExecutorService threads=Executors.newFixedThreadPool(2);AssetEquityJobs jobs=new AssetEquityJobs(store,new EquityValuationService(db,market));
        try {
            Future<?> created=threads.submit(()->{auth("SUPER_ADMIN");try{service.create(r);}finally{SecurityContextHolder.clearContext();}});
            assertTrue(walletWritten.await(5,TimeUnit.SECONDS));equal("1000",wallet());assertEquals(0,count("contract_order"));
            jobs.capture();jobs.aggregate();assertEquals(0,count("asset_history_1m"));
            Future<?> ordinary=threads.submit(()->db.update("update asset_account set available=available+10,row_version=row_version+1 where user_id=1 and coin='CONTRACT'"));
            release.countDown();created.get(10,TimeUnit.SECONDS);ordinary.get(10,TimeUnit.SECONDS);
            equal("1110",wallet());equal("100",value(t));assertEquals(2,db.queryForObject("select row_version from asset_account where user_id=1",Integer.class));
            jobs.capture();equal("1110",db.queryForObject("select net_equity from asset_history_1m where user_id=1 order by bucket_start desc limit 1",BigDecimal.class));
        } finally {release.countDown();threads.shutdownNow();jobs.stop();}
    }
    @Test void bounded10000PointTransactionAndOverflowRollback() {
        List<Object[]> rows=new ArrayList<>();for(int i=0;i<10000;i++)rows.add(new Object[]{t+i*60000,t+(i+1)*60000});
        db.batchUpdate("insert into asset_history_1m(user_id,basis_version,bucket_start,observed_at,net_equity,valuation_status,reason_code,quote_batch_id,valuation_evidence,created_at) values(1,'net_equity_v1',?,?,1000,'COMPLETE','','fixture','{}',0)",rows);
        long started=System.nanoTime();create(request(true,true));System.out.println("MANUAL_10000_POINT_TRANSACTION_MS="+(System.nanoTime()-started)/1000000);
        equal("1100",value(t));equal("1100",value(t+9999*60000));assertEquals(10000,count("asset_history_1m"));
        point(1,t+10000*60000,"1000");assertThrows(BusinessException.class,()->service.preview(request(true,true)));assertEquals(1,count("contract_order"));
        db.update("delete from asset_history_1m where bucket_start<>?",t);db.update("update asset_history_1m set net_equity=9999999999999999");
        ManualOrderService.Request r=request(true,true);r.previewToken=service.preview(r).get("previewToken").toString();assertThrows(BusinessException.class,()->service.create(r));equal("1100",wallet());assertEquals(1,count("manual_order_record"));
    }
    @Test void expiredPreviewAndHistoricalFxAreRejected() {
        ManualOrderService.Request r=request(true,false);r.previewToken=service.preview(r).get("previewToken").toString();
        Map<?,?> previews=(Map<?,?>)ReflectionTestUtils.getField(service,"previews");ReflectionTestUtils.setField(previews.get(r.previewToken),"expires",0L);assertThrows(BusinessException.class,()->service.create(r));
        symbol.setQuoteCurrency("EUR");r.previewToken=null;assertThrows(BusinessException.class,()->service.preview(r));assertEquals(0,count("contract_order"));equal("1000",wallet());
    }
    @Test void existingRealPositionIsNotTouchedAndManualCreationIsAllowed() {
        db.update("insert into contract_order(user_id,symbol,side,type,status,quantity,leverage,lot_size,margin,fee,open_price,row_version,limit_match_enabled,created_at,updated_at) values(1,'FIXTUREUSD','BUY','MARKET','OPEN',1,10,1,10,2,100,0,0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        db.update("update asset_account set frozen=10 where user_id=1");
        Map<String,Object> original=db.queryForMap("select * from contract_order where status='OPEN'");
        create(request(true,true));assertEquals(original,db.queryForMap("select * from contract_order where status='OPEN'"));
        equal("10",db.queryForObject("select frozen from asset_account where user_id=1",BigDecimal.class));equal("1100",wallet());
        assertEquals(1,db.queryForObject("select count(*) from contract_order where order_source='USER'",Integer.class));
    }
    @Test void allFourTablesCrossDayNegativeAdjustmentAndClippedSyntheticMinute() {
        long next=day+86400000;
        point(1,t-60000,"1000");point(1,t,"1000");point(1,t+39*60000,"1050");
        point(1,next-60000,"900");point(1,next,"800");point(1,next+4*86400000,"700");
        for(int level=1;level<=3;level++)store.progress(db,"rollup_"+level,next+5*86400000,0,0);
        create(request(true,true));
        Map<String,Object> four=db.queryForMap("select * from asset_history_4h where user_id=1 and bucket_start=?",day+8*3600000);
        equal("1000",four.get("open_value"));equal("1150",four.get("high_value"));equal("1000",four.get("low_value"));equal("1150",four.get("close_value"));
        assertEquals(3L,((Number)four.get("valid_sample_count")).longValue());
        Map<String,Object> daily=db.queryForMap("select * from asset_history_1d where user_id=1 and bucket_start=?",day);
        equal("1000",daily.get("open_value"));equal("1150",daily.get("high_value"));equal("1000",daily.get("close_value"));
        equal("900",db.queryForObject("select close_value from asset_history_1d where user_id=1 and bucket_start=?",BigDecimal.class,next));
        assertEquals(3,count("asset_history_1d")); // No calendar fill between next day and day five.
        ManualOrderService.Request loss=request(true,true);loss.openLocal=loss.closeLocal;loss.input=n("-100");create(loss);
        equal("1000",value(t));equal("800",value(next));equal("1000",wallet());
        equal("1050",db.queryForObject("select high_value from asset_history_4h where user_id=1 and bucket_start=?",BigDecimal.class,day+8*3600000));
        // A missing minute becomes an estimated point with a logical time, not an observation.
        ManualOrderService.Request missing=request(true,true);missing.openLocal=local(t);missing.closeLocal=local(t+60000);missing.input=n("-100");create(missing);
        assertNull(db.queryForObject("select observed_at from asset_history_1m where user_id=1 and bucket_start=?",Long.class,t+60000));
        List<AssetHistoryBucket> clipped=store.window(db,1,1,t+60001,t+120001,System.currentTimeMillis());
        assertEquals(1,clipped.size());equal("900",clipped.get(0).close);assertEquals(t+120000,clipped.get(0).through);
    }

}

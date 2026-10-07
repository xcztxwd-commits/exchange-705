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
    static List<Map<String,Object>> migrationMetadata,tenantMigrationMetadata;
    static long day=Instant.parse("2026-09-20T00:00:00Z").toEpochMilli(),t=day+11*3600000+20*60000;
    ManualOrderService service;TradingSymbol symbol;ForexQuoteMarketService market;TradingSymbolRepository repo;
    @BeforeAll static void schema() throws Exception {
        source=com.gtcfesk.exchange.tenant.DedicatedMysqlFixture.fromProperty("manual.mysql.fixture");db=new JdbcTemplate(source);
        assertTrue(db.queryForObject("select version()",String.class).startsWith("5.7."));
        assertTrue(db.queryForObject("select database()",String.class).startsWith("mt705_probe_"));
        db.update("insert into admin_user(id,tenant_id,account,email,password_hash,role,enabled,row_version,created_at,updated_at) values(900001,2,'manual-fixture-admin','manual-admin@fixture.invalid','not-a-login','super_admin',1,0,UTC_TIMESTAMP(),UTC_TIMESTAMP()),(900002,2,'manual-fixture-other-admin','manual-other-admin@fixture.invalid','not-a-login','super_admin',1,0,UTC_TIMESTAMP(),UTC_TIMESTAMP()) on duplicate key update row_version=row_version");
        assertEquals(2L,db.queryForObject("select count(*) from admin_user where tenant_id=2 and (id=900001 and account='manual-fixture-admin' or id=900002 and account='manual-fixture-other-admin')",Long.class));
        // Global DDL receipts are shared read-only metadata, not tenant-owned business rows.
        migrationMetadata=db.queryForList("select * from asset_history_migration order by migration_id");
        tenantMigrationMetadata=db.queryForList("select * from tenant_schema_version order by version");
        assertEquals(2026100402L,db.queryForObject("select max(minimum_application_epoch) from tenant_schema_version",Long.class));
        store=new AssetEquityStore(source,new ObjectMapper());
    }
    static void auth(String role){com.gtcfesk.exchange.tenant.TenantContext.open(2L);SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("900001","unused",Collections.singletonList(new SimpleGrantedAuthority("ROLE_"+role))));}
    @BeforeEach void setup() {
        auth("SUPER_ADMIN");
        for(String table:Arrays.asList("manual_order_binding","manual_order_record","contract_order","asset_account","asset_history_1m","asset_history_1h","asset_history_4h","asset_history_1d","asset_history_baseline","asset_history_job_state","asset_history_quote_batch","asset_history_revision","user_account","trading_symbol"))db.update("delete from "+table+" where tenant_id=2");
        db.update("insert into user_account(tenant_id,id,email,password_hash,row_version) values(2,1,'manual-user@local.invalid','not-a-login',0),(2,2,'other@local.invalid','not-a-login',0)");
        db.update("insert into asset_account(tenant_id,user_id,coin,available,frozen,row_version) values(2,1,'CONTRACT',1000,0,0),(2,2,'CONTRACT',999,0,0)");
        db.update("insert into trading_symbol(tenant_id,id,symbol,base_currency,quote_currency,name,market_source,source_category,lot_size,fee_multiplier,control_enabled) values(2,20001,'FIXTUREUSD','FIXTURE','USD','isolated fixture','yahoo','Forex',1,2,0)");
        db.update("insert into asset_history_quote_batch(tenant_id,batch_id,prepared_at,evidence) values(2,'fixture',?,'{}')",System.currentTimeMillis());
        symbol=new TradingSymbol();symbol.setId(20001L);symbol.setSymbol("FIXTUREUSD");symbol.setIsEnabled(true);symbol.setLotSize(n("1"));symbol.setFeeMultiplier(n("2"));symbol.setMarketSource("yahoo");symbol.setQuoteCurrency("USD");symbol.setRowVersion(0);
        repo=mock(TradingSymbolRepository.class);when(repo.findByTenantIdAndSymbol(2L, "FIXTUREUSD")).thenReturn(Optional.of(symbol));
        market=mock(ForexQuoteMarketService.class);
        // The production picker now requests a shared 720-minute window. Keep exact-minute prices and every assertion unchanged.
        when(market.historicalKline(eq("FIXTUREUSD"),eq("1m"),anyInt(),anyLong())).thenAnswer(inv->{
            long last=inv.<Long>getArgument(3)-59999;int count=inv.getArgument(2);List<Map<String,Object>> candles=new ArrayList<>();
            for(int i=count-1;i>=0;i--){long minute=last-i*60000;Map<String,Object> candle=new HashMap<>();candle.put("timestamp",minute);candle.put("open_price",minute==t-60000?"100":"110");candles.add(candle);}
            return Collections.singletonMap("data",Collections.singletonMap("kline_list",candles));
        });
        service=newService(null);
        for(int level=1;level<=3;level++)store.progress(db,"rollup_"+level,day+86400000,0,System.currentTimeMillis());
    }
    ManualOrderService newService(String failure) {
        com.gtcfesk.exchange.market.MarketCategoryService categories=mock(com.gtcfesk.exchange.market.MarketCategoryService.class);
        when(categories.leverageEnabled(any())).thenReturn(true);
        ManualOrderService s=new ManualOrderService(source,new ObjectMapper(),repo,new ManualOrderPrices(market),null,new ManualOrderHistory(store),categories) {
            @Override protected void checkpoint(String stage) {if(stage.equals(failure))throw new IllegalStateException("injected "+stage);}
        };
        ReflectionTestUtils.setField(s,"enabled",true);return s;
    }
    @AfterEach void clear(){SecurityContextHolder.clearContext();com.gtcfesk.exchange.tenant.TenantContext.clear();}
    @Test void cryptoUnitProtocolAndPersistedSnapshot() {
        symbol.setSourceCategory("Crypto");symbol.setBaseCurrency("BTC");symbol.setQuantityUnitType("BASE_ASSET");symbol.setSpecVersion(1L);
        symbol.setMinOrderQuantity(n("0.001"));symbol.setQuantityStep(n("0.001"));symbol.setMinOrderNotional(BigDecimal.ZERO);symbol.setFeeMultiplier(n("0.03"));
        ManualOrderService.Request r=request(true,false);r.driver="QUANTITY";r.input=n("0.001");
        assertThrows(BusinessException.class,()->service.preview(r));equal("1000",wallet());assertEquals(0,count("contract_order"));
        r.quantityUnitType="BASE_ASSET";r.specVersion=1L;create(r);
        equal("0.001",db.queryForObject("select quantity from contract_order where tenant_id=2",BigDecimal.class));equal("0.00003",db.queryForObject("select fee from contract_order where tenant_id=2",BigDecimal.class));
        assertEquals("BASE_ASSET",db.queryForObject("select quantity_unit_type from contract_order where tenant_id=2",String.class));assertEquals("BTC",db.queryForObject("select quantity_asset from contract_order where tenant_id=2",String.class));
        assertEquals(1L,db.queryForObject("select spec_version from contract_order where tenant_id=2",Long.class));equal("1000.00997",wallet());
    }
    ManualOrderService.Request request(boolean wallet,boolean history) {
        ManualOrderService.Request r=new ManualOrderService.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.side="BUY";r.timezone="UTC";r.openLocal=local(t-60000);r.closeLocal=local(t);r.driver="NET";r.input=n("100");r.leverage=n("10");r.walletEnabled=wallet;r.historyEnabled=history;r.idempotencyKey=UUID.randomUUID().toString();return r;
    }
    static String local(long at){return LocalDateTime.ofInstant(Instant.ofEpochMilli(at),ZoneOffset.UTC).toString();}
    Map<String,Object> create(ManualOrderService.Request r){r.previewToken=service.preview(r).get("previewToken").toString();return service.create(r);}
    void point(long user,long at,String amount){db.update("insert into asset_history_1m(tenant_id,user_id,basis_version,bucket_start,observed_at,net_equity,valuation_status,reason_code,quote_batch_id,valuation_evidence,created_at) values(2,?,'net_equity_v1',?,?,?,'INCOMPLETE','OLD_EVIDENCE','fixture','{}',?)",user,at,at+60000,amount==null?null:n(amount),at+60000);}
    BigDecimal wallet(){return db.queryForObject("select available from asset_account where tenant_id=2 and user_id=1",BigDecimal.class);}
    BigDecimal value(long at){return db.queryForObject("select net_equity from asset_history_1m where tenant_id=2 and user_id=1 and basis_version='net_equity_v1' and bucket_start=?",BigDecimal.class,at);}
    @Test void generationOnlyPreviewsAndThenUsesExistingAtomicCreate() {
        // Fixed historical close keeps this transaction test independent of the latest-close search.
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.timezone="UTC";r.targetNet=n("100");r.openLocal=local(t-60000);r.closeLocal=local(t+60*60000);r.walletEnabled=true;r.historyEnabled=true;
        Map<String,Object> out=service.generate(r);
        assertEquals(0,count("contract_order"));assertEquals(0,count("manual_order_record"));equal("1000",wallet());
        ManualOrderService.Request generated=new ObjectMapper().convertValue(out.get("request"),ManualOrderService.Request.class);
        assertEquals(r.openLocal,generated.openLocal);
        equal("100",generated.targetNet);
        long close=ManualOrderCalculation.minute(generated.closeLocal,generated.timezone,generated.closeOffset);
        assertEquals(t+60*60000,close);
        generated.previewToken=out.get("previewToken").toString();generated.idempotencyKey=UUID.randomUUID().toString();
        service.create(generated);service.create(generated);
        equal("1100",wallet());assertEquals(1,count("contract_order"));assertEquals(1,count("manual_order_record"));
    }
    @Test void netOnlyGenerationAndImpossibleConstraintsNeverWriteMoney() {
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.timezone="UTC";r.targetNet=n("-100");
        Map<String,Object> out=service.generate(r);
        assertTrue(ManualOrderGenerator.withinTarget(new BigDecimal(((Map<?,?>)out.get("calculation")).get("net").toString()),r.targetNet));
        assertEquals(0,count("contract_order"));equal("1000",wallet());
        r.targetNet=n("10000");r.quantity=n("0.01");r.openLocal=local(t-60000);r.closeLocal=local(t);r.side="BUY";
        assertThrows(BusinessException.class,()->service.generate(r));assertEquals(0,count("contract_order"));equal("1000",wallet());
        r.historyEnabled=true;assertThrows(BusinessException.class,()->service.generate(r));
        SecurityContextHolder.clearContext();assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.generate(r));
    }
    @Test void highPrecisionLotAllocationAndNetTargetsGenerateReadOnlyPreview() {
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.timezone="UTC";
        r.openLocal=local(t-60000);r.closeLocal=local(t);r.side="BUY";r.leverage=n("10");
        r.quantity=n("12.3456789");r.percent=n("15");r.targetNet=n("100");
        Map<String,Object> out=service.generate(r);
        Map<?,?> calc=(Map<?,?>)out.get("calculation"),info=(Map<?,?>)out.get("generation");
        BigDecimal quantity=n(calc.get("quantity").toString()),percent=n(calc.get("percent").toString()),net=n(calc.get("net").toString());
        assertTrue(ManualOrderGenerator.withinTarget(quantity,r.quantity));
        assertTrue(ManualOrderGenerator.withinTarget(percent,r.percent));
        assertTrue(ManualOrderGenerator.withinTarget(net,r.targetNet));
        assertEquals(r.quantity,info.get("quantityTarget"));
        assertEquals(0,count("contract_order"));equal("1000",wallet());
    }
    @Test void decimalWireRoundTripPreservesPreviewAndDurableIdempotence() throws Exception {
        ManualOrderService.Request r=request(true,false);r.driver="QUANTITY";r.input=n("0.0100000000000000");r.leverage=n("10.00");r.targetNet=n("0.0800000000000000");
        r.previewToken=service.preview(r).get("previewToken").toString();
        r.input=n("1E-2");r.leverage=n("1E+1");r.targetNet=n("8E-2");
        Map<String,Object> first=service.create(r);equal("1000.08",wallet());
        r.input=n("0.0100");r.leverage=n("10.000");r.targetNet=n("0.0800");
        assertEquals(first.get("orderId"),newService(null).create(r).get("orderId"));
        Map<String,Object> legacy=new TreeMap<>(new ObjectMapper().convertValue(r,Map.class));legacy.remove("previewToken");legacy.remove("idempotencyKey");legacy.put("operator",900001L);
        String legacyHash=ReflectionTestUtils.invokeMethod(service,"digest",new ObjectMapper().writeValueAsString(legacy));
        db.update("update manual_order_record set request_hash=? where tenant_id=2 and idempotency_key=?",legacyHash,r.idempotencyKey);
        r.input=n("1E-2");assertEquals(first.get("orderId"),newService(null).create(r).get("orderId"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("900002","unused",Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        assertThrows(BusinessException.class,()->service.create(r));auth("SUPER_ADMIN");
        r.input=n("0.02");assertThrows(BusinessException.class,()->service.create(r));
        assertEquals(1,count("contract_order"));assertEquals(1,count("manual_order_record"));equal("1000.08",wallet());
    }
    @Test void equivalentNumbersDoNotBypassPreviewExpiryOrIdentity() {
        ManualOrderService.Request r=request(false,false);r.input=n("100.00");r.previewToken=service.preview(r).get("previewToken").toString();r.input=n("1E2");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("900002","unused",Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        assertThrows(BusinessException.class,()->service.create(r));auth("SUPER_ADMIN");
        Map<?,?> cache=(Map<?,?>)ReflectionTestUtils.getField(service,"previews");ReflectionTestUtils.setField(cache.get(r.previewToken),"expires",0L);
        assertThrows(BusinessException.class,()->service.create(r));assertEquals(0,count("contract_order"));equal("1000",wallet());
    }
    @Test void leverageBoundsCannotBeBypassedByPreviewOrCreate() {
        symbol.setMaxLeverage(n("5"));
        ManualOrderGenerator.Request g=new ManualOrderGenerator.Request();g.userId=1L;g.symbol="FIXTUREUSD";g.timezone="UTC";
        g.openLocal=local(t-60000);g.closeLocal=local(t);g.leverage=n("10");
        assertThrows(BusinessException.class,()->service.generate(g));
        g.leverage=n("0.5");assertThrows(BusinessException.class,()->service.generate(g));
        for(String value:Arrays.asList("0.5","5.01","100.01")) {
            ManualOrderService.Request r=request(false,false);r.leverage=n(value);
            assertThrows(BusinessException.class,()->service.preview(r));
            assertThrows(BusinessException.class,()->service.create(r));
        }
        symbol.setMaxLeverage(n("20"));
        ManualOrderService.Request r=request(false,false);r.leverage=n("10.4");
        r.previewToken=service.preview(r).get("previewToken").toString();
        // Fresh DB upper bound wins even if an administrative change forgot to increment row_version.
        db.update("update trading_symbol set max_leverage=5 where tenant_id=2 and id=20001");
        assertThrows(BusinessException.class,()->service.create(r));
        assertEquals(0,count("contract_order"));assertEquals(0,count("manual_order_record"));equal("1000",wallet());
        db.update("update trading_symbol set max_leverage=20 where tenant_id=2 and id=20001");
        service.create(r);equal("10.4",db.queryForObject("select leverage from contract_order where tenant_id=2",BigDecimal.class));
    }
    @Test void categoryDisablingLeverageIsRecheckedAtCreate() {
        com.gtcfesk.exchange.market.MarketCategoryService categories=(com.gtcfesk.exchange.market.MarketCategoryService)ReflectionTestUtils.getField(service,"categories");
        ManualOrderService.Request r=request(false,false);r.previewToken=service.preview(r).get("previewToken").toString();
        when(categories.leverageEnabled(any())).thenReturn(false);
        assertThrows(BusinessException.class,()->service.create(r));assertThrows(BusinessException.class,()->service.preview(r));
        r.leverage=BigDecimal.ONE;create(r);equal("1",db.queryForObject("select leverage from contract_order where tenant_id=2",BigDecimal.class));equal("1000",wallet());
    }
    @Test void fixedLeverageSurvivesPreviewAndCreation() {
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.timezone="UTC";
        r.openLocal=local(t-60000);r.closeLocal=local(t);r.side="BUY";r.leverage=n("10");
        r.quantity=n("0.01");r.percent=n("0.0128");r.targetNet=n("0.08");r.targetClosePrice=n("110");
        assertThrows(BusinessException.class,()->service.generate(r));
        assertEquals(0,count("contract_order"));equal("1000",wallet());
        r.percent=n("0.012");Map<String,Object> out=service.generate(r);
        Map<?,?> generated=(Map<?,?>)out.get("request"),info=(Map<?,?>)out.get("generation");
        BigDecimal actual=n(generated.get("leverage").toString());
        assertEquals(0,actual.compareTo(r.leverage));
        assertEquals(r.leverage,info.get("leverageTarget"));
        assertEquals(ManualOrderGenerator.error(actual,r.leverage),info.get("leverageErrorPercent"));
        assertEquals(0,count("contract_order"));equal("1000",wallet());
        ManualOrderService.Request create=new ObjectMapper().convertValue(generated,ManualOrderService.Request.class);
        create.previewToken=out.get("previewToken").toString();create.idempotencyKey=UUID.randomUUID().toString();
        service.create(create);assertEquals(1,count("contract_order"));equal("1000",wallet());
        equal(actual.toString(),db.queryForObject("select leverage from contract_order where tenant_id=2",BigDecimal.class));
    }
    @Test void feeDominatedAllocationStillGeneratesReadOnlyPreview() {
        symbol.setFeeMultiplier(n("10000"));
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.timezone="UTC";
        r.openLocal=local(t-60000);r.closeLocal=local(t);r.side="BUY";
        r.quantity=n("0.01");r.percent=n("9.9");r.targetNet=n("-99.9");
        Map<String,Object> out=service.generate(r);
        Map<?,?> actual=(Map<?,?>)out.get("calculation"),generated=(Map<?,?>)out.get("request");
        assertTrue(ManualOrderGenerator.withinTarget(n(actual.get("percent").toString()),r.percent));
        assertTrue(ManualOrderGenerator.withinTarget(n(actual.get("net").toString()),r.targetNet));
        assertTrue(n(generated.get("leverage").toString()).signum()>0);
        assertEquals(0,count("contract_order"));assertEquals(0,count("manual_order_record"));equal("1000",wallet());
    }
    @Test void blankCloseUsesLatestRealMinuteAndSearchesOnlyPriorThirtyDays() {
        long latest=Math.floorDiv(System.currentTimeMillis(),60000)*60000-60000,opening=latest-240*60000;
        doAnswer(inv->{long end=inv.getArgument(3);int size=inv.getArgument(2);List<Map<String,Object>> rows=new ArrayList<>();
            for(long minute:new long[]{opening,latest})if(minute<=end && minute>end-size*60000L){Map<String,Object> row=new HashMap<>();row.put("timestamp",minute);row.put("open_price",minute==opening?"100":"110");rows.add(row);}
            return Collections.singletonMap("data",Collections.singletonMap("kline_list",rows));
        }).when(market).historicalKline(eq("FIXTUREUSD"),eq("1m"),anyInt(),anyLong());
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.timezone="UTC";r.targetClosePrice=n("110");
        Map<String,Object> out=service.generate(r);
        Map<?,?> request=(Map<?,?>)out.get("request"),info=(Map<?,?>)out.get("generation");
        assertEquals(local(latest),request.get("closeLocal"));assertEquals(local(opening),request.get("openLocal"));
        assertEquals(Boolean.TRUE,info.get("closeAutomaticallySelected"));equal("110",((Map<?,?>)out.get("quotes")).get("closePrice"));
        assertEquals(0,count("contract_order"));equal("1000",wallet());
        r.targetClosePrice=n("116");assertTrue(assertThrows(BusinessException.class,()->service.generate(r)).getMessage().contains("目标平仓价"));
        r.targetClosePrice=n("110");r.quantity=n("0.01");r.targetNet=n("10000");
        assertTrue(assertThrows(BusinessException.class,()->service.generate(r)).getMessage().contains("之前 7 天没有符合条件的开仓分钟"));
        r.quantity=null;r.targetNet=null;r.openLocal=local(latest-ManualOrderGenerator.RANGE-60000);
        assertTrue(assertThrows(BusinessException.class,()->service.generate(r)).getMessage().contains("7 天内"));
        doReturn(Collections.singletonMap("data",Collections.singletonMap("kline_list",Collections.emptyList())))
            .when(market).historicalKline(eq("FIXTUREUSD"),eq("1m"),anyInt(),anyLong());
        r.openLocal=null;
        assertTrue(assertThrows(BusinessException.class,()->service.generate(r)).getMessage().contains("最近 7 天没有可用的平仓分钟行情"));
    }
    @Test void targetConstraintSurvivesNormalPreviewAndCannotBeSilentlyMissed() {
        ManualOrderService.Request r=request(false,false);r.driver="QUANTITY";r.input=n("0.01");r.targetNet=n("100");
        assertThrows(BusinessException.class,()->service.preview(r));assertEquals(0,count("contract_order"));
        r.input=n("12.50");Map<String,Object> p=service.preview(r);equal("0",(BigDecimal)((Map<?,?>)p.get("calculation")).get("difference"));
        r.targetNet=n("0");assertThrows(BusinessException.class,()->service.preview(r));
    }
    int count(String table){return db.queryForObject("select count(*) from "+table+" where tenant_id=2",Integer.class);}
    @Test void generation256MasksThroughServiceAreReadOnly() throws Exception {
        long opening=Math.floorDiv(System.currentTimeMillis(),60000)*60000-6*3600000,closing=opening+4*3600000;
        when(market.historicalKline(anyString(),eq("1m"),anyInt(),anyLong())).thenAnswer(inv->{
            long end=inv.getArgument(3);int size=inv.getArgument(2);List<Map<String,Object>> rows=new ArrayList<>();
            for(long time:new long[]{opening,closing})if(time<=end && time>end-size*60000L){Map<String,Object> row=new HashMap<>();row.put("timestamp",time);row.put("open_price",time==opening?"100":"110");rows.add(row);}
            return Collections.singletonMap("data",Collections.singletonMap("kline_list",rows));
        });
        List<String> rows=new ArrayList<>();rows.add("mask,status,milliseconds,market_calls,pairs,net,quantity,leverage,percent");
        for(int mask=0;mask<256;mask++){
            ManualOrderGenerator.Request r=ManualOrderGenerationMatrixTest.request(mask);r.userId=1L;r.symbol="FIXTUREUSD";r.timezone="UTC";
            if((mask&128)!=0)r.targetClosePrice=n("110");
            if((mask&2)!=0)r.openLocal=local(opening);if((mask&4)!=0)r.closeLocal=local(closing);
            clearInvocations(market);long started=System.nanoTime();Map<String,Object> out=service.generate(r);
            Map<?,?> calc=(Map<?,?>)out.get("calculation"),request=(Map<?,?>)out.get("request"),quotes=(Map<?,?>)out.get("quotes"),info=(Map<?,?>)out.get("generation");
            BigDecimal q=n(calc.get("quantity").toString()),l=n(request.get("leverage").toString()),p0=n(quotes.get("openPrice").toString()),p1=n(quotes.get("closePrice").toString());
            BigDecimal fee=q.multiply(n("2")),margin=q.multiply(p0).divide(l,16,java.math.RoundingMode.CEILING),net=p1.subtract(p0).multiply(q).multiply("BUY".equals(request.get("side"))?BigDecimal.ONE:BigDecimal.ONE.negate()).subtract(fee);
            equal(net.toString(),calc.get("net"));equal(fee.toString(),calc.get("fee"));equal(margin.toString(),calc.get("margin"));
            if(r.targetNet!=null)assertTrue(net.subtract(r.targetNet).abs().compareTo(r.targetNet.abs().multiply(n("0.05")))<=0);
            if(r.targetClosePrice!=null)assertTrue(ManualOrderGenerator.withinTarget(p1,r.targetClosePrice));
            if(r.quantity!=null)assertTrue(ManualOrderGenerator.withinTarget(q,r.quantity));if(r.leverage!=null)assertTrue(ManualOrderGenerator.withinTarget(l,r.leverage));
            if(r.percent!=null)assertTrue(ManualOrderGenerator.withinTarget(n(calc.get("percent").toString()),r.percent));
            if(r.side!=null)assertEquals(r.side,request.get("side"));if(r.openLocal!=null)assertEquals(r.openLocal,request.get("openLocal"));if(r.closeLocal!=null)assertEquals(r.closeLocal,request.get("closeLocal"));
            assertEquals(0,count("contract_order"));assertEquals(0,count("manual_order_record"));equal("1000",wallet());
            for(String table:Arrays.asList("asset_history_1m","asset_history_1h","asset_history_4h","asset_history_1d"))assertEquals(0,count(table));
            int calls=mockingDetails(market).getInvocations().size();assertTrue(calls<=ManualOrderGenerator.RANGE/(720*60000L)+4,"bounded thirty-day windows plus preview");
            rows.add(mask+",PASS,"+(System.nanoTime()-started)/1000000.0+","+calls+","+info.get("searchedPairs")+","+net+","+q+","+l+","+calc.get("percent"));
        }
        java.nio.file.Path dir=java.nio.file.Paths.get("target/manual-generation-matrix");java.nio.file.Files.createDirectories(dir);java.nio.file.Files.write(dir.resolve("service-matrix-256.csv"),rows,java.nio.charset.StandardCharsets.UTF_8);
    }
    @Test void generationSecurityUnknownInputsAndTimeBoundaries() throws Exception {
        ManualOrderGenerator.Request r=new ManualOrderGenerator.Request();r.userId=1L;r.symbol="FIXTUREUSD";r.timezone="UTC";r.targetNet=n("-100");
        for(String role:Arrays.asList("USER","AGENT","ADMIN")){auth(role);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.generate(r));}
        SecurityContextHolder.clearContext();assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.generate(r));auth("SUPER_ADMIN");
        ReflectionTestUtils.setField(service,"enabled",false);assertThrows(BusinessException.class,()->service.generate(r));ReflectionTestUtils.setField(service,"enabled",true);
        for(String extra:Arrays.asList("openPrice","profit","orderSource","unknown"))assertThrows(Exception.class,()->new ObjectMapper().readValue("{\""+extra+"\":1}",ManualOrderGenerator.Request.class));
        long current=Math.floorDiv(System.currentTimeMillis(),60000)*60000;
        for(long time:new long[]{current,current+60000}){r.openLocal=local(time);assertThrows(BusinessException.class,()->service.generate(r));}
        r.openLocal=local(t);r.closeLocal=local(t-60000);assertThrows(BusinessException.class,()->service.generate(r));
        ManualOrderService.Request p=request(true,false);p.previewToken=service.preview(p).get("previewToken").toString();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("900002","unused",Collections.singletonList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        assertThrows(BusinessException.class,()->service.create(p));assertEquals(0,count("contract_order"));equal("1000",wallet());
    }
    @Test void exactlyThirtyDaysFromFixedOpenIsIncluded() {
        long opening=Math.floorDiv(System.currentTimeMillis(),60000)*60000-ManualOrderGenerator.RANGE-86400000L,closing=opening+ManualOrderGenerator.RANGE;
        when(market.historicalKline(anyString(),eq("1m"),anyInt(),anyLong())).thenAnswer(inv->{
            long end=inv.getArgument(3);int size=inv.getArgument(2);List<Map<String,Object>> rows=new ArrayList<>();
            for(long time:new long[]{opening,closing})if(time<=end && time>end-size*60000L){Map<String,Object> row=new HashMap<>();row.put("timestamp",time);row.put("open_price",time==opening?"100":"110");rows.add(row);}
            return Collections.singletonMap("data",Collections.singletonMap("kline_list",rows));
        });
        ManualOrderGenerator.Request r=ManualOrderGenerationMatrixTest.request(1|8|16|32);r.userId=1L;r.symbol="FIXTUREUSD";r.timezone="UTC";r.openLocal=local(opening);
        Map<String,Object> out=service.generate(r);assertEquals(local(closing),((Map<?,?>)out.get("request")).get("closeLocal"));equal("80",((Map<?,?>)out.get("calculation")).get("net"));assertEquals(0,count("contract_order"));
    }
    @Test void differentKeysConcurrentRejectStaleThenRetryAndSurviveServiceRestart() throws Exception {
        ManualOrderService.Request a=request(true,true),b=request(true,true);a.previewToken=service.preview(a).get("previewToken").toString();b.previewToken=service.preview(b).get("previewToken").toString();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();for(ManualOrderService.Request r:Arrays.asList(a,b))futures.add(pool.submit(()->{auth("SUPER_ADMIN");try{service.create(r);return true;}catch(BusinessException e){assertTrue(e.getMessage().contains("钱包已变化"));return false;}finally{SecurityContextHolder.clearContext();}}));
            boolean first=futures.get(0).get(10,TimeUnit.SECONDS),second=futures.get(1).get(10,TimeUnit.SECONDS);assertNotEquals(first,second);
            equal("1100",wallet());assertEquals(1,count("contract_order"));create(first?b:a);equal("1200",wallet());equal("200",value(t));
            service=newService(null);service.create(a);service.create(b);equal("1200",wallet());assertEquals(2,count("contract_order"));
        }finally{pool.shutdownNow();}
    }
    @Test void threeLegalCombinationsAndDefaults() {
        point(1,t,"1000");create(request(false,false));equal("1000",wallet());equal("1000",value(t));
        create(request(true,false));equal("1100",wallet());equal("1000",value(t));
        create(request(true,true));equal("1200",wallet());equal("1100",value(t));
        assertEquals(3,count("manual_order_record"));assertEquals(3,db.queryForObject("select count(*) from contract_order where tenant_id=2 and status='CLOSED' and order_source='MANUAL_TEST' and lot_size=1 and limit_match_enabled=0",Integer.class));
        assertThrows(BusinessException.class,()->service.preview(request(false,true)));
        // Preserve the original no-DDL/no-replay invariant against the actual full migration protocol.
        assertEquals(migrationMetadata,db.queryForList("select * from asset_history_migration order by migration_id"));
        assertEquals(tenantMigrationMetadata,db.queryForList("select * from tenant_schema_version order by version"));
    }
    @Test void negativeWalletGrossFeeAndNoMarginRefund() {
        db.update("update asset_account set available=50,frozen=7 where tenant_id=2 and user_id=1");
        ManualOrderService.Request loss=request(true,false);loss.side="SELL";loss.input=n("-100");
        // D01 money invariant: a valid calculation never permits a negative available wallet.
        assertThrows(BusinessException.class,()->create(loss));equal("50",wallet());
        equal("7",db.queryForObject("select frozen from asset_account where tenant_id=2 and user_id=1",BigDecimal.class));
        assertEquals(0,count("contract_order"));assertEquals(0,count("manual_order_record"));
        ManualOrderService.Request fee=request(true,false);fee.openLocal=fee.closeLocal;fee.input=n("-100");
        assertThrows(BusinessException.class,()->create(fee));equal("50",wallet());equal("7",db.queryForObject("select frozen from asset_account where tenant_id=2 and user_id=1",BigDecimal.class));
        assertEquals(0,count("contract_order"));assertEquals(0,count("manual_order_record"));
        loss.input=n("-10");create(loss);assertTrue(wallet().signum()>=0);equal("7",db.queryForObject("select frozen from asset_account where tenant_id=2 and user_id=1",BigDecimal.class));
        assertEquals(1,count("contract_order"));assertEquals(1,count("manual_order_record"));
    }
    @Test void existingSuffixAndFinalizedBoundaryOhlc() {
        for(int m=0;m<60;m++)point(1,day+11*3600000+m*60000,"1000");
        AssetEquityJobs jobs=new AssetEquityJobs(store,new EquityValuationService(db,market));
        try {store.progress(db,"rollup_1",day+11*3600000,0,0);jobs.aggregate();}finally{jobs.stop();}
        assertTrue(count("asset_history_1h")>0);long beforeWatermark=store.state(db,"rollup_3",0)[0];create(request(true,true));
        equal("1000",value(t-60000));equal("1100",value(t));equal("1100",value(t+39*60000));
        Map<String,Object> h=db.queryForMap("select * from asset_history_1h where tenant_id=2 and user_id=1 and bucket_start=?",day+11*3600000);
        equal("1000",h.get("open_value"));equal("1100",h.get("high_value"));equal("1000",h.get("low_value"));equal("1100",h.get("close_value"));assertEquals(t+60000,((Number)h.get("high_at")).longValue());assertEquals(60L,((Number)h.get("valid_sample_count")).longValue());
        assertEquals(beforeWatermark,store.state(db,"rollup_3",0)[0]);
    }
    @ParameterizedTest @ValueSource(strings={"0","-5","800"}) void nearestValidIncludingZeroNegativeAndNullRepair(String base) {
        point(1,t-120000,base);point(1,t-60000,null);point(1,t,null);point(1,t+60000,"1050");point(2,t-60000,"99999");
        create(request(true,true));equal(n(base).add(n("100")).toString(),value(t));equal("1150",value(t+60000));assertNull(value(t-60000));assertEquals(5,count("asset_history_1m"));
        Map<String,Object> row=db.queryForMap("select * from asset_history_1m where tenant_id=2 and user_id=1 and bucket_start=?",t);assertEquals("OLD_EVIDENCE",row.get("reason_code"));assertEquals("{}",row.get("valuation_evidence"));assertEquals("MANUAL_CARRY",row.get("origin"));
        assertEquals(t+60000,store.source(db,0,Collections.singletonList(1L),t,t+60000,System.currentTimeMillis(),true).get(0).through);
    }
    @Test void inclusive24HoursZeroFallbackAndNoFutureOrOtherBasis() {
        point(1,t-86400000,"8");create(request(true,true));equal("108",value(t));
        db.update("delete from asset_history_1m where tenant_id=2 and user_id=1");point(1,t-86460000,"900");point(1,t+60000,"900");point(2,t-60000,"999");
        point(1,t-60000,"999");db.update("update asset_history_1m set basis_version='other' where tenant_id=2 and user_id=1 and bucket_start=?",t-60000);
        create(request(true,true));equal("100",value(t));assertEquals(5,count("asset_history_1m"));
        Map<String,Object> row=db.queryForMap("select * from asset_history_1m where tenant_id=2 and user_id=1 and bucket_start=?",t);assertNull(row.get("observed_at"));assertNull(row.get("quote_batch_id"));assertEquals("MANUAL_ZERO",row.get("origin"));assertTrue(((Number)row.get("created_at")).longValue()>t+86400000);
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
        assertEquals(1,db.queryForObject("select is_free_lock('tenant_2_equity_v1_capture')",Integer.class));assertEquals(1,db.queryForObject("select is_free_lock('tenant_2_equity_v1_rollup')",Integer.class));
        service=newService(null);create(r);equal("1100",wallet());equal("1100",value(t));
    }
    @Test void concurrentSameKeyOnlyOneEffect() throws Exception {
        ManualOrderService.Request r=request(true,true);r.previewToken=service.preview(r).get("previewToken").toString();ExecutorService threads=Executors.newFixedThreadPool(4);
        try {List<Future<Map<String,Object>>> futures=new ArrayList<>();for(int i=0;i<4;i++)futures.add(threads.submit(()->{auth("SUPER_ADMIN");try{return service.create(r);}finally{SecurityContextHolder.clearContext();}}));Object id=futures.get(0).get(10,TimeUnit.SECONDS).get("orderId");for(Future<Map<String,Object>> f:futures)assertEquals(id,f.get(10,TimeUnit.SECONDS).get("orderId"));}
        finally{threads.shutdownNow();}
        equal("1100",wallet());equal("100",value(t));assertEquals(1,count("contract_order"));assertEquals(1,count("manual_order_record"));
    }
    @Test void walletVersionPreviewTamperAndDisabledRoles() {
        ManualOrderService.Request r=request(true,false);r.previewToken=service.preview(r).get("previewToken").toString();db.update("update asset_account set available=available+10,row_version=row_version+1 where tenant_id=2 and user_id=1");assertThrows(BusinessException.class,()->service.create(r));equal("1010",wallet());
        r.previewToken=service.preview(r).get("previewToken").toString();r.input=n("200");assertThrows(BusinessException.class,()->service.create(r));
        ReflectionTestUtils.setField(service,"enabled",false);assertThrows(BusinessException.class,()->service.preview(r));ReflectionTestUtils.setField(service,"enabled",true);
        for(String role:Arrays.asList("USER","AGENT","ADMIN")){auth(role);assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.preview(r));}assertEquals(0,count("contract_order"));
    }
    @Test void lockBusyRejectsAndOrdinaryWalletUpdateIsNotLost() throws Exception {
        ManualOrderService.Request r=request(true,true);r.previewToken=service.preview(r).get("previewToken").toString();
        try(Connection c=source.getConnection()) {JdbcTemplate other=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(c,true));other.queryForObject("select get_lock('tenant_2_equity_v1_capture',0)",Integer.class);assertThrows(BusinessException.class,()->service.create(r));other.queryForObject("select release_lock('tenant_2_equity_v1_capture')",Integer.class);}
        assertEquals(0,count("contract_order"));service.create(r);equal("1100",wallet());
    }
    @Test void realCaptureAfterCommitNoDoubleAdditionAndManualBaselineNotPolluted() {
        create(request(true,true));AssetEquityJobs jobs=new AssetEquityJobs(store,new EquityValuationService(db,market));try{jobs.capture();}finally{jobs.stop();}
        BigDecimal latest=db.queryForObject("select net_equity from asset_history_1m where tenant_id=2 and user_id=1 order by bucket_start desc limit 1",BigDecimal.class);equal("1100",latest);
        EquityValuationService.Value duplicate=new EquityValuationService.Value(1,t+60000);duplicate.finish();store.saveMinutes(db,new EquityValuationService.Batch(),Collections.singletonList(duplicate),t+60000);
        equal("100",value(t));assertTrue(db.queryForObject("select capture_from from asset_history_baseline where tenant_id=2 and user_id=1",Long.class)>t+60000);
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
        equal("1100",db.queryForObject("select close_value from asset_history_4h where tenant_id=2 and user_id=1",BigDecimal.class));
        equal("1100",db.queryForObject("select close_value from asset_history_1d where tenant_id=2 and user_id=1",BigDecimal.class));
        assertEquals(1,count("asset_history_4h"));assertEquals(1,count("asset_history_1d"));
    }
    @Test void quoteReuseMissingPriceAndSameMinuteFees() {
        ManualOrderService.Request r=request(false,false);r.previewToken=service.preview(r).get("previewToken").toString();r.input=n("200");service.preview(r);verify(market,times(2)).historicalKline(anyString(),anyString(),anyInt(),anyLong());
        r.openLocal=r.closeLocal;r.driver="QUANTITY";r.input=n("1");Map<String,Object> out=create(r);equal("-2",((Map<?,?>)out.get("calculation")).get("net"));
        when(market.historicalKline(anyString(),anyString(),anyInt(),anyLong())).thenReturn(Collections.emptyMap());r.previewToken=null;assertThrows(BusinessException.class,()->service.preview(r));
    }
    @Test void concurrentCaptureRollupAndWalletCannotSeeHalfCommit() throws Exception {
        CountDownLatch walletWritten=new CountDownLatch(1),release=new CountDownLatch(1);
        service=new ManualOrderService(source,new ObjectMapper(),repo,new ManualOrderPrices(market),null,new ManualOrderHistory(store),(com.gtcfesk.exchange.market.MarketCategoryService)ReflectionTestUtils.getField(service,"categories")) {
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
            Future<?> ordinary=threads.submit(()->db.update("update asset_account set available=available+10,row_version=row_version+1 where tenant_id=2 and user_id=1 and coin='CONTRACT'"));
            release.countDown();created.get(10,TimeUnit.SECONDS);ordinary.get(10,TimeUnit.SECONDS);
            equal("1110",wallet());equal("100",value(t));assertEquals(2,db.queryForObject("select row_version from asset_account where tenant_id=2 and user_id=1",Integer.class));
            jobs.capture();equal("1110",db.queryForObject("select net_equity from asset_history_1m where tenant_id=2 and user_id=1 order by bucket_start desc limit 1",BigDecimal.class));
        } finally {release.countDown();threads.shutdownNow();jobs.stop();}
    }
    @Test void bounded10000PointTransactionAndOverflowRollback() {
        List<Object[]> rows=new ArrayList<>();for(int i=0;i<10000;i++)rows.add(new Object[]{t+i*60000,t+(i+1)*60000});
        db.batchUpdate("insert into asset_history_1m(tenant_id,user_id,basis_version,bucket_start,observed_at,net_equity,valuation_status,reason_code,quote_batch_id,valuation_evidence,created_at) values(2,1,'net_equity_v1',?,?,1000,'COMPLETE','','fixture','{}',0)",rows);
        long started=System.nanoTime();create(request(true,true));System.out.println("MANUAL_10000_POINT_TRANSACTION_MS="+(System.nanoTime()-started)/1000000);
        equal("1100",value(t));equal("1100",value(t+9999*60000));assertEquals(10000,count("asset_history_1m"));
        point(1,t+10000*60000,"1000");assertThrows(BusinessException.class,()->service.preview(request(true,true)));assertEquals(1,count("contract_order"));
        db.update("delete from asset_history_1m where tenant_id=2 and bucket_start<>?",t);db.update("update asset_history_1m set net_equity=9999999999999999 where tenant_id=2");
        ManualOrderService.Request r=request(true,true);r.previewToken=service.preview(r).get("previewToken").toString();assertThrows(BusinessException.class,()->service.create(r));equal("1100",wallet());assertEquals(1,count("manual_order_record"));
    }
    @Test void expiredPreviewAndHistoricalFxAreRejected() {
        ManualOrderService.Request r=request(true,false);r.previewToken=service.preview(r).get("previewToken").toString();
        Map<?,?> previews=(Map<?,?>)ReflectionTestUtils.getField(service,"previews");ReflectionTestUtils.setField(previews.get(r.previewToken),"expires",0L);assertThrows(BusinessException.class,()->service.create(r));
        symbol.setQuoteCurrency("EUR");r.previewToken=null;assertThrows(BusinessException.class,()->service.preview(r));assertEquals(0,count("contract_order"));equal("1000",wallet());
    }
    @Test void existingRealPositionIsNotTouchedAndManualCreationIsAllowed() {
        db.update("insert into contract_order(tenant_id,user_id,symbol,side,type,status,quantity,leverage,lot_size,margin,fee,open_price,row_version,limit_match_enabled,created_at,updated_at) values(2,1,'FIXTUREUSD','BUY','MARKET','OPEN',1,10,1,10,2,100,0,0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        db.update("update asset_account set frozen=10 where tenant_id=2 and user_id=1");
        Map<String,Object> original=db.queryForMap("select * from contract_order where tenant_id=2 and status='OPEN'");
        create(request(true,true));assertEquals(original,db.queryForMap("select * from contract_order where tenant_id=2 and status='OPEN'"));
        equal("10",db.queryForObject("select frozen from asset_account where tenant_id=2 and user_id=1",BigDecimal.class));equal("1100",wallet());
        assertEquals(1,db.queryForObject("select count(*) from contract_order where tenant_id=2 and order_source='USER'",Integer.class));
    }
    @Test void allFourTablesCrossDayNegativeAdjustmentAndClippedSyntheticMinute() {
        long next=day+86400000;
        point(1,t-60000,"1000");point(1,t,"1000");point(1,t+39*60000,"1050");
        point(1,next-60000,"900");point(1,next,"800");point(1,next+4*86400000,"700");
        for(int level=1;level<=3;level++)store.progress(db,"rollup_"+level,next+5*86400000,0,0);
        create(request(true,true));
        Map<String,Object> four=db.queryForMap("select * from asset_history_4h where tenant_id=2 and user_id=1 and bucket_start=?",day+8*3600000);
        equal("1000",four.get("open_value"));equal("1150",four.get("high_value"));equal("1000",four.get("low_value"));equal("1150",four.get("close_value"));
        assertEquals(3L,((Number)four.get("valid_sample_count")).longValue());
        Map<String,Object> daily=db.queryForMap("select * from asset_history_1d where tenant_id=2 and user_id=1 and bucket_start=?",day);
        equal("1000",daily.get("open_value"));equal("1150",daily.get("high_value"));equal("1000",daily.get("close_value"));
        equal("900",db.queryForObject("select close_value from asset_history_1d where tenant_id=2 and user_id=1 and bucket_start=?",BigDecimal.class,next));
        assertEquals(3,count("asset_history_1d")); // No calendar fill between next day and day five.
        ManualOrderService.Request loss=request(true,true);loss.openLocal=loss.closeLocal;loss.input=n("-100");create(loss);
        equal("1000",value(t));equal("800",value(next));equal("1000",wallet());
        equal("1050",db.queryForObject("select high_value from asset_history_4h where tenant_id=2 and user_id=1 and bucket_start=?",BigDecimal.class,day+8*3600000));
        // A missing minute becomes an estimated point with a logical time, not an observation.
        ManualOrderService.Request missing=request(true,true);missing.openLocal=local(t);missing.closeLocal=local(t+60000);missing.input=n("-100");create(missing);
        assertNull(db.queryForObject("select observed_at from asset_history_1m where tenant_id=2 and user_id=1 and bucket_start=?",Long.class,t+60000));
        List<AssetHistoryBucket> clipped=store.window(db,1,1,t+60001,t+120001,System.currentTimeMillis());
        assertEquals(1,clipped.size());equal("900",clipped.get(0).close);assertEquals(t+120000,clipped.get(0).through);
    }

}

package com.gtcfesk.exchange.trade;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.control.ControlIdentity;
import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.user.*;
import com.gtcfesk.exchange.market.MarketCategoryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.File;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Explicit opt-in only: a disposable migrated localhost mt705 clone, never the application database. */
@EnabledIfSystemProperty(named="manual.control.fixture",matches=".+")
class ManualControlJdbcMySqlTest {
 static DriverManagerDataSource source;static JdbcTemplate db;long user,actor,minute;String symbolName;ManualOrderService service;
 @BeforeAll @SuppressWarnings("unchecked") static void connect()throws Exception{
  Map<String,String> c=new ObjectMapper().readValue(new File(System.getProperty("manual.control.fixture")),Map.class);
  assertTrue(c.get("url").matches("jdbc:mysql://127\\.0\\.0\\.1:(64029|33318|33418)/mt705_probe_[a-zA-Z0-9_]+\\?.*"));
  source=com.gtcfesk.exchange.tenant.DedicatedMysqlFixture.open(c);db=new JdbcTemplate(source);assertTrue(db.queryForObject("select database()",String.class).startsWith("mt705_probe_"));
 }
 @BeforeEach void setup(){
  TenantContext.open(2L);String unique=UUID.randomUUID().toString();String email=unique+"@test.invalid";
  db.update("insert into user_account(tenant_id,email,password_hash,user_type,status,created_at,updated_at,row_version) values(2,?,'not-a-login','user','normal',UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",email);
  user=db.queryForObject("select id from user_account where tenant_id=2 and email=?",Long.class,email);
  db.update("insert into asset_account(tenant_id,user_id,coin,available,frozen,created_at,updated_at,row_version) values(2,?,'CONTRACT',1000,0,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",user);
  db.update("insert into control_admin(account,password_hash,enabled,mfa_enabled,session_version,row_version) values(?,'not-a-login',1,0,0,0)",unique);actor=db.queryForObject("select id from control_admin where account=?",Long.class,unique);control();
  symbolName="AUDIT"+unique.substring(0,8);db.update("insert into trading_symbol(tenant_id,symbol,name,base_currency,quote_currency,market_source,source_category,category,lot_size,fee_multiplier,max_leverage,is_enabled,control_enabled,created_at,updated_at,row_version) values(2,?,?,'BTC','USD','yahoo','Crypto','Crypto',1,2,100,1,0,UTC_TIMESTAMP(),UTC_TIMESTAMP(),0)",symbolName,symbolName);
  TradingSymbol symbol=new TradingSymbol();symbol.setId(db.queryForObject("select id from trading_symbol where tenant_id=2 and symbol=?",Long.class,symbolName));symbol.setSymbol(symbolName);symbol.setIsEnabled(true);symbol.setCategory("Crypto");symbol.setSourceCategory("Crypto");symbol.setBaseCurrency("BTC");symbol.setQuoteCurrency("USD");symbol.setMarketSource("yahoo");symbol.setLotSize(BigDecimal.ONE);symbol.setFeeMultiplier(new BigDecimal("2"));symbol.setMaxLeverage(new BigDecimal("100"));symbol.setRowVersion(0);
  TradingSymbolRepository repository=mock(TradingSymbolRepository.class);when(repository.findByTenantIdAndSymbol(2L,symbolName)).thenReturn(Optional.of(symbol));
  ManualOrderPrices prices=mock(ManualOrderPrices.class);Map<String,Object> quote=new HashMap<>();quote.put("openPrice",new BigDecimal("100"));quote.put("closePrice",new BigDecimal("110"));quote.put("openRate",BigDecimal.ONE);quote.put("closeRate",BigDecimal.ONE);quote.put("marginRate",new BigDecimal("100"));when(prices.quote(any(),anyLong(),anyLong())).thenReturn(quote);
  MarketCategoryService categories=mock(MarketCategoryService.class);when(categories.leverageEnabled(anyString())).thenReturn(true);
  service=new ManualOrderService(source,new ObjectMapper(),repository,prices,null,new ManualOrderHistory(new AssetEquityStore(source,new ObjectMapper())),categories);ReflectionTestUtils.setField(service,"enabled",true);minute=Math.floorDiv(System.currentTimeMillis(),60000)*60000-60000;
 }
 void control(){UsernamePasswordAuthenticationToken a=new UsernamePasswordAuthenticationToken("-"+actor,null,Arrays.asList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),new SimpleGrantedAuthority("ROLE_CONTROL_ACCESS")));a.setDetails(new ControlIdentity(actor,2L,"manual-test-"+actor));SecurityContextHolder.getContext().setAuthentication(a);}
 @AfterEach void clear(){SecurityContextHolder.clearContext();TenantContext.clear();}
 ManualOrderService.Request request(boolean history){ManualOrderService.Request r=new ManualOrderService.Request();r.userId=user;r.symbol=symbolName;r.side="BUY";r.timezone="UTC";r.openLocal=LocalDateTime.ofInstant(Instant.ofEpochMilli(minute-60000),ZoneOffset.UTC).toString();r.closeLocal=LocalDateTime.ofInstant(Instant.ofEpochMilli(minute),ZoneOffset.UTC).toString();r.driver="QUANTITY";r.input=BigDecimal.ONE;r.leverage=BigDecimal.TEN;r.walletEnabled=true;r.historyEnabled=history;r.idempotencyKey=UUID.randomUUID().toString();r.previewToken=service.preview(r).get("previewToken").toString();return r;}
 BigDecimal wallet(){return db.queryForObject("select available from asset_account where tenant_id=2 and user_id=? and coin='CONTRACT'",BigDecimal.class,user);}
 long count(String table){return db.queryForObject("select count(*) from "+table+" where tenant_id=2 and user_id=?",Long.class,user);}
 long audits(){return db.queryForObject("select count(*) from control_audit_log where actor_id=? and tenant_id=2 and access_session_id=? and action='MANUAL_ORDER_CREATE'",Long.class,actor,"manual-test-"+actor);}
 @Test void durableReplayPreservesRealActorAndMutatesWalletOnce(){ManualOrderService.Request r=request(true);Map<String,Object> first=service.create(r);assertEquals(first.get("orderId"),service.create(r).get("orderId"));assertEquals(0,new BigDecimal("1008").compareTo(wallet()));assertEquals(1,count("contract_order"));assertEquals(1,count("manual_order_record"));assertEquals(1,audits());String evidence=db.queryForObject("select evidence from manual_order_record where tenant_id=2 and user_id=?",String.class,user);assertTrue(evidence.contains("\"actorType\":\"CONTROL\""));assertTrue(evidence.contains("\"actorId\":"+actor));}
 @Test void rejectedAuditInsertRollsBackOrderWalletAndHistory(){ManualOrderService.Request r=request(true);String trigger="manual_test_fail_"+actor;db.execute("create trigger "+trigger+" before insert on control_audit_log for each row begin if NEW.actor_id="+actor+" then signal sqlstate '45000' set message_text='test audit rejected'; end if; end");try{assertThrows(RuntimeException.class,()->service.create(r));assertEquals(0,new BigDecimal("1000").compareTo(wallet()));assertEquals(0,count("contract_order"));assertEquals(0,count("manual_order_record"));assertEquals(0,count("asset_history_1m"));assertEquals(0,audits());}finally{db.execute("drop trigger "+trigger);}service.create(r);assertEquals(1,audits());}
 @Test void concurrentSameKeyCommitsOnce()throws Exception{ManualOrderService.Request r=request(false);ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);List<Future<?>> calls=new ArrayList<>();try{for(int i=0;i<2;i++)calls.add(pool.submit(()->{try(TenantContext.Scope scope=TenantContext.open(2L)){control();start.await();service.create(r);}catch(InterruptedException e){throw new RuntimeException(e);}finally{SecurityContextHolder.clearContext();}}));start.countDown();for(Future<?> f:calls)f.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(1,count("contract_order"));assertEquals(1,audits());assertEquals(0,new BigDecimal("1008").compareTo(wallet()));}
 @Test void mismatchedTenantAndOtherActorCannotReplay(){ManualOrderService.Request r=request(false);service.create(r);actor++;control();assertThrows(RuntimeException.class,()->service.create(r));actor--;TenantContext.clear();try(TenantContext.Scope ignored=TenantContext.open(3L)){control();assertThrows(RuntimeException.class,()->service.create(r));}assertEquals(0,new BigDecimal("1008").compareTo(wallet()));}
}

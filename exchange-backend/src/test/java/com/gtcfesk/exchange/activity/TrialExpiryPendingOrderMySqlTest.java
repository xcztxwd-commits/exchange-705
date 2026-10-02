package com.gtcfesk.exchange.activity;

import com.fasterxml.jackson.databind.*;
import com.gtcfesk.exchange.ExchangeBackendApplication;
import com.gtcfesk.exchange.admin.AdminUser;
import com.gtcfesk.exchange.control.*;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.trade.ContractOrderService;
import com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest;
import org.junit.jupiter.api.*;
import com.gtcfesk.exchange.tenant.DedicatedMysqlFixture;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.*;
import javax.sql.DataSource;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Dedicated real MySQL/Redis/TCP/JWT probe. Only clock and quote INPUTS are synthetic.
 * All production financial services, guards, transactions, row locks and business jobs run. */
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
 classes={ExchangeBackendApplication.class,TrialExpiryPendingOrderMySqlTest.Inputs.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TrialExpiryPendingOrderMySqlTest {
 static final Instant BASE=Instant.parse("2026-10-01T00:00:00Z");
 static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
 static final String PASSWORD="T02-synthetic-test-only!";
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r) throws Exception {
  DriverManagerDataSource verified=DedicatedMysqlFixture.fromProperty("stage2.mysql.fixture");
  Path privateDirectory=Paths.get(System.getProperty("stage2.mysql.fixture")).getParent();
  Properties configuration=new Properties();
  try(Reader input=Files.newBufferedReader(privateDirectory.resolve("application.properties"))){configuration.load(input);}
  configuration.forEach((key,value)->r.add(key.toString(),()->value));
  r.add("spring.datasource.url",verified::getUrl);r.add("spring.datasource.username",verified::getUsername);r.add("spring.datasource.password",verified::getPassword);
  r.add("spring.jpa.hibernate.ddl-auto",()->"validate");r.add("spring.jpa.open-in-view",()->"false");
  r.add("server.port",()->"0");r.add("platform.bootstrap.enabled",()->"false");
  r.add("platform.base-domain",()->"t02.test");r.add("platform.admin-origin",()->"https://admin.t02.test");r.add("platform.control-origin",()->"https://control.t02.test");
  r.add("logging.level.root",()->"WARN");r.add("spring.jmx.enabled",()->"false");
 }
 @TestConfiguration static class Inputs {
  @Bean @Primary MutableClock t02Clock(){return new MutableClock();}
  @Bean @Primary QuoteInput t02QuoteInput(){return new QuoteInput();}
 }
 static class MutableClock extends Clock {
  final AtomicReference<Instant> value=new AtomicReference<>(BASE);
  public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){if(!ZoneOffset.UTC.equals(zone))throw new IllegalArgumentException("UTC probe");return this;}
  public Instant instant(){return value.get();}void at(long days){value.set(BASE.plusSeconds(days*86400));}
 }
 static class QuoteInput extends ForexQuoteMarketService {
  final Map<String,BigDecimal> prices=new ConcurrentHashMap<>();
  final ThreadLocal<Map<String,BigDecimal>> callerPrices=new ThreadLocal<>();
  void matcherQuote(String symbol,String value,Runnable work){
   Map<String,BigDecimal> prior=callerPrices.get();callerPrices.set(Collections.singletonMap(key(symbol),n(value)));
   try{work.run();}finally{if(prior==null)callerPrices.remove();else callerPrices.set(prior);}
  }
  String key(String symbol){return TenantContext.requireTenantId()+":"+symbol;}
  void price(String symbol,String value){prices.put(key(symbol),n(value));}
  @Override public void start(){}@Override public void stop(){}@Override public void requestSymbolRefresh(){}@Override public synchronized void refreshSymbols(){}
  @Override public BigDecimal freshPrice(String symbol){Map<String,BigDecimal> pulse=callerPrices.get();String key=key(symbol);return pulse!=null&&pulse.containsKey(key)?pulse.get(key):prices.get(key);}
  @Override public boolean knownSymbol(String symbol){return prices.containsKey(key(symbol));}
  @Override public Map<String,BigDecimal> freshPrices(){String prefix=TenantContext.requireTenantId()+":";Map<String,BigDecimal> out=new HashMap<>();prices.forEach((k,v)->{if(k.startsWith(prefix))out.put(k.substring(prefix.length()),v);});return out;}
  Map<String,Object> quote(String symbol){Map<String,Object> out=new HashMap<>();BigDecimal price=freshPrice(symbol);out.put("price",price);out.put("available",price!=null);out.put("tradeAvailable",price!=null);out.put("status",price==null?"unavailable":"available");out.put("timestamp",System.currentTimeMillis());out.put("expiresAt",System.currentTimeMillis()+60000);return out;}
  @Override public Map<String,Object> getPrice(String symbol,String category){return quote(symbol);}
  @Override public Map<String,Object> internalPrice(String symbol){return quote(symbol);}
  @Override public synchronized Map<String,Object> snapshotPrice(String symbol){return quote(symbol);}
 }
 @Autowired DataSource dataSource;@Autowired PlatformTransactionManager manager;
 @Autowired TrialFunds funds;@Autowired ContractOrderService contracts;@Autowired UserAccountRepository users;
 @Autowired TrialAccountRepository trials;@Autowired MutableClock clock;@Autowired QuoteInput quotes;
 @Autowired PasswordEncoder passwords;@Autowired org.springframework.context.ApplicationContext context;
 @PersistenceContext EntityManager em;@LocalServerPort int port;
 TransactionTemplate tx;TenantContext.Scope scope;String hash;long user;String symbol;
 @BeforeAll void seedTenants() throws Exception {
  assertEquals("true",System.getProperty("sun.net.http.allowRestrictedHeaders"),"Set the fork JVM property before HttpURLConnection caches restricted Host handling");TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
  tx=new TransactionTemplate(manager);hash=passwords.encode(PASSWORD);
  try(Connection c=dataSource.getConnection()){assertEquals("MySQL",c.getMetaData().getDatabaseProductName());event("database",Collections.singletonMap("url",c.getMetaData().getURL()));}
  assertFalse(org.mockito.Mockito.mockingDetails(funds).isMock());assertFalse(org.mockito.Mockito.mockingDetails(contracts).isMock());
  assertFalse(org.mockito.Mockito.mockingDetails(context.getBean(com.gtcfesk.exchange.market.MarketOrderProcessor.class)).isMock());
  assertEquals("PONG",context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class).getConnectionFactory().getConnection().ping());
  for(long id:new long[]{2L,3L}){final long tenant=id;tx.execute(s->{Tenant t=em.find(Tenant.class,tenant,LockModeType.PESSIMISTIC_WRITE);assertNotNull(t);assertEquals(tenant==2L?"stage2_money_a":"stage2_money_b",t.getCode());assertEquals("MAINTENANCE",t.getStatus());assertEquals(0L,((Number)em.createNativeQuery("SELECT COUNT(*) FROM user_account WHERE tenant_id=?1").setParameter(1,tenant).getSingleResult()).longValue(),"Trial suite requires its own fresh clone, not another suite data reset");t.setName("T02 synthetic "+tenant);t.setFrontendHost("t"+tenant+".t02.test");t.setStatus("ACTIVE");t.setDomainVerified(true);t.setConfigReady(true);em.flush();assertEquals(tenant,t.getId().longValue());return null;});
   try(TenantContext.Scope ignored=TenantContext.open(tenant)){tx.execute(s->{AdminUser a=new AdminUser();a.setTenantId(tenant);a.setAccount("t02admin"+tenant);a.setEmail("t02admin"+tenant+"@example.invalid");a.setPasswordHash(hash);a.setRole("super_admin");em.persist(a);em.flush();BackendLogin b=new BackendLogin();b.setTenantId(tenant);b.setSubjectType("ADMIN");b.setNormalizedAccount(a.getEmail());b.setAdminUserId(a.getId());em.persist(b);
    em.createNativeQuery("INSERT INTO tenant_policy(tenant_id,policy_key,policy_value,locked,version) VALUES(?1,'feature.contract','true',false,0)").setParameter(1,tenant).executeUpdate();
    for(String[] entry:new String[][]{{"site.name","T02 isolated test"},{"system.timezone","UTC"}})em.createNativeQuery("INSERT INTO system_config(tenant_id,config_key,config_value,created_at,updated_at) VALUES(?1,?2,?3,UTC_TIMESTAMP(),UTC_TIMESTAMP())").setParameter(1,tenant).setParameter(2,entry[0]).setParameter(3,entry[1]).executeUpdate();return null;});}
  }
  event("real-services",Collections.singletonMap("businessTimerRunning",Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().equals("market-orders"))));
 }
 @BeforeEach void setup(){scope=TenantContext.open(2L);clock.at(0);user=newUser();symbol="T02BTC"+user;quotes.price(symbol,"100");tx.execute(s->{TradingSymbol v=new TradingSymbol();v.setTenantId(2L);v.setSymbol(symbol);v.setName("T02 deterministic quote input");v.setBaseCurrency("BTC");v.setQuoteCurrency("USD");v.setCategory("Crypto");v.setSourceCategory("Crypto");v.setMarketSource("BINANCE");v.setLotSize(n("1"));v.setFeeMultiplier(n("1"));v.setMaxLeverage(n("10"));v.setQuantityUnitType("BASE_ASSET");v.setSpecVersion(1L);v.setMinOrderQuantity(n("0.01"));v.setQuantityStep(n("0.01"));v.setMinOrderNotional(n("1"));em.persist(v);return null;});}
 @AfterEach void end(){if(scope!=null)scope.close();}
 long newUser(){return tx.execute(s->{UserAccount u=new UserAccount();u.setTenantId(TenantContext.requireTenantId());u.setEmail("t02-"+UUID.randomUUID()+"@example.invalid");u.setPasswordHash(hash);u.setKycStatus("VERIFIED");u.setKycLevel(1);em.persist(u);em.flush();for(String coin:Arrays.asList("CONTRACT","OPTION","FUND")){AssetAccount a=new AssetAccount();a.setTenantId(TenantContext.requireTenantId());a.setUserId(u.getId());a.setCoin(coin);a.setAvailable(n("1000"));em.persist(a);}KycRecord k=new KycRecord();k.setTenantId(TenantContext.requireTenantId());k.setUserId(u.getId());k.setRealName("T02 synthetic");k.setIdNumber("TEST-"+u.getId());k.setStatus("APPROVED");em.persist(k);return u.getId();});}
 static BigDecimal n(String v){return new BigDecimal(v);}static void money(String expected,Object actual){assertEquals(0,n(expected).compareTo(new BigDecimal(actual.toString())),"money expected "+expected+" actual "+actual);}
 void grant(String amount,String key,int days){funds.grant(user,n(amount),null,null,key,days);}
 CreateContractOrderRequest request(String type,String source,String qty){CreateContractOrderRequest r=new CreateContractOrderRequest();r.setSymbol(symbol);r.setType(type);r.setSide("BUY");r.setQuantity(n(qty));r.setPrice(n("90"));r.setLeverage(n("10"));r.setFundingSource(source);r.setSpecVersion(1L);r.setQuantityUnitType("BASE_ASSET");return r;}
 ContractOrder order(String type,String source){return contracts.createOrder(user,request(type,source,"1"));}
 List<Map<String,Object>> sql(String statement,Object...args){try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(statement)){c.setAutoCommit(true);for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);try(ResultSet r=p.executeQuery()){List<Map<String,Object>> out=new ArrayList<>();ResultSetMetaData m=r.getMetaData();while(r.next()){Map<String,Object> row=new LinkedHashMap<>();for(int i=1;i<=m.getColumnCount();i++)row.put(m.getColumnLabel(i),r.getObject(i));out.add(row);}return out;}}catch(SQLException e){throw new IllegalStateException(e);}}
 Map<String,Object> account(){return sql("SELECT available,frozen,expired,profits,trial_eligible,row_version FROM trial_account WHERE tenant_id=2 AND user_id=?",user).get(0);}
 String state(long id){return sql("SELECT status FROM contract_order WHERE tenant_id=2 AND id=?",id).get(0).get("status").toString();}
 long reasons(String prefix,long id){return ((Number)sql("SELECT COUNT(*) AS n FROM trial_ledger WHERE tenant_id=2 AND user_id=? AND reason=?",user,prefix+id).get(0).get("n")).longValue();}
 void proof(String name){Map<String,Object> p=new LinkedHashMap<>();p.put("user",user);p.put("account",sql("SELECT * FROM trial_account WHERE user_id=?",user));p.put("grants",sql("SELECT * FROM trial_grant WHERE user_id=? ORDER BY id",user));p.put("orders",sql("SELECT id,tenant_id,user_id,status,funding_source,trial_reserved,trial_allocations,margin,fee,open_price,close_price FROM contract_order WHERE user_id=? ORDER BY id",user));p.put("ledger",sql("SELECT id,tenant_id,user_id,reason,available,frozen,delta FROM trial_ledger WHERE user_id=? ORDER BY id",user));p.put("cash",sql("SELECT coin,available,frozen FROM asset_account WHERE user_id=? ORDER BY coin",user));event(name,p);}
 synchronized void event(String kind,Object data){try{Map<String,Object> out=new LinkedHashMap<>();out.put("kind",kind);out.put("thread",Thread.currentThread().getName());out.put("at",Instant.now().toString());out.put("data",data);Files.write(Paths.get(System.getenv("T02_EXPIRY_EVIDENCE"),"sql-http-events.jsonl"),(JSON.writeValueAsString(out)+"\n").getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(IOException e){throw new IllegalStateException(e);}}
 @Test @Order(1) void refreshDoesNotEraseExpiryCancellation(){grant("300","refresh",3);ContractOrder p=order("LIMIT","TRIAL");Map<String,Object> trace=new LinkedHashMap<>();tx.execute(s->{users.lockById(user).get();ContractOrder loaded=em.find(ContractOrder.class,p.getId());assertEquals("PENDING",loaded.getStatus());clock.at(3);funds.lock(user);trace.put("afterMaintenance",loaded.getStatus());trace.put("otherConnectionBeforeCommit",state(p.getId()));em.refresh(loaded,LockModeType.PESSIMISTIC_WRITE);trace.put("afterRefresh",loaded.getStatus());return null;});trace.put("freshDatabaseAfterCommit",state(p.getId()));event("refresh-interleaving",trace);proof("refresh-committed");assertEquals("CANCELLED",trace.get("afterMaintenance"));assertEquals("PENDING",trace.get("otherConnectionBeforeCommit"),"Flush must not expose an uncommitted partial cancellation");assertEquals("CANCELLED",trace.get("afterRefresh"));assertEquals("CANCELLED",state(p.getId()));money("0",account().get("frozen"));money("300",account().get("expired"));assertEquals(1,reasons("TRIAL_EXPIRED_CANCEL:",p.getId()));}
 @Test @Order(2) void productionMatcherAndRealHttpPreserveD01()throws Exception {grant("300","http-D01",3);String token=login();long open=httpOrder(token,"MARKET","TRIAL"),pending=httpOrder(token,"LIMIT","TRIAL");tx.execute(s->{users.lockById(user).get();clock.at(3);contracts.matchPendingLimitOrders();return null;});proof("D01-after-production-matcher");assertEquals("CANCELLED",state(pending));assertEquals("OPEN",state(open));money("0",account().get("available"));money("11",account().get("frozen"));money("289",account().get("expired"));assertEquals(1,reasons("TRIAL_EXPIRED_CANCEL:",pending));assertEquals(0,reasons("CONTRACT_CANCEL:",pending));
  JsonNode assets=http("GET","/api/user/assets",token,null,1,200);assertFalse(assets.path("trialEligible").asBoolean());money("11",assets.path("trialFrozen").decimalValue());
  JsonNode orders=http("GET","/api/trade/contract/orders",token,null,1,200);assertEquals("CANCELLED",findOrder(orders,pending).path("status").asText());assertEquals("OPEN",findOrder(orders,open).path("status").asText());
  for(int i=0;i<3;i++){funds.snapshot(user);contracts.matchPendingLimitOrders();}// A matcher-only synthetic pulse: publishing 90 globally correctly liquidates the open 10-margin position.
  // Keep the real timer running on the stable input; this assertion tests expired pending matching, not liquidation.
  quotes.matcherQuote(symbol,"90",()->contracts.matchPendingLimitOrders());assertEquals("CANCELLED",state(pending));assertEquals(1,reasons("TRIAL_EXPIRED_CANCEL:",pending));
  assertFalse(http("POST","/api/trade/contract/order",token,request("MARKET","TRIAL","1"),1,400).path("success").asBoolean());
  quotes.price(symbol,"110");assertTrue(http("POST","/api/trade/contract/order/"+open+"/close",token,Collections.emptyMap(),1,200).path("success").asBoolean());proof("D01-after-real-HTTP-close");assertEquals("CLOSED",state(open));money("0",account().get("available"));money("0",account().get("frozen"));money("300",account().get("expired"));money("9",account().get("profits"));money("1009",sql("SELECT available FROM asset_account WHERE user_id=? AND coin='CONTRACT'",user).get(0).get("available"));assertEquals(1,reasons("CONTRACT_SETTLE:",open));
  http("POST","/api/trade/contract/order/"+open+"/close",token,Collections.emptyMap(),1,400);http("POST","/api/trade/contract/order/"+pending+"/cancel",token,null,1,400);assertEquals(1,reasons("CONTRACT_SETTLE:",open));
  long cash=httpOrder(token,"MARKET","CONTRACT");assertEquals("OPEN",state(cash));http("GET","/api/trade/contract/orders",token,null,2,401);http("GET","/api/trade/contract/orders",null,null,1,401);proof("D01-final");
 }
 @Test @Order(3) void bothManualCancelEntrypointsHonorExpiry(){for(boolean admin:new boolean[]{false,true}){if(admin){clock.at(0);user=newUser();}grant("300","manual-"+admin,3);ContractOrder p=order("LIMIT","TRIAL");tx.execute(s->{users.lockById(user).get();clock.at(3);if(admin)contracts.adminCancelOrder(p.getId());else contracts.cancelOrder(user,p.getId());return null;});proof("manual-at-expiry-"+admin);assertEquals("CANCELLED",state(p.getId()));money("0",account().get("available"));money("0",account().get("frozen"));money("300",account().get("expired"));assertEquals(1,reasons("TRIAL_EXPIRED_CANCEL:",p.getId()));assertEquals(0,reasons("CONTRACT_CANCEL:",p.getId()));}}
 @Test @Order(4) void adminCancelsActiveTrialAndCashWithoutLeakingReservation(){grant("300","admin-active",3);ContractOrder trial=order("LIMIT","TRIAL"),cash=order("LIMIT","CONTRACT");contracts.adminCancelOrder(trial.getId());contracts.adminCancelOrder(cash.getId());proof("admin-active-and-cash");assertEquals("CANCELLED",state(trial.getId()));assertEquals("CANCELLED",state(cash.getId()));money("300",account().get("available"));money("0",account().get("frozen"));money("0",account().get("expired"));money("1000",sql("SELECT available FROM asset_account WHERE user_id=? AND coin='CONTRACT'",user).get(0).get("available"));money("0",sql("SELECT frozen FROM asset_account WHERE user_id=? AND coin='CONTRACT'",user).get(0).get("frozen"));assertEquals(1,reasons("CONTRACT_CANCEL:",trial.getId()));}
 @Test @Order(5) void partialExpiryLeavesActiveGrantCashAndOtherTenantUntouched(){grant("15","partial-old",3);ContractOrder open=order("MARKET","TRIAL");clock.at(1);grant("100","partial-new",3);ContractOrder mixed=contracts.createOrder(user,request("LIMIT","TRIAL","2")),active=order("LIMIT","TRIAL"),cash=order("LIMIT","CONTRACT");
  long other;scope.close();scope=null;try(TenantContext.Scope ignored=TenantContext.open(3L)){other=newUser();funds.grant(other,n("700"),null,null,"tenant-two",10);}finally{scope=TenantContext.open(2L);}List<Map<String,Object>> otherBefore=sql("SELECT available,frozen,expired,trial_eligible,row_version FROM trial_account WHERE tenant_id=3 AND user_id=?",other);
  tx.execute(s->{users.lockById(user).get();clock.at(3);funds.lock(user);return null;});proof("partial-expiry");assertEquals("CANCELLED",state(mixed.getId()));assertEquals("PENDING",state(active.getId()));assertEquals("PENDING",state(cash.getId()));assertEquals("OPEN",state(open.getId()));money("90",account().get("available"));money("21",account().get("frozen"));money("4",account().get("expired"));assertEquals(1,reasons("TRIAL_EXPIRED_CANCEL:",mixed.getId()));assertEquals(0,reasons("TRIAL_EXPIRED_CANCEL:",active.getId()));assertEquals(otherBefore,sql("SELECT available,frozen,expired,trial_eligible,row_version FROM trial_account WHERE tenant_id=3 AND user_id=?",other));
  for(int i=0;i<3;i++)funds.snapshot(user);money("90",account().get("available"));money("21",account().get("frozen"));scope.close();scope=null;try(TenantContext.Scope ignored=TenantContext.open(3L)){assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->contracts.cancelOrder(user,mixed.getId()));}finally{scope=TenantContext.open(2L);}assertEquals("CANCELLED",state(mixed.getId()));
  quotes.price(symbol,"110");contracts.closeOrder(user,open.getId(),null);money("15",account().get("expired"));money("90",account().get("available"));money("10",account().get("frozen"));proof("partial-open-exit");
 }
 @Test @Order(6) void expiryWritesRollbackAtomically(){grant("300","rollback",3);ContractOrder p=order("LIMIT","TRIAL");assertThrows(IllegalStateException.class,()->tx.execute(s->{users.lockById(user).get();clock.at(3);funds.lock(user);clock.at(0);throw new IllegalStateException("intentional rollback after expiry writes");}));proof("expiry-rollback");assertEquals("PENDING",state(p.getId()));money("290",account().get("available"));money("10",account().get("frozen"));money("0",account().get("expired"));assertEquals(0,reasons("TRIAL_EXPIRED_CANCEL:",p.getId()));contracts.cancelOrder(user,p.getId());}
 @RepeatedTest(5) @Order(7) void concurrentMatcherMaintenanceAndDuplicateCancel()throws Exception {
  grant("300","race",3);ContractOrder p=order("LIMIT","TRIAL");ExecutorService workers=Executors.newFixedThreadPool(4);CountDownLatch loaded=new CountDownLatch(4),go=new CountDownLatch(1);List<Future<String>> calls=new ArrayList<>();
  try{tx.execute(s->{users.lockById(user).get();clock.at(3);for(int i=0;i<4;i++){final int action=i;calls.add(workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(2L)){try{return tx.execute(st->{em.find(ContractOrder.class,p.getId());loaded.countDown();await(go);switch(action){case 0:contracts.matchPendingLimitOrders();break;case 1:funds.snapshot(user);break;case 2:contracts.cancelOrder(user,p.getId());break;default:contracts.adminCancelOrder(p.getId());}return "committed";});}catch(com.gtcfesk.exchange.common.BusinessException|org.springframework.dao.OptimisticLockingFailureException expected){return expected.getClass().getSimpleName();}}}));}await(loaded);go.countDown();return null;});
   List<String> results=new ArrayList<>();for(Future<String> f:calls)results.add(f.get(30,TimeUnit.SECONDS));event("concurrent-results",results);funds.snapshot(user);contracts.matchPendingLimitOrders();proof("concurrent-fresh-connection");assertEquals("CANCELLED",state(p.getId()));money("0",account().get("available"));money("0",account().get("frozen"));money("300",account().get("expired"));assertEquals(1,reasons("TRIAL_EXPIRED_CANCEL:",p.getId()));assertEquals(0,reasons("CONTRACT_CANCEL:",p.getId()));
  }finally{go.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(30,TimeUnit.SECONDS));}
 }
 static void await(CountDownLatch latch){try{assertTrue(latch.await(20,TimeUnit.SECONDS),"deterministic barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
 String login()throws Exception{Map<String,Object> r=new HashMap<>();r.put("account",users.findByTenantIdAndId(2L,user).get().getEmail());r.put("password",PASSWORD);return http("POST","/api/auth/login",null,r,1,200).path("token").asText();}
 long httpOrder(String token,String type,String source)throws Exception{CreateContractOrderRequest input=request(type,source,"1");input.setRequestId("trial-http-"+UUID.randomUUID());return http("POST","/api/trade/contract/order",token,input,1,200).path("orderId").asLong();}
 JsonNode findOrder(JsonNode reply,long id){for(JsonNode o:reply.path("list"))if(o.path("id").asLong()==id)return o;throw new AssertionError("Real HTTP order missing "+id);}
 JsonNode http(String method,String path,String token,Object body,int tenant,int expected)throws Exception {
  HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("Host","t"+(tenant+1)+".t02.test");c.setRequestProperty("Origin","https://t"+(tenant+1)+".t02.test");if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);if(body!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream out=c.getOutputStream()){out.write(JSON.writeValueAsBytes(body));}}
  int status=c.getResponseCode();InputStream input=status>=400?c.getErrorStream():c.getInputStream();String raw="";if(input!=null){try(InputStream in=input;ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int count;while((count=in.read(b))!=-1)out.write(b,0,count);raw=new String(out.toByteArray(),StandardCharsets.UTF_8);}}
  JsonNode reply=raw.isEmpty()?JSON.createObjectNode():JSON.readTree(raw);JsonNode safe=reply.deepCopy();if(safe.isObject()&&safe.has("token"))((com.fasterxml.jackson.databind.node.ObjectNode)safe).put("token","[real JWT redacted]");Map<String,Object> proof=new LinkedHashMap<>();proof.put("method",method);proof.put("path",path);proof.put("tenant",tenant);proof.put("status",status);proof.put("body",safe);event("HTTP",proof);c.disconnect();assertEquals(expected,status,"HTTP "+path+" "+raw);return reply;
 }
}

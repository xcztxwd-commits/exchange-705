package com.gtcfesk.exchange.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.ExchangeBackendApplication;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.user.FinancialYieldService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/** Real existing finance services, formal owned schema and small physical pool; no QuoteInput or Boot seed. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes={ExchangeBackendApplication.class,JointFinancialEnabledMySqlIT.Observation.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class JointFinancialEnabledMySqlIT {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis()*1000);
    private static final Queue<Integer> SQL_ERRORS=new ConcurrentLinkedQueue<>();
    private static final Set<Long> CONNECTIONS=ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<CyclicBarrier> FIRST_USER_LOCK=new ThreadLocal<>();
    private static final Set<Long> BARRIER_CONNECTIONS=ConcurrentHashMap.newKeySet();
    @DynamicPropertySource static void configured(DynamicPropertyRegistry registry)throws Exception {JointFundingEnabledMySqlIT.configured(registry);}
    @TestConfiguration static class Observation {
        // This probe invokes the real money services explicitly, not unrelated wall-clock jobs.
        // Keep their service beans intact while preventing background history writes during the crash window.
        @Bean static org.springframework.beans.factory.config.BeanFactoryPostProcessor noUnrelatedSchedules(){return factory->{
            org.springframework.beans.factory.support.BeanDefinitionRegistry registry=(org.springframework.beans.factory.support.BeanDefinitionRegistry)factory;
            String name=org.springframework.scheduling.config.TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
            if(!registry.containsBeanDefinition(name))throw new IllegalStateException("Expected real scheduling registrar");
            registry.removeBeanDefinition(name);
        };}
        @Bean static BeanPostProcessor recordActualSqlFailures(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){
                if(!(bean instanceof DataSource)||bean instanceof Observed)return bean;return new Observed((DataSource)bean);
            }
        };}
    }
    static class Observed extends DelegatingDataSource {
        Observed(DataSource source){super(source);}
        @Override public Connection getConnection()throws SQLException {
            Connection physical=super.getConnection();
            try(Statement s=physical.createStatement();ResultSet r=s.executeQuery("SELECT CONNECTION_ID()")){assertTrue(r.next());CONNECTIONS.add(r.getLong(1));}
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
                CyclicBarrier barrier=FIRST_USER_LOCK.get();
                if(barrier!=null && m.getName().equals("prepareStatement") && a!=null && a[0] instanceof String) {
                    String sql=((String)a[0]).toLowerCase(Locale.ROOT);
                    if(sql.contains("user_account") && sql.contains("for update")) {
                        FIRST_USER_LOCK.remove();assertFalse(physical.getAutoCommit());assertEquals(Connection.TRANSACTION_REPEATABLE_READ,physical.getTransactionIsolation());
                        try(Statement s=physical.createStatement();ResultSet rows=s.executeQuery("SELECT CONNECTION_ID()")){assertTrue(rows.next());BARRIER_CONNECTIONS.add(rows.getLong(1));}
                        barrier.await(10,TimeUnit.SECONDS);
                    }
                }
                Object value=invoke(physical,m,a);
                if(value instanceof Statement) {
                    Class<?> type=value instanceof CallableStatement?CallableStatement.class:value instanceof PreparedStatement?PreparedStatement.class:Statement.class;
                    return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(q,n,b)->invoke(value,n,b));
                }
                return value;
            });
        }
    }
    static Object invoke(Object target,Method method,Object[] args)throws Throwable {
        try{return method.invoke(target,args);}catch(InvocationTargetException failure){Throwable cause=failure.getCause();if(cause instanceof SQLException)SQL_ERRORS.add(((SQLException)cause).getErrorCode());throw cause;}
    }
    @Autowired JdbcTemplate db;
    @Autowired FinancialYieldService yields;
    @Autowired FinancialOrderRepository orders;
    @Autowired FinancialProductRepository products;
    @Autowired FinancialYieldRecordRepository receipts;
    @Autowired com.gtcfesk.exchange.control.TenantRepository tenants;
    private TenantContext.Scope scope;private long tenant,user;private FinancialProduct product;private final List<String> triggers=new ArrayList<>();
    private Map<String,List<String>> original;private Path evidence;
    @BeforeEach void open()throws Exception {
        evidence=Paths.get(System.getProperty("joint.mysql.fixture")).toAbsolutePath().getParent().resolve("joint-financial-enabled-raw");Files.createDirectories(evidence);
        original=allRows();
        Map<String,List<String>> beforeBoot=JSON.readValue(Files.readAllBytes(evidence.getParent().resolve("joint-funding-enabled-raw/before-boot-original-full-columns.json")),new com.fasterxml.jackson.core.type.TypeReference<Map<String,List<String>>>(){});
        for(String table:beforeBoot.keySet()){List<String> remaining=new ArrayList<>(original.get(table));for(String row:beforeBoot.get(table))assertTrue(remaining.remove(row),"Boot changed protected original row: "+table);}
        SQL_ERRORS.clear();
        com.gtcfesk.exchange.control.Tenant t=new com.gtcfesk.exchange.control.Tenant();t.setCode("financial-"+UUID.randomUUID());t.setName("Owned synthetic finance");t.setStatus("MAINTENANCE");tenant=tenants.saveAndFlush(t).getId();scope=TenantContext.open(tenant);user=user("1000","100");
        product=new FinancialProduct();product.setName("Owned synthetic original financial terms");product.setDailyYieldRate(BigDecimal.ONE);product.setRentalFee(BigDecimal.ZERO);product.setMinPurchase(BigDecimal.ONE);product.setMaxPurchase(new BigDecimal("1000"));product.setTermDays(3);product=products.saveAndFlush(product);
        assertFalse(org.mockito.Mockito.mockingDetails(yields).isMock());assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(yields));
    }
    @AfterEach void preserve()throws Exception {
        try {
            for(String trigger:triggers)db.execute("DROP TRIGGER "+trigger);
            Map<String,List<String>> after=allRows();for(String table:original.keySet()){List<String> remaining=new ArrayList<>(after.get(table));for(String row:original.get(table))assertTrue(remaining.remove(row),"Protected original row changed: "+table);}
            assertFalse(SQL_ERRORS.contains(1213),"An internal retry must not hide an actual deadlock");
            Files.write(evidence.resolve("receipt-"+UUID.randomUUID()+".json"),JSON.writeValueAsBytes(Map.of("tenant",tenant,"originalRowsPreserved",true,"sqlErrorCodes",SQL_ERRORS,"physicalConnections",CONNECTIONS,"barrierConnections",BARRIER_CONNECTIONS,"after",after)));
        } finally {if(scope!=null)scope.close();TenantContext.clear();}
    }
    private long user(String available,String frozen){long id=IDS.incrementAndGet();db.update("INSERT INTO user_account(tenant_id,id,email,password_hash,status,user_type,created_at,updated_at,row_version) VALUES(?,?,?,'not-a-login','normal','normal',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",tenant,id,"financial-"+id+"@fixture.invalid");db.update("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(?,?,'FUND',?,?,0)",tenant,id,new BigDecimal(available),new BigDecimal(frozen));return id;}
    private FinancialOrder order(long owner,int days){FinancialOrder o=new FinancialOrder();o.setUserId(owner);o.setProductId(product.getId());o.setProductName(product.getName());o.setPurchaseAmount(new BigDecimal("100"));o.setDailyYieldRate(BigDecimal.ONE);o.setDailyYield(BigDecimal.ONE);o.setTermDays(days);o.setTotalYield(BigDecimal.valueOf(days));o.setPenaltyRate(BigDecimal.ZERO);LocalDateTime start=LocalDate.now().minusDays(days+1).atTime(12,0);o.setPurchaseTime(start);o.setEndTime(start.plusDays(days));return orders.saveAndFlush(o);}
    private void money(long owner,String available,String frozen){Map<String,Object> a=db.queryForMap("SELECT available,frozen FROM asset_account WHERE tenant_id=? AND user_id=? AND coin='FUND'",tenant,owner);assertEquals(0,new BigDecimal(available).compareTo((BigDecimal)a.get("available")));assertEquals(0,new BigDecimal(frozen).compareTo((BigDecimal)a.get("frozen")));}
    private Map<String,List<String>> allRows()throws Exception {Map<String,List<String>> out=new TreeMap<>();for(String table:db.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name",String.class)){assertTrue(table.matches("[a-zA-Z0-9_]+"));assertTrue(db.queryForObject("SELECT COUNT(*) FROM `"+table+"`",Long.class)<50000);List<String> rows=new ArrayList<>();for(Map<String,Object> row:db.queryForList("SELECT * FROM `"+table+"`"))rows.add(JSON.writeValueAsString(new TreeMap<>(row)));Collections.sort(rows);out.put(table,rows);}return out;}
    private String fault(String table,String operation,String condition){String name="owned_fin_"+UUID.randomUUID().toString().replace("-","").substring(0,18);db.execute("CREATE TRIGGER "+name+" AFTER "+operation+" ON "+table+" FOR EACH ROW BEGIN IF NEW.tenant_id="+tenant+" AND "+condition+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='OWNED_FINANCIAL_WRITE_FAILURE'; END IF; END");triggers.add(name);return name;}
    private void remove(String trigger){db.execute("DROP TRIGGER "+trigger);triggers.remove(trigger);}
    private void actualFailure(Runnable work){RuntimeException error=assertThrows(RuntimeException.class,work::run);Throwable root=error;while(root.getCause()!=null)root=root.getCause();assertInstanceOf(SQLException.class,root);assertEquals("45000",((SQLException)root).getSQLState());assertTrue(root.getMessage().contains("OWNED_FINANCIAL_WRITE_FAILURE"));}
    private long count(long id){return db.queryForObject("SELECT COUNT(*) FROM financial_yield_record WHERE tenant_id=? AND order_id=?",Long.class,tenant,id);}
    @Test void bounded31DaySparseLegacyPaidReceiptRetainsOriginalMoneyAndRetryOnce()throws Exception {
        FinancialOrder order=order(user,65);FinancialYieldRecord old=new FinancialYieldRecord();old.setOrderId(order.getId());old.setUserId(user);old.setProductId(product.getId());old.setProductName(product.getName());old.setYieldDate(order.getPurchaseTime().toLocalDate().plusDays(4));old.setDailyYield(new BigDecimal("2"));old.setCumulativeYield(new BigDecimal("2"));old.setStatus("PAID");old.setPaidAt(order.getPurchaseTime().plusDays(5));old=receipts.saveAndFlush(old);long oldId=old.getId();Map<String,Object> saved=db.queryForMap("SELECT * FROM financial_yield_record WHERE tenant_id=? AND id=?",tenant,oldId);
        yields.calculateDailyYield();assertEquals(31,count(order.getId()));money(user,"1000","100");assertEquals(order.getPurchaseTime().toLocalDate().plusDays(30),orders.findByTenantIdAndId(tenant,order.getId()).orElseThrow().getLastAccruedDate());
        yields.calculateDailyYield();assertEquals(62,count(order.getId()));money(user,"1000","100");yields.calculateDailyYield();assertEquals(65,count(order.getId()));money(user,"1100","0");
        assertEquals(0,new BigDecimal("66").compareTo(orders.findByTenantIdAndId(tenant,order.getId()).orElseThrow().getAccruedYield()));assertEquals(saved,db.queryForMap("SELECT * FROM financial_yield_record WHERE tenant_id=? AND id=?",tenant,oldId));
        yields.payoutAllPendingYields();money(user,"1164","0");Map<String,List<String>> terminal=allRows();yields.calculateDailyYield();yields.payoutAllPendingYields();assertEquals(terminal,allRows());
    }
    @Test void everyAccrualMaturityWriteAndSuccessAuditFailureRollsBackThenRetryOnce()throws Exception {
        FinancialOrder order=order(user,3);List<String[]> points=List.of(new String[]{"financial_yield_record","INSERT","NEW.order_id="+order.getId()},new String[]{"control_audit_log","INSERT","NEW.action='FINANCIAL_YIELD_CALCULATE'"},new String[]{"asset_account","UPDATE","NEW.user_id="+user},new String[]{"control_audit_log","INSERT","NEW.action='FINANCIAL_MATURE'"},new String[]{"financial_order","UPDATE","NEW.id="+order.getId()});
        for(String[] point:points){String trigger=fault(point[0],point[1],point[2]);Map<String,List<String>> before=allRows();try{actualFailure(yields::calculateDailyYield);assertEquals(before,allRows(),String.join(":",point));}finally{remove(trigger);}}
        yields.calculateDailyYield();assertEquals(3,count(order.getId()));money(user,"1100","0");Map<String,List<String>> terminal=allRows();yields.calculateDailyYield();assertEquals(terminal,allRows());
    }
    @Test void publicFullBatchPayoutSecondUserFailureRollsBackAllWriters()throws Exception {
        long second=user("2000","100");FinancialOrder a=order(user,2),b=order(second,2);yields.calculateDailyYield();money(user,"1100","0");money(second,"2100","0");
        long later=db.queryForObject("SELECT MAX(id) FROM financial_yield_record WHERE tenant_id=? AND user_id=?",Long.class,tenant,second);
        for(String[] point:List.of(new String[]{"asset_account","UPDATE","NEW.user_id="+second},new String[]{"financial_yield_record","UPDATE","NEW.id="+later},new String[]{"control_audit_log","INSERT","NEW.action='FINANCIAL_YIELD_PAY' AND NEW.object_ref='"+later+"'"})){
            String trigger=fault(point[0],point[1],point[2]);Map<String,List<String>> before=allRows();try{actualFailure(yields::payoutAllPendingYields);assertEquals(before,allRows(),"Legacy full-batch must never leave first user committed");}finally{remove(trigger);}
        }
        yields.payoutAllPendingYields();money(user,"1102","0");money(second,"2102","0");Map<String,List<String>> terminal=allRows();yields.payoutAllPendingYields();assertEquals(terminal,allRows());
    }
    @Test void actualTwoConnectionAccrualAndPayoutDoNotHideDeadlocksOrDoubleCredit()throws Exception {
        FinancialOrder order=order(user,62);yields.calculateDailyYield();assertEquals(31,count(order.getId()));money(user,"1000","100");CountDownLatch start=new CountDownLatch(1);ExecutorService workers=Executors.newFixedThreadPool(2);
        CyclicBarrier beforeFirstUserLock=new CyclicBarrier(2);BARRIER_CONNECTIONS.clear();
        try{Future<?> a=workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){FIRST_USER_LOCK.set(beforeFirstUserLock);await(start);yields.calculateDailyYield();}finally{FIRST_USER_LOCK.remove();}});Future<?> b=workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){FIRST_USER_LOCK.set(beforeFirstUserLock);await(start);yields.payoutAllPendingYields();}finally{FIRST_USER_LOCK.remove();}});start.countDown();a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);}finally{start.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(10,TimeUnit.SECONDS));}
        assertEquals(2,BARRIER_CONNECTIONS.size(),"Both real RR transactions reached their first user lock before either proceeded");
        yields.calculateDailyYield();yields.payoutAllPendingYields();assertEquals(62,count(order.getId()));money(user,"1162","0");assertFalse(SQL_ERRORS.contains(1213));assertTrue(CONNECTIONS.size()>=2);Map<String,List<String>> terminal=allRows();yields.calculateDailyYield();yields.payoutAllPendingYields();assertEquals(terminal,allRows());
    }
    @Test void actualProcessDeathBeforeAndAfterMoneyCommitAndFreshSuccessorKeepExactlyOnce()throws Exception {
        FinancialOrder order=order(user,3);
        for(String operation:List.of("ACCRUE","PAY")){
            Map<String,List<String>> before=allRows();
            runFinancialChild("BEFORE",operation,91);assertEquals(before,allRows(),"Abrupt process death before real COMMIT rolls back all money and receipts");
            runFinancialChild("AFTER",operation,92);
            assertEquals(3,count(order.getId()));money(user,"ACCRUE".equals(operation)?"1100":"1103","0");
            assertEquals("COMPLETED",orders.findByTenantIdAndId(tenant,order.getId()).orElseThrow().getStatus());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND action='FINANCIAL_MATURE'",Integer.class,tenant).intValue());
            assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND action='FINANCIAL_YIELD_CALCULATE'",Integer.class,tenant).intValue());
            if("PAY".equals(operation))assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM financial_yield_record WHERE tenant_id=? AND status='PAID'",Integer.class,tenant).intValue());
            Map<String,List<String>> committed=allRows();runFinancialChild("RECOVER",operation,0);
            assertEquals(committed,allRows(),"Fresh JVM successor may not duplicate committed money, dates, principal or success audit");
        }
    }
    private void runFinancialChild(String mode,String operation,int expected)throws Exception {
        Path home=Files.createDirectory(evidence.resolve("child-"+mode+"-"+operation+"-"+UUID.randomUUID()));
        Path source=Paths.get(System.getProperty("joint.mysql.fixture")).toAbsolutePath();
        Files.copy(source,home.resolve("connection.json"));Files.copy(source.getParent().resolve("application.properties"),home.resolve("application.properties"));
        Path marker=home.resolve("physical-commit.json"),log=home.resolve("process.log");
        List<String> command=List.of(Paths.get(System.getProperty("java.home"),"bin","java.exe").toString(),"-Xmx384m","-Dfile.encoding=UTF-8","-Duser.timezone=UTC","-Djoint.mysql.fixture="+home.resolve("connection.json"),"-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),FinancialCrashChild.class.getName(),mode,operation,Long.toString(tenant),marker.toString());
        Process child=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        long pid=child.pid();String created=child.toHandle().info().startInstant().orElseThrow().toString();boolean timedOut=false;
        try{
            timedOut=!child.waitFor(150,TimeUnit.SECONDS);assertFalse(timedOut,"Owned child timed out; see "+log);
            assertEquals(expected,child.exitValue(),"Actual child exit, see "+log);assertTrue(Files.isRegularFile(marker));
            Map<?,?> event=JSON.readValue(Files.readAllBytes(marker),Map.class);assertEquals(pid,((Number)event.get("pid")).longValue());assertEquals(tenant,((Number)event.get("tenant")).longValue());
            assertEquals("RECOVER".equals(mode)?"SUCCESSOR_RETURNED":mode+"_PHYSICAL_COMMIT",event.get("phase"));
            if(expected!=0){assertTrue(((Number)event.get("moneyStatements")).intValue()>0);assertEquals(Connection.TRANSACTION_REPEATABLE_READ,((Number)event.get("isolation")).intValue());}
        }finally{
            if(child.isAlive()){child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS));}
            Files.write(home.resolve("process-result.json"),JSON.writeValueAsBytes(Map.of("pid",pid,"createdAt",created,"exitCode",child.exitValue(),"expectedExitCode",expected,"timedOut",timedOut,"command",command)));
        }
    }

    private static void await(CountDownLatch latch){try{assertTrue(latch.await(10,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
}

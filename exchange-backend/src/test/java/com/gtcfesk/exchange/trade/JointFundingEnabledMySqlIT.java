package com.gtcfesk.exchange.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.ExchangeBackendApplication;
import com.gtcfesk.exchange.activity.TrialFunds;
import com.gtcfesk.exchange.control.ControlAuditService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.market.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.user.TransferController;
import org.junit.jupiter.api.*;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Owned enabled Boot acceptance. A missing certified fixture fails closed; source presence is not a pass.
 * Depends on production lifecycle switches defaulting TRUE:
 * market.engine.auto-start / app.market.processor.auto-start.
 * No QuoteInput, service/audit/authority replacement, create-drop or relaxed fixture guard.
 * Selectors cover Contract/Option settlement, creation, current authority, FX and controlled exits.
 * The explicit legacy selector temporarily disables only the Contract S3 switch; every claim needs its own actual run evidence.
 */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes={ExchangeBackendApplication.class,JointFundingEnabledMySqlIT.Observation.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class JointFundingEnabledMySqlIT {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final AtomicLong IDS=new AtomicLong(System.currentTimeMillis()*1000);
    private static final long TENANT=1;
    private static final ThreadLocal<Trace> TRACE=new ThreadLocal<>();
    private static DriverManagerDataSource certified;
    private static JdbcTemplate beforeBoot;
    private static Map<String,List<String>> original;
    private static Path evidence;

    @DynamicPropertySource static void configured(DynamicPropertyRegistry registry)throws Exception {
        certified=DedicatedMysqlFixture.fromProperty("joint.mysql.fixture");
        Path definition=Paths.get(System.getProperty("joint.mysql.fixture")).toAbsolutePath().normalize();
        evidence=definition.getParent().resolve("joint-funding-enabled-raw");
        Files.createDirectories(evidence);
        Properties owned=new Properties();
        try(java.io.Reader reader=Files.newBufferedReader(definition.getParent().resolve("application.properties"))){owned.load(reader);}
        owned.forEach((key,value)->registry.add(key.toString(),()->value));
        registry.add("spring.datasource.url",certified::getUrl);
        registry.add("spring.datasource.username",certified::getUsername);
        registry.add("spring.datasource.password",certified::getPassword);
        registry.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        registry.add("spring.jpa.database-platform",()->"org.hibernate.dialect.MySQL57Dialect");
        registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
        registry.add("spring.jpa.generate-ddl",()->"false");
        registry.add("spring.sql.init.mode",()->"never");
        registry.add("spring.datasource.hikari.maximum-pool-size",()->"2");
        registry.add("spring.datasource.hikari.connection-timeout",()->"15000");
        // The dedicated funding fixture deliberately has no Redis server; bound real client cleanup, never mock it.
        registry.add("spring.redis.timeout",()->"200ms");
        registry.add("spring.redis.connect-timeout",()->"200ms");
        registry.add("platform.bootstrap.enabled",()->"false");
        registry.add("app.market.s3-scheduling-enabled",()->"true");
        registry.add("app.market.s4-source-projection-enabled",()->"false");
        registry.add("market.engine.auto-start",()->"false");
        registry.add("app.market.processor.auto-start",()->"false");
        registry.add("financial.yield.initial-delay-ms",()->"86400000");
        registry.add("activity.order-events.initial-delay-ms",()->"86400000");
        registry.add("simulation.enabled",()->"false");
        registry.add("market.home-sparkline.enabled",()->"false");
        registry.add("market.depth.enabled",()->"false");
        registry.add("calendar.sync.enabled",()->"false");
        registry.add("calendar.reminders.enabled",()->"false");
        registry.add("calendar.initial-delay-ms",()->"86400000");
        registry.add("news.sync.enabled",()->"false");
        registry.add("news.gdelt.enabled",()->"false");
        registry.add("news.initial-delay-ms",()->"86400000");
        registry.add("asset.history.equity.collect-enabled",()->"false");
        registry.add("control.retention.cron",()->"-");
        registry.add("spring.jmx.enabled",()->"false");
        registry.add("server.port",()->"0");
        beforeBoot=new JdbcTemplate(certified);
        Map<String,Object> physical=beforeBoot.queryForMap(
            "SELECT @@server_uuid AS uuid,@@port AS port,VERSION() AS version,DATABASE() AS db");
        assertTrue(String.valueOf(physical.get("version")).startsWith("5.7."));
        long epoch=Long.parseLong(new String(Files.readAllBytes(
            Paths.get("src/main/resources/META-INF/mt705-schema-epoch")),java.nio.charset.StandardCharsets.US_ASCII).trim());
        Map<String,Object> schema=beforeBoot.queryForMap(
            "SELECT MAX(version) AS version,MAX(minimum_application_epoch) AS epoch,"+
            "SUM(CASE WHEN business_activation_ready<>0 THEN 1 ELSE 0 END) AS open_gates FROM tenant_schema_version");
        assertEquals(epoch,((Number)schema.get("version")).longValue());
        assertEquals(epoch,((Number)schema.get("epoch")).longValue());
        assertEquals(0,((Number)schema.get("open_gates")).longValue());
        original=rows(beforeBoot);
        // These independent pollers have no lifecycle switch/initial delay. Never drain or rewrite old jobs to boot.
        assertEquals(0,beforeBoot.queryForObject("SELECT COUNT(*) FROM control_chat_archive_job WHERE state IN ('QUEUED','RUNNING')",Integer.class).intValue(),"Old archive work requires a separate controlled worker fixture");
        assertEquals(0,beforeBoot.queryForObject("SELECT COUNT(*) FROM market_control_command WHERE state IN ('ACCEPTED','PREPARING','READY')",Integer.class).intValue(),"Old command queue may not be activated by this funding fixture");
        Files.write(evidence.resolve("before-boot-original-full-columns.json"),JSON.writeValueAsBytes(original));
        Files.write(evidence.resolve("physical-identity.json"),JSON.writeValueAsBytes(physical));
    }
    @TestConfiguration static class Observation {
        @Bean static BeanPostProcessor observedOwnedDatasource(){return new BeanPostProcessor(){
            @Override public Object postProcessAfterInitialization(Object bean,String name){
                return bean instanceof DataSource && !(bean instanceof RecordingSource)
                    ?new RecordingSource((DataSource)bean):bean;
            }
        };}
    }
    @Autowired JdbcTemplate db;
    @Autowired ContractOrderService contracts;
    @Autowired OptionOrderService optionService;
    @Autowired OptionOrderRepository optionOrders;
    @Autowired OptionDurationRepository optionDurations;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired ContractOrderRepository orders;
    @Autowired TradingSymbolRepository symbols;
    @Autowired AssetAccountRepository assets;
    @Autowired ForexQuoteMarketService actualMarket;
    @Autowired PersistentPriceControl actualControls;
    @Autowired MarketOrderProcessor actualProcessor;
    @Autowired FundingQuoteAuthority authority;
    @Autowired TrialFunds trialFunds;
    @Autowired ControlAuditService audit;
    @Autowired TransferController transfers;
    @Autowired org.springframework.context.ApplicationContext application;
    private TenantContext.Scope scope;
    private final List<String> ownedTriggers=new ArrayList<>();
    private final List<Trace> traces=new ArrayList<>();

    @BeforeEach void ready()throws Exception {
        TenantContext.clear();SecurityContextHolder.clearContext();
        assertOldRowsPreserved(); // Detect actual @PostConstruct pollution; never restore/hide it.
        assertFalse(org.mockito.Mockito.mockingDetails(actualMarket).isMock());
        assertFalse(org.mockito.Mockito.mockingDetails(actualControls).isMock());
        assertFalse(org.mockito.Mockito.mockingDetails(authority).isMock());
        assertFalse(org.mockito.Mockito.mockingDetails(trialFunds).isMock());
        assertFalse(org.mockito.Mockito.mockingDetails(audit).isMock());
        assertTrue(AopUtils.isAopProxy(contracts),"Actual REQUIRED methods remain Spring-proxied");
        assertTrue(AopUtils.isAopProxy(transfers),"Transfer receipt test requires the actual Spring transaction proxy");
        assertFalse(application.containsBean("tenantFixture"),"No imported BootTenantFixture tenant/policy seeding");
        assertFalse(application.containsBean("certifiedApplicationMysql"),"No legacy fixture datasource property override");
        assertNotNull(ReflectionTestUtils.getField(authority,"entityManager"),"Actual Boot must flush real JPA");
        assertTrue(db.getDataSource() instanceof RecordingSource,"Observe actual application datasource");
        scope=TenantContext.open(TENANT);
    }
    @AfterEach void evidenceAndCleanup()throws Exception {
        TRACE.remove();SecurityContextHolder.clearContext();if(scope!=null)scope.close();TenantContext.clear();
        // Only exact test-owned triggers on the already certified clone are removed.
        for(String trigger:ownedTriggers)db.execute("DROP TRIGGER IF EXISTS "+trigger);
        for(Trace trace:traces)Files.write(evidence.resolve(trace.name+".json"),JSON.writeValueAsBytes(trace.events));
        assertOldRowsPreserved();
    }



    @Test void enabledControlledClosesAtomicReceiptAuditAndAckRecoveryWithoutNewBusiness()throws Exception {
        com.gtcfesk.exchange.admin.ControlledExitService service=application.getBean(com.gtcfesk.exchange.admin.ControlledExitService.class);
        for(String kind:Arrays.asList("contract","option")){
            long tenant=ownedContractTenant(),user=ownedContractUser(tenant);TradingSymbol config=contractCreateSymbol();
            long id=ownedControlledOrder(kind,tenant,user,config);controlledAdmin(tenant);
            db.update("UPDATE tenant SET status='MAINTENANCE' WHERE id=?",tenant);
            db.update("UPDATE kyc_record SET status='REJECTED' WHERE tenant_id=? AND user_id=?",tenant,user);
            com.gtcfesk.exchange.admin.ControlledExitService.Input input=controlledInput();
            Map<String,List<String>> before=moneyRows();int n=0;
            for(String table:Arrays.asList("balance_adjustment","operation_log")){
                publish(config,"110");String trigger="jexit_"+id+"_"+(n++);ownedTriggers.add(trigger);
                db.execute("CREATE TRIGGER "+trigger+" AFTER INSERT ON "+table+" FOR EACH ROW BEGIN IF NEW.tenant_id="+tenant+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned controlled write rollback'; END IF; END");
                Trace failure=observe("controlled-"+kind+"-"+table+"-"+id);
                assertThrows(RuntimeException.class,()->service.execute(kind,id,"close",input));TRACE.remove();
                assertTrue(failure.events.stream().anyMatch(e->"sql-failed".equals(e.get("kind"))&&String.valueOf(e.get("sql")).contains(table)),"Selected actual write must be reached");
                assertEquals(before,moneyRows());assertTrue(failure.events.stream().anyMatch(e->"rollback-return".equals(e.get("kind"))));
                db.execute("DROP TRIGGER "+trigger);ownedTriggers.remove(trigger);
            }
            publish(config,"110");Trace uncertain=observe("controlled-"+kind+"-actual-commit-ack-"+id);
            uncertain.beforeRuntimeLock=()->uncertain.dropCommitAcknowledgment=true;
            assertThrows(RuntimeException.class,()->service.execute(kind,id,"close",input));TRACE.remove();
            assertTrue(uncertain.events.stream().anyMatch(e->"ack-fault-after-real-commit".equals(e.get("kind"))));uncertain.assertFundingAuthorizationOrder(kind+"_order");
            assertEquals("CLOSED",db.queryForObject("SELECT status FROM "+kind+"_order WHERE tenant_id=? AND id=?",String.class,tenant,id));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM balance_adjustment WHERE tenant_id=? AND request_key=?",Integer.class,tenant,input.requestId).intValue());
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM operation_log WHERE tenant_id=? AND target_type=? AND target_id=?",Integer.class,tenant,kind,id).intValue());
            String receipt=db.queryForObject("SELECT changes FROM balance_adjustment WHERE tenant_id=? AND request_key=?",String.class,tenant,input.requestId);
            db.update("UPDATE market_engine_runtime SET quote_json=NULL WHERE tenant_id=? AND symbol_id=?",tenant,config.getId());
            Map<String,List<String>> committed=moneyRows();Trace replay=observe("controlled-"+kind+"-receipt-without-quote-"+id);
            Map<String,Object> result=service.execute(kind,id,"close",input);TRACE.remove();assertEquals(JSON.readValue(receipt,Map.class),result);
            assertFalse(replay.moneyDml());assertEquals(committed,moneyRows());
            input.reason="Different authenticated command reason";assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.execute(kind,id,"close",input));assertEquals(committed,moneyRows());
        }
    }

    @Test void enabledControlledStopBeforeAuthorityRejectsWithoutExpiryMaintenance()throws Exception {
        com.gtcfesk.exchange.admin.ControlledExitService service=application.getBean(com.gtcfesk.exchange.admin.ControlledExitService.class);
        for(String kind:Arrays.asList("contract","option")){
            long tenant=ownedContractTenant(),user=ownedContractUser(tenant);TradingSymbol config=contractCreateSymbol();long id=ownedControlledOrder(kind,tenant,user,config);
            trialFunds.grant(user,new BigDecimal("20"),null,null,"controlled-expiry-"+user,null);
            db.update("UPDATE trial_grant SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE tenant_id=? AND user_id=?",tenant,user);
            publish(config,"110");Map<String,Object> raw=raw("110",clock());actualControls.start(config,raw,new BigDecimal("110"),60,new BigDecimal("111"),1,false,false,"controlled-"+UUID.randomUUID());actualControls.pump(config,raw,clock(),60000);
            Map<String,List<String>> before=moneyRows();com.gtcfesk.exchange.admin.ControlledExitService.Input input=controlledInput();
            Trace trace=new Trace("controlled-"+kind+"-stop-zero-money-"+id);traces.add(trace);
            CountDownLatch prepared=new CountDownLatch(1),release=new CountDownLatch(1);trace.beforeRuntimeLock=()->{prepared.countDown();await(release);};ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                Future<?> pending=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){controlledAdmin(tenant);TRACE.set(trace);return service.execute(kind,id,"close",input);}finally{TRACE.remove();SecurityContextHolder.clearContext();}});
                assertTrue(prepared.await(10,TimeUnit.SECONDS));actualControls.stop(config.getId(),clock());release.countDown();
                ExecutionException rejected=assertThrows(ExecutionException.class,()->pending.get(15,TimeUnit.SECONDS));assertTrue(rejected.getCause() instanceof com.gtcfesk.exchange.common.BusinessException);
                assertFalse(trace.moneyDml());assertEquals(before,moneyRows());
            }finally{release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
            controlledAdmin(tenant);
            assertThrows(com.gtcfesk.exchange.common.BusinessException.class,()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->service.execute(kind,id,"close",input)));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("1",null,Arrays.asList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_AGENT"))));
            assertThrows(org.springframework.security.access.AccessDeniedException.class,()->service.execute(kind,id,"close",input));assertEquals(before,moneyRows());
        }
    }
    private long ownedControlledOrder(String kind,long tenant,long user,TradingSymbol config){
        String coin="contract".equals(kind)?"CONTRACT":"OPTION";db.update("UPDATE asset_account SET frozen=? WHERE tenant_id=? AND user_id=? AND coin=?","contract".equals(kind)?10:100,tenant,user,coin);
        if("option".equals(kind))return optionOrder(user,config,optionDuration(),"OPTION",BigDecimal.ZERO,null).getId();
        ContractOrder position=order(user,config,"OPEN","10");position.setLotSize(BigDecimal.ONE);return orders.saveAndFlush(position).getId();
    }
    private void controlledAdmin(long tenant){
        long admin=db.queryForObject("SELECT id FROM admin_user WHERE tenant_id=? AND enabled=1 ORDER BY id LIMIT 1",Long.class,tenant);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(Long.toString(admin),null,Arrays.asList(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
    }
    private com.gtcfesk.exchange.admin.ControlledExitService.Input controlledInput(){
        com.gtcfesk.exchange.admin.ControlledExitService.Input input=new com.gtcfesk.exchange.admin.ControlledExitService.Input();input.requestId="owned-controlled-"+IDS.incrementAndGet();input.reason="Owned actual atomic exit acceptance";return input;
    }


    @Test void legacyContractWholeBatchSecondAuditFailureKeepsOuterRequiredAtomic()throws Exception {
        Object service=org.springframework.test.util.AopTestUtils.getTargetObject(contracts);
        Object enabled=ReflectionTestUtils.getField(service,"s3SchedulingEnabled");
        ReflectionTestUtils.setField(service,"s3SchedulingEnabled",false);
        try {
            for(String mode:Arrays.asList("PENDING","AUTO","FORCE")) {
                long tenant=ownedContractTenant(),firstUser=ownedContractUser(tenant),secondUser=ownedContractUser(tenant);
                TradingSymbol config=contractCreateSymbol();String available="PENDING".equals(mode)?"1000":"FORCE".equals(mode)?"0":"90";
                String frozen="PENDING".equals(mode)?"100":"10",price="FORCE".equals(mode)?"80":"110";
                db.update("UPDATE asset_account SET available=?,frozen=? WHERE tenant_id=? AND coin='CONTRACT' AND user_id IN (?,?)",new BigDecimal(available),new BigDecimal(frozen),tenant,firstUser,secondUser);
                ContractOrder first=order(firstUser,config,"PENDING".equals(mode)?"PENDING":"OPEN",frozen);
                ContractOrder second=order(secondUser,config,"PENDING".equals(mode)?"PENDING":"OPEN",frozen);
                for(ContractOrder order:Arrays.asList(first,second)) {
                    if("PENDING".equals(mode)){order.setType("LIMIT");order.setLimitMatchEnabled(true);order.setPrice(new BigDecimal("120"));order.setLotSize(BigDecimal.ONE);}
                    else if("AUTO".equals(mode))order.setTakeProfit(new BigDecimal("105"));
                    orders.saveAndFlush(order);
                }
                actualMarket.refreshSymbols();publish(config,price);money(price,actualMarket.freshPrice(config.getSymbol()));
                String action="PENDING".equals(mode)?"CONTRACT_FILL":"CONTRACT_CLOSE";
                String trigger="joint_owned_legacy_second_"+second.getId();ownedTriggers.add(trigger);
                db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON control_audit_log FOR EACH ROW BEGIN IF NEW.tenant_id="+tenant+
                    " AND NEW.action='"+action+"' AND NEW.object_ref='"+second.getId()+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='joint owned legacy second audit failure'; END IF; END");
                Map<String,List<String>> before=moneyRows();
                org.springframework.transaction.support.TransactionTemplate outer=new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED,outer.getPropagationBehavior());
                assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_DEFAULT,outer.getIsolationLevel());
                Runnable batch=()->outer.execute(status->{
                    assertEquals(Connection.TRANSACTION_REPEATABLE_READ,db.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)Connection::getTransactionIsolation).intValue());
                    if("PENDING".equals(mode))assertEquals(2,contracts.matchPendingLimitOrders());
                    else if("AUTO".equals(mode))contracts.checkAndAutoCloseOrders(config.getSymbol(),BigDecimal.ONE);
                    else contracts.checkAndForceCloseOrders(Collections.singletonMap(config.getSymbol(),BigDecimal.ONE));
                    return null;
                });
                Trace failure=observe("legacy-contract-"+mode.toLowerCase(Locale.ROOT)+"-second-audit-rollback-"+second.getId());
                try{assertThrows(RuntimeException.class,batch::run);}finally{TRACE.remove();}
                assertEquals(before,moneyRows(),"The first successful order, both wallets, both orders and audits belong to the same old full batch");
                int failed=-1;
                for(int i=0;i<failure.events.size();i++){Map<String,Object> event=failure.events.get(i);
                    if("sql-failed".equals(event.get("kind"))&&String.valueOf(event.get("sql")).toLowerCase(Locale.ROOT).startsWith("insert into control_audit_log ")){assertEquals(-1,failed);failed=i;}}
                assertTrue(failed>=0,"The owned second order audit must actually reach MySQL");
                long physical=((Number)failure.events.get(failed).get("connection_id")).longValue();int audits=0,ordersWritten=0,walletsWritten=0,rollback=-1;
                for(int i=0;i<failure.events.size();i++){Map<String,Object> event=failure.events.get(i);if(((Number)event.get("connection_id")).longValue()!=physical)continue;
                    String kind=String.valueOf(event.get("kind")),sql=String.valueOf(event.get("sql")).toLowerCase(Locale.ROOT);
                    assertFalse("commit-call".equals(kind)||"commit-return".equals(kind),"Old full batch may not commit its first order separately");
                    if(i<failed&&"sql-return".equals(kind)){if(sql.startsWith("insert into control_audit_log "))audits++;if(sql.startsWith("update contract_order "))ordersWritten++;if(sql.startsWith("update asset_account "))walletsWritten++;}
                    if(i>failed&&"rollback-return".equals(kind))rollback=i;
                }
                assertEquals(1,audits,"First order success audit must execute before the second fails");assertTrue(ordersWritten>0&&walletsWritten>0,"First order and cash SQL must physically execute before the second audit fails");assertTrue(rollback>failed);
                db.execute("DROP TRIGGER "+trigger);ownedTriggers.remove(trigger);publish(config,price);
                Trace retry=observe("legacy-contract-"+mode.toLowerCase(Locale.ROOT)+"-whole-batch-retry-"+second.getId());try{batch.run();}finally{TRACE.remove();}
                assertEquals(1,retry.commitCalls());assertEquals(1,retry.realCommitReturns());
                for(ContractOrder order:Arrays.asList(first,second)){
                    assertEquals("PENDING".equals(mode)?"OPEN":"CLOSED",orders.findByTenantIdAndId(tenant,order.getId()).orElseThrow(AssertionError::new).getStatus());
                    AssetAccount wallet=assets.findByTenantIdAndUserIdAndCoin(tenant,order.getUserId(),"CONTRACT").orElseThrow(AssertionError::new);
                    money("PENDING".equals(mode)?"990":"FORCE".equals(mode)?"0":"110",wallet.getAvailable());money("PENDING".equals(mode)?"110":"0",wallet.getFrozen());
                    assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND action=? AND object_ref=?",Integer.class,tenant,action,order.getId().toString()).intValue());
                }
            }
        }finally{TRACE.remove();ReflectionTestUtils.setField(service,"s3SchedulingEnabled",enabled);}
    }

    @Test void enabledContractCreateEveryWriteRollbackAndManualCloseReceipts()throws Exception {
        long tenant=ownedContractTenant();
        for(String source:Arrays.asList("CONTRACT","TRIAL")){
            long user=ownedContractUser(tenant);TradingSymbol config=contractCreateSymbol();if("TRIAL".equals(source))trialFunds.grant(user,new BigDecimal("300"),null,null,"create-contract-"+user,null);
            publish(config,"110");com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest request=contractCreate(config,source,"MARKET");Map<String,List<String>> before=moneyRows();
            String[][] points="TRIAL".equals(source)?new String[][]{{"trial_account","UPDATE"},{"trial_grant","UPDATE"},{"trial_ledger","INSERT"},{"contract_order","INSERT"},{"control_audit_log","INSERT"}}
                :new String[][]{{"asset_account","UPDATE"},{"contract_order","INSERT"},{"control_audit_log","INSERT"}};
            int n=0;for(String[] point:points){
                String trigger="jcc_"+user+"_"+(n++);ownedTriggers.add(trigger);String condition="control_audit_log".equals(point[0])?"NEW.tenant_id="+tenant+" AND NEW.action='CONTRACT_CREATE'":"NEW.tenant_id="+tenant+" AND NEW.user_id="+user;
                db.execute("CREATE TRIGGER "+trigger+" BEFORE "+point[1]+" ON "+point[0]+" FOR EACH ROW BEGIN IF "+condition+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned contract create failure'; END IF; END");
                Trace failure=observe("contract-create-fail-"+point[0]+"-"+user);assertThrows(RuntimeException.class,()->contracts.createOrderWithAuthority(user,request));TRACE.remove();
                assertTrue(failure.events.stream().anyMatch(e->"sql-failed".equals(e.get("kind"))&&String.valueOf(e.get("sql")).contains(point[0])),"The selected actual write must be reached");
                assertEquals(before,moneyRows());assertTrue(failure.events.stream().anyMatch(e->"rollback-return".equals(e.get("kind"))));db.execute("DROP TRIGGER "+trigger);
            }
            Trace uncertain=observe("contract-create-actual-commit-ack-"+user);uncertain.beforeRuntimeLock=()->uncertain.dropCommitAcknowledgment=true;
            assertThrows(RuntimeException.class,()->contracts.createOrderWithAuthority(user,request));TRACE.remove();uncertain.assertFundingAuthorizationOrder("contract_order");
            assertTrue(uncertain.events.stream().anyMatch(e->"ack-fault-after-real-commit".equals(e.get("kind"))));
            ContractOrder created=orders.findByTenantIdAndUserIdAndRequestKey(tenant,user,request.getRequestId()).orElseThrow(AssertionError::new);
            money("110",created.getOpenPrice());money("110",created.getMargin());money("2",created.getFee());assertEquals(source,created.getFundingSource());
            db.update("UPDATE market_engine_runtime SET quote_json=NULL WHERE tenant_id=? AND symbol_id=?",tenant,config.getId());Map<String,List<String>> committed=moneyRows();
            Trace receipt=observe("contract-create-no-requote-receipt-"+user);assertEquals(created.getId(),contracts.createOrderWithAuthority(user,request).getId());TRACE.remove();assertEquals(committed,moneyRows());assertFalse(receipt.moneyDml());
            assertFalse(receipt.events.stream().anyMatch(e->String.valueOf(e.get("sql")).contains("market_engine_runtime")));
            publish(config,"120");Trace close=observe("contract-public-close-actual-commit-ack-"+user);close.beforeRuntimeLock=()->close.dropCommitAcknowledgment=true;
            assertThrows(RuntimeException.class,()->contracts.closeOrderWithAuthority(user,created.getId(),new BigDecimal("999999")));TRACE.remove();close.assertFundingAuthorizationOrder("contract_order");
            ContractOrder closed=orders.findByTenantIdAndId(tenant,created.getId()).orElseThrow(AssertionError::new);assertEquals("CLOSED",closed.getStatus());money("120",closed.getClosePrice());money("10",closed.getProfit());
            money("1008",assets.findByTenantIdAndUserIdAndCoin(tenant,user,"CONTRACT").orElseThrow(AssertionError::new).getAvailable());money("0",assets.findByTenantIdAndUserIdAndCoin(tenant,user,"CONTRACT").orElseThrow(AssertionError::new).getFrozen());
            if("TRIAL".equals(source)){money("300",db.queryForObject("SELECT available FROM trial_account WHERE tenant_id=? AND user_id=?",BigDecimal.class,tenant,user));money("0",db.queryForObject("SELECT frozen FROM trial_account WHERE tenant_id=? AND user_id=?",BigDecimal.class,tenant,user));}
            db.update("UPDATE market_engine_runtime SET quote_json=NULL WHERE tenant_id=? AND symbol_id=?",tenant,config.getId());committed=moneyRows();Trace closedReceipt=observe("contract-public-close-no-requote-"+user);
            assertEquals(created.getId(),contracts.closeOrderWithAuthority(user,created.getId(),null).getId());TRACE.remove();assertEquals(committed,moneyRows());assertFalse(closedReceipt.moneyDml());assertFalse(closedReceipt.events.stream().anyMatch(e->String.valueOf(e.get("sql")).contains("market_engine_runtime")));
            for(String action:Arrays.asList("CONTRACT_CREATE","CONTRACT_CLOSE"))assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND action=? AND object_ref=?",Integer.class,tenant,action,created.getId().toString()).intValue());
        }
    }

    @Test void enabledContractLimitAndPendingUseCurrentKycCategoryAndMarketBeforeMaintenance()throws Exception {
        for(String guard:Arrays.asList("KYC","CATEGORY","MARKET")){
            long tenant=ownedContractTenant(),user=ownedContractUser(tenant);TradingSymbol config=contractCreateSymbol();trialFunds.grant(user,new BigDecimal("300"),null,null,"pending-current-"+user,1);publish(config,"110");
            com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest request=contractCreate(config,"TRIAL","LIMIT");request.setPrice(new BigDecimal("120"));request.setLeverage(new BigDecimal("2"));
            Trace creation=observe("contract-limit-authorized-create-"+user);ContractOrder order=contracts.createOrderWithAuthority(user,request);TRACE.remove();creation.assertFundingAuthorizationOrder("contract_order");assertEquals("PENDING",order.getStatus());money("60",order.getMargin());
            db.update("UPDATE trial_grant SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE tenant_id=? AND user_id=?",tenant,user);publish(config,"110");Map<String,List<String>> before=moneyRows();
            Trace trace=new Trace("contract-pending-current-"+guard+"-"+user);traces.add(trace);CountDownLatch reached=new CountDownLatch(1),release=new CountDownLatch(1);trace.beforeRuntimeLock=()->{reached.countDown();await(release);};ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                Future<Map<Long,String>> result=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){TRACE.set(trace);return contracts.matchPendingLimitOrdersS3(2);}finally{TRACE.remove();}});
                assertTrue(reached.await(10,TimeUnit.SECONDS));
                if("KYC".equals(guard))db.update("UPDATE kyc_record SET status='REJECTED' WHERE tenant_id=? AND user_id=?",tenant,user);
                if("CATEGORY".equals(guard))application.getBean(com.gtcfesk.exchange.admin.SystemConfigService.class).saveConfig("home.categories","[{\"key\":\"Crypto\",\"sortOrder\":1,\"leverageEnabled\":false}]","OWNED");
                if("MARKET".equals(guard)){MarketHoursConfig.Settings settings=MarketHoursConfig.defaults();MarketHoursConfig.Rule rule=new MarketHoursConfig.Rule();rule.mode="CLOSED";rule.reason="OWNED current pending guard";settings.symbols.put(config.getId().toString(),rule);application.getBean(com.gtcfesk.exchange.admin.SystemConfigService.class).saveConfig(MarketHoursConfig.KEY,MarketHoursConfig.encode(settings),"OWNED");}
                release.countDown();String state=result.get(15,TimeUnit.SECONDS).get(order.getId());assertEquals("MARKET".equals(guard)?"RETRY:BusinessException":"SKIPPED",state);
                assertEquals(before,moneyRows(),"Rejected current authority must precede grant expiry and pending cancellation");assertFalse(trace.moneyDml());assertS3PhysicalReadCommitted(trace);assertGrantLockBeforeRuntime(trace);assertPendingExpiryLockBeforeRuntime(trace);
            }finally{release.countDown();worker.shutdownNow();}
        }
    }

    @Test void enabledContractPreparedCloseAuthorizesBeforeCallbackAndRollsBackWholeCaller()throws Exception {
        long tenant=ownedContractTenant(),user=ownedContractUser(tenant);TradingSymbol config=contractCreateSymbol();trialFunds.grant(user,new BigDecimal("300"),null,null,"prepared-close-"+user,1);publish(config,"110");
        ContractOrder created=contracts.createOrderWithAuthority(user,contractCreate(config,"TRIAL","MARKET"));publish(config,"120");Map<String,Object> stale=contracts.prepareCloseQuote(created.getId());
        config.setPricePrecision(6);config=symbols.saveAndFlush(config);Map<String,List<String>> before=moneyRows();java.util.concurrent.atomic.AtomicBoolean called=new java.util.concurrent.atomic.AtomicBoolean();
        org.springframework.transaction.support.TransactionTemplate transaction=new org.springframework.transaction.support.TransactionTemplate(transactionManager);transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        Trace reject=observe("contract-prepared-close-reject-before-callback-"+user);assertThrows(RuntimeException.class,()->transaction.execute(status->contracts.closePrepared(user,created.getId(),stale,()->called.set(true))));TRACE.remove();assertFalse(called.get());assertFalse(reject.moneyDml());assertEquals(before,moneyRows());
        publish(config,"120");Map<String,Object> prepared=contracts.prepareCloseQuote(created.getId());Trace failure=observe("contract-prepared-close-caller-rollback-"+user);
        assertThrows(IllegalStateException.class,()->transaction.execute(status->{contracts.closePrepared(user,created.getId(),prepared,()->{called.set(true);trialFunds.snapshot(user);});throw new IllegalStateException("OWNED outer command failure");}));TRACE.remove();
        assertTrue(called.get());assertEquals(before,moneyRows());assertTrue(failure.events.stream().anyMatch(e->"rollback-return".equals(e.get("kind"))));
        Trace success=observe("contract-prepared-close-caller-commit-"+user);ContractOrder closed=transaction.execute(status->contracts.closePrepared(user,created.getId(),prepared,()->trialFunds.snapshot(user)));TRACE.remove();assertEquals("CLOSED",closed.getStatus());success.assertFundingAuthorizationOrder("contract_order");
        assertThrows(RuntimeException.class,()->transaction.execute(status->contracts.closePrepared(user,created.getId(),prepared,()->fail("No new command may snapshot an already closed order"))));
    }

    private long ownedContractTenant(){long tenant=ownedTradingTenant();com.gtcfesk.exchange.control.TenantPolicy policy=new com.gtcfesk.exchange.control.TenantPolicy();policy.setTenantId(tenant);policy.setKey("feature.contract");policy.setValue("true");policy.setLocked(true);application.getBean(com.gtcfesk.exchange.control.TenantPolicyRepository.class).saveAndFlush(policy);return tenant;}
    private long ownedContractUser(long tenant){long user=ownedOptionUser(tenant,true);db.update("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(?,?,'CONTRACT',1000,0,0)",tenant,user);return user;}
    private TradingSymbol contractCreateSymbol(){TradingSymbol config=symbol("USD");config.setLotSize(BigDecimal.ONE);config.setFeeMultiplier(new BigDecimal("2"));config.setMaxLeverage(new BigDecimal("10"));return symbols.saveAndFlush(config);}
    private com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest contractCreate(TradingSymbol symbol,String source,String type){com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest request=new com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest();request.setRequestId("owned-contract-create-"+IDS.incrementAndGet());request.setFundingSource(source);request.setSymbol(symbol.getSymbol());request.setSide("BUY");request.setType(type);request.setQuantity(BigDecimal.ONE);request.setLeverage(BigDecimal.ONE);request.setCurrentPrice(new BigDecimal("999999"));return request;}

    @Test void enabledOptionCreateTrialRollbackAckLossAndReceipt()throws Exception {
        long tenant=ownedTradingTenant(),user=ownedOptionUser(tenant,true);TradingSymbol config=symbol("USD");OptionDuration duration=optionDuration();
        trialFunds.grant(user,new BigDecimal("40"),null,null,"old-"+user,1);
        ContractOrder pending=new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
            trialFunds.lock(user);AssetAccount cash=assets.findByTenantIdAndUserIdAndCoin(tenant,user,"OPTION").orElseThrow(AssertionError::new);
            TrialFunds.Reservation reservation=trialFunds.reserve(user,cash,new BigDecimal("5"),"TRIAL","OPTION","OWNED_PREPENDING");
            ContractOrder row=new ContractOrder();row.setUserId(user);row.setSymbol(config.getSymbol());row.setStatus("PENDING");row.setType("LIMIT");row.setSide("BUY");
            row.setQuantity(BigDecimal.ONE);row.setLeverage(BigDecimal.ONE);row.setPrice(new BigDecimal("100"));row.setOpenPrice(new BigDecimal("100"));row.setMargin(new BigDecimal("5"));row.setFee(BigDecimal.ZERO);row.setProfit(BigDecimal.ZERO);
            row.setFundingSource("TRIAL");row.setTrialReserved(reservation.trial);row.setTrialAllocations(reservation.allocations);return orders.saveAndFlush(row);
        });
        trialFunds.grant(user,new BigDecimal("200"),null,null,"live-"+user,null);
        db.update("UPDATE trial_grant SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE tenant_id=? AND user_id=? AND request_key=?",tenant,user,"old-"+user);
        publish(config,"110");com.gtcfesk.exchange.trade.dto.CreateOptionOrderRequest request=optionCreate(config,duration,"TRIAL");
        Map<String,List<String>> before=moneyRows();String trigger="joc_"+user;ownedTriggers.add(trigger);
        db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON control_audit_log FOR EACH ROW BEGIN IF NEW.tenant_id="+tenant+" AND NEW.action='OPTION_CREATE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned create audit failure'; END IF; END");
        Trace failure=observe("option-create-trial-audit-failure-"+user);assertThrows(RuntimeException.class,()->optionService.createOrderWithAuthority(user,request));TRACE.remove();
        assertEquals(before,moneyRows(),"Create audit failure rolls back expiry, pending cancellation, grant reserve and inserted order");assertGrantLockBeforeRuntime(failure);assertPendingExpiryLockBeforeRuntime(failure);
        assertTrue(failure.events.stream().anyMatch(e->"sql-failed".equals(e.get("kind"))&&String.valueOf(e.get("sql")).contains("control_audit_log")));assertTrue(failure.events.stream().anyMatch(e->"rollback-return".equals(e.get("kind"))));db.execute("DROP TRIGGER "+trigger);
        Trace uncertain=observe("option-create-trial-real-commit-ack-"+user);uncertain.beforeRuntimeLock=()->uncertain.dropCommitAcknowledgment=true;
        assertThrows(RuntimeException.class,()->optionService.createOrderWithAuthority(user,request));TRACE.remove();uncertain.assertFundingAuthorizationOrder("option_order");
        assertTrue(uncertain.events.stream().anyMatch(e->"ack-fault-after-real-commit".equals(e.get("kind"))));
        OptionOrder created=optionOrders.findByTenantIdAndUserIdAndRequestKey(tenant,user,request.getRequestId()).orElseThrow(AssertionError::new);
        money("110",created.getOpenPrice());money("100",created.getTrialReserved());assertEquals("TRIAL",created.getFundingSource());
        assertEquals("CANCELLED",orders.findByTenantIdAndId(tenant,pending.getId()).orElseThrow(AssertionError::new).getStatus());
        money("40",db.queryForObject("SELECT expired FROM trial_account WHERE tenant_id=? AND user_id=?",BigDecimal.class,tenant,user));money("100",db.queryForObject("SELECT frozen FROM trial_account WHERE tenant_id=? AND user_id=?",BigDecimal.class,tenant,user));money("100",db.queryForObject("SELECT available FROM trial_account WHERE tenant_id=? AND user_id=?",BigDecimal.class,tenant,user));
        money("1000",assets.findByTenantIdAndUserIdAndCoin(tenant,user,"OPTION").orElseThrow(AssertionError::new).getAvailable());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND action='OPTION_CREATE' AND object_ref=?",Integer.class,tenant,created.getId().toString()).intValue());
        // Receipt must remain usable even when the current engine quote is unavailable.
        db.update("UPDATE market_engine_runtime SET quote_json=NULL WHERE tenant_id=? AND symbol_id=?",tenant,config.getId());
        Map<String,List<String>> committed=moneyRows();Trace recover=observe("option-create-no-requote-receipt-"+user);
        assertEquals(created.getId(),optionService.createOrderWithAuthority(user,request).getId());TRACE.remove();assertFalse(recover.moneyDml());assertEquals(committed,moneyRows());
        assertFalse(recover.events.stream().anyMatch(e->String.valueOf(e.get("sql")).contains("market_engine_runtime")));
        request.setAmount(new BigDecimal("101"));assertThrows(RuntimeException.class,()->optionService.createOrderWithAuthority(user,request));
    }

    @Test void enabledOptionCreateUsesCurrentPolicyKycDurationAndWindowBeforeMoney()throws Exception {
        long tenant=ownedTradingTenant();
        for(String guard:Arrays.asList("KYC","KYC_SWITCH","FEATURE","DURATION","WINDOW")){
            long user=ownedOptionUser(tenant,true);TradingSymbol config=symbol("USD");OptionDuration duration=optionDuration();
            trialFunds.grant(user,new BigDecimal("200"),null,null,"guard-"+user,1);
            db.update("UPDATE trial_grant SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE tenant_id=? AND user_id=?",tenant,user);
            if("KYC_SWITCH".equals(guard)){
                application.getBean(com.gtcfesk.exchange.admin.SystemConfigService.class).saveConfig("trade.kyc.required","false","OWNED");
                db.update("UPDATE kyc_record SET status='REJECTED' WHERE tenant_id=? AND user_id=?",tenant,user);
            }
            publish(config,"110");com.gtcfesk.exchange.trade.dto.CreateOptionOrderRequest request=optionCreate(config,duration,"TRIAL");
            Map<String,List<String>> before=moneyRows();Trace trace=new Trace("option-create-current-"+guard+"-"+user);traces.add(trace);
            CountDownLatch atAuthority=new CountDownLatch(1),release=new CountDownLatch(1);trace.beforeRuntimeLock=()->{atAuthority.countDown();await(release);};
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try{
                Future<?> future=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){TRACE.set(trace);RuntimeException denied=assertThrows(RuntimeException.class,()->optionService.createOrderWithAuthority(user,request));
                    if(guard.startsWith("KYC"))assertTrue(denied instanceof com.gtcfesk.exchange.common.KycRequiredException,denied.toString());
                    if("FEATURE".equals(guard))assertTrue(denied.getMessage().contains("功能未获授权"),denied.toString());
                    if("DURATION".equals(guard))assertTrue(denied.getMessage().contains("交易周期不存在或已停用"),denied.toString());
                    if("WINDOW".equals(guard))assertTrue(denied.getMessage().contains("当前休市"),denied.toString());
                }finally{TRACE.remove();}});
                assertTrue(atAuthority.await(10,TimeUnit.SECONDS));
                if("KYC".equals(guard))db.update("UPDATE kyc_record SET status='REJECTED' WHERE tenant_id=? AND user_id=?",tenant,user);
                if("KYC_SWITCH".equals(guard))application.getBean(com.gtcfesk.exchange.admin.SystemConfigService.class).saveConfig("trade.kyc.required","true","OWNED");
                if("FEATURE".equals(guard))db.update("UPDATE tenant_policy SET policy_value='false',version=version+1 WHERE tenant_id=? AND policy_key='feature.option'",tenant);
                if("DURATION".equals(guard)){OptionDuration disabled=new OptionDuration();disabled.setEnabled(false);application.getBean(com.gtcfesk.exchange.admin.AdminDurationController.class).updateDuration(duration.getId(),disabled);}
                if("WINDOW".equals(guard)){
                    MarketHoursConfig.Settings settings=MarketHoursConfig.defaults();MarketHoursConfig.Rule rule=new MarketHoursConfig.Rule();rule.mode="CLOSED";rule.reason="OWNED current guard";settings.symbols.put(config.getId().toString(),rule);
                    application.getBean(com.gtcfesk.exchange.admin.SystemConfigService.class).saveConfig(MarketHoursConfig.KEY,MarketHoursConfig.encode(settings),"OWNED");
                }
                release.countDown();future.get(15,TimeUnit.SECONDS);assertFalse(trace.moneyDml());assertEquals(before,moneyRows());assertGrantLockBeforeRuntime(trace);
            }finally{release.countDown();worker.shutdownNow();}
            if("FEATURE".equals(guard))db.update("UPDATE tenant_policy SET policy_value='true',version=version+1 WHERE tenant_id=? AND policy_key='feature.option'",tenant);
        }
    }

    @Test void enabledOptionCreateSharesTenantAuthorityAcrossIndependentCashUsers()throws Exception {
        long tenant=ownedTradingTenant(),first=ownedOptionUser(tenant,true),second=ownedOptionUser(tenant,true);TradingSymbol a=symbol("USD"),b=symbol("USD");
        OptionDuration da=optionDuration(),dbb=optionDuration();publish(a,"110");publish(b,"120");
        com.gtcfesk.exchange.trade.dto.CreateOptionOrderRequest ra=optionCreate(a,da,"OPTION"),rb=optionCreate(b,dbb,"OPTION");
        Trace trace=new Trace("option-create-shared-tenant-"+first);traces.add(trace);CountDownLatch authorized=new CountDownLatch(1),release=new CountDownLatch(1);
        trace.beforeMoneyDml=()->{authorized.countDown();await(release);};ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<OptionOrder> fa=workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){TRACE.set(trace);return optionService.createOrderWithAuthority(first,ra);}finally{TRACE.remove();}});
            assertTrue(authorized.await(10,TimeUnit.SECONDS));
            Future<OptionOrder> fb=workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){return optionService.createOrderWithAuthority(second,rb);}});
            OptionOrder secondOrder=fb.get(5,TimeUnit.SECONDS);assertFalse(fa.isDone(),"A is still holding its shared tenant authority and funding locks");money("120",secondOrder.getOpenPrice());
            release.countDown();OptionOrder firstOrder=fa.get(15,TimeUnit.SECONDS);money("110",firstOrder.getOpenPrice());trace.assertFundingAuthorizationOrder("option_order");
            for(long user:Arrays.asList(first,second)){AssetAccount wallet=assets.findByTenantIdAndUserIdAndCoin(tenant,user,"OPTION").orElseThrow(AssertionError::new);money("900",wallet.getAvailable());money("100",wallet.getFrozen());}
        }finally{release.countDown();workers.shutdownNow();}
    }

    @Test void currentKycFirstSubmissionsAvoidCrossUserGapAndReviewLocksUserFirst()throws Exception {
        long tenant=ownedTradingTenant(),first=ownedOptionUser(tenant,false),second=ownedOptionUser(tenant,false);
        com.gtcfesk.exchange.user.KycIdentityService identity=application.getBean(com.gtcfesk.exchange.user.KycIdentityService.class);
        KycRecord a=kycSubmission(first),b=kycSubmission(second);Trace trace=new Trace("kyc-first-no-gap-"+first);traces.add(trace);
        CountDownLatch empty=new CountDownLatch(1),release=new CountDownLatch(1);trace.afterKycRead=()->{empty.countDown();await(release);};ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<KycRecord> fa=workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){TRACE.set(trace);return identity.submitCurrent(a);}finally{TRACE.remove();}});
            assertTrue(empty.await(10,TimeUnit.SECONDS));
            Future<KycRecord> fb=workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){return identity.submitCurrent(b);}});
            KycRecord secondRow=fb.get(5,TimeUnit.SECONDS);assertFalse(fa.isDone());release.countDown();KycRecord firstRow=fa.get(15,TimeUnit.SECONDS);
            assertS3PhysicalReadCommitted(trace);assertThrows(RuntimeException.class,()->identity.submitCurrent(kycSubmission(first)));
            Trace review=observe("kyc-review-user-first-"+first);
            assertEquals(200,application.getBean(com.gtcfesk.exchange.admin.KycReviewController.class).approveKyc(null,firstRow.getId(),Collections.singletonMap("remark","OWNED")).getStatusCodeValue());TRACE.remove();
            long userAt=review.events.stream().filter(e->"sql-return".equals(e.get("kind"))&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("user_account")&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("for update")).mapToLong(e->((Number)e.get("at_nanos")).longValue()).min().orElseThrow(AssertionError::new);
            long kycAt=review.events.stream().filter(e->"sql-return".equals(e.get("kind"))&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("kyc_record")&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("for update")).mapToLong(e->((Number)e.get("at_nanos")).longValue()).min().orElseThrow(AssertionError::new);assertTrue(userAt<kycAt);
            assertEquals(200,application.getBean(com.gtcfesk.exchange.admin.KycReviewController.class).rejectKyc(null,secondRow.getId(),Collections.singletonMap("remark","OWNED")).getStatusCodeValue());
            assertEquals("VERIFIED",db.queryForObject("SELECT kyc_status FROM user_account WHERE tenant_id=? AND id=?",String.class,tenant,first));
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM kyc_record WHERE tenant_id=? AND user_id IN (?,?)",Integer.class,tenant,first,second).intValue());
        }finally{release.countDown();workers.shutdownNow();}
        assertThrows(IllegalStateException.class,()->new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->identity.submitCurrent(kycSubmission(first))));
    }

    @Test void enabledOptionHistoricalMissingDurationKeepsDefaultUntilCommitAndConfigWriterRetainsDirtyTenant()throws Exception {
        long tenant=ownedTradingTenant(),user=ownedOptionUser(tenant,true);TradingSymbol config=symbol("USD");
        OptionDuration missing=new OptionDuration();missing.setDuration(1200000+(int)(IDS.incrementAndGet()%100000));
        OptionOrder order=optionOrder(user,config,missing,"OPTION",BigDecimal.ZERO,null);
        db.update("UPDATE asset_account SET frozen=100 WHERE tenant_id=? AND user_id=? AND coin='OPTION'",tenant,user);publish(config,"110");
        Trace trace=new Trace("option-missing-duration-anchor-"+order.getId());traces.add(trace);
        CountDownLatch missingRead=new CountDownLatch(1),release=new CountDownLatch(1),writerStarted=new CountDownLatch(1);
        trace.afterDurationRead=()->{missingRead.countDown();await(release);};ExecutorService workers=Executors.newFixedThreadPool(2);
        try{
            Future<OptionOrder> funding=workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){TRACE.set(trace);return optionService.closeOrderWithAuthority(user,order.getId(),BigDecimal.ZERO);}finally{TRACE.remove();}});
            assertTrue(missingRead.await(10,TimeUnit.SECONDS));
            Future<?> writer=workers.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){
                OptionDuration added=new OptionDuration();added.setDuration(missing.getDuration());added.setLabel("OWNED");added.setProfitRate(new BigDecimal("0.5"));writerStarted.countDown();
                return application.getBean(com.gtcfesk.exchange.admin.AdminDurationController.class).createDuration(added);
            }});
            assertTrue(writerStarted.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->writer.get(1,TimeUnit.SECONDS));
            release.countDown();OptionOrder closed=funding.get(15,TimeUnit.SECONDS);writer.get(15,TimeUnit.SECONDS);
            money("80",closed.getProfit());money("1180",assets.findByTenantIdAndUserIdAndCoin(tenant,user,"OPTION").orElseThrow(AssertionError::new).getAvailable());
            trace.assertFundingAuthorizationOrder("option_order");
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM option_duration WHERE tenant_id=? AND duration=?",Integer.class,tenant,missing.getDuration()).intValue());
        }finally{release.countDown();workers.shutdownNow();}
        Trace write=observe("config-writer-dirty-tenant-after-anchor-"+tenant);
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
            com.gtcfesk.exchange.control.Tenant row=application.getBean(com.gtcfesk.exchange.control.TenantRepository.class).findById(tenant).orElseThrow(AssertionError::new);
            row.setName("OWNED retained dirty name");application.getBean(com.gtcfesk.exchange.admin.SystemConfigService.class).saveConfig("site.name","OWNED retained site","OWNED");return null;
        });TRACE.remove();
        assertEquals("OWNED retained dirty name",db.queryForObject("SELECT name FROM tenant WHERE id=?",String.class,tenant));
        long lock=write.events.stream().filter(e->"sql-return".equals(e.get("kind"))&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("from tenant where")&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("for update")).mapToLong(e->((Number)e.get("at_nanos")).longValue()).min().orElseThrow(AssertionError::new);
        long change=write.events.stream().filter(e->"sql-call".equals(e.get("kind"))&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).matches("(?s).*update\\s+tenant\\s+set.*")).mapToLong(e->((Number)e.get("at_nanos")).longValue()).min().orElseThrow(AssertionError::new);assertTrue(lock<change,"Dirty tenant flush occurs only after its current WRITE anchor");
    }

    private long ownedTradingTenant(){
        com.gtcfesk.exchange.control.Tenant tenant=new com.gtcfesk.exchange.control.Tenant();String key="owned-create-"+IDS.incrementAndGet();
        tenant.setCode(key);tenant.setName("OWNED SYNTHETIC NEW BUSINESS");tenant.setFrontendHost(key+".fixture.invalid");tenant.setStatus("ACTIVE");tenant.setConfigReady(true);tenant.setDomainVerified(true);
        tenant=application.getBean(com.gtcfesk.exchange.control.TenantRepository.class).saveAndFlush(tenant);long id=tenant.getId();scope.close();scope=TenantContext.open(id);
        com.gtcfesk.exchange.control.TenantPolicy feature=new com.gtcfesk.exchange.control.TenantPolicy();feature.setTenantId(id);feature.setKey("feature.option");feature.setValue("true");feature.setLocked(true);application.getBean(com.gtcfesk.exchange.control.TenantPolicyRepository.class).saveAndFlush(feature);
        for(String[] pair:new String[][]{{"site.name","OWNED fixture"},{"system.timezone","UTC"}}){SystemConfig config=new SystemConfig();config.setConfigKey(pair[0]);config.setConfigValue(pair[1]);application.getBean(SystemConfigRepository.class).saveAndFlush(config);}
        com.gtcfesk.exchange.admin.AdminUser admin=new com.gtcfesk.exchange.admin.AdminUser();admin.setAccount(key);admin.setEmail(key+"@fixture.invalid");admin.setPasswordHash("not-a-login");admin.setRole("super_admin");admin.setEnabled(true);
        admin=application.getBean(com.gtcfesk.exchange.admin.AdminUserRepository.class).saveAndFlush(admin);
        com.gtcfesk.exchange.control.BackendLogin login=new com.gtcfesk.exchange.control.BackendLogin();login.setTenantId(id);login.setAdminUserId(admin.getId());login.setNormalizedAccount(key);login.setSubjectType("ADMIN");application.getBean(com.gtcfesk.exchange.control.BackendLoginRepository.class).saveAndFlush(login);return id;
    }
    private long ownedOptionUser(long tenant,boolean approved){
        long id=IDS.incrementAndGet();db.update("INSERT INTO user_account(tenant_id,id,email,password_hash,status,user_type,created_at,updated_at,row_version) VALUES(?,?,?,'not-a-login','normal','normal',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",tenant,id,"owned-"+id+"@fixture.invalid");
        db.update("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(?,?,'OPTION',1000,0,0)",tenant,id);
        if(approved)db.update("INSERT INTO kyc_record(tenant_id,user_id,real_name,id_number,status,created_at,updated_at) VALUES(?,?,'OWNED SYNTHETIC IDENTITY','NOT-A-REAL-IDENTITY','APPROVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",tenant,id);return id;
    }
    private KycRecord kycSubmission(long user){KycRecord row=new KycRecord();row.setUserId(user);row.setRealName("OWNED SYNTHETIC IDENTITY");row.setIdNumber("NOT-A-REAL-IDENTITY");row.setIdFrontImage("/owned-not-a-real-image");row.setIdBackImage("/owned-not-a-real-image");return row;}
    private com.gtcfesk.exchange.trade.dto.CreateOptionOrderRequest optionCreate(TradingSymbol symbol,OptionDuration duration,String source){
        com.gtcfesk.exchange.trade.dto.CreateOptionOrderRequest request=new com.gtcfesk.exchange.trade.dto.CreateOptionOrderRequest();request.setRequestId("owned-create-"+IDS.incrementAndGet());request.setSymbol(symbol.getSymbol());request.setDirection("UP");request.setAmount(new BigDecimal("100"));request.setDuration(duration.getDuration());request.setFundingSource(source);request.setCurrentPrice(new BigDecimal("999999"));return request;
    }

    @Test void enabledContractTrialPortfolioSecondLedgerFailureKeepsPendingExpiryAtomic()throws Exception {
        long user=user("0","0");TradingSymbol config=symbol("USD");trialFunds.grant(user,new BigDecimal("40"),null,null,"contract-trial-portfolio-"+user,1);
        ContractOrder first=reservedContract(user,config,"OPEN","TRIAL","10","10"),second=reservedContract(user,config,"OPEN","TRIAL","10","10");
        ContractOrder pending=reservedContract(user,config,"PENDING","TRIAL","5","5");
        db.update("UPDATE trial_grant SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE tenant_id=? AND user_id=?",TENANT,user);
        publish(config,"80");Map<String,List<String>> before=moneyRows();
        String trigger="joint_owned_trial_ledger_"+second.getId();ownedTriggers.add(trigger);
        db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON trial_ledger FOR EACH ROW BEGIN IF NEW.tenant_id="+TENANT+" AND NEW.user_id="+user+
                " AND NEW.reason='CONTRACT_SETTLE:"+second.getId()+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='joint owned second trial settlement ledger failure'; END IF; END");
        Trace failure=observe("contract-trial-portfolio-fail-"+second.getId());
        assertThrows(RuntimeException.class,()->contracts.checkAndForceClosePortfolioS3(user,"TRIAL"));TRACE.remove();
        assertEquals(before,moneyRows(),"Expired grant, pending cancellation, both positions, wallet and first audit all roll back");
        assertEquals(0,failure.commitCalls());assertFalse(failure.events.stream().noneMatch(e->"rollback-return".equals(e.get("kind"))));
        assertS3PhysicalReadCommitted(failure);assertGrantLockBeforeRuntime(failure);assertPendingExpiryLockBeforeRuntime(failure);
        db.execute("DROP TRIGGER "+trigger);ownedTriggers.remove(trigger);publish(config,"80");
        Trace retry=observe("contract-trial-portfolio-retry-"+second.getId());
        assertEquals(2,contracts.checkAndForceClosePortfolioS3(user,"TRIAL"));TRACE.remove();
        assertEquals("CLOSED",orders.findByTenantIdAndId(TENANT,first.getId()).orElseThrow(AssertionError::new).getStatus());
        assertEquals("CLOSED",orders.findByTenantIdAndId(TENANT,second.getId()).orElseThrow(AssertionError::new).getStatus());
        assertEquals("CANCELLED",orders.findByTenantIdAndId(TENANT,pending.getId()).orElseThrow(AssertionError::new).getStatus());
        money("0",wallet(user).getAvailable());money("0",wallet(user).getFrozen());
        Map<String,Object> trial=db.queryForMap("SELECT available,frozen,expired,uncovered_loss FROM trial_account WHERE tenant_id=? AND user_id=?",TENANT,user);
        money("0",(BigDecimal)trial.get("available"));money("0",(BigDecimal)trial.get("frozen"));money("40",(BigDecimal)trial.get("expired"));money("40",(BigDecimal)trial.get("uncovered_loss"));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM trial_ledger WHERE tenant_id=? AND user_id=? AND reason=?",Integer.class,TENANT,user,"TRIAL_EXPIRED_CANCEL:"+pending.getId()).intValue());
        assertEquals(1,audits("S3_CONTRACT_FORCE_CLOSE",first.getId()));assertEquals(1,audits("S3_CONTRACT_FORCE_CLOSE",second.getId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND action='S3_CONTRACT_FORCE_PORTFOLIO' AND object_ref=?",Integer.class,TENANT,user+":TRIAL").intValue());
        retry.assertFundingAuthorizationOrder("contract_order");assertPendingExpiryLockBeforeRuntime(retry);
        Map<String,List<String>> committed=moneyRows();Trace repeat=observe("contract-trial-portfolio-repeat-"+second.getId());
        assertEquals(0,contracts.checkAndForceClosePortfolioS3(user,"TRIAL"));TRACE.remove();assertFalse(repeat.moneyDml());assertEquals(committed,moneyRows());
    }

    @Test void enabledContractTrialAndNullPendingResizeKeepRecordedFunding()throws Exception {
        for(String source:Arrays.asList("TRIAL",null)) {
            long user=user("1000","0");TradingSymbol config=symbol("USD");
            trialFunds.grant(user,new BigDecimal(source==null?"40":"200"),null,null,"contract-resize-grant-"+user,1);
            ContractOrder pending=reservedContract(user,config,"PENDING",source,"100",source==null?"40":"100");
            pending.setPrice(new BigDecimal("120"));orders.saveAndFlush(pending);publish(config,"110");
            Trace fill=observe("contract-trial-null-fill-"+pending.getId());
            assertEquals("OPENED",contracts.matchPendingLimitOrdersS3(100).get(pending.getId()));TRACE.remove();
            ContractOrder current=orders.findByTenantIdAndId(TENANT,pending.getId()).orElseThrow(AssertionError::new);
            assertEquals(source,current.getFundingSource());money("110",current.getMargin());money("110",current.getOpenPrice());money(source==null?"40":"110",current.getTrialReserved());
            money(source==null?"930":"1000",wallet(user).getAvailable());money(source==null?"70":"0",wallet(user).getFrozen());
            fill.assertFundingAuthorizationOrder("contract_order");assertS3PhysicalReadCommitted(fill);assertGrantLockBeforeRuntime(fill);
            assertEquals(1,audits("S3_CONTRACT_FILL",current.getId()));current.setTakeProfit(new BigDecimal("115"));orders.saveAndFlush(current);publish(config,"120");
            Trace close=observe("contract-trial-null-auto-"+pending.getId());
            assertEquals("CLOSED",contracts.checkAndAutoCloseOrdersS3(100).get(pending.getId()));TRACE.remove();
            money("1010",wallet(user).getAvailable());money("0",wallet(user).getFrozen());
            money(source==null?"40":"200",db.queryForObject("SELECT available FROM trial_account WHERE tenant_id=? AND user_id=?",BigDecimal.class,TENANT,user));
            money("0",db.queryForObject("SELECT frozen FROM trial_account WHERE tenant_id=? AND user_id=?",BigDecimal.class,TENANT,user));
            assertEquals(1,audits("S3_CONTRACT_AUTO_CLOSE",pending.getId()));close.assertFundingAuthorizationOrder("contract_order");
        }
    }

    @Test void enabledContractTrialStopRejectsBeforePendingExpiryAndMoneyWrites()throws Exception {
        long user=user("0","0");TradingSymbol config=symbol("USD");trialFunds.grant(user,new BigDecimal("20"),null,null,"contract-stop-grant-"+user,1);
        ContractOrder open=reservedContract(user,config,"OPEN","TRIAL","10","10"),pending=reservedContract(user,config,"PENDING","TRIAL","5","5");
        db.update("UPDATE trial_grant SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE tenant_id=? AND user_id=?",TENANT,user);
        publish(config,"80");Map<String,Object> raw=raw("80",clock());actualControls.start(config,raw,new BigDecimal("80"),60,new BigDecimal("81"),1,false,false,"contract-stop-"+UUID.randomUUID());
        actualControls.pump(config,raw,clock(),60000);Map<String,List<String>> before=moneyRows();
        Trace trace=new Trace("contract-trial-stop-reject-"+open.getId());traces.add(trace);CountDownLatch prepared=new CountDownLatch(1),release=new CountDownLatch(1);
        trace.beforeRuntimeLock=()->{prepared.countDown();await(release);};ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<Integer> result=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(TENANT)){TRACE.set(trace);
                try{return contracts.checkAndForceClosePortfolioS3(user,"TRIAL");}finally{TRACE.remove();}}});
            assertTrue(prepared.await(10,TimeUnit.SECONDS));actualControls.stop(config.getId(),clock());release.countDown();
            ExecutionException denied=assertThrows(ExecutionException.class,()->result.get(15,TimeUnit.SECONDS));assertTrue(denied.getCause() instanceof com.gtcfesk.exchange.common.BusinessException);
            assertEquals(before,moneyRows());assertFalse(trace.moneyDml());assertGrantLockBeforeRuntime(trace);assertPendingExpiryLockBeforeRuntime(trace);
            assertEquals("PENDING",orders.findByTenantIdAndId(TENANT,pending.getId()).orElseThrow(AssertionError::new).getStatus());
        }finally{release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
    }

    private ContractOrder reservedContract(long user,TradingSymbol symbol,String status,String source,String cost,String trialPortion){
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(tx->{
            trialFunds.lock(user);AssetAccount cash=wallet(user);BigDecimal total=new BigDecimal(cost),portion=new BigDecimal(trialPortion);
            TrialFunds.Reservation bonus=trialFunds.reserve(user,cash,portion,"TRIAL","CONTRACT","CONTRACT_FIXTURE_RESERVE");
            if(total.compareTo(portion)>0)trialFunds.reserve(user,cash,total.subtract(portion),"CONTRACT","CONTRACT","CONTRACT_FIXTURE_RESERVE");
            ContractOrder order=order(user,symbol,status,cost);order.setFundingSource(source);order.setTrialReserved(portion);order.setTrialAllocations(source==null?null:bonus.allocations);order.setLotSize(BigDecimal.ONE);
            if("PENDING".equals(status)){order.setType("LIMIT");order.setLimitMatchEnabled(true);order.setPrice(new BigDecimal("120"));}
            return orders.saveAndFlush(order);
        });
    }
    private void assertPendingExpiryLockBeforeRuntime(Trace trace){
        Map<String,Object> runtime=trace.events.stream().filter(e->"sql-return".equals(e.get("kind"))&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("market_engine_runtime")&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("for update")).findFirst().orElseThrow(AssertionError::new);
        long connection=((Number)runtime.get("connection_id")).longValue(),at=((Number)runtime.get("at_nanos")).longValue();
        assertTrue(trace.events.stream().anyMatch(e->((Number)e.get("connection_id")).longValue()==connection&&"sql-return".equals(e.get("kind"))&&((Number)e.get("at_nanos")).longValue()<at&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("contract_order")&&String.valueOf(e.get("sql")).contains("status='PENDING'")&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("for update")),"The full expiry cancellation set must be current-locked before quote runtime authority");
    }

    @Test void enabledOptionCurrentAnchorAfterEarlierReadLocksNewGrantBeforeOrder()throws Exception {
        long user=optionUser("1000","100");TradingSymbol config=symbol("USD");OptionDuration duration=optionDuration();
        OptionOrder order=optionOrder(user,config,duration,"OPTION",BigDecimal.ZERO,null);publish(config,"110");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM trial_account WHERE tenant_id=? AND user_id=?",Integer.class,TENANT,user).intValue());
        Trace trace=new Trace("option-rc-anchor-after-read-"+order.getId());traces.add(trace);
        CountDownLatch earlierRead=new CountDownLatch(1),grantCommitted=new CountDownLatch(1);
        trace.beforeUserLock=()->{earlierRead.countDown();await(grantCommitted);};
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<OptionOrder> result=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(TENANT)){TRACE.set(trace);
                try{return optionService.closeOrderWithAuthority(user,order.getId(),null);}finally{TRACE.remove();}}});
            assertTrue(earlierRead.await(10,TimeUnit.SECONDS));
            assertTrue(trace.events.stream().anyMatch(e->"sql-return".equals(e.get("kind"))&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("user_account")&&!String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("for update")),"An earlier consistent read must occur before the concurrent first grant commits");
            trialFunds.grant(user,new BigDecimal("100"),null,null,"option-rc-first-grant-"+user,1);
            grantCommitted.countDown();assertEquals("CLOSED",result.get(15,TimeUnit.SECONDS).getStatus());
            Map<String,Object> firstCommit=trace.events.stream().filter(e->"commit-return".equals(e.get("kind"))).findFirst().orElseThrow(AssertionError::new);
            long connection=((Number)firstCommit.get("connection_id")).longValue(),at=((Number)firstCommit.get("at_nanos")).longValue();
            assertTrue(trace.events.stream().anyMatch(e->((Number)e.get("connection_id")).longValue()==connection&&((Number)e.get("at_nanos")).longValue()<at&&"sql-return".equals(e.get("kind"))&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("trial_grant")&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("for update")),"The FIRST physical transaction must lock the newly committed grant; a later receipt transaction cannot hide an RR miss");
            assertS3PhysicalReadCommitted(trace);assertGrantLockBeforeRuntime(trace);trace.assertFundingAuthorizationOrder("option_order");
            money("100",db.queryForObject("SELECT available FROM trial_account WHERE tenant_id=? AND user_id=?",BigDecimal.class,TENANT,user));
            money("1180",optionWallet(user).getAvailable());money("0",optionWallet(user).getFrozen());
        }finally{grantCommitted.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void enabledOptionMissingAnchorDoesNotBlockOtherFirstGrantAndLegacyKeepsDefault()throws Exception {
        long user=optionUser("1000","100"),other=optionUser("1000","0");
        TradingSymbol config=symbol("USD");OptionDuration duration=optionDuration();OptionOrder order=optionOrder(user,config,duration,"OPTION",BigDecimal.ZERO,null);publish(config,"110");
        Trace trace=new Trace("option-rc-missing-anchor-"+order.getId());traces.add(trace);
        CountDownLatch fundsLocked=new CountDownLatch(1),release=new CountDownLatch(1);
        trace.beforeRuntimeLock=()->{fundsLocked.countDown();await(release);};
        ExecutorService worker=Executors.newFixedThreadPool(2);
        try {
            Future<OptionOrder> close=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(TENANT)){TRACE.set(trace);
                try{return optionService.closeOrderWithAuthority(user,order.getId(),null);}finally{TRACE.remove();}}});
            assertTrue(fundsLocked.await(10,TimeUnit.SECONDS));
            Future<?> firstGrant=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(TENANT)){
                trialFunds.grant(other,new BigDecimal("100"),null,null,"option-other-first-grant-"+other,1);}});
            firstGrant.get(5,TimeUnit.SECONDS); // Must finish while the first user's funding transaction still holds locks.
            assertFalse(close.isDone());release.countDown();assertEquals("CLOSED",close.get(15,TimeUnit.SECONDS).getStatus());
            assertS3PhysicalReadCommitted(trace);assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM trial_account WHERE tenant_id=? AND user_id=?",Integer.class,TENANT,user).intValue());
            Object service=org.springframework.test.util.AopTestUtils.getUltimateTargetObject(optionService);
            ReflectionTestUtils.setField(service,"s3SchedulingEnabled",false);
            try {
                org.springframework.transaction.support.TransactionTemplate legacy=ReflectionTestUtils.invokeMethod(service,"required");
                assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED,legacy.getPropagationBehavior());
                assertEquals(org.springframework.transaction.TransactionDefinition.ISOLATION_DEFAULT,legacy.getIsolationLevel());
                Integer isolation=legacy.execute(status->db.execute((org.springframework.jdbc.core.ConnectionCallback<Integer>)Connection::getTransactionIsolation));
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ,isolation.intValue(),"Legacy still inherits the certified MySQL RR default");
            }finally{ReflectionTestUtils.setField(service,"s3SchedulingEnabled",true);}
        }finally{release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
    }

    private void assertS3PhysicalReadCommitted(Trace trace){
        List<Map<String,Object>> locks=new ArrayList<>();for(Map<String,Object> event:trace.events)if("user-lock-isolation".equals(event.get("kind")))locks.add(event);
        assertFalse(locks.isEmpty());for(Map<String,Object> event:locks)assertEquals(Integer.toString(Connection.TRANSACTION_READ_COMMITTED),event.get("sql"),"Actual funded connection isolation, not only template configuration");
    }

    @Test void enabledOptionExpiryRollbackKeepsSiblingCommitAndRetrySingle()throws Exception {
        long failed=optionUser("1000","100"),healthy=optionUser("1000","100");
        TradingSymbol config=symbol("USD");OptionDuration duration=optionDuration();
        OptionOrder first=optionOrder(failed,config,duration,"OPTION",BigDecimal.ZERO,null);
        OptionOrder second=optionOrder(healthy,config,duration,"OPTION",BigDecimal.ZERO,null);
        first.setPresetProfitType("LOSS");optionOrders.saveAndFlush(first);
        List<Map<String,Object>> walletBefore=db.queryForList("SELECT * FROM asset_account WHERE tenant_id=? AND user_id=? ORDER BY coin,id",TENANT,failed);
        List<Map<String,Object>> orderBefore=db.queryForList("SELECT * FROM option_order WHERE tenant_id=? AND id=?",TENANT,first.getId());
        String trigger="joint_owned_option_"+first.getId();ownedTriggers.add(trigger);
        db.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON option_order FOR EACH ROW BEGIN IF NEW.tenant_id="+TENANT+
                " AND NEW.id="+first.getId()+" AND NEW.status='CLOSED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='joint owned option flush failure'; END IF; END");
        publish(config,"110");Trace run=observe("option-expiry-sibling-"+first.getId());
        scope.close();scope=null;TenantContext.clear();
        try{actualProcessor.settleOptions();}finally{TRACE.remove();scope=TenantContext.open(TENANT);}
        assertEquals(walletBefore,db.queryForList("SELECT * FROM asset_account WHERE tenant_id=? AND user_id=? ORDER BY coin,id",TENANT,failed));
        assertEquals(orderBefore,db.queryForList("SELECT * FROM option_order WHERE tenant_id=? AND id=?",TENANT,first.getId()));
        assertEquals(0,audits("OPTION_SETTLE",first.getId()));
        assertEquals("CLOSED",optionOrders.findByTenantIdAndId(TENANT,second.getId()).orElseThrow(AssertionError::new).getStatus());
        money("1180",optionWallet(healthy).getAvailable());money("0",optionWallet(healthy).getFrozen());
        assertEquals(1,audits("OPTION_SETTLE",second.getId()));
        assertTrue(run.events.stream().anyMatch(e->"sql-failed".equals(e.get("kind"))&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("option_order")));
        assertTrue(run.events.stream().anyMatch(e->"rollback-return".equals(e.get("kind"))));
        run.assertFundingAuthorizationOrder("option_order");
        db.execute("DROP TRIGGER "+trigger);ownedTriggers.remove(trigger);
        publish(config,"110");Trace retry=observe("option-expiry-retry-"+first.getId());
        OptionOrder closed=optionService.closeOrderWithAuthority(failed,first.getId(),new BigDecimal("999999"));TRACE.remove();
        assertEquals("CLOSED",closed.getStatus());money("-50",closed.getProfit());money("110",closed.getClosePrice());
        money("1050",optionWallet(failed).getAvailable());money("0",optionWallet(failed).getFrozen());
        assertEquals(1,audits("OPTION_SETTLE",first.getId()));retry.assertFundingAuthorizationOrder("option_order");
        Map<String,List<String>> committed=moneyRows();Trace duplicate=observe("option-expiry-repeat-"+first.getId());
        assertEquals(first.getId(),optionService.closeOrderWithAuthority(failed,first.getId(),null).getId());TRACE.remove();
        assertFalse(duplicate.moneyDml());assertEquals(committed,moneyRows());
        assertFalse(duplicate.events.stream().anyMatch(e->String.valueOf(e.get("sql")).contains("market_engine_runtime")),"CLOSED receipt cannot depend on a fresh quote");
    }

    @Test void enabledOptionTrialAndHistoricalNullKeepExpiredPrincipalSeparate()throws Exception {
        for(String source:Arrays.asList("TRIAL",null)) {
            long user=optionUser("1000","0");TradingSymbol config=symbol("USD");OptionDuration duration=optionDuration();
            trialFunds.grant(user,new BigDecimal("100"),null,null,"option-owned-grant-"+user,1);
            OptionOrder seeded=new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
                trialFunds.lock(user);AssetAccount cash=optionWallet(user);
                BigDecimal portion=new BigDecimal(source==null?"40":"100");
                TrialFunds.Reservation trial=trialFunds.reserve(user,cash,portion,"TRIAL","OPTION","OPTION_FIXTURE_RESERVE");
                if(source==null)trialFunds.reserve(user,cash,new BigDecimal("60"),"OPTION","OPTION","OPTION_FIXTURE_RESERVE");
                return optionOrder(user,config,duration,source,portion,source==null?null:trial.allocations);
            });
            db.update("UPDATE trial_grant SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE tenant_id=? AND user_id=?",TENANT,user);
            publish(config,"110");Trace close=observe("option-expired-"+(source==null?"NULL":"TRIAL")+"-"+seeded.getId());
            OptionOrder result=optionService.closeOrderWithAuthority(user,seeded.getId(),null);TRACE.remove();
            assertEquals("CLOSED",result.getStatus());assertEquals(source,result.getFundingSource());money("80",result.getProfit());
            money("1080",optionWallet(user).getAvailable());money("0",optionWallet(user).getFrozen());
            Map<String,Object> trial=db.queryForMap("SELECT available,frozen,expired,profits FROM trial_account WHERE tenant_id=? AND user_id=?",TENANT,user);
            money("0",(BigDecimal)trial.get("available"));money("0",(BigDecimal)trial.get("frozen"));money("100",(BigDecimal)trial.get("expired"));money("80",(BigDecimal)trial.get("profits"));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM trial_ledger WHERE tenant_id=? AND user_id=? AND reason=?",Integer.class,TENANT,user,"OPTION_SETTLE:"+seeded.getId()).intValue());
            assertEquals(1,audits("OPTION_SETTLE",seeded.getId()));close.assertFundingAuthorizationOrder("option_order");
            assertGrantLockBeforeRuntime(close);
        }
    }

    @Test void enabledOptionStoppedQuoteRejectsBeforeTrialExpiryMoneyDml()throws Exception {
        long user=optionUser("1000","0");TradingSymbol config=symbol("USD");OptionDuration duration=optionDuration();
        trialFunds.grant(user,new BigDecimal("100"),null,null,"option-reject-grant-"+user,1);
        OptionOrder order=new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status->{
            trialFunds.lock(user);TrialFunds.Reservation trial=trialFunds.reserve(user,optionWallet(user),new BigDecimal("100"),"TRIAL","OPTION","OPTION_FIXTURE_RESERVE");
            return optionOrder(user,config,duration,"TRIAL",trial.trial,trial.allocations);
        });
        db.update("UPDATE trial_grant SET expires_at=DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 1 SECOND) WHERE tenant_id=? AND user_id=?",TENANT,user);
        publish(config,"110");Map<String,Object> raw=raw("110",clock());
        actualControls.start(config,raw,new BigDecimal("110"),60,new BigDecimal("111"),1,false,false,"option-stop-"+UUID.randomUUID());
        actualControls.pump(config,raw,clock(),60000);Map<String,List<String>> before=moneyRows();
        Trace trace=new Trace("option-stop-reject-"+order.getId());traces.add(trace);
        CountDownLatch prepared=new CountDownLatch(1),release=new CountDownLatch(1);
        trace.beforeRuntimeLock=()->{prepared.countDown();await(release);};
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<OptionOrder> result=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(TENANT)){TRACE.set(trace);
                try{return optionService.closeOrderWithAuthority(user,order.getId(),null);}finally{TRACE.remove();}}});
            assertTrue(prepared.await(10,TimeUnit.SECONDS));actualControls.stop(config.getId(),clock());release.countDown();
            ExecutionException rejection=assertThrows(ExecutionException.class,()->result.get(15,TimeUnit.SECONDS));
            assertTrue(rejection.getCause() instanceof com.gtcfesk.exchange.common.BusinessException);
            assertEquals(before,moneyRows());assertFalse(trace.moneyDml());assertGrantLockBeforeRuntime(trace);
        }finally{release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void enabledOptionActualCommitAckLossRecoversClosedWithoutQuote()throws Exception {
        long user=optionUser("1000","100");TradingSymbol config=symbol("USD");OptionDuration duration=optionDuration();
        OptionOrder order=optionOrder(user,config,duration,"OPTION",BigDecimal.ZERO,null);publish(config,"110");
        Trace uncertain=observe("option-real-commit-ack-loss-"+order.getId());
        // Arm only after the short read-only receipt transaction; fault the actual funded commit.
        uncertain.beforeRuntimeLock=()->uncertain.dropCommitAcknowledgment=true;
        assertThrows(RuntimeException.class,()->optionService.closeOrderWithAuthority(user,order.getId(),null));TRACE.remove();
        assertEquals(1,uncertain.events.stream().filter(e->"ack-fault-after-real-commit".equals(e.get("kind"))).count());
        uncertain.assertFundingAuthorizationOrder("option_order");Map<String,List<String>> committed=moneyRows();
        Trace recover=observe("option-ack-recover-"+order.getId());
        assertEquals("CLOSED",optionService.closeOrderWithAuthority(user,order.getId(),null).getStatus());TRACE.remove();
        assertEquals(committed,moneyRows());assertFalse(recover.moneyDml());
        assertFalse(recover.events.stream().anyMatch(e->String.valueOf(e.get("sql")).contains("market_engine_runtime")));
        money("1180",optionWallet(user).getAvailable());money("0",optionWallet(user).getFrozen());assertEquals(1,audits("OPTION_SETTLE",order.getId()));
    }

    private long optionUser(String available,String frozen) {
        long user=user("0","0");
        db.update("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(?,?,'OPTION',?,?,0)",TENANT,user,new BigDecimal(available),new BigDecimal(frozen));return user;
    }
    private AssetAccount optionWallet(long user){return assets.findByTenantIdAndUserIdAndCoin(TENANT,user,"OPTION").orElseThrow(AssertionError::new);}
    private OptionDuration optionDuration(){
        OptionDuration duration=new OptionDuration();duration.setDuration(1000000+(int)(IDS.incrementAndGet()%100000));
        duration.setLabel("OWNED");duration.setSortOrder(0);duration.setEnabled(true);duration.setProfitRate(new BigDecimal("0.8"));duration.setLossRate(new BigDecimal("0.5"));
        duration.setMinAmount(BigDecimal.ONE);duration.setMaxAmount(new BigDecimal("10000"));return optionDurations.saveAndFlush(duration);
    }
    private OptionOrder optionOrder(long user,TradingSymbol symbol,OptionDuration duration,String source,BigDecimal trial,String allocations){
        OptionOrder order=new OptionOrder();order.setUserId(user);order.setSymbol(symbol.getSymbol());order.setDirection("UP");order.setAmount(new BigDecimal("100"));
        order.setOpenPrice(new BigDecimal("100"));order.setStatus("TRADING");order.setDuration(duration.getDuration());order.setProfit(BigDecimal.ZERO);
        order.setOpenTime(java.time.LocalDateTime.now().minusSeconds(duration.getDuration()+10L));order.setFundingSource(source);order.setTrialReserved(trial);order.setTrialAllocations(allocations);
        return optionOrders.saveAndFlush(order);
    }
    private void assertGrantLockBeforeRuntime(Trace trace){
        Map<String,Object> runtime=trace.events.stream().filter(e->"sql-return".equals(e.get("kind"))&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("market_engine_runtime")&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("for update")).findFirst().orElseThrow(AssertionError::new);
        long connection=((Number)runtime.get("connection_id")).longValue(),at=((Number)runtime.get("at_nanos")).longValue();
        long start=trace.events.stream().filter(e->((Number)e.get("connection_id")).longValue()==connection&&((Number)e.get("at_nanos")).longValue()<at&&( "commit-return".equals(e.get("kind"))||"rollback-return".equals(e.get("kind")))).mapToLong(e->((Number)e.get("at_nanos")).longValue()).max().orElse(Long.MIN_VALUE);
        assertTrue(trace.events.stream().anyMatch(e->((Number)e.get("connection_id")).longValue()==connection&&"sql-return".equals(e.get("kind"))&&((Number)e.get("at_nanos")).longValue()>start&&((Number)e.get("at_nanos")).longValue()<at&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("trial_grant")&&String.valueOf(e.get("sql")).toLowerCase(Locale.ROOT).contains("for update")),"Full current grant locks precede runtime authority in the same physical funding transaction");
    }

    @Test void realProducerRealProcessorCashMatcherAndAutoClose()throws Exception {
        long user=user("1000","100");
        TradingSymbol config=symbol("USD");
        ContractOrder pending=order(user,config,"PENDING","100");
        pending.setType("LIMIT");pending.setLimitMatchEnabled(true);pending.setPrice(new BigDecimal("120"));
        pending.setLotSize(BigDecimal.ONE);pending.setTakeProfit(null);orders.saveAndFlush(pending);
        publish(config,"110");
        Trace match=observe("real-cash-matcher-"+pending.getId());
        runActualProcessor();TRACE.remove();
        ContractOrder filled=orders.findByTenantIdAndId(TENANT,pending.getId()).orElseThrow(AssertionError::new);
        assertEquals("OPEN",filled.getStatus());
        money("110",filled.getOpenPrice());money("110",filled.getMargin());
        money("990",wallet(user).getAvailable());money("110",wallet(user).getFrozen());
        assertEquals(1,audits("S3_CONTRACT_FILL",pending.getId()));
        match.assertFundingAuthorizationOrder("contract_order");
        filled.setTakeProfit(new BigDecimal("115"));orders.saveAndFlush(filled);publish(config,"120");
        Trace close=observe("real-cash-auto-"+pending.getId());
        runActualProcessor();TRACE.remove();
        filled=orders.findByTenantIdAndId(TENANT,pending.getId()).orElseThrow(AssertionError::new);
        assertEquals("CLOSED",filled.getStatus());money("120",filled.getClosePrice());
        money("1110",wallet(user).getAvailable());money("0",wallet(user).getFrozen());
        assertEquals(1,audits("S3_CONTRACT_AUTO_CLOSE",pending.getId()));
        close.assertFundingAuthorizationOrder("contract_order");
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM trial_account WHERE tenant_id=? AND user_id=?",Integer.class,TENANT,user).intValue());
    }

    @Test void pendingCashOrderFlushFailureRollsBackWalletAndSuccessAuditThenRetryIsSingle()throws Exception {
        long user=user("1000","100");TradingSymbol config=symbol("USD");
        ContractOrder pending=order(user,config,"PENDING","100");
        pending.setType("LIMIT");pending.setLimitMatchEnabled(true);pending.setPrice(new BigDecimal("120"));
        pending.setLotSize(BigDecimal.ONE);pending.setTakeProfit(null);orders.saveAndFlush(pending);
        publish(config,"110");Map<String,List<String>> before=moneyRows();
        String trigger="joint_owned_order_flush_"+pending.getId();ownedTriggers.add(trigger);
        db.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON contract_order FOR EACH ROW BEGIN "+
            "IF OLD.tenant_id="+TENANT+" AND OLD.id="+pending.getId()+" AND OLD.status='PENDING' AND NEW.status='OPEN' "+
            "THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='joint owned pending cash order flush failure'; END IF; END");
        Trace failure=observe("real-cash-order-flush-rollback-"+pending.getId());
        Map<Long,String> rejected;
        try {rejected=contracts.matchPendingLimitOrdersS3(100);}finally{TRACE.remove();}
        assertNotNull(rejected.get(pending.getId()));assertTrue(rejected.get(pending.getId()).startsWith("RETRY:"));
        assertEquals(before,moneyRows(),"Order, wallet versions and already inserted success audit must all roll back");
        int failedIndex=-1;
        for(int i=0;i<failure.events.size();i++) {
            Map<String,Object> event=failure.events.get(i);
            if("sql-failed".equals(event.get("kind"))
                    && String.valueOf(event.get("sql")).toLowerCase(Locale.ROOT).startsWith("update contract_order ")) {
                assertEquals(-1,failedIndex,"Exactly one owned pending-order UPDATE must fail");failedIndex=i;
            }
        }
        assertTrue(failedIndex>=0,"The actual deferred contract_order SQL must reach the injected failure");
        long connection=((Number)failure.events.get(failedIndex).get("connection_id")).longValue();
        int previousEnd=-1,rollbackIndex=-1;
        for(int i=0;i<failure.events.size();i++) {
            Map<String,Object> event=failure.events.get(i);
            if(((Number)event.get("connection_id")).longValue()!=connection)continue;
            String kind=String.valueOf(event.get("kind"));
            if(i<failedIndex && ("commit-return".equals(kind)||"rollback-return".equals(kind)))previousEnd=i;
            if(i>failedIndex && "rollback-return".equals(kind)){rollbackIndex=i;break;}
        }
        assertTrue(rollbackIndex>failedIndex,"The same physical funding connection must actually roll back");
        int runtimeAt=-1,auditAt=-1,cashAt=-1;
        // Preparation may commit read-only transactions; inspect only this failed funding transaction.
        for(int i=previousEnd+1;i<rollbackIndex;i++) {
            Map<String,Object> event=failure.events.get(i);
            if(((Number)event.get("connection_id")).longValue()!=connection)continue;
            String kind=String.valueOf(event.get("kind"));
            assertFalse("commit-call".equals(kind)||"commit-return".equals(kind),"Failed funding transaction must never commit");
            if(!"sql-return".equals(kind))continue;
            String sql=String.valueOf(event.get("sql")).toLowerCase(Locale.ROOT);
            if(sql.contains("market_engine_runtime")&&sql.contains("for update"))runtimeAt=i;
            if(sql.startsWith("insert into control_audit_log "))auditAt=i;
            if(sql.startsWith("update asset_account "))cashAt=i;
        }
        assertTrue(runtimeAt>=0 && auditAt>runtimeAt && cashAt>runtimeAt && auditAt<failedIndex && cashAt<failedIndex,
            "Both actual audit INSERT and wallet UPDATE must succeed under the same authority lock before order flush fails");
        db.execute("DROP TRIGGER "+trigger);ownedTriggers.remove(trigger);
        publish(config,"110");Trace retry=observe("real-cash-order-flush-retry-"+pending.getId());
        try {assertEquals("OPENED",contracts.matchPendingLimitOrdersS3(100).get(pending.getId()));}finally{TRACE.remove();}
        ContractOrder filled=orders.findByTenantIdAndId(TENANT,pending.getId()).orElseThrow(AssertionError::new);
        assertEquals("OPEN",filled.getStatus());money("110",filled.getOpenPrice());money("110",filled.getMargin());
        money("990",wallet(user).getAvailable());money("110",wallet(user).getFrozen());
        assertEquals(1,audits("S3_CONTRACT_FILL",pending.getId()));retry.assertFundingAuthorizationOrder("contract_order");
        Map<String,List<String>> committed=moneyRows();Trace repeated=observe("real-cash-order-flush-repeat-"+pending.getId());
        try {assertFalse(contracts.matchPendingLimitOrdersS3(100).containsKey(pending.getId()));}finally{TRACE.remove();}
        assertFalse(repeated.moneyDml());assertEquals(committed,moneyRows());assertEquals(1,audits("S3_CONTRACT_FILL",pending.getId()));
    }

    @Test void realUsdtCashPublicAutoClose()throws Exception {
        long user=user("90","10");TradingSymbol config=symbol("USDT");
        ContractOrder position=order(user,config,"OPEN","10");position.setTakeProfit(new BigDecimal("105"));orders.saveAndFlush(position);
        publish(config,"110");Trace trace=observe("real-usdt-auto-"+position.getId());
        assertEquals("CLOSED",contracts.checkAndAutoCloseOrdersS3(100).get(position.getId()));TRACE.remove();
        money("110",wallet(user).getAvailable());money("0",wallet(user).getFrozen());
        assertEquals(1,audits("S3_CONTRACT_AUTO_CLOSE",position.getId()));
        trace.assertFundingAuthorizationOrder("contract_order");
    }

    @Test void completeTwoPositionPortfolioSecondSuccessAuditFailureRollsBackEveryRow()throws Exception {
        long user=user("0","20");TradingSymbol config=symbol("USD");
        ContractOrder first=order(user,config,"OPEN","10"),second=order(user,config,"OPEN","10");
        publish(config,"80");
        Map<String,List<String>> moneyBefore=moneyRows();
        String trigger="joint_owned_audit_"+second.getId();ownedTriggers.add(trigger);
        db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON control_audit_log FOR EACH ROW BEGIN "+
            "IF NEW.tenant_id="+TENANT+" AND NEW.action='S3_CONTRACT_FORCE_CLOSE' AND NEW.object_ref='"+second.getId()+
            "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='joint owned second required success audit failure'; END IF; END");
        Trace failure=observe("real-portfolio-rollback-"+second.getId());
        assertThrows(RuntimeException.class,()->contracts.checkAndForceClosePortfolioS3(user,"CONTRACT"));TRACE.remove();
        assertEquals(moneyBefore,moneyRows(),"All physical writes incl row versions and first success audit must roll back");
        assertEquals(0,failure.commitCalls());
        db.execute("DROP TRIGGER "+trigger);ownedTriggers.remove(trigger);
        publish(config,"80");Trace success=observe("real-portfolio-success-"+second.getId());
        assertEquals(2,contracts.checkAndForceClosePortfolioS3(user,"CONTRACT"));TRACE.remove();
        money("0",wallet(user).getAvailable());money("0",wallet(user).getFrozen());
        assertEquals("CLOSED",orders.findByTenantIdAndId(TENANT,first.getId()).orElseThrow(AssertionError::new).getStatus());
        assertEquals("CLOSED",orders.findByTenantIdAndId(TENANT,second.getId()).orElseThrow(AssertionError::new).getStatus());
        assertEquals(1,audits("S3_CONTRACT_FORCE_CLOSE",first.getId()));
        assertEquals(1,audits("S3_CONTRACT_FORCE_CLOSE",second.getId()));
        success.assertFundingAuthorizationOrder("contract_order");
    }

    @Test void actualStopAfterPreparedQuoteBeforeAuthorityCurrentLockRejectsZeroMoneyDml()throws Exception {
        long user=user("90","10");TradingSymbol config=symbol("USD");
        ContractOrder position=order(user,config,"OPEN","10");position.setTakeProfit(new BigDecimal("105"));orders.saveAndFlush(position);
        publish(config,"110");long at=clock();
        Map<String,Object> raw=raw("110",at);
        actualControls.start(config,raw,new BigDecimal("110"),60,new BigDecimal("111"),1,false,false,"joint-"+UUID.randomUUID());
        actualControls.pump(config,raw,clock(),60000);
        Map<String,List<String>> before=moneyRows();
        Trace trace=new Trace("real-stop-reject-"+position.getId());traces.add(trace);
        CountDownLatch prepared=new CountDownLatch(1),release=new CountDownLatch(1);
        trace.beforeRuntimeLock=()->{prepared.countDown();await(release);};
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<Map<Long,String>> result=worker.submit(()->{
                try(TenantContext.Scope ignored=TenantContext.open(TENANT)){TRACE.set(trace);
                    try{return contracts.checkAndAutoCloseOrdersS3(100);}finally{TRACE.remove();}}
            });
            assertTrue(prepared.await(10,TimeUnit.SECONDS),"Public entry reached current authority after outside-TX preparation");
            actualControls.stop(config.getId(),clock());release.countDown();
            assertTrue(result.get(15,TimeUnit.SECONDS).get(position.getId()).startsWith("RETRY:"));
            assertEquals(before,moneyRows());assertFalse(trace.moneyDml(),"Rejected candidate must execute no funding/order/trial/audit DML");
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
    }

    @Test void trueDelegateCommitThenAckFailureRecoversSameTransferKeyOnce()throws Exception {
        long user=user("100","0");
        db.update("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(?,?,'FUND',1000,0,0)",TENANT,user);
        TransferController.TransferRequest request=new TransferController.TransferRequest();
        request.setFromAccount("FUND");request.setToAccount("CONTRACT");request.setAmount(BigDecimal.TEN);
        request.setRequestId("joint-owned-ack-"+user);
        UsernamePasswordAuthenticationToken identity=new UsernamePasswordAuthenticationToken(Long.toString(user),null,Collections.emptyList());
        Trace uncertain=observe("true-commit-ack-loss-"+user);uncertain.dropCommitAcknowledgment=true;
        assertThrows(RuntimeException.class,()->transfers.transfer(identity,request));TRACE.remove();
        assertEquals(1,uncertain.realCommitReturns(),"Failure must follow real delegate commit, not replace it");
        // New invocation opens a new physical TX; no old managed state or guessed HTTP outcome authorizes recovery.
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(200,transfers.transfer(identity,request).getStatusCodeValue());
        money("110",wallet(user).getAvailable());
        money("990",db.queryForObject("SELECT available FROM asset_account WHERE tenant_id=? AND user_id=? AND coin='FUND'",BigDecimal.class,TENANT,user));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM transfer_record WHERE tenant_id=? AND user_id=? AND request_id=?",Integer.class,TENANT,user,request.getRequestId()).intValue());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND action='TRANSFER' AND detail LIKE ?",Integer.class,TENANT,"%userId="+user+";%").intValue());
        request.setAmount(new BigDecimal("11"));
        assertThrows(RuntimeException.class,()->transfers.transfer(identity,request),"Same key/different payload may not reuse a receipt");
        // This proves transfer-key recovery only. It does not certify portfolio attempt/result recovery.
    }

    @Test void enabledFxActualProducerNonfixedCloseCommitAckLossIsSingle()throws Exception {
        long tenant=ownedTradingTenant(),user=fxUser(tenant,"90","10");
        TradingSymbol config=symbol("USDC");ContractOrder position=order(user,config,"OPEN","10");
        position.setLotSize(BigDecimal.ONE);position.setTakeProfit(new BigDecimal("105"));orders.saveAndFlush(position);
        actualMarket.refreshSymbols();fxIngress(config.getSymbol(),"Crypto","110",clock());fxIngress("USDCUSDT","Crypto","0.95",clock());
        com.fasterxml.jackson.databind.JsonNode receipt=fxReceipt(config,"USDCUSDT");
        assertEquals("USDC",receipt.path("currency").asText());assertEquals("binance",receipt.path("source").asText());
        assertFalse(receipt.path("inverse").asBoolean());assertFalse(receipt.path("provider").asText().isEmpty());
        assertTrue(receipt.path("receiptVersion").asLong()>0);assertFalse(receipt.path("eventId").asText().isEmpty());
        money("110",db.queryForObject("SELECT price FROM market_source_quote WHERE tenant_id=? AND symbol_id=?",BigDecimal.class,tenant,config.getId()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM market_source_event WHERE tenant_id=? AND symbol_id=?",Integer.class,tenant,config.getId()).intValue(),"FX companion must not be inserted as an instrument price");
        Trace uncertain=observe("fx-nonfixed-real-commit-ack-"+position.getId());
        uncertain.beforeRuntimeLock=()->uncertain.dropCommitAcknowledgment=true;
        Map<Long,String> result;
        try{result=contracts.checkAndAutoCloseOrdersS3(100);}finally{TRACE.remove();}
        assertTrue(result.get(position.getId()).startsWith("RETRY:"));
        assertTrue(uncertain.events.stream().anyMatch(e->"ack-fault-after-real-commit".equals(e.get("kind"))));
        uncertain.assertFundingAuthorizationOrder("contract_order");
        ContractOrder closed=orders.findByTenantIdAndId(tenant,position.getId()).orElseThrow(AssertionError::new);
        assertEquals("CLOSED",closed.getStatus());money("110",closed.getClosePrice());money("0.95",closed.getSettlementConversionRate());money("9.5",closed.getProfit());
        money("109.5",fxWallet(tenant,user).getAvailable());money("0",fxWallet(tenant,user).getFrozen());assertEquals(1,fxAudits(tenant,"S3_CONTRACT_AUTO_CLOSE",position.getId()));
        Map<String,List<String>> committed=moneyRows();Trace recovery=observe("fx-nonfixed-ack-recovery-"+position.getId());
        try{assertFalse(contracts.checkAndAutoCloseOrdersS3(100).containsKey(position.getId()));}finally{TRACE.remove();}
        assertFalse(recovery.moneyDml());assertEquals(committed,moneyRows());
        assertFalse(recovery.events.stream().anyMatch(e->String.valueOf(e.get("sql")).contains("market_engine_runtime")),"Closed-row recovery does not need another FX quote");
    }

    @Test void enabledFxCrossMarginResizeAuditRollbackAndQuoteCurrencySettlement()throws Exception {
        long tenant=ownedTradingTenant(),user=fxUser(tenant,"10000","1000");TradingSymbol config=fxCrossSymbol();
        fxOpenOwnedSymbol(config);
        ContractOrder pending=order(user,config,"PENDING","1000");pending.setType("LIMIT");pending.setLimitMatchEnabled(true);
        pending.setPrice(new BigDecimal("120"));pending.setQuantity(new BigDecimal("0.01"));pending.setLotSize(new BigDecimal("100000"));
        pending.setFxBaseCurrency("EUR");orders.saveAndFlush(pending);
        actualMarket.refreshSymbols();fxIngress(config.getSymbol(),"Forex","110",clock());
        fxIngress("JPY=X","Forex","100",clock());fxIngress("EURUSD=X","Forex","1.25",clock());
        com.fasterxml.jackson.databind.JsonNode quoteReceipt=fxReceipt(config,"JPY=X"),marginReceipt=fxReceipt(config,"EURUSD=X");
        assertTrue(quoteReceipt.path("inverse").asBoolean());money("100",quoteReceipt.path("price").decimalValue());
        assertFalse(marginReceipt.path("inverse").asBoolean());money("1.25",marginReceipt.path("price").decimalValue());
        Map<String,List<String>> before=moneyRows();String trigger="jfx_"+pending.getId();ownedTriggers.add(trigger);
        db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON control_audit_log FOR EACH ROW BEGIN IF NEW.tenant_id="+tenant+
            " AND NEW.action='S3_CONTRACT_FILL' AND NEW.object_ref='"+pending.getId()+"' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='owned FX resize audit failure'; END IF; END");
        Trace failure=observe("fx-cross-resize-audit-rollback-"+pending.getId());
        try{assertTrue(contracts.matchPendingLimitOrdersS3(100).get(pending.getId()).startsWith("RETRY:"));}finally{TRACE.remove();}
        assertEquals(before,moneyRows());assertTrue(failure.events.stream().anyMatch(e->"sql-failed".equals(e.get("kind"))&&String.valueOf(e.get("sql")).contains("control_audit_log")));
        assertTrue(failure.events.stream().anyMatch(e->"rollback-return".equals(e.get("kind"))));db.execute("DROP TRIGGER "+trigger);ownedTriggers.remove(trigger);
        Trace fill=observe("fx-cross-resize-accepted-"+pending.getId());
        try{assertEquals("OPENED",contracts.matchPendingLimitOrdersS3(100).get(pending.getId()));}finally{TRACE.remove();}
        ContractOrder filled=orders.findByTenantIdAndId(tenant,pending.getId()).orElseThrow(AssertionError::new);
        money("110",filled.getOpenPrice());money("1250",filled.getMargin());money("1.25",filled.getMarginConversionRate());
        money("9750",fxWallet(tenant,user).getAvailable());money("1250",fxWallet(tenant,user).getFrozen());fill.assertFundingAuthorizationOrder("contract_order");
        assertEquals(1,fxAudits(tenant,"S3_CONTRACT_FILL",filled.getId()));
        filled.setTakeProfit(new BigDecimal("115"));orders.saveAndFlush(filled);fxIngress(config.getSymbol(),"Forex","120",clock());
        Trace close=observe("fx-cross-quote-settlement-"+pending.getId());
        try{assertEquals("CLOSED",contracts.checkAndAutoCloseOrdersS3(100).get(pending.getId()));}finally{TRACE.remove();}
        ContractOrder closed=orders.findByTenantIdAndId(tenant,pending.getId()).orElseThrow(AssertionError::new);
        money("0.01",closed.getSettlementConversionRate());money("1.25",closed.getMarginConversionRate());money("100",closed.getProfit());
        money("11100",fxWallet(tenant,user).getAvailable());money("0",fxWallet(tenant,user).getFrozen());
        assertEquals(1,fxAudits(tenant,"S3_CONTRACT_AUTO_CLOSE",closed.getId()));close.assertFundingAuthorizationOrder("contract_order");
    }

    @Test void enabledFxPreparedRateMutationAndExpiryRejectBeforeAnyMoneyDml()throws Exception {
        for(boolean expire:new boolean[]{false,true}) {
            long tenant=ownedTradingTenant(),user=fxUser(tenant,"90","10");TradingSymbol config=symbol("USDC");
            ContractOrder position=order(user,config,"OPEN","10");position.setLotSize(BigDecimal.ONE);position.setTakeProfit(new BigDecimal("105"));orders.saveAndFlush(position);
            actualMarket.refreshSymbols();fxIngress(config.getSymbol(),"Crypto","110",clock());
            Map<String,List<String>> before=moneyRows();
            fxIngress("USDCUSDT","Crypto","0.95",clock()-(expire?54000:1000));
            long expiry=fxReceipt(config,"USDCUSDT").path("expiresAt").asLong();
            Map<String,Object> primary=authority.prepare(config.getSymbol());
            Trace trace=new Trace("fx-prepared-"+(expire?"expired-":"changed-")+position.getId());traces.add(trace);
            CountDownLatch prepared=new CountDownLatch(1),release=new CountDownLatch(1);trace.beforeRuntimeLock=()->{prepared.countDown();await(release);};
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<Map<Long,String>> pending=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){TRACE.set(trace);
                    try{return contracts.checkAndAutoCloseOrdersS3(100);}finally{TRACE.remove();}}});
                assertTrue(prepared.await(10,TimeUnit.SECONDS),"Actual public Contract preparation must reach the real current-lock barrier");
                if(expire) {while(clock()<=expiry)Thread.sleep(10);}
                else {
                    fxIngress("USDCUSDT","Crypto","0.90",clock());Map<String,Object> updated=authority.prepare(config.getSymbol());
                    for(String key:Arrays.asList("price","timestamp","executionSampledAt","executionExpiresAt"))assertEquals(primary.get(key),updated.get(key),key);
                }
                release.countDown();assertTrue(pending.get(15,TimeUnit.SECONDS).get(position.getId()).startsWith("RETRY:"));
                assertFalse(trace.moneyDml(),"Expired/changed prepared FX must reject before order/wallet/trial/audit SQL");assertEquals(before,moneyRows());
                assertEquals("OPEN",orders.findByTenantIdAndId(tenant,position.getId()).orElseThrow(AssertionError::new).getStatus());
                assertEquals(0,fxAudits(tenant,"S3_CONTRACT_AUTO_CLOSE",position.getId()));
            } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
        }
    }

    @Test void enabledFxExpiryAfterFundingAuthorizationRollsBackActualOrderWalletAndAudit()throws Exception {
        long tenant=ownedTradingTenant(),user=fxUser(tenant,"90","10");TradingSymbol config=symbol("USDC");
        ContractOrder position=order(user,config,"OPEN","10");position.setLotSize(BigDecimal.ONE);position.setTakeProfit(new BigDecimal("105"));orders.saveAndFlush(position);
        actualMarket.refreshSymbols();fxIngress(config.getSymbol(),"Crypto","110",clock());Map<String,List<String>> before=moneyRows();
        fxIngress("USDCUSDT","Crypto","0.95",clock()-54000);long expiry=fxReceipt(config,"USDCUSDT").path("expiresAt").asLong();
        Trace trace=new Trace("fx-real-before-commit-expiry-"+position.getId());traces.add(trace);
        CountDownLatch authorized=new CountDownLatch(1),release=new CountDownLatch(1);trace.beforeMoneyDml=()->{authorized.countDown();await(release);};
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<Map<Long,String>> pending=worker.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){TRACE.set(trace);
                try{return contracts.checkAndAutoCloseOrdersS3(100);}finally{TRACE.remove();}}});
            assertTrue(authorized.await(10,TimeUnit.SECONDS),"Real authority must finish before the first actual funding SQL");
            while(clock()<=expiry)Thread.sleep(10);release.countDown();
            assertTrue(pending.get(15,TimeUnit.SECONDS).get(position.getId()).startsWith("RETRY:"));
            assertTrue(trace.moneyDml(),"This case deliberately reaches real deferred JPA/audit writes, not an early guard");
            assertTrue(trace.events.stream().anyMatch(e->"rollback-return".equals(e.get("kind"))));assertEquals(before,moneyRows());
            assertEquals(0,fxAudits(tenant,"S3_CONTRACT_AUTO_CLOSE",position.getId()));
        } finally {release.countDown();worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
    }

    private long fxUser(long tenant,String available,String frozen) {
        long user=ownedOptionUser(tenant,true);
        db.update("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(?,?,'CONTRACT',?,?,0)",tenant,user,new BigDecimal(available),new BigDecimal(frozen));return user;
    }
    private AssetAccount fxWallet(long tenant,long user){return assets.findByTenantIdAndUserIdAndCoin(tenant,user,"CONTRACT").orElseThrow(AssertionError::new);}
    private long fxAudits(long tenant,String action,long order){return db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND action=? AND object_ref=?",Long.class,tenant,action,Long.toString(order));}
    private TradingSymbol fxCrossSymbol() {
        TradingSymbol config=symbol("JPY");config.setMarketSource("yahoo");config.setBaseCurrency("EUR");
        config.setSourceCategory("Forex");config.setCategory("Forex");config.setLotSize(new BigDecimal("100000"));
        config.setMinTradeAmount(new BigDecimal("0.01"));config.setFeeMultiplier(BigDecimal.ZERO);return symbols.saveAndFlush(config);
    }
    private void fxOpenOwnedSymbol(TradingSymbol config) {
        com.gtcfesk.exchange.admin.SystemConfigService configs=application.getBean(com.gtcfesk.exchange.admin.SystemConfigService.class);
        MarketHoursService.OverrideInput input=new MarketHoursService.OverrideInput();input.scope="SYMBOL";input.target=config.getId().toString();
        input.mode="OPEN";input.until=java.time.Instant.now().plusSeconds(3600).toString();input.reason="OWNED FX MySQL fixture only";
        input.revision=MarketHoursConfig.parse(configs.getConfigValue(MarketHoursConfig.KEY)).revision;
        application.getBean(MarketHoursService.class).override(input);
    }
    private void fxIngress(String code,String category,String price,long sourceAt) {
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Actual producer outside funding transaction");
        Map<String,Object> raw=new LinkedHashMap<>();raw.put("price",new BigDecimal(price));raw.put("timestamp",sourceAt);raw.put("eventId","owned-fx-"+UUID.randomUUID());
        Boolean accepted=ReflectionTestUtils.invokeMethod(actualMarket,"acceptQuote",code,category,raw,"http",clock());
        assertEquals(Boolean.TRUE,accepted,"Actual subscribed producer must accept the fixture provider frame");
    }
    private com.fasterxml.jackson.databind.JsonNode fxReceipt(TradingSymbol config,String code)throws Exception {
        String body=db.queryForObject("SELECT quote_json FROM market_engine_runtime WHERE tenant_id=? AND symbol_id=?",String.class,TenantContext.requireTenantId(),config.getId());
        Files.write(evidence.resolve("fx-runtime-"+config.getId()+"-"+IDS.incrementAndGet()+".json"),body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        com.fasterxml.jackson.databind.JsonNode book=JSON.readTree(body).path("fundingConversions");
        assertTrue(book.isObject());assertTrue(book.size()>0&&book.size()<=4);
        java.util.Iterator<com.fasterxml.jackson.databind.JsonNode> values=book.elements();
        while(values.hasNext()){com.fasterxml.jackson.databind.JsonNode receipt=values.next();if(code.equals(receipt.path("code").asText()))return receipt;}
        throw new AssertionError("Missing actual committed companion: "+code);
    }

    private void runActualProcessor(){scope.close();scope=null;TenantContext.clear();actualProcessor.runOnce();scope=TenantContext.open(TENANT);}
    private long clock(){return db.queryForObject("SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)",Long.class);}
    private long user(String available,String frozen) {
        long id=IDS.incrementAndGet();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM user_account WHERE id=?",Integer.class,id).intValue());
        db.update("INSERT INTO user_account(tenant_id,id,email,password_hash,status,user_type,created_at,updated_at,row_version) VALUES(?,?,?,'not-a-login','normal','normal',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)",TENANT,id,"joint-"+id+"@fixture.invalid");
        db.update("INSERT INTO asset_account(tenant_id,user_id,coin,available,frozen,row_version) VALUES(?,?,'CONTRACT',?,?,0)",TENANT,id,new BigDecimal(available),new BigDecimal(frozen));
        // Explicit fake, owned fixture identity; real KYC service is not mocked/disabled.
        db.update("INSERT INTO kyc_record(tenant_id,user_id,real_name,id_number,status,created_at,updated_at) VALUES(?,?,'OWNED SYNTHETIC IDENTITY','NOT-A-REAL-IDENTITY','APPROVED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",TENANT,id);
        return id;
    }
    private TradingSymbol symbol(String currency){
        TradingSymbol config=new TradingSymbol();config.setSymbol("J"+IDS.incrementAndGet());config.setName(config.getSymbol());
        config.setBaseCurrency("TEST");config.setQuoteCurrency(currency);config.setMarketSource("binance");
        config.setSourceCategory("Crypto");config.setCategory("Crypto");config.setPricePrecision(8);config.setIsEnabled(true);
        return symbols.saveAndFlush(config);
    }
    private ContractOrder order(long user,TradingSymbol config,String state,String margin){
        ContractOrder order=new ContractOrder();order.setUserId(user);order.setSymbol(config.getSymbol());order.setFundingSource("CONTRACT");
        order.setQuoteCurrency(config.getQuoteCurrency());order.setQuoteSource(config.getMarketSource());order.setTrialReserved(BigDecimal.ZERO);
        order.setType("MARKET");order.setSide("BUY");order.setStatus(state);order.setQuantity(BigDecimal.ONE);order.setLeverage(BigDecimal.ONE);
        order.setOpenPrice(new BigDecimal("100"));order.setMargin(new BigDecimal(margin));order.setFee(BigDecimal.ZERO);order.setProfit(BigDecimal.ZERO);
        return orders.saveAndFlush(order);
    }
    private Map<String,Object> raw(String price,long now){
        Map<String,Object> raw=new LinkedHashMap<>();raw.put("price",new BigDecimal(price));raw.put("timestamp",now);raw.put("sourceTimestamp",now);
        raw.put("fetchedAt",now);raw.put("expiresAt",now+60000);raw.put("available",true);raw.put("sourceAvailable",true);raw.put("status","available");return raw;
    }
    private void publish(TradingSymbol config,String price){long now=clock();Map<String,Object> raw=raw(price,now);actualControls.sourceQuote(config,raw,now);actualControls.pump(config,raw,clock(),60000);}
    private AssetAccount wallet(long user){return assets.findByTenantIdAndUserIdAndCoin(TENANT,user,"CONTRACT").orElseThrow(AssertionError::new);}
    private long audits(String action,long order){return db.queryForObject("SELECT COUNT(*) FROM control_audit_log WHERE tenant_id=? AND action=? AND object_ref=?",Long.class,TENANT,action,Long.toString(order));}
    private static void money(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}
    private Trace observe(String name){Trace trace=new Trace(name);traces.add(trace);TRACE.set(trace);return trace;}
    private static void await(CountDownLatch latch){try{assertTrue(latch.await(15,TimeUnit.SECONDS),"Owned barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    private static Map<String,List<String>> rows(JdbcTemplate database)throws Exception {
        Map<String,List<String>> snapshot=new TreeMap<>();
        for(String table:database.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name",String.class)){
            assertTrue(table.matches("[A-Za-z0-9_]+"));List<String> entries=new ArrayList<>();
            for(Map<String,Object> row:database.queryForList("SELECT * FROM \u0060"+table+"\u0060"))entries.add(JSON.writeValueAsString(new TreeMap<>(row)));
            Collections.sort(entries);snapshot.put(table,entries);
        }
        return snapshot;
    }
    private Map<String,List<String>> moneyRows()throws Exception {
        Map<String,List<String>> state=rows(db);
        state.entrySet().removeIf(e->!Arrays.asList("user_account","asset_account","contract_order","option_order","trial_account","trial_grant","trial_ledger","financial_order","financial_yield_record","balance_adjustment","control_audit_log","operation_log").contains(e.getKey()));
        return state;
    }
    private void assertOldRowsPreserved()throws Exception {
        Map<String,List<String>> after=rows(beforeBoot);
        for(Map.Entry<String,List<String>> entry:original.entrySet()){
            // Multiset comparison also protects duplicate identical rows in tables without a unique key.
            Map<String,Integer> remaining=new HashMap<>();
            for(String row:after.getOrDefault(entry.getKey(),Collections.emptyList()))remaining.merge(row,1,Integer::sum);
            for(String row:entry.getValue()){
                int count=remaining.getOrDefault(row,0);
                assertTrue(count>0,"Original full-column row/multiplicity changed: "+entry.getKey());
                remaining.put(row,count-1);
            }
        }
        assertEquals(original.get("user_id_sequence"),after.get("user_id_sequence"),"Original sequence receipt FAIL must not be hidden by fixture resets");
    }
    private static final class Trace {
        final String name;final List<Map<String,Object>> events=new CopyOnWriteArrayList<>();
        volatile Runnable beforeRuntimeLock,beforeUserLock,beforeMoneyDml,afterKycRead,afterDurationRead;volatile boolean dropCommitAcknowledgment;
        Trace(String name){this.name=name;}
        void event(long connection,String kind,String sql){
            Map<String,Object> entry=new LinkedHashMap<>();entry.put("connection_id",connection);entry.put("kind",kind);
            entry.put("at_nanos",System.nanoTime());if(sql!=null)entry.put("sql",sql);events.add(entry);
        }
        long commitCalls(){return events.stream().filter(e->"commit-call".equals(e.get("kind"))).count();}
        long realCommitReturns(){return events.stream().filter(e->"commit-return".equals(e.get("kind"))).count();}
        boolean moneyDml(){return events.stream().anyMatch(e->"sql-call".equals(e.get("kind"))&&moneySql(String.valueOf(e.get("sql"))));}
        void assertFundingAuthorizationOrder(String table){
            boolean found=false;
            for(Map<String,Object> commit:events)if("commit-call".equals(commit.get("kind"))){
                long connection=((Number)commit.get("connection_id")).longValue(),commitAt=((Number)commit.get("at_nanos")).longValue();
                long previousEnd=Long.MIN_VALUE;
                for(Map<String,Object> end:events){
                    long at=((Number)end.get("at_nanos")).longValue();
                    if(((Number)end.get("connection_id")).longValue()==connection && at<commitAt
                            && ("commit-return".equals(end.get("kind")) || "rollback-return".equals(end.get("kind"))))
                        previousEnd=Math.max(previousEnd,at);
                }
                long runtime=0,firstDml=Long.MAX_VALUE,lastDml=0,authorization=0;boolean target=false;
                for(Map<String,Object> event:events){
                    long at=((Number)event.get("at_nanos")).longValue();
                    if(((Number)event.get("connection_id")).longValue()!=connection || !"sql-return".equals(event.get("kind"))
                            || at<=previousEnd || at>=commitAt)continue;
                    String sql=String.valueOf(event.get("sql")).toLowerCase(Locale.ROOT);
                    if(sql.contains("market_engine_runtime")&&sql.contains("for update"))runtime=Math.max(runtime,at);
                    if(moneySql(sql)){firstDml=Math.min(firstDml,at);lastDml=Math.max(lastDml,at);if(sql.contains(table))target=true;}
                    if(sql.contains("unix_timestamp(current_timestamp(3))"))authorization=Math.max(authorization,at);
                }
                if(!target)continue;found=true;
                assertTrue(runtime>0,"Funding current runtime lock on the same physical transaction/CONNECTION_ID");
                assertTrue(firstDml>runtime,"Every funding DML must follow complete runtime authority locking");
                assertTrue(authorization>lastDml,"Every deferred order/wallet/success audit SQL flush precedes DB authorization point");
                assertTrue(commitAt>authorization,"DB authorization precedes actual commit invocation; no COMMIT ACK deadline is asserted");
            }
            assertTrue(found,"Actual funded physical transaction required; empty scheduler success is not proof");
        }
    }
    private static boolean moneySql(String sql){
        String value=sql.trim().toLowerCase(Locale.ROOT);
        return value.matches("(?s).*(insert\\s+into|update|delete\\s+from)\\s+\u0060?(user_account|asset_account|contract_order|option_order|trial_account|trial_grant|trial_ledger|financial_order|financial_yield_record|balance_adjustment|control_audit_log|operation_log)\\b.*");
    }
    private static final class RecordingSource extends AbstractDataSource {
        final DataSource delegate;RecordingSource(DataSource value){delegate=value;}
        @Override public Connection getConnection()throws SQLException{return wrap(delegate.getConnection());}
        @Override public Connection getConnection(String user,String password)throws SQLException{return wrap(delegate.getConnection(user,password));}
        @Override public <T>T unwrap(Class<T> kind)throws SQLException{return kind.isInstance(this)?kind.cast(this):delegate.unwrap(kind);}
        @Override public boolean isWrapperFor(Class<?> kind)throws SQLException{return kind.isInstance(this)||delegate.isWrapperFor(kind);}
        private Connection wrap(Connection connection)throws SQLException {
            Trace trace=TRACE.get();if(trace==null)return connection;
            long id;try(Statement s=connection.createStatement();ResultSet r=s.executeQuery("SELECT CONNECTION_ID()")){assertTrue(r.next());id=r.getLong(1);}
            final long physical=id;
            return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if("commit".equals(method.getName()))trace.event(physical,"commit-call",null);
                Object result=invoke(connection,method,args);
                if("commit".equals(method.getName())){
                    trace.event(physical,"commit-return",null);
                    if(trace.dropCommitAcknowledgment){trace.dropCommitAcknowledgment=false;trace.event(physical,"ack-fault-after-real-commit",null);throw new SQLException("Owned simulated commit acknowledgment loss after real commit","08006");}
                }
                if("rollback".equals(method.getName()))trace.event(physical,"rollback-return",null);
                if(result instanceof Statement&&Arrays.asList("createStatement","prepareStatement","prepareCall").contains(method.getName())){
                    Statement statement=(Statement)result;
                    String prepared="createStatement".equals(method.getName())?null:String.valueOf(args[0]);
                    Class<?> kind="prepareCall".equals(method.getName())?CallableStatement.class:prepared==null?Statement.class:PreparedStatement.class;
                    return Proxy.newProxyInstance(kind.getClassLoader(),new Class<?>[]{kind},(ignored,operation,values)->{
                        boolean execute=operation.getName().startsWith("execute");
                        String sql=prepared==null&&execute&&values!=null&&values.length>0?String.valueOf(values[0]):prepared;
                        if(execute&&sql!=null){
                            if(sql.toLowerCase(Locale.ROOT).contains("user_account")&&sql.toLowerCase(Locale.ROOT).contains("for update")) {
                                trace.event(physical,"user-lock-isolation",Integer.toString(connection.getTransactionIsolation()));
                                if(trace.beforeUserLock!=null){Runnable barrier=trace.beforeUserLock;trace.beforeUserLock=null;barrier.run();}
                            }
                            if(sql.toLowerCase(Locale.ROOT).contains("market_engine_runtime")&&sql.toLowerCase(Locale.ROOT).contains("for update")&&trace.beforeRuntimeLock!=null){
                                Runnable barrier=trace.beforeRuntimeLock;trace.beforeRuntimeLock=null;barrier.run();
                            }
                            if(moneySql(sql)&&trace.beforeMoneyDml!=null){Runnable barrier=trace.beforeMoneyDml;trace.beforeMoneyDml=null;barrier.run();}
                            trace.event(physical,"sql-call",sql);
                        }
                        try{Object value=invoke(statement,operation,values);if(execute&&sql!=null){
                            trace.event(physical,"sql-return",sql);
                            if(sql.toLowerCase(Locale.ROOT).contains("kyc_record")&&sql.toLowerCase(Locale.ROOT).contains("for update")&&trace.afterKycRead!=null){Runnable barrier=trace.afterKycRead;trace.afterKycRead=null;barrier.run();}
                            if(sql.toLowerCase(Locale.ROOT).contains("option_duration")&&sql.toLowerCase(Locale.ROOT).contains("for update")&&trace.afterDurationRead!=null){Runnable barrier=trace.afterDurationRead;trace.afterDurationRead=null;barrier.run();}
                        }return value;}
                        catch(Throwable failure){if(execute&&sql!=null)trace.event(physical,"sql-failed",sql);throw failure;}
                    });
                }
                return result;
            });
        }
    }
    private static Object invoke(Object target,Method method,Object[] arguments)throws Throwable{
        try{return method.invoke(target,arguments);}catch(InvocationTargetException failure){throw failure.getCause();}
    }
}

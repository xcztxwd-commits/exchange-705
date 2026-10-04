package com.gtcfesk.exchange.control;
import com.gtcfesk.exchange.admin.LoanReviewService;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.user.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real repositories, database row locks and database-rejected audit writes. No scheduler or application boot. */
@SpringJUnitConfig(ControlFundsTransactionTest.Config.class)
class ControlFundsTransactionTest {
 static {((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);}
 @Configuration @EnableTransactionManagement(proxyTargetClass=true)
 @EnableJpaRepositories(basePackages="com.gtcfesk.exchange",repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
 @Import({LoanReviewService.class,LoanService.class,FinancialService.class,FinancialYieldService.class,ControlAuditService.class})
 static class Config {
  @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:funds_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000","sa","");}
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.gtcfesk.exchange");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","create-drop");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");f.setJpaProperties(p);return f;}
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
  @Bean TenantReadinessService readiness(){return mock(TenantReadinessService.class);}
  @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
  @Bean TenantPolicyService policy(){return mock(TenantPolicyService.class);}
  @Bean TenantJobRunner tenantJobs(){return mock(TenantJobRunner.class);}
  // Scheduler is unused in this service-only fixture; keep production failure delivery required.
  @Bean OperationalIssueService operationalIssues(){return mock(OperationalIssueService.class);}
  @Bean LoanPersonalInfoService personal(){LoanPersonalInfoService s=mock(LoanPersonalInfoService.class);org.mockito.stubbing.Answer<LoanPersonalInfo> approved=i->{LoanPersonalInfo p=new LoanPersonalInfo();p.setUserId(i.getArgument(0));p.setRealName("test");p.setIdNumber("TEST-IDENTITY");return p;};when(s.requireApprovedPersonalInfo(anyLong())).thenAnswer(approved);when(s.requireApprovedPersonalInfoForFunds(anyLong())).thenAnswer(approved);return s;}
 }
 @Autowired LoanReviewService review; @Autowired LoanService loans; @Autowired FinancialService financial; @Autowired FinancialYieldService yields;
 @Autowired UserAccountRepository users; @Autowired AssetAccountRepository assets; @Autowired LoanRecordRepository records; @Autowired FinancialOrderRepository orders; @Autowired FinancialProductRepository products;
 @Autowired PlatformTransactionManager manager; @Autowired DataSource source; @Autowired TenantPolicyService policy; @PersistenceContext EntityManager em;
 JdbcTemplate db;Long user,actor,product;
 void control(){UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("-"+actor,null,Arrays.asList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),new SimpleGrantedAuthority("ROLE_CONTROL_ACCESS")));auth.setDetails(new ControlIdentity(actor,1L,"funds-test"));SecurityContextHolder.getContext().setAuthentication(auth);}
 @BeforeEach void setup(){TenantContext.open(1L);db=new JdbcTemplate(source);db.update("update financial_order set status='COMPLETED' where status='IN_PROGRESS'");tx(()->{UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@test.invalid");u.setPasswordHash("unused");users.save(u);user=u.getId();AssetAccount a=new AssetAccount();a.setUserId(user);a.setCoin("FUND");a.setAvailable(new BigDecimal("1000"));a.setFrozen(BigDecimal.ZERO);assets.save(a);ControlAdmin c=new ControlAdmin();c.setAccount(UUID.randomUUID().toString());c.setPasswordHash("unused");em.persist(c);actor=c.getId();FinancialProduct p=new FinancialProduct();p.setName("test");p.setDailyYieldRate(BigDecimal.ONE);p.setRentalFee(BigDecimal.ZERO);p.setMinPurchase(BigDecimal.ONE);p.setMaxPurchase(new BigDecimal("10000"));p.setTermDays(7);products.save(p);product=p.getId();});control();reset(policy);}
 @AfterEach void clean(){TenantContext.clear();SecurityContextHolder.clearContext();}
 void tx(Runnable action){new TransactionTemplate(manager).execute(s->{action.run();return null;});}
 LoanRecord loan(String state){LoanRecord r=new LoanRecord();r.setUserId(user);r.setAmount(new BigDecimal("100"));r.setDays(7);r.setDailyRate(BigDecimal.ZERO);r.setFreeDays(7);r.setTotalInterest(BigDecimal.ZERO);r.setRepaymentAmount(new BigDecimal("100"));r.setRealName("test");r.setIdNumber("TEST-IDENTITY");r.setContractSigned(true);r.setStatus(state);if("APPROVED".equals(state))r.setApprovedAt(LocalDateTime.now());tx(()->records.save(r));return r;}
 FinancialOrder order(boolean matured){FinancialOrder o=new FinancialOrder();o.setUserId(user);o.setProductId(product);o.setProductName("test");o.setPurchaseAmount(new BigDecimal("100"));o.setDailyYieldRate(BigDecimal.ONE);o.setDailyYield(BigDecimal.ONE);o.setTotalYield(new BigDecimal("7"));o.setTermDays(7);o.setPenaltyRate(new BigDecimal("10"));o.setEndTime(matured?LocalDateTime.now().minusDays(1):LocalDateTime.now().plusDays(7));tx(()->{orders.save(o);AssetAccount a=assets.lockByUserId(user).get(0);a.setFrozen(a.getFrozen().add(new BigDecimal("100")));assets.save(a);});return o;}
 BigDecimal available(){return db.queryForObject("select available from asset_account where tenant_id=1 and user_id=? and coin='FUND'",BigDecimal.class,user);}
 void money(String expected){assertEquals(0,new BigDecimal(expected).compareTo(available()));}
 long audits(String action,long id){return db.queryForObject("select count(*) from control_audit_log where actor_id=? and tenant_id=1 and access_session_id='funds-test' and action=? and object_ref=?",Long.class,actor,action,Long.toString(id));}
 String state(String table,long id){return db.queryForObject("select status from "+table+" where tenant_id=1 and id=?",String.class,id);}
 void auditFailure(String action,Runnable command){String constraint="audit_failure_"+UUID.randomUUID().toString().replace("-","");db.execute("alter table control_audit_log add constraint "+constraint+" check(action <> '"+action+"' or actor_id <> "+actor+")");try{assertThrows(RuntimeException.class,command::run);}finally{db.execute("alter table control_audit_log drop constraint "+constraint);}}
 @Test void approveAndRepayAreScopedIdempotentAndAudited(){LoanRecord r=loan("SIGNED");review.approveLoan(r.getId());review.approveLoan(r.getId());money("1100");assertEquals(1,audits("LOAN_APPROVE",r.getId()));loans.earlyRepayment(r.getId(),user);loans.earlyRepayment(r.getId(),user);money("1000");assertEquals(1,audits("LOAN_REPAY",r.getId()));verify(policy,times(1)).requireNewBusiness("loan");verifyNoMoreInteractions(policy);}
 @Test void signedButUnfundedLoanCannotBeRepaid(){LoanRecord r=loan("SIGNED");assertThrows(RuntimeException.class,()->loans.earlyRepayment(r.getId(),user));money("1000");assertEquals("SIGNED",state("loan_record",r.getId()));}
 @Test void auditFailureRollsBackApprovalAndRepayment(){LoanRecord r=loan("SIGNED");auditFailure("LOAN_APPROVE",()->review.approveLoan(r.getId()));money("1000");assertEquals("SIGNED",state("loan_record",r.getId()));review.approveLoan(r.getId());auditFailure("LOAN_REPAY",()->loans.earlyRepayment(r.getId(),user));money("1100");assertEquals("APPROVED",state("loan_record",r.getId()));}
 @Test void auditFailureRollsBackRejectAndFinancialRedemption(){LoanRecord r=loan("PENDING");auditFailure("LOAN_REJECT",()->review.rejectLoan(r.getId(),"test"));assertEquals("PENDING",state("loan_record",r.getId()));FinancialOrder o=order(false);auditFailure("FINANCIAL_REDEEM",()->financial.earlyRedeem(user,o.getId()));money("1000");assertEquals("IN_PROGRESS",state("financial_order",o.getId()));financial.earlyRedeem(user,o.getId());financial.earlyRedeem(user,o.getId());money("1090");assertEquals(1,audits("FINANCIAL_REDEEM",o.getId()));verifyNoInteractions(policy);}
 @Test void crossTenantAndWrongUserCannotChangeMoney(){LoanRecord r=loan("SIGNED");FinancialOrder o=order(false);assertThrows(RuntimeException.class,()->loans.earlyRepayment(r.getId(),user+1));assertThrows(RuntimeException.class,()->financial.earlyRedeem(user+1,o.getId()));TenantContext.clear();try(TenantContext.Scope ignored=TenantContext.open(2L)){assertThrows(RuntimeException.class,()->review.approveLoan(r.getId()));assertThrows(RuntimeException.class,()->financial.earlyRedeem(user,o.getId()));}TenantContext.open(1L);money("1000");}
 @Test void concurrentApprovalAndRepaymentMoveMoneyOnce()throws Exception{LoanRecord r=loan("SIGNED");parallel(()->review.approveLoan(r.getId()),()->review.approveLoan(r.getId()));money("1100");parallel(()->loans.earlyRepayment(r.getId(),user),()->loans.earlyRepayment(r.getId(),user));money("1000");assertEquals(1,audits("LOAN_APPROVE",r.getId()));assertEquals(1,audits("LOAN_REPAY",r.getId()));}
 void parallel(Runnable... tasks)throws Exception{ExecutorService executor=Executors.newFixedThreadPool(tasks.length);CountDownLatch start=new CountDownLatch(1);List<Future<?>> futures=new ArrayList<>();try{for(Runnable task:tasks)futures.add(executor.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(1L)){control();start.await();task.run();}catch(InterruptedException e){throw new RuntimeException(e);}finally{SecurityContextHolder.clearContext();}}));start.countDown();for(Future<?> f:futures)f.get(30,TimeUnit.SECONDS);}finally{executor.shutdownNow();}}
 @Test void maturityAndRedemptionCannotReturnPrincipalTwice()throws Exception{FinancialOrder o=order(true);parallel(()->yields.calculateDailyYield(),()->{try{financial.earlyRedeem(user,o.getId());}catch(com.gtcfesk.exchange.common.BusinessException expected){assertEquals("COMPLETED",state("financial_order",o.getId()));}});String status=state("financial_order",o.getId());money("REDEEMED".equals(status)?"1090":"1100");assertEquals(0,BigDecimal.ZERO.compareTo(db.queryForObject("select frozen from asset_account where tenant_id=1 and user_id=?",BigDecimal.class,user)));assertEquals(1,audits("FINANCIAL_MATURE",o.getId())+audits("FINANCIAL_REDEEM",o.getId()));}
 @Test void agentCannotRunTenantWideFinancialCommands(){SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("agent-"+user,null,Collections.singleton(new SimpleGrantedAuthority("ROLE_AGENT"))));assertThrows(org.springframework.security.access.AccessDeniedException.class,()->yields.calculateDailyYield());assertThrows(org.springframework.security.access.AccessDeniedException.class,()->yields.payoutAllPendingYields());money("1000");}
 @Test void financialYieldAndMaturityAuditFailuresRollback(){FinancialOrder o=order(false);yields.calculateDailyYield();Long id=db.queryForObject("select id from financial_yield_record where tenant_id=1 and order_id=?",Long.class,o.getId());auditFailure("FINANCIAL_YIELD_PAY",()->yields.payoutYield(id));money("1000");assertEquals("PENDING",state("financial_yield_record",id));yields.payoutYield(id);yields.payoutYield(id);money("1001");assertEquals(1,audits("FINANCIAL_YIELD_PAY",id));tx(()->{FinancialOrder r=orders.lockById(o.getId()).get();r.setEndTime(LocalDateTime.now().minusDays(1));orders.save(r);});auditFailure("FINANCIAL_MATURE",()->yields.calculateDailyYield());money("1001");assertEquals("IN_PROGRESS",state("financial_order",o.getId()));}
}

package com.gtcfesk.exchange.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gtcfesk.exchange.admin.*;
import com.gtcfesk.exchange.admin.dto.UpdateUserBalanceRequest;
import com.gtcfesk.exchange.config.BackendAccess;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.user.*;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;

/** Opt-in disposable MySQL 5.7 probe: actual repositories/services/row locks/transactions/audit writes.
 * No Spring Boot, scheduler, feeds, SMTP or application configuration is loaded. */
public final class FundsMySqlProbe {
    static DataSource source;
    static int checks;
    static final List<String> results = new ArrayList<>();
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    @EnableJpaRepositories(basePackages="com.gtcfesk.exchange", repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
    @Import({LoanReviewService.class,LoanService.class,FinancialService.class,FinancialYieldService.class,
        BalanceAdjustmentService.class,DepositOrderService.class,ControlAuditService.class,
        BackendAccess.class,AdminPermissionService.class,WithdrawReviewController.class})
    public static class ConfigurationOnly {
        @Bean public static org.springframework.context.support.PropertySourcesPlaceholderConfigurer placeholders(){org.springframework.context.support.PropertySourcesPlaceholderConfigurer p=new org.springframework.context.support.PropertySourcesPlaceholderConfigurer();Properties values=new Properties();values.setProperty("jwt.expireSeconds","300");values.setProperty("jwt.secret","probe-only-not-used-to-sign-any-token");p.setProperties(values);return p;}
        @Bean public DataSource dataSource(){return source;}
        @Bean public LocalContainerEntityManagerFactoryBean entityManagerFactory(){
            LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(source);
            f.setPackagesToScan("com.gtcfesk.exchange");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","validate");
            p.setProperty("hibernate.dialect","org.hibernate.dialect.MySQL57Dialect");
            p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            f.setJpaProperties(p);return f;
        }
        @Bean public PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
        @Bean public ObjectMapper json(){return new ObjectMapper();}
        // Only non-funds dependencies are stubs. Permission/scope, all persistence, and audit remain real.
        @Bean public TenantReadinessService readiness(){return mock(TenantReadinessService.class);}
        @Bean public com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
        @Bean public TenantPolicyService policy(){return mock(TenantPolicyService.class);}
        @Bean public TenantJobRunner jobs(){return mock(TenantJobRunner.class);}
        @Bean public OperationalIssueService issues(){return mock(OperationalIssueService.class);}
        @Bean public FiatCurrencyService fiat(){return mock(FiatCurrencyService.class);}
        @Bean public AgentActionService actions(){return mock(AgentActionService.class);}
        @Bean public com.gtcfesk.exchange.common.JwtUtil jwt(){return mock(com.gtcfesk.exchange.common.JwtUtil.class);}
        @Bean public LoanPersonalInfoService personal(){LoanPersonalInfoService s=mock(LoanPersonalInfoService.class);
            when(s.requireApprovedPersonalInfo(anyLong())).thenAnswer(i->{LoanPersonalInfo p=new LoanPersonalInfo();p.setUserId(i.getArgument(0));p.setRealName("probe");p.setIdNumber("PROBE-IDENTITY");return p;});return s;}
    }
    final AnnotationConfigApplicationContext context;
    final JdbcTemplate db;
    final TransactionTemplate transaction;
    final UserAccountRepository users;
    final AssetAccountRepository assets;
    final LoanRecordRepository loanRecords;
    final FinancialOrderRepository orders;
    final FinancialProductRepository products;
    final LoanReviewService review;
    final LoanService loans;
    final FinancialService financial;
    final FinancialYieldService yields;
    final BalanceAdjustmentService adjustments;
    final DepositOrderService deposits;
    final WithdrawReviewController withdrawals;
    Long tenant, otherTenant, actor, user, product;
    String session;
    FundsMySqlProbe(AnnotationConfigApplicationContext c){
        context=c;db=new JdbcTemplate(source);transaction=new TransactionTemplate(c.getBean(PlatformTransactionManager.class));
        users=c.getBean(UserAccountRepository.class);assets=c.getBean(AssetAccountRepository.class);loanRecords=c.getBean(LoanRecordRepository.class);
        orders=c.getBean(FinancialOrderRepository.class);products=c.getBean(FinancialProductRepository.class);
        review=c.getBean(LoanReviewService.class);loans=c.getBean(LoanService.class);financial=c.getBean(FinancialService.class);
        yields=c.getBean(FinancialYieldService.class);adjustments=c.getBean(BalanceAdjustmentService.class);
        deposits=c.getBean(DepositOrderService.class);withdrawals=c.getBean(WithdrawReviewController.class);
    }
    static void check(boolean condition,String description){if(!condition)throw new AssertionError(description);checks++;results.add(description);System.out.println("PASS "+description);}
    void tx(Runnable action){transaction.execute(status->{action.run();return null;});}
    void initialize(){
        check(db.queryForObject("select version()",String.class).startsWith("5.7."),"real MySQL 5.7 engine");
        tx(()->{
            com.gtcfesk.exchange.control.TenantRepository tenants=context.getBean(com.gtcfesk.exchange.control.TenantRepository.class);
            Tenant t=new Tenant();t.setCode("funds-"+UUID.randomUUID());t.setName("Isolated funds probe");tenant=tenants.saveAndFlush(t).getId();
            Tenant b=new Tenant();b.setCode("foreign-"+UUID.randomUUID());b.setName("Foreign funds probe");otherTenant=tenants.saveAndFlush(b).getId();
            ControlAdmin admin=new ControlAdmin();admin.setAccount("probe-"+UUID.randomUUID());admin.setPasswordHash("not-a-login");admin.setEnabled(false);
            actor=context.getBean(ControlAdminRepository.class).saveAndFlush(admin).getId();
        });
        session="funds-mysql-"+actor;
        check(context.getBean(EntityManagerFactory.class).getMetamodel().getEntities().size()>=50,"strict full-entity migrated-schema validation");
    }
    void control(Long targetTenant){UsernamePasswordAuthenticationToken auth=new UsernamePasswordAuthenticationToken("-"+actor,null,
        Arrays.asList(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"),new SimpleGrantedAuthority("ROLE_CONTROL_ACCESS")));
        auth.setDetails(new ControlIdentity(actor,targetTenant,session));SecurityContextHolder.getContext().setAuthentication(auth);}
    void fixture(){tx(()->{
        UserAccount u=new UserAccount();u.setEmail(UUID.randomUUID()+"@test.invalid");u.setPasswordHash("not-a-login");users.saveAndFlush(u);user=u.getId();
        AssetAccount a=new AssetAccount();a.setUserId(user);a.setCoin("FUND");a.setAvailable(new BigDecimal("1000"));a.setFrozen(BigDecimal.ZERO);assets.saveAndFlush(a);
        FinancialProduct p=new FinancialProduct();p.setName("probe");p.setDailyYieldRate(BigDecimal.ONE);p.setRentalFee(BigDecimal.ZERO);p.setMinPurchase(BigDecimal.ONE);
        p.setMaxPurchase(new BigDecimal("10000"));p.setTermDays(7);p.setPenaltyRate(BigDecimal.TEN);products.saveAndFlush(p);product=p.getId();
    });}
    BigDecimal balance(String column){return db.queryForObject("select "+column+" from asset_account where tenant_id=? and user_id=? and coin='FUND'",BigDecimal.class,tenant,user);}
    void money(String available,String frozen,String description){check(new BigDecimal(available).compareTo(balance("available"))==0&&new BigDecimal(frozen).compareTo(balance("frozen"))==0,description);}
    long count(String table){return db.queryForObject("select count(*) from "+table+" where tenant_id=? and user_id=?",Long.class,tenant,user);}
    long audit(String action,Long object){String sql="select count(*) from control_audit_log where tenant_id=? and actor_id=? and access_session_id=? and action=?";
        return object==null?db.queryForObject(sql,Long.class,tenant,actor,session,action):db.queryForObject(sql+" and object_ref=?",Long.class,tenant,actor,session,action,object.toString());}
    String state(String table,Long id){return db.queryForObject("select status from "+table+" where tenant_id=? and id=?",String.class,tenant,id);}
    void fails(Runnable command,String description){boolean rejected=false;try{command.run();}catch(RuntimeException expected){rejected=true;}check(rejected,description);}
    void auditFailure(String action,Runnable command){String name="funds_probe_"+actor;
        db.execute("create trigger "+name+" before insert on control_audit_log for each row begin if NEW.actor_id="+actor+" and NEW.action='"+action+"' then signal sqlstate '45000' set message_text='probe audit rejected'; end if; end");
        try{fails(command,action+" rejects a real MySQL audit INSERT");}finally{db.execute("drop trigger "+name);}
    }
    List<RuntimeException> parallel(Runnable... operations)throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(operations.length);CountDownLatch start=new CountDownLatch(1);List<Future<RuntimeException>> futures=new ArrayList<>();
        try{for(Runnable operation:operations)futures.add(pool.submit(()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){
            control(tenant);start.await();operation.run();return null;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
        catch(RuntimeException failure){return failure;}finally{SecurityContextHolder.clearContext();}}));start.countDown();
        List<RuntimeException> failures=new ArrayList<>();for(Future<RuntimeException> future:futures){RuntimeException failure=future.get(30,TimeUnit.SECONDS);if(failure!=null)failures.add(failure);}return failures;
        }finally{pool.shutdownNow();}
    }
    void noFailures(List<RuntimeException> failures,String description){if(!failures.isEmpty())throw failures.get(0);check(true,description);}
    UpdateUserBalanceRequest adjustment(){UpdateUserBalanceRequest r=new UpdateUserBalanceRequest();r.setUserId(user);r.setConfirm(true);r.setRemark("isolated concurrency probe");r.setIdempotencyKey(UUID.randomUUID().toString());r.setFundBalance(new BigDecimal("1200"));return r;}
    void balanceAdjustments()throws Exception{
        fixture();UpdateUserBalanceRequest r=adjustment();long before=audit("BALANCE_ADJUST",null);
        auditFailure("BALANCE_ADJUST",()->adjustments.adjust(r));money("1000","0","adjustment audit failure restores wallet");check(count("balance_adjustment")==0,"adjustment audit failure restores idempotency ledger");
        noFailures(parallel(()->adjustments.adjust(r),()->adjustments.adjust(r)),"concurrent adjustment replay succeeds");money("1200","0","adjustment modifies wallet once");
        check(count("balance_adjustment")==1&&audit("BALANCE_ADJUST",null)==before+1,"one adjustment ledger and real CONTROL audit");
        r.setFundBalance(new BigDecimal("1300"));fails(()->adjustments.adjust(r),"conflicting adjustment replay rejected");money("1200","0","conflicting replay leaves wallet unchanged");
    }
    DepositRecord deposit(){DepositRecord d=new DepositRecord();d.setUserId(user);d.setOrderNo("PROBE-"+UUID.randomUUID());d.setSource("USER_SUBMITTED");d.setAccountType("FUND");d.setType("bank");d.setNetwork("USD");d.setAddress("probe");d.setAmount(new BigDecimal("100"));d.setCurrency("USD");d.setOriginalAmount(new BigDecimal("100"));d.setExchangeRate(BigDecimal.ONE);d.setFeeAmount(BigDecimal.ZERO);d.setFeeRate(BigDecimal.ZERO);d.setCreatedByType("USER");d.setCreatedById(user);tx(()->context.getBean(DepositRecordRepository.class).saveAndFlush(d));return d;}
    void depositReview()throws Exception{
        fixture();DepositRecord d=deposit();auditFailure("DEPOSIT_REVIEW",()->deposits.review(d.getId(),true,"probe approve"));
        money("1000","0","deposit audit failure restores wallet");check("PENDING".equals(state("deposit_record",d.getId()))&&count("deposit_credit_record")==0,"deposit audit failure restores order and credit ledger");
        List<RuntimeException> failures=parallel(()->deposits.review(d.getId(),true,"probe approve"),()->deposits.review(d.getId(),true,"probe approve"));
        check(failures.size()==1,"duplicate deposit review admits exactly one transaction");money("1100","0","duplicate deposit credits exactly once");
        check(count("deposit_credit_record")==1&&audit("DEPOSIT_REVIEW",d.getId())==1,"one deposit credit and CONTROL audit");
        check("CONTROL".equals(db.queryForObject("select reviewed_by_type from deposit_record where tenant_id=? and id=?",String.class,tenant,d.getId())),"deposit keeps real CONTROL reviewer type");
    }
    LoanRecord loan(){LoanRecord r=new LoanRecord();r.setUserId(user);r.setAmount(new BigDecimal("100"));r.setDays(7);r.setDailyRate(BigDecimal.ZERO);r.setFreeDays(7);r.setTotalInterest(BigDecimal.ZERO);r.setRepaymentAmount(new BigDecimal("100"));r.setRealName("probe");r.setIdNumber("PROBE-IDENTITY");r.setContractSigned(true);r.setStatus("SIGNED");tx(()->loanRecords.saveAndFlush(r));return r;}
    void loanMoney()throws Exception{
        fixture();LoanRecord r=loan();fails(()->loans.earlyRepayment(r.getId(),user),"unfunded signed loan cannot debit wallet");
        auditFailure("LOAN_APPROVE",()->review.approveLoan(r.getId()));money("1000","0","loan disbursement audit failure restores wallet");check("SIGNED".equals(state("loan_record",r.getId())),"loan disbursement audit failure restores status");
        noFailures(parallel(()->review.approveLoan(r.getId()),()->review.approveLoan(r.getId())),"concurrent disbursement replay succeeds");money("1100","0","loan disbursement happens once");
        auditFailure("LOAN_REPAY",()->loans.earlyRepayment(r.getId(),user));money("1100","0","repayment audit failure restores wallet");check("APPROVED".equals(state("loan_record",r.getId())),"repayment audit failure restores loan");
        noFailures(parallel(()->loans.earlyRepayment(r.getId(),user),()->loans.earlyRepayment(r.getId(),user)),"concurrent repayment replay succeeds");money("1000","0","loan repayment debits once");
        check(audit("LOAN_APPROVE",r.getId())==1&&audit("LOAN_REPAY",r.getId())==1,"loan lifecycle has exactly one audit per transition");
    }
    void financialExit()throws Exception{
        fixture();auditFailure("FINANCIAL_PURCHASE",()->financial.purchaseProduct(user,product,new BigDecimal("100")));money("1000","0","purchase audit failure restores both balances");check(count("financial_order")==0,"purchase audit failure restores order ledger");FinancialOrder o=financial.purchaseProduct(user,product,new BigDecimal("100"));money("900","100","real financial purchase freezes principal");
        auditFailure("FINANCIAL_REDEEM",()->financial.earlyRedeem(user,o.getId()));money("900","100","redemption audit failure restores both balances");check("IN_PROGRESS".equals(state("financial_order",o.getId())),"redemption audit failure restores order");
        noFailures(parallel(()->financial.earlyRedeem(user,o.getId()),()->financial.earlyRedeem(user,o.getId())),"concurrent redemption replay succeeds");money("990","0","redemption returns principal minus penalty once");check(audit("FINANCIAL_REDEEM",o.getId())==1,"one CONTROL redemption audit");
        fixture();FinancialOrder m=financial.purchaseProduct(user,product,new BigDecimal("100"));tx(()->{FinancialOrder locked=orders.lockById(m.getId()).get();locked.setEndTime(LocalDateTime.now().minusDays(1));orders.saveAndFlush(locked);});
        auditFailure("FINANCIAL_MATURE",yields::calculateDailyYield);money("900","100","maturity audit failure restores frozen principal");
        noFailures(parallel(yields::calculateDailyYield,()->{try{financial.earlyRedeem(user,m.getId());}catch(com.gtcfesk.exchange.common.BusinessException expected){if(!"COMPLETED".equals(state("financial_order",m.getId())))throw expected;}}),"maturity and redemption race resolves without double principal");
        money("REDEEMED".equals(state("financial_order",m.getId()))?"990":"1000","0","maturity/redemption conservation after real row-lock race");
        check(audit("FINANCIAL_REDEEM",m.getId())+audit("FINANCIAL_MATURE",m.getId())==1,"single terminal transition CONTROL audit");
    }
    WithdrawRecord withdrawal(){WithdrawRecord w=new WithdrawRecord();w.setUserId(user);w.setType("digital");w.setNetwork("USD");w.setAddress("probe");w.setAmount(new BigDecimal("100"));w.setFee(BigDecimal.ZERO);w.setActualAmount(new BigDecimal("100"));tx(()->{context.getBean(WithdrawRecordRepository.class).saveAndFlush(w);AssetAccount a=assets.lockByUserId(user).get(0);a.setAvailable(new BigDecimal("900"));a.setFrozen(new BigDecimal("100"));assets.saveAndFlush(a);});return w;}
    void ok(org.springframework.http.ResponseEntity<?> response){if(!response.getStatusCode().is2xxSuccessful())throw new IllegalStateException("Withdrawal operation rejected");}
    void withdrawalReview()throws Exception{
        fixture();WithdrawRecord w=withdrawal();WithdrawReviewController.RejectRequest request=new WithdrawReviewController.RejectRequest();request.setRemark("probe reject");
        auditFailure("WITHDRAW_REJECTED",()->ok(withdrawals.rejectWithdraw(w.getId(),request,null)));money("900","100","withdraw rejection audit failure restores wallet");
        check("PENDING".equals(state("withdraw_record",w.getId())),"withdraw rejection audit failure restores order");
        List<RuntimeException> failures=parallel(()->ok(withdrawals.rejectWithdraw(w.getId(),request,null)),()->ok(withdrawals.rejectWithdraw(w.getId(),request,null)));
        check(failures.size()==1,"duplicate withdrawal rejection admits one transition");money("1000","0","withdrawal rejected refund happens once");check(audit("WITHDRAW_REJECTED",w.getId())==1,"one real CONTROL withdrawal rejection audit");
        fixture();WithdrawRecord completed=withdrawal();ok(withdrawals.approveWithdraw(completed.getId(),null,null));
        auditFailure("WITHDRAW_COMPLETED",()->ok(withdrawals.completeWithdraw(completed.getId(),null)));money("900","100","withdraw completion audit failure restores frozen balance");
        check("APPROVED".equals(state("withdraw_record",completed.getId())),"withdraw completion audit failure restores state");
        failures=parallel(()->ok(withdrawals.completeWithdraw(completed.getId(),null)),()->ok(withdrawals.completeWithdraw(completed.getId(),null)));
        check(failures.size()==1,"duplicate withdrawal completion admits one transition");money("900","0","withdraw completion consumes frozen amount once");check(audit("WITHDRAW_COMPLETED",completed.getId())==1,"one real CONTROL withdrawal completion audit");
    }
    void crossTenant(){fixture();LoanRecord r=loan();DepositRecord d=deposit();FinancialOrder o=financial.purchaseProduct(user,product,new BigDecimal("100"));Long targetUser=user;
        fails(()->financial.earlyRedeem(targetUser+1,o.getId()),"wrong user cannot redeem owned order");
        TenantContext.clear();try(TenantContext.Scope ignored=TenantContext.open(otherTenant)){control(otherTenant);
            fails(()->review.approveLoan(r.getId()),"foreign tenant loan approval denied");fails(()->deposits.review(d.getId(),true,"probe approve"),"foreign tenant deposit approval denied");
            fails(()->financial.earlyRedeem(targetUser,o.getId()),"foreign tenant redemption denied");fails(()->adjustments.adjust(adjustment()),"foreign tenant balance adjustment denied");
        }finally{TenantContext.open(tenant);control(tenant);}money("900","100","all foreign-tenant attempts leave original balances unchanged");
    }
    @SuppressWarnings("unchecked") public static void main(String[] args)throws Exception{
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.WARN);
        if(args.length!=1)throw new IllegalArgumentException("Restricted fixture file required");
        Map<String,String> settings=new ObjectMapper().readValue(new File(args[0]),Map.class);
        source=com.gtcfesk.exchange.tenant.DedicatedMysqlFixture.open(settings);
        check(new JdbcTemplate(source).queryForObject("select database()",String.class).startsWith("mt705_probe_"),"dedicated migrated clone only");
        try(AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext(ConfigurationOnly.class)){
            FundsMySqlProbe probe=new FundsMySqlProbe(context);probe.initialize();
            try(TenantContext.Scope ignored=TenantContext.open(probe.tenant)){probe.control(probe.tenant);
                probe.balanceAdjustments();probe.depositReview();probe.loanMoney();probe.financialExit();probe.withdrawalReview();probe.crossTenant();
            }finally{TenantContext.clear();SecurityContextHolder.clearContext();}
            System.out.println("FUNDS_MYSQL_57_PASS checks="+checks+" tenant="+probe.tenant+" actor="+probe.actor);
        }
    }
}

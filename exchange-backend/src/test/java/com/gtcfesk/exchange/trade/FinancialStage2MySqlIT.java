package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.ExchangeBackendApplication;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.user.FinancialYieldService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Migrated MySQL, real row locks and services; explicitly synthetic initial obligations/funds. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes={ExchangeBackendApplication.class,OptionSettlementMySqlIT.Inputs.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class FinancialStage2MySqlIT {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception{
        OptionSettlementMySqlIT.properties(r);
        r.add("financial.yield.initial-delay-ms",()->"3600000");
    }
    @Autowired FinancialYieldService yields; @Autowired FinancialOrderRepository orders;
    @Autowired FinancialProductRepository products; @Autowired UserAccountRepository users;
    @Autowired AssetAccountRepository assets; @Autowired com.gtcfesk.exchange.control.TenantRepository tenants;
    @Autowired JdbcTemplate db; @Autowired TenantJobRunner jobs;
    TenantContext.Scope scope;long tenant,user;FinancialProduct product;
    @BeforeEach void setup(){
        com.gtcfesk.exchange.control.Tenant t=new com.gtcfesk.exchange.control.Tenant();t.setCode("s2f"+UUID.randomUUID().toString().substring(0,8));t.setName("Stage2 synthetic finance");t.setStatus("MAINTENANCE");tenant=tenants.saveAndFlush(t).getId();scope=TenantContext.open(tenant);
        UserAccount u=new UserAccount();u.setEmail("s2-finance-"+UUID.randomUUID()+"@example.invalid");u.setPasswordHash("not-a-login");user=users.saveAndFlush(u).getId();
        AssetAccount a=new AssetAccount();a.setUserId(user);a.setCoin("FUND");a.setAvailable(new BigDecimal("1000"));a.setFrozen(new BigDecimal("100"));assets.saveAndFlush(a);
        product=new FinancialProduct();product.setName("Synthetic initial finance product");product.setDailyYieldRate(BigDecimal.ONE);product.setRentalFee(BigDecimal.ZERO);product.setMinPurchase(BigDecimal.ONE);product.setMaxPurchase(new BigDecimal("1000"));product.setTermDays(3);products.saveAndFlush(product);
    }
    @AfterEach void clear(){if(scope!=null)scope.close();TenantContext.clear();}
    FinancialOrder order(boolean mature,boolean valid){
        FinancialOrder o=new FinancialOrder();o.setUserId(user);o.setProductId(product.getId());o.setProductName(product.getName());o.setPurchaseAmount(new BigDecimal("100"));o.setDailyYieldRate(BigDecimal.ONE);o.setDailyYield(valid?BigDecimal.ONE:BigDecimal.ONE.negate());o.setTermDays(mature?3:7);o.setTotalYield(new BigDecimal(mature?"3":"7"));o.setPenaltyRate(BigDecimal.ZERO);
        LocalDateTime start=LocalDateTime.now().minusDays(mature?3:2).minusHours(1);o.setPurchaseTime(start);o.setEndTime(start.plusDays(o.getTermDays()));return orders.saveAndFlush(o);
    }
    void money(String available,String frozen){AssetAccount a=assets.findByTenantIdAndUserIdAndCoin(tenant,user,"FUND").get();assertEquals(0,new BigDecimal(available).compareTo(a.getAvailable()));assertEquals(0,new BigDecimal(frozen).compareTo(a.getFrozen()));}
    long count(long id){return db.queryForObject("SELECT COUNT(*) FROM financial_yield_record WHERE tenant_id=? AND order_id=?",Long.class,tenant,id);}
    @Test void maturityCatchesEveryCalendarDayBeforeReturningPrincipalOnce(){
        FinancialOrder o=order(true,true);yields.calculateDailyYield();yields.calculateDailyYield();assertEquals(3,count(o.getId()));assertEquals("COMPLETED",orders.findByTenantIdAndId(tenant,o.getId()).get().getStatus());money("1100","0");
        List<BigDecimal> cumulative=db.queryForList("SELECT cumulative_yield FROM financial_yield_record WHERE tenant_id=? AND order_id=? ORDER BY yield_date",BigDecimal.class,tenant,o.getId());for(int i=0;i<3;i++)assertEquals(0,BigDecimal.valueOf(i+1).compareTo(cumulative.get(i)));
        yields.payoutAllPendingYields();yields.payoutAllPendingYields();money("1103","0");
    }
    @Test void missedDaysAndConcurrentTicksProduceOneRecordAndPayoutPerDate()throws Exception{
        FinancialOrder o=order(false,true);ExecutorService threads=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);Runnable tick=()->{try(TenantContext.Scope ignored=TenantContext.open(tenant)){try{start.await();}catch(InterruptedException e){throw new RuntimeException(e);}jobs.call(tenant,()->{yields.calculateDailyYield();return null;});}};
        try{Future<?> a=threads.submit(tick),b=threads.submit(tick);start.countDown();a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);}finally{threads.shutdownNow();}
        assertEquals(3,count(o.getId()));money("1000","100");yields.payoutAllPendingYields();yields.payoutAllPendingYields();money("1003","100");
    }
    @Test void mysqlInsertFailureRollsBackAccrualAndMaturityThenRetryIsSingle(){
        FinancialOrder o=order(true,true);String trigger="s2_financial_"+o.getId();db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON financial_yield_record FOR EACH ROW BEGIN IF NEW.order_id="+o.getId()+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Stage2 controlled yield failure'; END IF; END");
        try{assertThrows(RuntimeException.class,()->yields.calculateDailyYield());assertEquals(0,count(o.getId()));assertEquals("IN_PROGRESS",orders.findByTenantIdAndId(tenant,o.getId()).get().getStatus());money("1000","100");}finally{db.execute("DROP TRIGGER "+trigger);}
        yields.calculateDailyYield();yields.calculateDailyYield();assertEquals(3,count(o.getId()));money("1100","0");
    }
    @Test void invalidHistoricalAccrualCannotDisappearOnMaturity(){
        FinancialOrder o=order(true,false);assertThrows(RuntimeException.class,()->yields.calculateDailyYield());assertEquals(0,count(o.getId()));assertEquals("IN_PROGRESS",orders.findByTenantIdAndId(tenant,o.getId()).get().getStatus());money("1000","100");
    }
}

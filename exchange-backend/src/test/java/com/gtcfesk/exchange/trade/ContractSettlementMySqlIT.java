package com.gtcfesk.exchange.trade;

import com.gtcfesk.exchange.ExchangeBackendApplication;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.tenant.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real migrated MySQL/services. Balances, historical snapshots and quote inputs are synthetic. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes={ExchangeBackendApplication.class,ContractSettlementMySqlIT.Inputs.class})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class ContractSettlementMySqlIT {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r)throws Exception {OptionSettlementMySqlIT.properties(r);}
    @TestConfiguration static class Inputs {
        @Bean @Primary ForexQuoteMarketService contractInput(){return new OptionSettlementMySqlIT.QuoteInput(){
            @Override public Map<String,BigDecimal> freshPrices(){return new HashMap<>();}
        };}
    }
    @Autowired ContractOrderService contracts; @Autowired ContractOrderRepository orders;
    @Autowired UserAccountRepository users; @Autowired AssetAccountRepository assets;
    @Autowired JdbcTemplate db; @Autowired TenantJobRunner jobs;
    @Autowired com.gtcfesk.exchange.market.MarketOrderProcessor marketTasks;
    TenantContext.Scope scope; String symbol;
    @BeforeEach void setup(){marketTasks.stop();scope=TenantContext.open(1L);symbol="S2C"+UUID.randomUUID().toString().substring(0,8);}
    @AfterEach void clear(){if(scope!=null)scope.close();TenantContext.clear();}
    long user(String available,String frozen){
        UserAccount u=new UserAccount();u.setEmail("s2contract-"+UUID.randomUUID()+"@example.invalid");u.setPasswordHash("not-a-login");users.saveAndFlush(u);
        AssetAccount a=new AssetAccount();a.setUserId(u.getId());a.setCoin("CONTRACT");a.setAvailable(new BigDecimal(available));a.setFrozen(new BigDecimal(frozen));assets.saveAndFlush(a);return u.getId();
    }
    ContractOrder order(long user,boolean corrupt,boolean sell){
        ContractOrder o=new ContractOrder();o.setUserId(user);o.setSymbol(symbol);o.setSide(sell?"SELL":"BUY");o.setType("MARKET");o.setStatus("OPEN");o.setQuantity(BigDecimal.ONE);o.setOpenPrice(new BigDecimal("100"));o.setMargin(BigDecimal.TEN);o.setFee(BigDecimal.ZERO);o.setLeverage(BigDecimal.ONE);o.setTakeProfit(new BigDecimal("105"));
        o.setFundingSource(corrupt?null:"CONTRACT");o.setTrialReserved(corrupt?new BigDecimal("5"):BigDecimal.ZERO);if(corrupt)o.setTrialAllocations("invalid-allocation");return orders.saveAndFlush(o);
    }
    void unchanged(long user,long id,String available,String frozen){
        assertEquals("OPEN",orders.findByTenantIdAndId(TenantContext.requireTenantId(),id).get().getStatus());
        AssetAccount a=assets.findByTenantIdAndUserIdAndCoin(TenantContext.requireTenantId(),user,"CONTRACT").get();
        assertEquals(0,new BigDecimal(available).compareTo(a.getAvailable()));assertEquals(0,new BigDecimal(frozen).compareTo(a.getFrozen()));
    }
    @Test void automaticCloseRollsBackCashAndOrderWhenHistoricalAllocationIsInvalid(){
        long user=user("90","5");ContractOrder o=order(user,true,false);
        assertThrows(RuntimeException.class,()->contracts.checkAndAutoCloseOrders(symbol,null));unchanged(user,o.getId(),"90","5");
        assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM trial_account WHERE tenant_id=1 AND user_id=?",Long.class,user));
        assertNull(orders.findByTenantIdAndId(1L,o.getId()).get().getCurrentPrice());
    }
    @Test void forceCloseRollsBackCashAndTrialPreparationWhenAllocationIsInvalid(){
        long user=user("0","5");ContractOrder o=order(user,true,true);
        assertThrows(RuntimeException.class,()->contracts.checkAndForceCloseOrders(new HashMap<>()));unchanged(user,o.getId(),"0","5");
        assertEquals(0L,db.queryForObject("SELECT COUNT(*) FROM trial_account WHERE tenant_id=1 AND user_id=?",Long.class,user));
    }
    @Test void failedTenantRollsBackButOtherTenantKeepsSettlementAndContextIsCleared(){
        long broken=user("90","5");ContractOrder bad=order(broken,true,false);scope.close();scope=null;
        long good;ContractOrder success;try(TenantContext.Scope ignored=TenantContext.open(3L)){good=user("90","10");success=order(good,false,false);}
        jobs.each("stage2-contract-auto",tenant->contracts.checkAndAutoCloseOrders(symbol,null));assertNull(TenantContext.currentTenantId());
        try(TenantContext.Scope ignored=TenantContext.open(1L)){unchanged(broken,bad.getId(),"90","5");}
        try(TenantContext.Scope ignored=TenantContext.open(3L)){assertEquals("CLOSED",orders.findByTenantIdAndId(3L,success.getId()).get().getStatus());AssetAccount a=assets.findByTenantIdAndUserIdAndCoin(3L,good,"CONTRACT").get();assertEquals(0,new BigDecimal("110").compareTo(a.getAvailable()));assertEquals(0,BigDecimal.ZERO.compareTo(a.getFrozen()));}
    }
}

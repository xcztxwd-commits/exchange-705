package com.gtcfesk.exchange.demo;

import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.market.ForexQuoteMarketService;
import org.junit.jupiter.api.*;
import com.gtcfesk.exchange.tenant.TenantContext;
import com.gtcfesk.exchange.control.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.mock.mockito.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import javax.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real H2 transactions and competing connections; never touches the configured real database. */
@DataJpaTest(properties = {"spring.datasource.url=jdbc:h2:mem:demo-isolation;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.jpa.properties.hibernate.format_sql=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = DemoPersistenceTest.Config.class)
@Import(DemoTradingService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DemoPersistenceTest {
 private TenantContext.Scope tenantScope;
 @AfterEach void closeTenantScope(){ if(tenantScope!=null)tenantScope.close(); }
    @Configuration
    @EnableJpaRepositories(basePackages={"com.gtcfesk.exchange.demo","com.gtcfesk.exchange.repository"}, repositoryFactoryBeanClass=com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean.class)
    @EntityScan(basePackageClasses = {DemoAccount.class, UserAccount.class})
    static class Config {
        @Bean com.gtcfesk.exchange.security.OutboundEndpointPolicy outbound(){return mock(com.gtcfesk.exchange.security.OutboundEndpointPolicy.class);}
        @Bean TenantReadinessService readiness(){return mock(TenantReadinessService.class);}

        @Bean TenantPolicyService tenantPolicy(){return mock(TenantPolicyService.class);}
    }
    @Autowired DemoTradingService service;
    @Autowired DemoAccountRepository accounts;
    @Autowired DemoOrderRepository orders;
    @SpyBean DemoLedgerRepository ledger;
    @Autowired UserAccountRepository users;
    @Autowired TradingSymbolRepository symbols;
    @Autowired PlatformTransactionManager transactions;
    @MockBean ForexQuoteMarketService quotes;
    Long userId;
    @BeforeEach void setup() {
        tenantScope = TenantContext.open(1L);
        new TransactionTemplate(transactions).execute(status -> {
            ledger.deleteAllByTenantId(1L); orders.deleteAllByTenantId(1L); accounts.deleteAllByTenantId(1L); symbols.deleteAllByTenantId(1L); users.deleteAllByTenantId(1L); users.flush();
            UserAccount user = new UserAccount(); user.setEmail("demo@example.invalid"); user.setPasswordHash("not-a-login");
            userId = users.saveAndFlush(user).getId();
            TradingSymbol symbol = new TradingSymbol(); symbol.setSymbol("BTCUSDT"); symbol.setName("Bitcoin");
            symbol.setBaseCurrency("BTC"); symbol.setQuoteCurrency("USDT"); symbol.setMarketSource("binance"); symbol.setSourceCategory("Crypto");
            symbols.saveAndFlush(symbol); return null;
        });
        when(quotes.getPrice("BTCUSDT", "Crypto")).thenAnswer(call -> {
            Map<String,Object> quote = new HashMap<>(); quote.put("price", new BigDecimal("100"));
            quote.put("timestamp", System.currentTimeMillis()); quote.put("fetchedAt", System.currentTimeMillis());
            quote.put("available", true); return quote;
        });
    }
    void race(Runnable action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(6); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int i=0;i<6;i++) work.add(pool.submit(() -> { try(TenantContext.Scope ignored=TenantContext.open(1L)) { start.await(); action.run(); } catch (InterruptedException e) { throw new RuntimeException(e); } }));
            start.countDown(); for (Future<?> f : work) f.get(20, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
    }
    @Test void simultaneousInitializationAndDuplicateBuyCloseStayAtomic() throws Exception {
        race(() -> service.initialize(userId)); assertEquals(1, accounts.countByTenantId(1L)); assertEquals(1, ledger.countByTenantId(1L));
        String key = UUID.randomUUID().toString();
        race(() -> service.buy(userId, key, 1, "BTCUSDT", new BigDecimal("1000")));
        assertEquals(1, orders.countByTenantId(1L)); assertEquals(2, ledger.countByTenantId(1L));
        assertEquals(0, new BigDecimal("98999").compareTo(accounts.findByTenantIdAndId(1L, userId).get().cash));
        String id = orders.findAllByTenantId(1L).get(0).id; race(() -> service.close(userId, id, 1));
        assertEquals(3, ledger.countByTenantId(1L));
        assertEquals(0, new BigDecimal("99998").compareTo(accounts.findByTenantIdAndId(1L, userId).get().cash));
    }
    @Test void ledgerFailureRollsBackCashAndOrder() {
        service.initialize(userId);
        doThrow(new IllegalStateException("injected ledger failure")).when(ledger).save(any(DemoLedger.class));
        assertThrows(IllegalStateException.class, () -> service.buy(userId, UUID.randomUUID().toString(), 1, "BTCUSDT", new BigDecimal("1000")));
        assertEquals(0, orders.countByTenantId(1L)); assertEquals(1, ledger.countByTenantId(1L));
        assertEquals(0, DemoTradingService.SEED.compareTo(accounts.findByTenantIdAndId(1L, userId).get().cash));
    }
    @Test void competingSpendsNeverOverdraw() throws Exception {
        service.initialize(userId); java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        race(() -> {
            try { service.buy(userId, UUID.randomUUID().toString(), 1, "BTCUSDT", new BigDecimal("60000")); accepted.incrementAndGet(); }
            catch (com.gtcfesk.exchange.common.BusinessException expected) { assertTrue(expected.getMessage().contains("余额不足")); }
        });
        assertEquals(1, accepted.get()); assertEquals(1, orders.countByTenantId(1L));
        assertEquals(0, new BigDecimal("39940").compareTo(accounts.findByTenantIdAndId(1L, userId).get().cash));
    }
}

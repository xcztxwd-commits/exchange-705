package com.gtcfesk.exchange.trade;
import com.gtcfesk.exchange.entity.*;
import com.gtcfesk.exchange.repository.*;
import com.gtcfesk.exchange.market.*;
import com.gtcfesk.exchange.user.KycIdentityService;
import com.gtcfesk.exchange.trade.dto.CreateContractOrderRequest;
import org.junit.jupiter.api.*;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import javax.persistence.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringJUnitConfig(CryptoQuantityPersistenceTest.Config.class)
@org.junit.jupiter.api.extension.ExtendWith(com.gtcfesk.exchange.tenant.TenantOneFixture.class)
class CryptoQuantityPersistenceTest {
 @org.springframework.boot.test.context.TestConfiguration @EnableJpaRepositories(repositoryFactoryBeanClass=com.gtcfesk.exchange.tenant.TenantRepositoryFactoryBean.class,basePackages={"com.gtcfesk.exchange.repository","com.gtcfesk.exchange.admin"})
 static class Config {
  @Bean DataSource dataSource(){return new DriverManagerDataSource("jdbc:h2:mem:cq_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000","sa","");}
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){
   LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);f.setPackagesToScan("com.gtcfesk.exchange.entity","com.gtcfesk.exchange.admin");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
   Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","create-drop");p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");f.setJpaProperties(p);return f;
  }
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
 }
 @Autowired TradingSymbolRepository symbols;@Autowired ContractOrderRepository orders;@Autowired AssetAccountRepository accounts;
 @Autowired PlatformTransactionManager manager;@Autowired EntityManagerFactory factory;
 ContractOrderService service;ForexQuoteMarketService quotes;TransactionTemplate tx;EntityManager em;TradingSymbol symbol;long user;
 @BeforeEach void setup(){
  tx=new TransactionTemplate(manager);em=SharedEntityManagerCreator.createSharedEntityManager(factory);quotes=mock(ForexQuoteMarketService.class);
  MarketCategoryService categories=mock(MarketCategoryService.class);when(categories.leverageEnabled(anyString())).thenReturn(true);
  KycIdentityService identity=mock(KycIdentityService.class);when(identity.isApproved(anyLong())).thenReturn(true);
  service=new ContractOrderService(identity,orders,accounts,symbols,quotes,manager,categories);ReflectionTestUtils.setField(service,"entityManager",em);ReflectionTestUtils.setField(service,"tenantPolicy",mock(com.gtcfesk.exchange.control.TenantPolicyService.class));
  symbol=CryptoQuantityRulesTest.fixture("BTC","80000","0.001").symbol;symbol.setSymbol("BTC"+UUID.randomUUID().toString().substring(0,8));symbol.setName("isolated crypto");symbol.setMarketSource("binance");symbol=symbols.saveAndFlush(symbol);user=symbol.getId();
  AssetAccount a=new AssetAccount();a.setUserId(user);a.setCoin("CONTRACT");a.setAvailable(new BigDecimal("100"));a.setFrozen(BigDecimal.ZERO);accounts.saveAndFlush(a);
  when(quotes.freshPrice(symbol.getSymbol())).thenReturn(new BigDecimal("80000"));
 }
 CreateContractOrderRequest request(){CreateContractOrderRequest r=new CreateContractOrderRequest();r.setSymbol(symbol.getSymbol());r.setSide("BUY");r.setType("MARKET");r.setQuantity(new BigDecimal("0.01"));r.setSpecVersion(1L);r.setQuantityUnitType("BASE_ASSET");return r;}
 @Test void persistentLifecycleAndConcurrentClose() throws Exception {
  ContractOrder o=tx.execute(s->service.createOrder(user,request()));
  assertEquals(0,new BigDecimal("91.9997").compareTo(accounts.findByTenantIdAndUserIdAndCoin(1L, user,"CONTRACT").get().getAvailable()));
  CountDownLatch both=new CountDownLatch(2);when(quotes.freshPrice(symbol.getSymbol())).thenAnswer(call->{both.countDown();assertTrue(both.await(5,TimeUnit.SECONDS));return new BigDecimal("80100");});
  ExecutorService pool=Executors.newFixedThreadPool(2);
  try {
   Callable<Boolean> close=()->{try{tx.execute(s->service.closeOrder(user,o.getId(),null));return true;}catch(com.gtcfesk.exchange.common.BusinessException e){assertEquals("只能平仓持仓中的订单",e.getMessage());return false;}};
   Future<Boolean> a=pool.submit(com.gtcfesk.exchange.tenant.TenantOneFixture.worker(close)),b=pool.submit(com.gtcfesk.exchange.tenant.TenantOneFixture.worker(close));assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
   AssetAccount account=accounts.findByTenantIdAndUserIdAndCoin(1L, user,"CONTRACT").get();assertEquals(0,new BigDecimal("100.9997").compareTo(account.getAvailable()));assertEquals(0,account.getFrozen().signum());
  }finally{pool.shutdownNow();}
 }
 @Test void concurrentSpecificationChangeCannotUseOldVersion() throws Exception {
  ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
  try {
   Future<?> writer=pool.submit(com.gtcfesk.exchange.tenant.TenantOneFixture.worker(()->tx.execute(status->{TradingSymbol s=em.find(TradingSymbol.class,symbol.getId(),LockModeType.PESSIMISTIC_WRITE);s.setSpecVersion(2L);locked.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;})));
   assertTrue(locked.await(5,TimeUnit.SECONDS));Future<?> opening=pool.submit(com.gtcfesk.exchange.tenant.TenantOneFixture.worker(()->tx.execute(status->service.createOrder(user,request()))));
   Thread.sleep(150);assertFalse(opening.isDone());release.countDown();writer.get(10,TimeUnit.SECONDS);
   ExecutionException failure=assertThrows(ExecutionException.class,()->opening.get(10,TimeUnit.SECONDS));assertTrue(failure.getCause() instanceof com.gtcfesk.exchange.common.BusinessException);
   assertEquals(0,new BigDecimal("100").compareTo(accounts.findByTenantIdAndUserIdAndCoin(1L, user,"CONTRACT").get().getAvailable()));assertTrue(orders.findByTenantIdAndUserIdOrderByCreatedAtDesc(1L, user).isEmpty());
  }finally{release.countDown();pool.shutdownNow();}
 }
}

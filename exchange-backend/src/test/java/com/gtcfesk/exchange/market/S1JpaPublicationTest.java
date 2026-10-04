package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import javax.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual Spring Data proxy + JpaTransactionManager + JDBC share one physical transaction. */
@SpringJUnitConfig(S1JpaPublicationTest.Config.class)
class S1JpaPublicationTest extends TenantMarketTestContext {
    @Configuration @EnableTransactionManagement(proxyTargetClass=true)
    @EnableJpaRepositories(basePackages="com.gtcfesk.exchange",repositoryFactoryBeanClass=TenantRepositoryFactoryBean.class)
    static class Config {
        @Bean DataSource dataSource() {return new DriverManagerDataSource("jdbc:h2:mem:s1jpa_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");}
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(source);
            f.setPackagesToScan("com.gtcfesk.exchange");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            Properties p=new Properties();p.setProperty("hibernate.hbm2ddl.auto","create-drop");
            p.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
            f.setJpaProperties(p);return f;
        }
        @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f) {return new JpaTransactionManager(f);}
    }
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager manager;
    @Autowired TradingSymbolRepository repository;
    ForexQuoteMarketService market;
    ControlHistoryStore store;
    PersistentPriceControl controls;
    TradingSymbol symbol;
    @BeforeEach void setup() {
        store=new ControlHistoryStore(new JdbcTemplate(source),manager);MarketSqlFixture.schema(store.db);
        symbol=new TradingSymbol();symbol.setSymbol("S1JPA-"+UUID.randomUUID().toString().substring(0,8));
        symbol.setName("S1 fixture");symbol.setBaseCurrency("XAU");symbol.setCategory("Metal");
        symbol.setSourceCategory("Metal");symbol.setMarketSource("yahoo");symbol.setAlltickSymbol(symbol.getSymbol());
        symbol=repository.saveAndFlush(symbol);
        controls=spy(new PersistentPriceControl(store));market=new ForexQuoteMarketService();
        ReflectionTestUtils.setField(market,"symbols",repository);ReflectionTestUtils.setField(market,"controlHistory",store);
        ReflectionTestUtils.setField(market,"controls",controls);ReflectionTestUtils.setField(market,"redis",mock(RedisMarketService.class));
        market.refreshSymbols();long now=System.currentTimeMillis();
        market.acceptQuote(symbol.getSymbol(),"Metal",Map.of("price",100,"timestamp",now,"eventId","jpa"),"http",now);
    }
    @AfterEach void close() {if(market!=null)market.stop();}
    TradingSymbol published() {
        Object state=ReflectionTestUtils.invokeMethod(market,"state");
        return (TradingSymbol)((Map<?,?>)ReflectionTestUtils.getField(state,"registry")).get(symbol.getSymbol());
    }
    BigDecimal offset() {return store.db.queryForObject("SELECT control_price_offset FROM trading_symbol WHERE tenant_id=1 AND id=?",BigDecimal.class,symbol.getId());}
    @Test void repositoryFlushAndJdbcRollbackCannotLeakMutableRegistry() {
        TradingSymbol original=published();long version=original.getRowVersion();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(tx->{
            market.manualControl(symbol.getId(),true,new BigDecimal("2"));
            assertSame(original,published());assertEquals(0,offset().compareTo(new BigDecimal("2")));
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            throw new IllegalStateException("after-real-JPA-flush");
        }));
        assertEquals(0,offset().compareTo(BigDecimal.ZERO));assertSame(original,published());
        market.manualControl(symbol.getId(),true,new BigDecimal("2"));
        assertNotSame(original,published());assertEquals(0,published().getControlPriceOffset().compareTo(new BigDecimal("2")));
        assertTrue(published().getRowVersion()>version);assertEquals(0,original.getControlPriceOffset().compareTo(BigDecimal.ZERO));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
    @Test void responseFailureRollsBackConfigurationAndRandomHistoryAndDoesNotPublish() {
        TradingSymbol original=published();
        doThrow(new IllegalStateException("response-failure-after-save")).when(controls).status(any(),anyMap(),anyMap(),anyLong());
        assertThrows(IllegalStateException.class,()->market.manualControl(symbol.getId(),true,new BigDecimal("3")));
        assertEquals(0,offset().compareTo(BigDecimal.ZERO));assertSame(original,published());
        ReflectionTestUtils.setField(market,"virtualTrading",true);
        RedisMarketService redis=(RedisMarketService)ReflectionTestUtils.getField(market,"redis");
        when(redis.getKlines(anyString(),eq("1m"))).thenReturn(Collections.singletonList(Map.of("timestamp",1700000400000L,"close_price",100)));
        store.db.execute("ALTER TABLE trading_symbol ADD CONSTRAINT s1_reject_random CHECK (id<>"+symbol.getId()+" OR random_market_enabled=FALSE)");
        try {assertThrows(RuntimeException.class,()->market.randomMarket(symbol.getId(),true,null));}
        finally {store.db.execute("ALTER TABLE trading_symbol DROP CONSTRAINT s1_reject_random");}
        assertFalse(repository.findByTenantIdAndId(1L,symbol.getId()).get().getRandomMarketEnabled());
        assertEquals(0,store.db.queryForObject("SELECT COUNT(*) FROM market_simulation_source_candle WHERE tenant_id=1 AND symbol_id=?",Integer.class,symbol.getId()));
        assertSame(original,published());
    }
}

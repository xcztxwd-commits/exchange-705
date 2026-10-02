package com.gtcfesk.exchange.market;

import com.gtcfesk.exchange.entity.TradingSymbol;
import com.gtcfesk.exchange.repository.TradingSymbolRepository;
import com.gtcfesk.exchange.tenant.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.test.util.ReflectionTestUtils;
import javax.persistence.*;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.mockito.Mockito.*;

/** Disposable MySQL/Redis and production cache/controller/quote reader. Only provider candles are synthetic. */
@org.springframework.boot.test.context.TestConfiguration @EnableTransactionManagement(proxyTargetClass=true)
public class HomeSparklineFixture {
    @Bean DataSource dataSource() {
        requireStack();
        return new DriverManagerDataSource("jdbc:mysql://127.0.0.1:33405/t05_sparkline?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
            "root",System.getenv("T05_MYSQL_PASSWORD"));
    }
    static void requireStack() {
        if(!"true".equals(System.getenv("T05_DISPOSABLE_STACK")) || System.getenv("T05_MYSQL_PASSWORD")==null)
            throw new IllegalStateException("Explicit T05 disposable stack opt-in required; shared endpoints are forbidden");
    }
    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource data) {
        LocalContainerEntityManagerFactoryBean f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(data);
        f.setPackagesToScan("com.gtcfesk.exchange.entity");
        f.setPersistenceUnitPostProcessors(p->p.getManagedClassNames().removeIf(name->!name.equals(TradingSymbol.class.getName())));
        f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        Properties props=new Properties();props.setProperty("hibernate.hbm2ddl.auto","create-drop");
        props.setProperty("hibernate.physical_naming_strategy","org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy");
        f.setJpaProperties(props);return f;
    }
    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
    @Bean TenantRepositoryFactoryBean<TradingSymbolRepository,TradingSymbol,Long> symbols(EntityManagerFactory f) {
        TenantRepositoryFactoryBean<TradingSymbolRepository,TradingSymbol,Long> bean=new TenantRepositoryFactoryBean<>(TradingSymbolRepository.class);
        bean.setEntityManager(SharedEntityManagerCreator.createSharedEntityManager(f));return bean;
    }
    @Bean JdbcTemplate jdbc(DataSource data) {
        JdbcTemplate jdbc=new JdbcTemplate(data);
        jdbc.execute("CREATE TABLE IF NOT EXISTS tenant (id BIGINT PRIMARY KEY)");
        return jdbc;
    }
    @Bean(destroyMethod="destroy") LettuceConnectionFactory connection() {
        requireStack();
        LettuceClientConfiguration client=LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(350)).shutdownTimeout(Duration.ofMillis(100)).build();
        return new LettuceConnectionFactory(new org.springframework.data.redis.connection.RedisStandaloneConfiguration("127.0.0.1",19405),client);
    }
    @Bean StringRedisTemplate redis(LettuceConnectionFactory connection){return new StringRedisTemplate(connection);}
    @Bean HomeSparklineCacheTest.Time clock(){return new HomeSparklineCacheTest.Time();}
    @Bean Source source(){return new Source();}
    @Bean HomeSparklineCache cache(StringRedisTemplate redis,TradingSymbolRepository symbols,Source source,HomeSparklineCacheTest.Time clock) {
        return new HomeSparklineCache(redis,symbols,source.quotes,Boolean.getBoolean("t05.demo"),clock);
    }

    public static class Source implements AutoCloseable {
        final AtomicInteger calculations=new AtomicInteger();
        final ForexQuoteMarketService quotes=spy(new ForexQuoteMarketService());
        Source() {
            doAnswer(call->{calculations.incrementAndGet();return call.callRealMethod();}).when(quotes).internalKline(anyString(),anyString(),anyInt());
        }
        @SuppressWarnings("unchecked") void stage(List<TradingSymbol> symbols,double base,boolean empty) {
            Object state=ReflectionTestUtils.invokeMethod(quotes,"state");
            Map<String,TradingSymbol> registry=new HashMap<>();
            for(TradingSymbol symbol:symbols) {
                registry.put(symbol.getSymbol(),symbol);
                Object group=ReflectionTestUtils.invokeMethod(quotes,"group",symbol.getSourceCategory());
                Map<String,Map<String,Object>> saved=(Map<String,Map<String,Object>>)ReflectionTestUtils.getField(group,"klines");
                ReflectionTestUtils.setField(group,"codes",Collections.singletonList(ForexQuoteMarketService.marketCode(symbol)));
                synchronized(group) {
                    if(empty) saved.clear();
                    else {
                        List<Map<String,Object>> rows=new ArrayList<>();
                        for(int i=0;i<20;i++){Map<String,Object> row=new HashMap<>();row.put("close_price",base+i/100.0+.002*Math.sin(i*base));row.put("kline_timestamp",System.currentTimeMillis()/1000+i*300);rows.add(row);}
                        Map<String,Object> result=new HashMap<>();result.put("fetchedAt",System.currentTimeMillis());result.put("data",Collections.singletonMap("kline_list",rows));
                        saved.put(ForexQuoteMarketService.marketCode(symbol)+":5m:20",result);
                    }
                }
            }
            ReflectionTestUtils.setField(state,"registry",registry);
        }
        public void close(){quotes.stop();}
    }
}
